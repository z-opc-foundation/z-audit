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
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链锚点端点验收（**真起 Spring 上下文 + H2 真落库**）。
 *
 * <p>核心验收项：**尾部截断能被检出** —— 这是 M1 明确留下的已知边界，
 * M2 用「外部锚定」补上。本测试走**真落库 + 绕过应用直接 DELETE 库**的路子，
 * 模拟真实攻击场景（有人有库写权限，直接删链尾若干条）。
 *
 * @author zifang
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.base.db.audit.url=jdbc:h2:mem:zaudit_anchor_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "z.base.db.audit.username=sa",
                "z.base.db.audit.password="
        })
class ChainAnchorApiE2ETest {

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
    @DisplayName("打锚 + 校验：链未变 ⇒ intact=true")
    void anchorAndVerifyIntact() throws Exception {
        record("t-a", "第一条");
        record("t-a", "第二条");

        ResponseEntity<String> anchor = post("/z-team/api/audit/chain/anchor?tenantId=t-a&anchorKey=k1");
        assertTrue(anchor.getStatusCode().is2xxSuccessful(), "状态码：" + anchor.getStatusCode());
        JsonNode a = MAPPER.readTree(anchor.getBody());
        assertTrue(a.get("ok").asBoolean());
        assertEquals("k1", a.get("anchor").get("anchorKey").asText());
        assertEquals(2, a.get("anchor").get("eventCount").asInt());

        ResponseEntity<String> verify = get("/z-team/api/audit/chain/anchor/verify?tenantId=t-a&anchorKey=k1");
        JsonNode v = MAPPER.readTree(verify.getBody());
        assertTrue(v.get("intact").asBoolean(), "未截断时链应完整：" + v.get("message").asText());
        assertFalse(v.get("indeterminate").asBoolean());
    }

    @Test
    @DisplayName("★ 尾部截断：绕过应用直接删库里的链尾 ⇒ 被检出")
    void tailTruncationDetected() throws Exception {
        record("t-b", "第一条");
        record("t-b", "第二条");
        record("t-b", "第三条");

        post("/z-team/api/audit/chain/anchor?tenantId=t-b&anchorKey=k1");

        // 绕过应用直接删库（模拟有库写权限的人删链尾两条）
        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM z_audit_event WHERE tenant_id = 't-b' "
                    + "AND detail IN ('第二条', '第三条')");
        }

        ResponseEntity<String> verify = get("/z-team/api/audit/chain/anchor/verify?tenantId=t-b&anchorKey=k1");
        JsonNode v = MAPPER.readTree(verify.getBody());
        assertFalse(v.get("intact").asBoolean(), "尾部截断必须被检出");
        assertTrue(v.get("message").asText().contains("截断"),
                "实际消息：" + v.get("message").asText());
        assertTrue(v.get("actualCount").asInt() < v.get("anchoredCount").asInt());
    }

    @Test
    @DisplayName("★ 锚点自身被改：绕过应用改 chainHash/event_count ⇒ 指纹检出")
    void anchorTamperingDetected() throws Exception {
        record("t-c", "第一条");
        post("/z-team/api/audit/chain/anchor?tenantId=t-c&anchorKey=k1");

        // 篡改者同时改 chain_hash 与 event_count，让「条数 + 链尾 hash」看起来正常
        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE z_audit_chain_anchor SET chain_hash = '"
                    + "0".repeat(64) + "', event_count = 999 WHERE anchor_key = 'k1'");
        }

        ResponseEntity<String> verify = get("/z-team/api/audit/chain/anchor/verify?tenantId=t-c&anchorKey=k1");
        JsonNode v = MAPPER.readTree(verify.getBody());
        assertFalse(v.get("intact").asBoolean(), "锚点被篡改必须被检出");
        assertTrue(v.get("message").asText().contains("指纹"),
                "实际消息：" + v.get("message").asText());
    }

    @Test
    @DisplayName("同键覆盖写：重复打同一键 ⇒ 锚点表只有一行")
    void sameKeyOverwrites() throws Exception {
        record("t-d", "第一条");
        post("/z-team/api/audit/chain/anchor?tenantId=t-d&anchorKey=k1");
        record("t-d", "第二条");
        post("/z-team/api/audit/chain/anchor?tenantId=t-d&anchorKey=k1");

        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM z_audit_chain_anchor "
                            + "WHERE anchor_key = 'k1' AND tenant_id = 't-d'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "同键应覆盖写，不堆积");
            }
        }
    }

    @Test
    @DisplayName("列锚点：返回全部锚点")
    void listAnchors() throws Exception {
        record("t-e", "第一条");
        post("/z-team/api/audit/chain/anchor?tenantId=t-e&anchorKey=k1");
        record("t-e", "第二条");
        post("/z-team/api/audit/chain/anchor?tenantId=t-e&anchorKey=k2");

        ResponseEntity<String> r = get("/z-team/api/audit/chain/anchors?tenantId=t-e");
        JsonNode n = MAPPER.readTree(r.getBody());
        assertEquals(2, n.get("total").asInt());
    }

    @Test
    @DisplayName("没有锚点 ⇒ indeterminate=true（不是「通过」）")
    void noAnchorIsIndeterminate() throws Exception {
        record("t-f", "第一条");

        ResponseEntity<String> r = get("/z-team/api/audit/chain/anchor/verify?tenantId=t-f&anchorKey=nope");
        JsonNode n = MAPPER.readTree(r.getBody());
        assertFalse(n.get("intact").asBoolean());
        assertTrue(n.get("indeterminate").asBoolean(), "无锚点应是「不可判定」，不是「通过」");
    }

    @Test
    @DisplayName("锚点表有 fingerprint 列且非空")
    void anchorRowHasFingerprint() throws Exception {
        record("t-g", "第一条");
        post("/z-team/api/audit/chain/anchor?tenantId=t-g&anchorKey=k1");

        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT fingerprint FROM z_audit_chain_anchor "
                            + "WHERE anchor_key = 'k1' AND tenant_id = 't-g'")) {
                assertTrue(rs.next());
                String fp = rs.getString(1);
                assertNotNull(fp);
                assertEquals(64, fp.length(), "指纹应为 SHA-256 十六进制 64 位");
            }
        }
    }

    // ---------------- 辅助 ----------------

    private void record(String tenantId, String detail) {
        ResponseEntity<String> r = post("/z-team/api/audit/events", """
                {"operatorId":"op-A","tenantId":"%s","action":"EXPORT","result":"SUCCESS","detail":"%s"}"""
                .formatted(tenantId, detail));
        assertTrue(r.getStatusCode().is2xxSuccessful(), "记事件失败：" + r.getBody());
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** 无 body 的 POST（打锚用）。 */
    private ResponseEntity<String> post(String path) {
        return rest.exchange(path, HttpMethod.POST, HttpEntity.EMPTY, String.class);
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }
}
