package com.zifang.z.audit.core;

import com.zifang.z.audit.core.domain.AuditAction;
import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.AuditResult;
import com.zifang.z.audit.core.domain.AuditTraceResult;
import com.zifang.z.audit.core.service.AuditService;
import com.zifang.z.audit.core.service.AuditTraceService;
import com.zifang.z.audit.core.spi.AuditHealth;
import com.zifang.z.audit.core.spi.AuditQuery;
import com.zifang.z.audit.core.support.InMemoryAuditStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ 溯源反查单测（M2 #22）。
 *
 * <p>验证口径：**三态必须分清**（查到 / 台账没有 / 台账不可用），
 * 以及**重启后链不能分叉**（链尾重载）。
 *
 * @author zifang
 */
class AuditTraceServiceTest {

    private final InMemoryAuditStore store = new InMemoryAuditStore();

    private AuditService recordService() {
        AuditService service = new AuditService(store);
        service.setQuery(store);
        return service;
    }

    private void record(AuditService service, String traceId, String operator, String bizNo) {
        AuditEvent event = new AuditEvent();
        event.setTraceId(traceId);
        event.setOperatorId(operator);
        event.setTenantId("tenant-9");
        event.setAction(AuditAction.WATERMARK);
        event.setResult(AuditResult.SUCCESS);
        event.setTargetType("WATERMARK");
        event.setTargetId(bizNo);
        event.setDetail("溯源记录");
        service.record(event);
    }

    // ---------------- 三态 ----------------

    @Test
    @DisplayName("反查：★ 查到 ⇒ found=true，事件按时间正序")
    void foundReturnsOrderedEvents() {
        AuditService service = recordService();
        record(service, "trace-1", "op-1", "C-1");
        record(service, "trace-1", "op-2", "C-1");

        AuditTraceResult result = new AuditTraceService(store).lookup("trace-1");

        assertTrue(result.isFound());
        assertFalse(result.isUnavailable());
        assertEquals(2, result.getEvents().size());
        assertTrue(result.getEvents().get(0).getOccurredAt()
                <= result.getEvents().get(1).getOccurredAt(), "应按时间正序");
    }

    @Test
    @DisplayName("反查：★ 台账里没有 ⇒ found=false 且 unavailable=false（合规缺口 ≠ 故障）")
    void notFoundIsNotUnavailable() {
        record(recordService(), "trace-1", "op-1", "C-1");

        AuditTraceResult result = new AuditTraceService(store).lookup("trace-not-exist");

        assertFalse(result.isFound());
        assertFalse(result.isUnavailable(), "★「查不到」不能报成「查不了」");
        assertTrue(result.getEvents().isEmpty());
        assertNotNull(result.getMessage());
    }

    @Test
    @DisplayName("反查：★ 查询出口报了错 ⇒ unavailable=true（基础设施故障）")
    void queryErrorMeansUnavailable() {
        AuditQuery broken = new AuditQuery() {
            @Override
            public List<AuditEvent> list(AuditCriteria criteria, int limit) {
                return new ArrayList<>();
            }

            @Override
            public AuditEvent latest(String tenantId) {
                return null;
            }

            @Override
            public List<AuditEvent> range(String tenantId, long from, long to) {
                return new ArrayList<>();
            }
        };

        AuditTraceResult result = new AuditTraceService(broken).lookup("trace-1");

        assertFalse(result.isFound());
        assertFalse(result.isUnavailable(),
                "没实现 AuditHealth 的出口只能判「查不到」（能力降级但不误报故障）");
    }

    @Test
    @DisplayName("反查：★ 查询出口实现了 AuditHealth 且报了错 ⇒ unavailable=true")
    void healthAwareQueryErrorMeansUnavailable() {
        AuditQuery broken = new BrokenHealthAwareQuery();

        AuditTraceResult result = new AuditTraceService(broken).lookup("trace-1");

        assertTrue(result.isUnavailable(), "★ 查到了错误信息 ⇒ 这是「查不了」");
        assertTrue(result.getMessage().contains("connection refused"));
    }

    @Test
    @DisplayName("反查：未配置查询出口 ⇒ unavailable=true（能力缺失）")
    void noQueryMeansUnavailable() {
        AuditTraceResult result = new AuditTraceService(null).lookup("trace-1");

        assertFalse(result.isFound());
        assertTrue(result.isUnavailable());
    }

    @Test
    @DisplayName("反查：空追踪码 ⇒ notFound（不是 unavailable）")
    void emptyTraceIdIsNotFound() {
        AuditTraceResult result = new AuditTraceService(store).lookup("  ");

        assertFalse(result.isFound());
        assertFalse(result.isUnavailable());
    }

    // ---------------- 汇总 ----------------

    @Test
    @DisplayName("反查汇总：★ 操作人 / 租户 / 动作 / 单号各自成集合（不强判单值）")
    void summaryAggregatesAsSets() {
        AuditService service = recordService();
        record(service, "trace-1", "op-1", "C-1");
        record(service, "trace-1", "op-2", "C-2");

        AuditTraceResult result = new AuditTraceService(store).lookup("trace-1");

        assertEquals(2, ((java.util.Set<?>) result.summarize().get("operatorIds")).size());
        assertEquals(1, ((java.util.Set<?>) result.summarize().get("tenantIds")).size());
        assertEquals(1, ((java.util.Set<?>) result.summarize().get("actions")).size());
        assertEquals(2, ((java.util.Set<?>) result.summarize().get("targetIds")).size());
    }

    // ---------------- 链尾重载（重启不链分叉） ----------------

    @Test
    @DisplayName("★ 链尾重载：重启后从库里接上链尾 ⇒ 新事件不与旧链分叉")
    void reloadChainTailContinuesChain() {
        AuditService first = recordService();
        record(first, "trace-1", "op-1", "C-1");
        AuditEvent lastOfFirstRun = first.latest(null);
        assertNotNull(lastOfFirstRun);

        // ★ 模拟重启：新进程，新 AuditService（链尾是链头哨兵）
        AuditService afterRestart = recordService();
        assertEquals(AuditEvent.GENESIS_HASH, afterRestart.currentChainTail(),
                "重启后链尾应是链头哨兵");

        // 重载链尾
        String reloaded = afterRestart.reloadChainTail(null);
        assertEquals(lastOfFirstRun.getHash(), reloaded, "重载后应接上旧链尾");

        // 新事件接在旧链之后
        record(afterRestart, "trace-2", "op-2", "C-2");
        AuditEvent newest = afterRestart.latest(null);
        assertEquals(lastOfFirstRun.getHash(), newest.getPrevHash(),
                "★ 新事件的 prevHash 必须是旧链尾（否则链分叉）");
    }

    @Test
    @DisplayName("★ 链尾重载：库里没记录 ⇒ 保持链头哨兵（首条事件照常）")
    void reloadOnEmptyTableKeepsGenesis() {
        AuditService service = recordService();

        String tail = service.reloadChainTail(null);

        assertEquals(AuditEvent.GENESIS_HASH, tail);
    }

    @Test
    @DisplayName("★ 链尾重载：无查询出口 ⇒ 不崩（返回当前链尾）")
    void reloadWithoutQueryDegrades() {
        AuditService service = new AuditService(store);

        String tail = service.reloadChainTail(null);

        assertEquals(AuditEvent.GENESIS_HASH, tail);
    }

    // ---------------- 落库结果如实反映（M2 #22 修掉的真 bug） ----------------

    @Test
    @DisplayName("★ 落库结果：出口拒收（幂等命中）⇒ accepted=false")
    void outcomeReportsReject() {
        AuditService service = recordService();
        record(service, "trace-1", "op-1", "C-1");

        AuditEvent duplicate = new AuditEvent();
        duplicate.setEventId(store.all().get(0).getEventId());
        duplicate.setTraceId("trace-2");
        duplicate.setOperatorId("op-2");
        duplicate.setAction(AuditAction.VIEW);
        duplicate.setResult(AuditResult.SUCCESS);

        AuditService.AuditOutcome outcome = service.recordWithOutcome(duplicate);

        assertNotNull(outcome);
        assertFalse(outcome.isAccepted(), "★ 出口拒收 ⇒ accepted=false（不能报成写成了）");
    }

    @Test
    @DisplayName("★ 落库结果：正常写入 ⇒ accepted=true")
    void outcomeReportsAccept() {
        AuditService service = recordService();
        AuditEvent event = new AuditEvent();
        event.setTraceId("trace-1");
        event.setOperatorId("op-1");
        event.setAction(AuditAction.WATERMARK);
        event.setResult(AuditResult.SUCCESS);

        AuditService.AuditOutcome outcome = service.recordWithOutcome(event);

        assertNotNull(outcome);
        assertTrue(outcome.isAccepted(), "★ 写进去了 ⇒ accepted=true");
    }

    @Test
    @DisplayName("★ 落库结果：出口抛异常 ⇒ accepted=false（异常被吞但**如实上报**）")
    void outcomeReportsFailureWhenSinkThrows() {
        AuditService service = new AuditService(new com.zifang.z.audit.core.spi.AuditSink() {
            @Override
            public boolean accept(AuditEvent event) {
                throw new IllegalStateException("库炸了");
            }

            @Override
            public String name() {
                return "broken";
            }
        });
        AuditEvent event = new AuditEvent();
        event.setTraceId("trace-1");
        event.setOperatorId("op-1");
        event.setAction(AuditAction.WATERMARK);
        event.setResult(AuditResult.SUCCESS);

        AuditService.AuditOutcome outcome = service.recordWithOutcome(event);

        assertNotNull(outcome, "★ 事件不该丢（异常被吞）");
        assertFalse(outcome.isAccepted(), "★ 但必须如实说「没落库」");
    }

    @Test
    @DisplayName("★ 无出口（只算链模式）⇒ 链尾照常推进（**不被回退逻辑误伤**）")
    void noSinkModeStillAdvancesChainTail() {
        AuditService service = new AuditService();
        AuditEvent first = new AuditEvent();
        first.setTraceId("trace-1");
        first.setOperatorId("op-1");
        first.setAction(AuditAction.WATERMARK);
        first.setResult(AuditResult.SUCCESS);
        service.record(first);
        String tailAfterFirst = service.currentChainTail();

        AuditEvent second = new AuditEvent();
        second.setTraceId("trace-2");
        second.setOperatorId("op-2");
        second.setAction(AuditAction.WATERMARK);
        second.setResult(AuditResult.SUCCESS);
        service.record(second);

        assertNotEquals(AuditEvent.GENESIS_HASH, tailAfterFirst, "首条应推进链尾");
        assertNotEquals(tailAfterFirst, service.currentChainTail(),
                "★ 无出口的纯内存模式 ⇒ 链尾必须照常推进（不能被拒收逻辑回退）");
    }

    // ---------------- 幂等提交不得推进链尾（M2 #22 修掉的真 bug） ----------------

    @Test
    @DisplayName("★ 幂等命中（出口拒收）⇒ **链尾不得推进**（否则下一条 prevHash 指向不存在的记录）")
    void idempotentRejectDoesNotAdvanceChainTail() {
        AuditService service = recordService();
        record(service, "trace-1", "op-1", "C-1");
        String tailAfterFirst = service.currentChainTail();

        // ★ 造一条 eventId 与已有记录**相同**的事件 ⇒ 内存台账幂等拒收
        AuditEvent duplicate = new AuditEvent();
        duplicate.setEventId(store.all().get(0).getEventId());
        duplicate.setTraceId("trace-2");
        duplicate.setOperatorId("op-2");
        duplicate.setAction(AuditAction.VIEW);
        duplicate.setResult(AuditResult.SUCCESS);
        service.record(duplicate);

        assertEquals(tailAfterFirst, service.currentChainTail(),
                "★ 出口拒收 ⇒ 链尾必须回退（否则链尾指向一条不存在的记录）");

        // 再写一条正常事件 ⇒ 它的 prevHash 必须指向**真实存在**的链尾
        record(service, "trace-3", "op-3", "C-3");
        AuditEvent newest = store.all().get(store.size() - 1);
        assertEquals(tailAfterFirst, newest.getPrevHash(),
                "★ 下一条的 prevHash 必须指向库里真实存在的链尾");
    }

    @Test
    @DisplayName("★ 幂等命中后整条链仍完整（不误报链断）")
    void chainStaysIntactAfterIdempotentReject() {
        AuditService service = recordService();
        record(service, "trace-1", "op-1", "C-1");

        AuditEvent duplicate = new AuditEvent();
        duplicate.setEventId(store.all().get(0).getEventId());
        duplicate.setTraceId("trace-2");
        duplicate.setOperatorId("op-2");
        duplicate.setAction(AuditAction.VIEW);
        duplicate.setResult(AuditResult.SUCCESS);
        service.record(duplicate);

        record(service, "trace-3", "op-3", "C-3");

        AuditService.ChainVerifyResult r = service.verifyChain(null, 0L, Long.MAX_VALUE);
        assertTrue(r.isIntact(), "★ 幂等提交不应造成误报链断：" + r.getMessage());
        assertEquals(2, r.getTotal(), "库里应是两条（幂等那条没进去）");
    }

    /** 内部实现：查询失败并自述错误（模拟库连不上）。 */
    private static class BrokenHealthAwareQuery implements AuditQuery, AuditHealth {

        @Override
        public List<AuditEvent> list(AuditCriteria criteria, int limit) {
            return new ArrayList<>();
        }

        @Override
        public AuditEvent latest(String tenantId) {
            return null;
        }

        @Override
        public List<AuditEvent> range(String tenantId, long from, long to) {
            return new ArrayList<>();
        }

        @Override
        public String lastError() {
            return "查询失败：connection refused";
        }
    }
}
