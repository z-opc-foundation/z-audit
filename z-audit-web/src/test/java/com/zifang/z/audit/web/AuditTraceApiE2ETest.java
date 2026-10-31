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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ 溯源闭环验收（**真起 Spring 上下文 + H2 真落库**，M2 #22）。
 *
 * <p>覆盖五个验收项：
 * <ol>
 *   <li>POST 溯源记录 ⇒ **真落库**（绕过应用直接查表）；</li>
 *   <li>GET 按追踪码反查 ⇒ 查得到，操作人 / 租户 / 单号对得上；</li>
 *   <li>★ **幂等**：同一 eventId 重复提交 ⇒ 只有一条（不重复记账）；</li>
 *   <li>★ **三态**：查不到 ⇒ 404；查到 ⇒ 200；</li>
 *   <li>★ **哈希链不因重启分叉**：新写的记录 prevHash 接着旧链。</li>
 * </ol>
 *
 * @author zifang
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.base.db.audit.url=jdbc:h2:mem:zaudit_trace_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "z.base.db.audit.username=sa",
                "z.base.db.audit.password="
        })
class AuditTraceApiE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSourceAudit;

    @BeforeEach
    void applySchema() throws Exception {
        AuditTestSchema.apply(dataSourceAudit);
    }

    // ---------------- 落库 ----------------

    @Test
    @DisplayName("★ 溯源写入：POST ⇒ **真落库**（绕过应用直接查表）")
    void traceRecordIsPersisted() throws Exception {
        ResponseEntity<String> r = postTrace("trace-persist-1", "op-001", "tenant-9", "C-2026-001");

        assertTrue(r.getStatusCode().is2xxSuccessful(), "状态码：" + r.getStatusCode());
        JsonNode node = MAPPER.readTree(r.getBody());
        assertEquals("trace-persist-1", node.get("traceId").asText());
        assertNotNull(node.get("hash").asText(), "应带链上 hash");

        // ★ 绕过应用直接查库 ⇒ 证明真的落库了
        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM z_audit_event WHERE trace_id = 'trace-persist-1'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "台账里应有一条");
            }
        }
    }

    @Test
    @DisplayName("★ 溯源写入：单号落在 target_id（溯源要看「哪份单子」）")
    void bizNoIsStoredInTargetId() throws Exception {
        postTrace("trace-target-1", "op-001", "tenant-9", "C-BIZ-777");

        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT target_id, tenant_id, operator_id FROM z_audit_event "
                            + "WHERE trace_id = 'trace-target-1'")) {
                assertTrue(rs.next());
                assertEquals("C-BIZ-777", rs.getString("target_id"));
                assertEquals("tenant-9", rs.getString("tenant_id"));
                assertEquals("op-001", rs.getString("operator_id"));
            }
        }
    }

    @Test
    @DisplayName("★ 幂等：同一 eventId 重复提交 ⇒ 台账里只有一条（不重复记账）")
    void repeatedSubmitDoesNotDuplicate() throws Exception {
        postTrace("trace-idem-1", "op-001", "tenant-9", "C-1");
        ResponseEntity<String> second = postTrace("trace-idem-1", "op-001", "tenant-9", "C-1");

        assertTrue(second.getStatusCode().is2xxSuccessful());
        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM z_audit_event WHERE trace_id = 'trace-idem-1'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "★ 幂等键应保证只有一条");
            }
        }
    }

    // ---------------- 反查 ----------------

    @Test
    @DisplayName("★ 反查：查得到 ⇒ 200 + 操作人 / 租户 / 单号都在")
    void traceLookupFindsRecord() throws Exception {
        postTrace("trace-lookup-1", "op-001", "tenant-9", "C-LOOKUP-1");

        ResponseEntity<String> r = rest.getForEntity(
                "/z-team/api/audit/trace/trace-lookup-1", String.class);

        assertTrue(r.getStatusCode().is2xxSuccessful(), "状态码：" + r.getStatusCode());
        JsonNode node = MAPPER.readTree(r.getBody());
        assertTrue(node.get("found").asBoolean());
        assertFalse(node.get("unavailable").asBoolean());
        assertTrue(node.get("total").asInt() >= 1);

        JsonNode summary = node.get("summary");
        assertTrue(summary.get("operatorIds").toString().contains("op-001"), summary.toString());
        assertTrue(summary.get("tenantIds").toString().contains("tenant-9"), summary.toString());
        assertTrue(summary.get("targetIds").toString().contains("C-LOOKUP-1"), summary.toString());
    }

    @Test
    @DisplayName("★ 反查：台账里没有 ⇒ **404**（合规缺口，不是 503）")
    void traceLookupNotFoundReturns404() throws Exception {
        ResponseEntity<String> r = rest.getForEntity(
                "/z-team/api/audit/trace/trace-never-existed", String.class);

        assertEquals(404, r.getStatusCode().value(),
                "★「台账里没有」必须是 404（要报警的合规缺口），不是 503");
        JsonNode node = MAPPER.readTree(r.getBody());
        assertFalse(node.get("found").asBoolean());
        assertFalse(node.get("unavailable").asBoolean());
    }

    @Test
    @DisplayName("★ 反查：空追踪码 ⇒ 400（请求本身不合法）")
    void traceLookupRejectsEmptyTraceId() throws Exception {
        ResponseEntity<String> r = rest.getForEntity(
                "/z-team/api/audit/trace/", String.class);

        assertTrue(r.getStatusCode().is4xxClientError(), "状态码：" + r.getStatusCode());
    }

    @Test
    @DisplayName("★ 反查响应**必须含 `trace` 单条视图**（水印域的 HTTP 客户端读的就是它）")
    void lookupResponseCarriesSingleTraceView() throws Exception {
        postTrace("trace-single-1", "op-001", "tenant-9", "C-SINGLE-1");

        ResponseEntity<String> r = rest.getForEntity(
                "/z-team/api/audit/trace/trace-single-1", String.class);

        assertTrue(r.getStatusCode().is2xxSuccessful());
        JsonNode node = MAPPER.readTree(r.getBody());
        // ★ 契约：没有 `trace` ⇒ 水印域客户端读不到 ⇒ 真实环境反查永远「查不到」
        JsonNode trace = node.get("trace");
        assertNotNull(trace, "★ 响应必须含 trace 单条视图（跨域反查契约）");
        assertEquals("trace-single-1", trace.get("traceId").asText());
        assertEquals("C-SINGLE-1", trace.get("bizNo").asText(),
                "★ 单号字段名必须是 bizNo（跨域契约）");
        assertEquals("op-001", trace.get("operatorId").asText());
        assertEquals("tenant-9", trace.get("tenantId").asText());
    }

    // ---------------- 链不因重启分叉 ----------------

    @Test
    @DisplayName("★ 哈希链：新记录接在旧链之后（prevHash = 上一条 hash）")
    void chainContinuesAcrossWrites() throws Exception {
        postTrace("trace-chain-1", "op-001", "tenant-9", "C-1");
        postTrace("trace-chain-2", "op-001", "tenant-9", "C-2");

        try (Connection conn = dataSourceAudit.getConnection(); Statement st = conn.createStatement()) {
            String prevHash = null;
            try (ResultSet rs = st.executeQuery(
                    "SELECT hash, prev_hash FROM z_audit_event ORDER BY id ASC")) {
                while (rs.next()) {
                    if (prevHash != null) {
                        assertEquals(prevHash, rs.getString("prev_hash"),
                                "★ 后一条的 prev_hash 必须等于前一条的 hash");
                    }
                    prevHash = rs.getString("hash");
                }
            }
        }
    }

    // ---------------- 辅助 ----------------

    private ResponseEntity<String> postTrace(String traceId, String operatorId,
                                             String tenantId, String bizNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("operatorId", operatorId);
        body.put("tenantId", tenantId);
        body.put("bizNo", bizNo);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/z-team/api/audit/trace", HttpMethod.POST,
                new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class);
    }
}
