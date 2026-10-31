package com.zifang.z.audit.core;

import com.zifang.z.audit.core.domain.AuditAction;
import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.AuditResult;
import com.zifang.z.audit.core.domain.ChainAnchor;
import com.zifang.z.audit.core.service.AuditService;
import com.zifang.z.audit.core.service.ChainAnchorService;
import com.zifang.z.audit.core.support.InMemoryAuditStore;
import com.zifang.z.audit.core.support.InMemoryChainAnchorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链锚点服务单测 —— **验证「尾部截断可检出」这条边界被补上**。
 *
 * @author zifang
 */
class ChainAnchorServiceTest {

    private final InMemoryAuditStore auditStore = new InMemoryAuditStore();
    private final InMemoryChainAnchorStore anchorStore = new InMemoryChainAnchorStore();

    private ChainAnchorService service() {
        ChainAnchorService s = new ChainAnchorService(anchorStore);
        s.setQuery(auditStore);
        return s;
    }

    private AuditService auditService() {
        AuditService s = new AuditService(auditStore);
        s.setQuery(auditStore);
        return s;
    }

    /** 记一条事件。 */
    private AuditEvent record(AuditService s, String action, String targetId) {
        AuditEvent e = new AuditEvent();
        e.setAction(AuditAction.fromCode(action));
        e.setResult(AuditResult.SUCCESS);
        e.setTargetId(targetId);
        e.setTenantId("t1");
        e.setOperatorId("op1");
        e.setOccurredAt(System.currentTimeMillis());
        return s.record(e);
    }

    // ---------------- 打锚 ----------------

    @Test
    @DisplayName("打锚：记录链尾 hash + 累计条数")
    void anchorRecordsTailAndCount() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        record(audit, "EXPORT", "c2");

        ChainAnchor anchor = service().anchor("t1", "k1");

        assertNotNull(anchor);
        assertEquals("k1", anchor.getAnchorKey());
        assertEquals(2L, anchor.getEventCount());
        assertNotNull(anchor.getChainHash());
        assertEquals(64, anchor.getChainHash().length());
    }

    @Test
    @DisplayName("打锚：链为空 ⇒ 返回 null（不锚空链）")
    void anchorOnEmptyChainReturnsNull() {
        assertNull(service().anchor("t1", "k1"));
    }

    @Test
    @DisplayName("打锚：同键覆盖写（幂等，不堆积）")
    void anchorSameKeyOverwrites() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");

        record(audit, "EXPORT", "c2");
        svc.anchor("t1", "k1");

        List<ChainAnchor> all = anchorStore.all();
        assertEquals(1, all.size());
        assertEquals(2L, all.get(0).getEventCount());
    }

    @Test
    @DisplayName("打锚：未指定键 ⇒ 自动生成键")
    void anchorAutoKey() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");

        ChainAnchor anchor = service().anchor("t1", null);

        assertNotNull(anchor);
        assertNotNull(anchor.getAnchorKey());
        assertFalse(anchor.getAnchorKey().isEmpty());
    }

    // ---------------- 校验：正常路径 ----------------

    @Test
    @DisplayName("校验：打锚后链未变 ⇒ 通过")
    void verifyIntactAfterAnchor() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        record(audit, "EXPORT", "c2");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");

        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", "k1");

        assertTrue(r.isIntact());
        assertFalse(r.isIndeterminate());
        assertEquals(2L, r.getActualCount());
    }

    @Test
    @DisplayName("校验：锚点之后新增事件 ⇒ 仍通过")
    void verifyIntactAfterNewEvents() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");

        record(audit, "EXPORT", "c2");
        record(audit, "EXPORT", "c3");

        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", "k1");

        assertTrue(r.isIntact());
        assertEquals(3L, r.getActualCount());
        assertEquals(1L, r.getAnchoredCount());
    }

    // ---------------- 校验：检出尾部截断 ----------------

    @Test
    @DisplayName("校验：★ 尾部被截断 ⇒ 检出（这是 M2 补上的核心边界）")
    void verifyDetectsTailTruncation() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        record(audit, "EXPORT", "c2");
        record(audit, "EXPORT", "c3");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");

        // 模拟「有人绕过应用直接删掉链尾两条」
        List<AuditEvent> all = auditStore.all();
        auditStore.removeByEventId(all.get(all.size() - 1).getEventId());
        auditStore.removeByEventId(all.get(all.size() - 2).getEventId());

        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", "k1");

        assertFalse(r.isIntact());
        assertFalse(r.isIndeterminate());
        assertTrue(r.getMessage().contains("截断"), "实际消息：" + r.getMessage());
        assertTrue(r.getActualCount() < r.getAnchoredCount());
    }

    @Test
    @DisplayName("校验：链被整条删空 ⇒ 检出截断")
    void verifyDetectsFullWipe() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        record(audit, "EXPORT", "c2");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");

        for (AuditEvent e : auditStore.all()) {
            auditStore.removeByEventId(e.getEventId());
        }

        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", "k1");

        assertFalse(r.isIntact());
        assertTrue(r.getMessage().contains("截断"), "实际消息：" + r.getMessage());
    }

    @Test
    @DisplayName("校验：锚点后某条被改 ⇒ 检出断口")
    void verifyDetectsTamperAfterAnchor() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");

        AuditEvent second = record(audit, "EXPORT", "c2");
        // 绕过应用直接改内容（hash 不变 ⇒ 链必断）
        second.setDetail("被偷偷改了");

        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", "k1");

        assertFalse(r.isIntact());
    }

    // ---------------- 校验：锚点自身被篡改 ----------------

    @Test
    @DisplayName("校验：★ 锚点自身被改过 ⇒ 检出（指纹不匹配）")
    void verifyDetectsAnchorTampering() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");

        // 篡改者同时改 chainHash 与 eventCount，让「条数 + 链尾 hash」看起来正常
        List<ChainAnchor> stored = anchorStore.all();
        ChainAnchor target = stored.get(0);
        target.setChainHash("0".repeat(64));
        target.setEventCount(99L);
        // 直接塞回存储（绕过 save 的指纹重算）
        anchorStore.save(target);
        // save 会重算指纹 ⇒ 篡改被「洗白」了；这里模拟真正的绕过：改存储内部
        tamperStoredAnchor("k1", "1".repeat(64), 99L);

        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", "k1");

        assertFalse(r.isIntact());
        assertTrue(r.getMessage().contains("指纹"), "实际消息：" + r.getMessage());
    }

    /** 直接改内存存储里的锚点（模拟绕过应用改库）。 */
    private void tamperStoredAnchor(String anchorKey, String chainHash, long eventCount) {
        // 通过 remove + save 的等价路径不行（save 会重算指纹）⇒ 用反射改内部 map
        try {
            java.lang.reflect.Field f = InMemoryChainAnchorStore.class.getDeclaredField("anchors");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<String, ChainAnchor> map =
                    (java.util.concurrent.ConcurrentHashMap<String, ChainAnchor>) f.get(anchorStore);
            for (ChainAnchor a : map.values()) {
                if (anchorKey.equals(a.getAnchorKey())) {
                    a.setChainHash(chainHash);
                    a.setEventCount(eventCount);
                    // ⚠️ **故意不动 storedFingerprint** ⇒ 指纹与内容对不上 ⇒ 正是要测的
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("篡改锚点失败", e);
        }
    }

    // ---------------- 不可判定路径 ----------------

    @Test
    @DisplayName("校验：没有锚点 ⇒ 不可判定（**不是「通过」**）")
    void verifyWithoutAnchorIsIndeterminate() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");

        ChainAnchorService.AnchorVerifyResult r = service().verify("t1", "k-not-exist");

        assertFalse(r.isIntact());
        assertTrue(r.isIndeterminate());
        assertTrue(r.getMessage().contains("无法验证"), "实际消息：" + r.getMessage());
    }

    @Test
    @DisplayName("校验：没有锚点存储 ⇒ 不可判定")
    void verifyWithoutStoreIsIndeterminate() {
        ChainAnchorService svc = new ChainAnchorService(null);
        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", "k1");

        assertFalse(r.isIntact());
        assertTrue(r.isIndeterminate());
    }

    @Test
    @DisplayName("校验：没有查询出口 ⇒ 不可判定")
    void verifyWithoutQueryIsIndeterminate() {
        ChainAnchorService svc = new ChainAnchorService(anchorStore);
        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", "k1");

        assertFalse(r.isIntact());
        assertTrue(r.isIndeterminate());
    }

    @Test
    @DisplayName("打锚：没有锚点存储 ⇒ 返回 null")
    void anchorWithoutStoreReturnsNull() {
        assertNull(new ChainAnchorService(null).anchor("t1", "k1"));
    }

    // ---------------- 指纹自洽 ----------------

    @Test
    @DisplayName("指纹：内容不变 ⇒ 指纹不变；改一个字段 ⇒ 指纹变")
    void fingerprintBindsContent() {
        ChainAnchor a = ChainAnchor.of("t1", "k1", "hash1", 10L);
        String fp = a.computeFingerprint();

        assertTrue(a.verifyFingerprint(fp));

        a.setEventCount(11L);
        assertFalse(a.verifyFingerprint(fp));
    }

    @Test
    @DisplayName("指纹：没有存储指纹时校验 ⇒ false（无从验证不算通过）")
    void verifyFingerprintWithoutStoredIsFalse() {
        ChainAnchor a = ChainAnchor.of("t1", "k1", "hash1", 10L);

        assertFalse(a.verifyStoredFingerprint());
    }

    @Test
    @DisplayName("列锚点：按锚点时刻正序")
    void listAnchorsSorted() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");
        record(audit, "EXPORT", "c2");
        svc.anchor("t1", "k2");

        List<ChainAnchor> all = svc.list("t1");

        assertEquals(2, all.size());
        assertTrue(all.get(0).getAnchoredAt() <= all.get(1).getAnchoredAt());
    }

    @Test
    @DisplayName("校验：未指定锚点键 ⇒ 用最新的那个")
    void verifyUsesLatestAnchorWhenKeyOmitted() {
        AuditService audit = auditService();
        record(audit, "EXPORT", "c1");
        ChainAnchorService svc = service();
        svc.anchor("t1", "k1");
        record(audit, "EXPORT", "c2");
        svc.anchor("t1", "k2");

        ChainAnchorService.AnchorVerifyResult r = svc.verify("t1", null);

        assertTrue(r.isIntact());
        assertNotNull(r.getAnchor());
        assertEquals("k2", r.getAnchor().getAnchorKey());
    }
}
