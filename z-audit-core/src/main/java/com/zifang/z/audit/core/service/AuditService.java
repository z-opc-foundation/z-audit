package com.zifang.z.audit.core.service;

import com.zifang.z.audit.core.domain.AuditAction;
import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.AuditResult;
import com.zifang.z.audit.core.domain.RetentionPolicy;
import com.zifang.z.audit.core.spi.AuditQuery;
import com.zifang.z.audit.core.spi.AuditSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 审计门面 —— 本仓对外的**唯一主入口**。
 *
 * <p><b>核心职责**：
 * <ol>
 *   <li><b>哈希链写入</b>：每条事件自动接上上一条的 hash（链式绑定）⇒ 篡改可检出；</li>
 *   <li><b>多路出口</b>：可挂多个 {@link AuditSink}（库 + 日志 + MQ），逐个投递；</li>
 *   <li><b>不拖垮主链路</b>：出口抛异常 ⇒ 吞掉记日志，审计失败不影响业务。</li>
 * </ol>
 *
 * <p><b>线程安全**：写链需要「取链尾 ⇒ 算 hash ⇒ 落库」三步原子化，
 * 否则并发下两条事件会拿到同一个 {@code prevHash} ⇒ 链分叉。
 * 本实现用 {@code synchronized} 串行化写入（审计吞吐不是瓶颈，
 * 正确性比并发度重要）。
 *
 * <p><b>没有查询 SPI 时</b>：{@link #latest(String)} 与 {@link #verifyChain} 返回空/null，
 * 链校验能力自动降级（仍可写）。
 *
 * @author zifang
 */
public class AuditService {

    /** 事件出口。 */
    private final List<AuditSink> sinks = new ArrayList<>();

    /** 查询出口（可空 ⇒ 无查询能力）。 */
    private AuditQuery query;

    /** 留存期策略。 */
    private RetentionPolicy retentionPolicy = new RetentionPolicy();

    /** 链尾缓存（避免每次写都查库；查询不可用时也靠它续链）。 */
    private String chainTailHash = AuditEvent.GENESIS_HASH;

    /** 事件 ID 生成器（可替换，便于测试固定 ID）。 */
    private IdGenerator idGenerator = new UuidIdGenerator();

    /** 默认构造（无出口 ⇒ 只算链不投递）。 */
    public AuditService() {
    }

    /**
     * 构造（带出口）。
     *
     * @param sink 出口（可空）
     */
    public AuditService(AuditSink sink) {
        addSink(sink);
    }

    /**
     * 挂一个出口。
     *
     * @param sink 出口（可空 ⇒ 忽略）
     */
    public void addSink(AuditSink sink) {
        if (sink != null) {
            sinks.add(sink);
        }
    }

    /**
     * 设查询出口。
     *
     * @param query 查询（可空 ⇒ 无查询能力）
     */
    public void setQuery(AuditQuery query) {
        this.query = query;
    }

    /**
     * 设留存期策略。
     *
     * @param policy 策略（可空 ⇒ 忽略）
     */
    public void setRetentionPolicy(RetentionPolicy policy) {
        if (policy != null) {
            this.retentionPolicy = policy;
        }
    }

    public RetentionPolicy getRetentionPolicy() {
        return retentionPolicy;
    }

    /**
     * 记一条审计事件（**主入口**）。
     *
     * <p>自动完成：生成 eventId、补时间戳、接链、算 hash、逐出口投递。
     *
     * @param event 事件（**不可为 null**）
     * @return 落好链的事件（带 hash / prevHash）；入参为 null 返回 null
     */
    public AuditEvent record(AuditEvent event) {
        if (event == null) {
            return null;
        }
        synchronized (this) {
            if (event.getEventId() == null || event.getEventId().isEmpty()) {
                event.setEventId(idGenerator.next());
            }
            if (event.getOccurredAt() <= 0) {
                event.setOccurredAt(System.currentTimeMillis());
            }
            if (event.getAction() == null) {
                event.setAction(AuditAction.OTHER);
            }
            if (event.getResult() == null) {
                event.setResult(AuditResult.SUCCESS);
            }
            event.setPrevHash(chainTailHash);
            event.setHash(event.computeHash(chainTailHash));
            chainTailHash = event.getHash();
        }
        deliver(event);
        return event;
    }

    /**
     * 便捷记法：只给最常用的五个字段。
     *
     * @param action   动作
     * @param operator 操作人
     * @param result   结果
     * @param targetId 对象 ID
     * @param detail   说明
     * @return 事件
     */
    public AuditEvent record(AuditAction action, String operator, AuditResult result,
                             String targetId, String detail) {
        AuditEvent event = new AuditEvent();
        event.setAction(action);
        event.setOperatorId(operator);
        event.setResult(result);
        event.setTargetId(targetId);
        event.setDetail(detail);
        return record(event);
    }

    /**
     * 校验指定时间窗内的哈希链是否完整。
     *
     * <p>逐条比对：{@code event.hash == event.computeHash(event.prevHash)}，
     * 且 {@code event.prevHash} 等于上一条的 {@code hash}。
     * 任一条对不上 ⇒ 链断（有人改过或删过）。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @param from     起（epoch 毫秒）
     * @param to       止（epoch 毫秒）
     * @return 校验结果
     */
    public ChainVerifyResult verifyChain(String tenantId, long from, long to) {
        if (query == null) {
            return new ChainVerifyResult(false, 0, 0, "未配置查询出口，无法校验");
        }
        List<AuditEvent> events = query.range(tenantId, from, to);
        if (events == null || events.isEmpty()) {
            return new ChainVerifyResult(true, 0, 0, "窗口内无事件，链完整（空链）");
        }
        int checked = 0;
        for (int i = 0; i < events.size(); i++) {
            AuditEvent e = events.get(i);
            String expectedPrev = (i == 0) ? e.getPrevHash() : events.get(i - 1).getHash();
            if (!e.verify(expectedPrev)) {
                return new ChainVerifyResult(false, checked, events.size(),
                        "第 " + (i + 1) + " 条哈希不匹配（eventId=" + e.getEventId() + "）");
            }
            checked++;
        }
        return new ChainVerifyResult(true, checked, events.size(), "链完整");
    }

    /**
     * 取链尾事件（最新一条）。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @return 链尾事件；无记录返回 null
     */
    public AuditEvent latest(String tenantId) {
        return query == null ? null : query.latest(tenantId);
    }

    /**
     * 按条件查事件。
     *
     * @param criteria 条件
     * @param limit    条数
     * @return 事件列表
     */
    public List<AuditEvent> list(AuditQuery.AuditCriteria criteria, int limit) {
        if (query == null) {
            return Collections.emptyList();
        }
        List<AuditEvent> list = query.list(criteria, limit);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 判断事件是否已过留存期。
     *
     * @param event 事件
     * @param now   当前时刻
     * @return true = 可清理
     */
    public boolean isExpired(AuditEvent event, long now) {
        return retentionPolicy.isExpired(event, now);
    }

    /**
     * 投递到各出口（**吞异常** —— 审计失败不拖垮业务）。
     *
     * @param event 事件
     */
    private void deliver(AuditEvent event) {
        for (AuditSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (RuntimeException e) {
                // 刻意吞掉：审计是辅链路，业务是主链路。
                // 出口实现自己该记日志的记日志，这里只保证不外抛。
                System.err.println("[z-audit] 出口 " + sink.name() + " 投递失败：" + e.getMessage());
            }
        }
    }

    /**
     * 事件 ID 生成器（可替换，便于测试固定 ID）。
     */
    public interface IdGenerator {
        /**
         * 下一个事件 ID。
         *
         * @return ID
         */
        String next();
    }

    /**
     * 默认 ID 生成器（UUID 去横线）。
     */
    public static class UuidIdGenerator implements IdGenerator {
        @Override
        public String next() {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /**
     * 链校验结果。
     */
    public static class ChainVerifyResult {

        /** 是否完整。 */
        private final boolean intact;
        /** 已校验条数。 */
        private final int checked;
        /** 总条数。 */
        private final int total;
        /** 说明。 */
        private final String message;

        public ChainVerifyResult(boolean intact, int checked, int total, String message) {
            this.intact = intact;
            this.checked = checked;
            this.total = total;
            this.message = message;
        }

        public boolean isIntact() {
            return intact;
        }

        public int getChecked() {
            return checked;
        }

        public int getTotal() {
            return total;
        }

        public String getMessage() {
            return message;
        }
    }
}
