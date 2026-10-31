package com.zifang.z.audit.core;

import com.zifang.z.audit.core.domain.AuditAction;
import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.AuditResult;
import com.zifang.z.audit.core.domain.RetentionPolicy;
import com.zifang.z.audit.core.service.AuditService;
import com.zifang.z.audit.core.spi.AuditQuery;
import com.zifang.z.audit.core.spi.AuditSink;
import com.zifang.z.audit.core.support.InMemoryAuditStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计引擎单测。
 *
 * @author zifang
 */
class AuditServiceTest {

    private final InMemoryAuditStore store = new InMemoryAuditStore();
    private final AuditService service = new AuditService(store);

    private AuditService serviceWithStore() {
        AuditService s = new AuditService(store);
        s.setQuery(store);
        return s;
    }

    // ---------------- 基本记录 ----------------

    @Test
    @DisplayName("记录：自动补 eventId / 时间 / 动作 / 结果")
    void recordFillsDefaults() {
        AuditEvent event = service.record(new AuditEvent());

        assertNotNull(event);
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0);
        assertEquals(AuditAction.OTHER, event.getAction());
        assertEquals(AuditResult.SUCCESS, event.getResult());
        assertNotNull(event.getHash());
    }

    @Test
    @DisplayName("记录：null 事件 ⇒ 返回 null 不抛异常")
    void recordNullSafe() {
        assertNull(service.record(null));
    }

    @Test
    @DisplayName("记录：事件投递到出口")
    void recordReachesSink() {
        service.record(AuditAction.EXPORT, "op-1", AuditResult.SUCCESS, "T-1", "导出了一批");

        assertEquals(1, store.size());
        assertEquals(AuditAction.EXPORT, store.all().get(0).getAction());
        assertEquals("op-1", store.all().get(0).getOperatorId());
    }

    @Test
    @DisplayName("记录：出口抛异常 ⇒ 不外抛（审计不拖垮业务）")
    void sinkFailureDoesNotPropagate() {
        AuditService s = new AuditService(new AuditSink() {
            @Override
            public boolean accept(AuditEvent event) {
                throw new IllegalStateException("出口挂了");
            }

            @Override
            public String name() {
                return "broken";
            }
        });
        s.addSink(store);

        AuditEvent event = s.record(AuditAction.VIEW, "op-1", AuditResult.SUCCESS, "T-1", "x");

        assertNotNull(event, "业务不该被审计出口拖垮");
        assertNotNull(event.getHash());
        assertEquals(1, store.size(), "另一个出口仍应收到");
    }

    // ---------------- 哈希链 ----------------

    @Test
    @DisplayName("哈希链：首条 prevHash = 链头哨兵")
    void chainStartsAtGenesis() {
        AuditEvent first = service.record(AuditAction.VIEW, "op-1", AuditResult.SUCCESS, "T-1", "a");

        assertEquals(AuditEvent.GENESIS_HASH, first.getPrevHash());
    }

    @Test
    @DisplayName("哈希链：后一条 prevHash = 前一条 hash")
    void chainLinksSequentially() {
        AuditEvent a = service.record(AuditAction.VIEW, "op-1", AuditResult.SUCCESS, "T-1", "a");
        AuditEvent b = service.record(AuditAction.EXPORT, "op-1", AuditResult.SUCCESS, "T-2", "b");

        assertEquals(a.getHash(), b.getPrevHash());
        assertFalse(a.getHash().equals(b.getHash()));
    }

    @Test
    @DisplayName("哈希链：多条连起来校验 ⇒ 完整")
    void chainVerifiesIntact() {
        AuditService s = serviceWithStore();
        for (int i = 0; i < 5; i++) {
            s.record(AuditAction.VIEW, "op-" + i, AuditResult.SUCCESS, "T-" + i, "事件" + i);
        }

        AuditService.ChainVerifyResult r = s.verifyChain(null, 0L, Long.MAX_VALUE);

        assertTrue(r.isIntact(), r.getMessage());
        assertEquals(5, r.getChecked());
    }

    @Test
    @DisplayName("哈希链：篡改内容 ⇒ 检出链断")
    void tamperingDetected() {
        AuditService s = serviceWithStore();
        for (int i = 0; i < 5; i++) {
            s.record(AuditAction.VIEW, "op-" + i, AuditResult.SUCCESS, "T-" + i, "事件" + i);
        }
        // 偷改中间那条的详情（不改 hash）
        AuditEvent victim = store.all().get(2);
        victim.setDetail("被偷偷改过了");

        AuditService.ChainVerifyResult r = s.verifyChain(null, 0L, Long.MAX_VALUE);

        assertFalse(r.isIntact(), "篡改必须被检出");
        assertTrue(r.getMessage().contains("哈希不匹配"), r.getMessage());
        assertEquals(2, r.getChecked(), "应在第 3 条（即 index 2）处发现断点");
    }

    @Test
    @DisplayName("哈希链：删掉中间一条 ⇒ 检出链断")
    void deletionDetected() {
        AuditService s = serviceWithStore();
        for (int i = 0; i < 5; i++) {
            s.record(AuditAction.VIEW, "op-" + i, AuditResult.SUCCESS, "T-" + i, "事件" + i);
        }
        // 删掉中间那条（prevHash 留在后一条里不变 ⇒ 后一条的 prevHash 对不上上一条的 hash）
        String victimId = store.all().get(2).getEventId();
        assertTrue(store.removeByEventId(victimId), "应删掉中间那条");

        AuditService.ChainVerifyResult r = s.verifyChain(null, 0L, Long.MAX_VALUE);

        assertFalse(r.isIntact(), "删除必须被检出");
        assertTrue(r.getMessage().contains("哈希不匹配"), r.getMessage());
    }

    @Test
    @DisplayName("哈希链：删掉**最后一条**查不出来（链尾无后继）—— 已知边界，须靠外部锚定")
    void tailDeletionIsNotDetectable() {
        AuditService s = serviceWithStore();
        for (int i = 0; i < 5; i++) {
            s.record(AuditAction.VIEW, "op-" + i, AuditResult.SUCCESS, "T-" + i, "事件" + i);
        }
        // ⚠ 删掉链尾：后一条不存在 ⇒ 没有任何记录能证明「本该有第 6 条」
        //    ⇒ **纯哈希链查不出尾部截断**。防这个要靠外部锚定
        //    （链头 hash 定期快照到独立存储 / 上链）。
        String tailId = store.all().get(4).getEventId();
        assertTrue(store.removeByEventId(tailId));

        AuditService.ChainVerifyResult r = s.verifyChain(null, 0L, Long.MAX_VALUE);

        assertTrue(r.isIntact(), "尾部截断在纯链内不可检出（已知边界，靠外部锚定防）");
        assertEquals(4, r.getTotal());
    }

    @Test
    @DisplayName("哈希链：空窗口 ⇒ 完整（空链）")
    void emptyChainIsIntact() {
        AuditService s = serviceWithStore();

        AuditService.ChainVerifyResult r = s.verifyChain(null, 0L, Long.MAX_VALUE);

        assertTrue(r.isIntact());
        assertEquals(0, r.getTotal());
    }

    @Test
    @DisplayName("哈希链：无查询出口 ⇒ 校验降级（不抛异常）")
    void verifyWithoutQueryDegrades() {
        AuditService s = new AuditService(store);

        AuditService.ChainVerifyResult r = s.verifyChain(null, 0L, Long.MAX_VALUE);

        assertFalse(r.isIntact());
        assertTrue(r.getMessage().contains("未配置查询出口"));
    }

    @Test
    @DisplayName("哈希：同内容不同 prevHash ⇒ hash 不同（链式绑定生效）")
    void hashDependsOnPrev() {
        AuditEvent e = new AuditEvent();
        e.setEventId("E1");
        e.setAction(AuditAction.VIEW);
        e.setResult(AuditResult.SUCCESS);
        e.setOccurredAt(1000L);

        String h1 = e.computeHash(AuditEvent.GENESIS_HASH);
        String h2 = e.computeHash("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

        assertFalse(h1.equals(h2));
    }

    @Test
    @DisplayName("哈希：verify 自洽性")
    void verifySelfConsistency() {
        AuditEvent e = new AuditEvent();
        e.setEventId("E1");
        e.setAction(AuditAction.EXPORT);
        e.setResult(AuditResult.SUCCESS);
        e.setOccurredAt(2000L);
        e.setPrevHash(AuditEvent.GENESIS_HASH);
        e.setHash(e.computeHash(AuditEvent.GENESIS_HASH));

        assertTrue(e.verify(AuditEvent.GENESIS_HASH));
        assertFalse(e.verify("some-other-hash"));
    }

    // ---------------- 查询 ----------------

    @Test
    @DisplayName("查询：按操作人过滤")
    void listByOperator() {
        AuditService s = serviceWithStore();
        s.record(AuditAction.VIEW, "op-1", AuditResult.SUCCESS, "T-1", "a");
        s.record(AuditAction.VIEW, "op-2", AuditResult.SUCCESS, "T-2", "b");

        AuditQuery.AuditCriteria c = new AuditQuery.AuditCriteria();
        c.setOperatorId("op-1");
        List<AuditEvent> list = s.list(c, 10);

        assertEquals(1, list.size());
        assertEquals("op-1", list.get(0).getOperatorId());
    }

    @Test
    @DisplayName("查询：按动作过滤")
    void listByAction() {
        AuditService s = serviceWithStore();
        s.record(AuditAction.EXPORT, "op-1", AuditResult.SUCCESS, "T-1", "a");
        s.record(AuditAction.VIEW, "op-1", AuditResult.SUCCESS, "T-2", "b");

        AuditQuery.AuditCriteria c = new AuditQuery.AuditCriteria();
        c.setAction("EXPORT");
        List<AuditEvent> list = s.list(c, 10);

        assertEquals(1, list.size());
        assertEquals(AuditAction.EXPORT, list.get(0).getAction());
    }

    @Test
    @DisplayName("查询：链尾取最新一条")
    void latestReturnsNewest() {
        AuditService s = serviceWithStore();
        s.record(AuditAction.VIEW, "op-1", AuditResult.SUCCESS, "T-1", "a");
        AuditEvent last = s.record(AuditAction.VIEW, "op-1", AuditResult.SUCCESS, "T-2", "b");

        AuditEvent latest = s.latest(null);

        assertNotNull(latest);
        assertEquals(last.getEventId(), latest.getEventId());
    }

    // ---------------- 留存期 ----------------

    @Test
    @DisplayName("留存期：出域动作留 3 年")
    void egressRetentionIsThreeYears() {
        RetentionPolicy policy = new RetentionPolicy();

        assertEquals(1095, policy.retentionDaysOf(AuditAction.EXPORT));
        assertEquals(1095, policy.retentionDaysOf(AuditAction.DOWNLOAD));
        assertEquals(1095, policy.retentionDaysOf(AuditAction.PRINT));
    }

    @Test
    @DisplayName("留存期：日常动作落默认档（180 天）")
    void defaultRetention() {
        RetentionPolicy policy = new RetentionPolicy();

        assertEquals(180, policy.retentionDaysOf(AuditAction.VIEW));
        assertEquals(180, policy.retentionDaysOf(AuditAction.OTHER));
    }

    @Test
    @DisplayName("留存期：过期判定")
    void expiryDetection() {
        RetentionPolicy policy = new RetentionPolicy();
        AuditEvent e = new AuditEvent();
        e.setAction(AuditAction.EXPORT);
        e.setOccurredAt(0L);

        long oneYear = 365L * 24 * 60 * 60 * 1000;
        assertFalse(policy.isExpired(e, oneYear), "一年内不该过期");
        assertTrue(policy.isExpired(e, 4L * oneYear), "四年后该过期");
    }

    @Test
    @DisplayName("留存期：可覆盖某动作的天数")
    void retentionOverridable() {
        RetentionPolicy policy = new RetentionPolicy();
        policy.put(AuditAction.VIEW, 30);

        assertEquals(30, policy.retentionDaysOf(AuditAction.VIEW));
    }

    // ---------------- 枚举 ----------------

    @Test
    @DisplayName("动作解析：大小写不敏感；未知归 OTHER")
    void actionFromCode() {
        assertEquals(AuditAction.EXPORT, AuditAction.fromCode("export"));
        assertEquals(AuditAction.EXPORT, AuditAction.fromCode("  Export  "));
        assertEquals(AuditAction.OTHER, AuditAction.fromCode("不存在"));
        assertEquals(AuditAction.OTHER, AuditAction.fromCode(null));
    }

    @Test
    @DisplayName("动作：出域标记")
    void egressFlag() {
        assertTrue(AuditAction.EXPORT.isEgress());
        assertTrue(AuditAction.PRINT.isEgress());
        assertFalse(AuditAction.VIEW.isEgress());
    }

    @Test
    @DisplayName("结果解析：未知归 FAILURE（宁可当失败不当成功）")
    void resultFromCode() {
        assertEquals(AuditResult.SUCCESS, AuditResult.fromCode("success"));
        assertEquals(AuditResult.DENIED, AuditResult.fromCode("DENIED"));
        assertEquals(AuditResult.FAILURE, AuditResult.fromCode("???"));
        assertEquals(AuditResult.FAILURE, AuditResult.fromCode(null));
    }

    /** 查询条件（测试里的简写，避免直接依赖 SPI 内部类名）。 */
}
