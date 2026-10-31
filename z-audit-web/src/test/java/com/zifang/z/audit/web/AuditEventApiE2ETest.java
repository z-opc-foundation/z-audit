package com.zifang.z.audit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计端点验收（**真起 Spring 上下文 + H2 真落库**）。
 *
 * <p>覆盖四个验收项：
 * <ol>
 *   <li>DDL 幂等（建两遍不报错）；</li>
 *   <li>POST 记事件 ⇒ 真落库 + 带 hash；</li>
 *   <li>GET 查事件 ⇒ 按条件过滤生效；</li>
 *   <li>GET 校验链 ⇒ 完整；直接改库里的 detail ⇒ 检出链断。</li>
 * </ol>
 *
 * @author zifang
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.base.db.audit.url=jdbc:h2:mem:zaudit_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "z.base.db.audit.username=sa",
                "z.base.db.audit.password="
        })
class AuditEventApiE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSourceAudit;

    @BeforeEach
    void applySchema() throws Exception {
        AuditTestSchema.apply(dataSourceAudit);
    }

    @Test
    @DisplayName("DDL 幂等：建两遍不报错、表存在")
    void ddlIsIdempotent() throws Exception {
        AuditTestSchema.apply(dataSourceAudit);
        AuditTestSchema.apply(dataSourceAudit);

        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM z_audit_event")) {
                assertTrue(rs.next());
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM z_audit_chain_anchor")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    @DisplayName("POST 记事件：真落库 + 带 hash + prevHash 链接")
    void recordPersistsWithHashChain() throws Exception {
        ResponseEntity<String> r1 = post("/z-team/api/audit/events", """
                {"traceId":"T-1","operatorId":"op-1","tenantId":"t-9","action":"EXPORT",
                 "result":"SUCCESS","targetType":"CONTRACT","targetId":"C-1","detail":"导出了一批"}""");
        assertTrue(r1.getStatusCode().is2xxSuccessful(), "状态码：" + r1.getStatusCode());
        JsonNode node = MAPPER.readTree(r1.getBody());
        assertTrue(node.get("ok").asBoolean());
        String hash1 = node.get("event").get("hash").asText();
        assertNotNull(hash1);
        assertEquals(64, hash1.length(), "SHA-256 十六进制应 64 位");

        ResponseEntity<String> r2 = post("/z-team/api/audit/events", """
                {"traceId":"T-1","operatorId":"op-1","tenantId":"t-9","action":"VIEW",
                 "result":"SUCCESS","targetType":"CONTRACT","targetId":"C-1","detail":"看了一眼"}""");
        JsonNode node2 = MAPPER.readTree(r2.getBody());
        String hash2 = node2.get("event").get("hash").asText();
        String prev2 = node2.get("event").get("hash").asText();
        assertFalse(hash1.equals(hash2), "两条事件 hash 不应相同");

        // 库里查 prevHash 链
        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT prev_hash, hash FROM z_audit_event ORDER BY id ASC")) {
                assertTrue(rs.next());
                String firstPrev = rs.getString(1);
                String firstHash = rs.getString(2);
                assertEquals(AuditEventGenesis.GENESIS, firstPrev, "首条 prevHash 应为链头哨兵");
                assertTrue(rs.next());
                assertEquals(firstHash, rs.getString(1), "第二条 prevHash 应等于第一条 hash");
            }
        }
        assertNotNull(prev2);
    }

    @Test
    @DisplayName("GET 查事件：按操作人 / 动作过滤")
    void listWithFilters() throws Exception {
        post("/z-team/api/audit/events", """
                {"operatorId":"op-A","tenantId":"t-9","action":"EXPORT","result":"SUCCESS","detail":"a"}""");
        post("/z-team/api/audit/events", """
                {"operatorId":"op-B","tenantId":"t-9","action":"VIEW","result":"SUCCESS","detail":"b"}""");

        ResponseEntity<String> byOperator = get("/z-team/api/audit/events?operatorId=op-A");
        JsonNode n1 = MAPPER.readTree(byOperator.getBody());
        assertEquals(1, n1.get("total").asInt(), "按操作人过滤应只剩 1 条");

        ResponseEntity<String> byAction = get("/z-team/api/audit/events?action=VIEW");
        JsonNode n2 = MAPPER.readTree(byAction.getBody());
        assertEquals(1, n2.get("total").asInt(), "按动作过滤应只剩 1 条");
    }

    @Test
    @DisplayName("GET 校验链：正常 ⇒ 完整；改库 ⇒ 检出链断")
    void chainVerifyDetectsTampering() throws Exception {
        post("/z-team/api/audit/events", """
                {"operatorId":"op-A","tenantId":"t-9","action":"EXPORT","result":"SUCCESS","detail":"原始内容"}""");
        post("/z-team/api/audit/events", """
                {"operatorId":"op-A","tenantId":"t-9","action":"VIEW","result":"SUCCESS","detail":"第二条"}""");

        ResponseEntity<String> verify = get("/z-team/api/audit/chain/verify");
        JsonNode v = MAPPER.readTree(verify.getBody());
        assertTrue(v.get("intact").asBoolean(), "未篡改时链应完整：" + v.get("message").asText());

        // 绕过应用直接改库（模拟有人篡改台账）
        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE z_audit_event SET detail = '被偷偷改过' "
                    + "WHERE detail = '原始内容'");
        }

        ResponseEntity<String> verify2 = get("/z-team/api/audit/chain/verify");
        JsonNode v2 = MAPPER.readTree(verify2.getBody());
        assertFalse(v2.get("intact").asBoolean(), "篡改必须被检出");
    }

    @Test
    @DisplayName("GET 链尾：返回最新一条")
    void latestReturnsNewest() throws Exception {
        post("/z-team/api/audit/events", """
                {"operatorId":"op-A","tenantId":"t-9","action":"VIEW","result":"SUCCESS","detail":"第一条"}""");
        post("/z-team/api/audit/events", """
                {"operatorId":"op-A","tenantId":"t-9","action":"EXPORT","result":"SUCCESS","detail":"第二条"}""");

        ResponseEntity<String> r = get("/z-team/api/audit/chain/latest");
        JsonNode n = MAPPER.readTree(r.getBody());
        assertEquals("第二条", n.get("event").get("detail").asText());
    }

    // ---------------- 辅助 ----------------

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }

    /** 链头哨兵（与 core 的常量一致，此处独立写一份避免跨模块依赖）。 */
    static final class AuditEventGenesis {
        static final String GENESIS =
                "0000000000000000000000000000000000000000000000000000000000000000";

        private AuditEventGenesis() {
        }
    }
}
