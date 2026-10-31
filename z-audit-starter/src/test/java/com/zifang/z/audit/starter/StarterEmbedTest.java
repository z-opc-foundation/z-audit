package com.zifang.z.audit.starter;

import com.example.embedhost.StarterHostTestApplication;
import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.AuditTraceResult;
import com.zifang.z.audit.core.service.AuditService;
import com.zifang.z.audit.core.service.AuditTraceService;
import com.zifang.z.audit.core.service.PersistentAuditStore;
import com.zifang.z.audit.starter.config.AuditChainTailLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * z-audit starter <b>嵌入式装配</b>验收（M2 #23）。
 *
 * <p>宿主模拟应用放 {@code com.example.embedhost} 无关包树 ⇒ 组件扫描不可能兜底，
 * 装配只能走 {@code AutoConfiguration.imports}。本仓 starter 是**自足版**
 * （引擎装配 + 本域数据源 + {@code @MapperScan} 同生共死）⇒ 嵌入验收必须真落库、可反查，
 * 不是只断言 bean 存在。
 *
 * @author zifang
 */
@SpringBootTest(
        classes = StarterHostTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "z.base.db.audit.url=jdbc:h2:mem:zaudit_starter_embed;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "z.base.db.audit.username=sa",
                "z.base.db.audit.password="
        })
class StarterEmbedTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditTraceService auditTraceService;

    @Autowired
    private PersistentAuditStore persistentAuditStore;

    @Autowired
    private AuditChainTailLoader auditChainTailLoader;

    @Autowired
    private DataSource dataSourceAudit;

    @Test
    @DisplayName("★ 宿主只放 starter jar ⇒ 引擎全家（含数据源）自动装配齐备")
    void hostEmbeddingStarterGetsFullEngine() {
        assertNotNull(auditService, "★ 审计门面必须仅靠 AutoConfiguration.imports 装配出来");
        assertNotNull(auditTraceService, "★ 溯源反查服务必须自动装配");
        assertNotNull(persistentAuditStore, "★ 落库存储必须自动装配（Mapper 注入不能哑火）");
        assertNotNull(auditChainTailLoader, "★ 链尾重载器必须自动装配（重启后链不能分叉）");
        assertNotNull(dataSourceAudit, "★ 本域数据源必须自动装配（starter 自足带走）");
    }

    @Test
    @DisplayName("★ 嵌入的引擎真能落库 + 按追踪码反查（不是空壳）")
    void embeddedEngineReallyPersistsAndLooksUp() throws Exception {
        AuditTestSchema.apply(dataSourceAudit);

        AuditEvent event = new AuditEvent();
        event.setTraceId("trace-abc-123");
        event.setOperatorId("op-001");
        AuditEvent stored = auditService.record(event);
        assertNotNull(stored, "★ 记账不能返回 null");
        assertNotNull(stored.getHash(), "★ 落库事件必须带链哈希（哈希链不是摆设）");

        AuditTraceResult result = auditTraceService.lookup("trace-abc-123");
        assertTrue(result.isFound(), "★ 刚记的事件必须能按追踪码反查到，实际：" + result.getMessage());
        assertEquals("trace-abc-123", result.getEvents().get(0).getTraceId(),
                "★ 反查回来的追踪码必须一致");
    }
}
