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
        AuditOutcome outcome = recordWithOutcome(event);
        return outcome == null ? null : outcome.getEvent();
    }

    /**
     * ★ 记一条审计事件，**并返回落库结果**（M2 #22 修掉的真 bug）。
     *
     * <p><b>为什么需要这个方法</b>：{@link #record(AuditEvent)} 把出口异常**吞掉**
     * （审计不拖垮业务是对的），但它**返回的 event 非 null** ⇒ 调用方以为「写成了」。
     * 于是编排层的水印溯源行在**台账炸了**时会报「已留痕」⇒
     * <b>合规缺口被静默掩盖</b>（「打了水印却没留痕」没人知道）。
     *
     * <p>本方法把「事件」与「有没有出口真的收下」一起返回 ⇒
     * 调用方能如实标出缺口。
     *
     * @param event 事件（可空）
     * @return 落库结果（事件为空 ⇒ null）
     */
    public AuditOutcome recordWithOutcome(AuditEvent event) {
        if (event == null) {
            return null;
        }
        String prevTail;
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
            prevTail = chainTailHash;
            event.setPrevHash(prevTail);
            event.setHash(event.computeHash(prevTail));
            chainTailHash = event.getHash();
        }
        boolean accepted = deliver(event);
        // ★ 「无出口」与「出口拒收」必须区分开（见下面注释）：
        //   - 无出口  ⇒ 只算链不投递的纯内存模式，链尾**照常推进**；
        //   - 出口拒收 ⇒ 事件没进库，链尾**必须回退**。
        // 早期版本没区分，导致纯内存模式下链尾永远不推进（链校验只能看到 1 条），
        // 而有出口时被拒收又推进（误报链断）。
        if (!accepted && !sinks.isEmpty()) {
            // ★ **没写进去 ⇒ 链尾不能推进**（M2 #22 修掉的真 bug）。
            //
            // 现象：出口因幂等（eventId 重复）或落库失败而**拒绝**了这条事件，
            //   但链尾已经在上面推进到了本条的 hash ⇒ 下一条新事件的 prevHash
            //   指向一条**库里根本不存在的记录** ⇒ 链校验在新旧交界处报「哈希不匹配」
            //   ⇒ **误报链断**（而台账其实没问题）。
            //
            // 为什么会踩到：幂等提交是正常业务动作（同一份内容被交两次），
            //   而幂等恰恰意味着「不产生新行」⇒ 链尾必须回退。
            //
            // 修法：链尾回退到本条的 prevHash；若查询出口可用，再从库里捞真实链尾接上
            //   （库里那条「同 eventId 的旧记录」才是真正的链尾）。
            synchronized (this) {
                chainTailHash = prevTail;
            }
            resyncChainTail();
        }
        return new AuditOutcome(event, accepted);
    }

    /**
     * ★ 从库里重新对一次链尾（**写失败 / 幂等命中后用**）。
     *
     * <p>为什么需要：链尾哈希是内存变量，与库里实际状态可能已经不一致
     * （写失败、幂等命中、别的进程写过）。这时候从库里捞最新的那条接上，
     * 保证下一条事件的 {@code prevHash} 指向**真实存在**的记录。
     *
     * <p>⚠ 捞不到就**保持当前链尾不动**（不要退化成链头哨兵 ——
     * 那会让链从中间断开）。
     */
    private void resyncChainTail() {
        if (query == null) {
            return;
        }
        try {
            AuditEvent tail = query.latest(null);
            if (tail != null && tail.getHash() != null && !tail.getHash().isEmpty()) {
                synchronized (this) {
                    chainTailHash = tail.getHash();
                }
            }
        } catch (RuntimeException e) {
            // 对不上就保持原链尾（不外抛：审计不拖垮业务）
            System.err.println("[z-audit] 链尾对账失败（保持原链尾）：" + e.getMessage());
        }
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
     * ★ 从库里的链尾**重载链尾哈希**（M2 #22 —— 重启后链不断）。
     *
     * <p><b>为什么必须有这个方法</b>：链尾哈希是本进程内存里的一个变量。
     * 进程重启后它是 {@link AuditEvent#GENESIS_HASH} ⇒ 下一条新事件的
     * {@code prevHash} 变成链头哨兵 ⇒ <b>链分叉了</b>：
     * <ul>
     *   <li>库里旧链的末条 hash 是 {@code X}；</li>
     *   <li>重启后新事件 prevHash = 全零 ⇒ 校验时「上一条对不上」⇒ 误报链断；</li>
     *   <li>更糟：篡改者只要看到新事件 prevHash 是全零，就知道「这里重启过」，
     *       可以在这里接一条伪造事件而不触发链校验失败。</li>
     * </ul>
     * ⇒ 启动时必须从库里把链尾捞回来接上。
     *
     * <p><b>⚠️ 这也正是「哈希链必须单写者」的由来</b>：若多个服务进程同时往
     * 同一张表写，各自维护自己的链尾 ⇒ 链必然分叉。
     * 正确的部署口径是：**z-audit 是 {@code z_audit_event} 表的唯一写者**，
     * 其他域（z-watermark / z-biz-security）通过 HTTP 把事件交给它写。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @return 重载后的链尾哈希；库里没记录返回链头哨兵
     */
    public String reloadChainTail(String tenantId) {
        if (query == null) {
            return chainTailHash;
        }
        AuditEvent tail = query.latest(tenantId);
        if (tail == null || tail.getHash() == null || tail.getHash().isEmpty()) {
            return chainTailHash;
        }
        synchronized (this) {
            chainTailHash = tail.getHash();
        }
        return chainTailHash;
    }

    /**
     * 当前链尾哈希（诊断用）。
     *
     * @return 链尾哈希
     */
    public String currentChainTail() {
        synchronized (this) {
            return chainTailHash;
        }
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
     * <p><b>★ 返回值语义</b>：true = **至少一个出口真的收下了**；
     * false = 全部出口都拒了（幂等命中 / 落库失败）。
     * 调用方据此决定**链尾要不要回退**（见 {@link #record}）。
     *
     * @param event 事件
     * @return 是否被至少一个出口接收
     */
    private boolean deliver(AuditEvent event) {
        boolean accepted = false;
        for (AuditSink sink : sinks) {
            try {
                if (sink.accept(event)) {
                    accepted = true;
                }
            } catch (RuntimeException e) {
                // 刻意吞掉：审计是辅链路，业务是主链路。
                // 出口实现自己该记日志的记日志，这里只保证不外抛。
                System.err.println("[z-audit] 出口 " + sink.name() + " 投递失败：" + e.getMessage());
            }
        }
        return accepted;
    }

    /**
     * ★ 落库结果（事件 + 有没有出口真的收下）。
     *
     * <p>存在的理由：{@link #record(AuditEvent)} 吞掉出口异常（对），
     * 但返回非 null 的事件（**错**）⇒ 调用方误以为「写成了」。
     * 本类把「有没有收下」摆到类型层面 ⇒ 合规缺口能如实上报。
     *
     * @author zifang
     */
    public static class AuditOutcome {

        /** 事件（已接链、已算 hash）。 */
        private final AuditEvent event;
        /** 是否被至少一个出口真的收下（false = 幂等命中 / 落库失败 / 无出口）。 */
        private final boolean accepted;

        public AuditOutcome(AuditEvent event, boolean accepted) {
            this.event = event;
            this.accepted = accepted;
        }

        public AuditEvent getEvent() {
            return event;
        }

        /**
         * 是否真的落库了。
         *
         * <p>⚠️ **无出口时也是 false** —— 纯内存的「只算链」模式本就没落库，
         * 别把它当成「落库失败」。
         *
         * @return true = 至少一个出口收下了
         */
        public boolean isAccepted() {
            return accepted;
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
