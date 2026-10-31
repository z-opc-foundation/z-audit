package com.zifang.z.audit.core.service;

import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.ChainAnchor;
import com.zifang.z.audit.core.spi.AuditQuery;
import com.zifang.z.audit.core.spi.ChainAnchorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 链锚点服务 —— **把「尾部截断不可检出」这个已知边界补上**。
 *
 * <p><b>要解决的问题</b>：哈希链能查出「中间被改 / 中间被删」，
 * 但查不出「最后几条被删」—— 没有后继记录能证明「本该有第 N 条」。
 * 本服务提供**外部锚定**机制：
 * <ol>
 *   <li>{@link #anchor}：在链上某个时刻打一个**锚点**（记链尾 hash + 累计条数 + 指纹）；</li>
 *   <li>{@link #verify}：拿锚点回查实际链 ⇒ 尾部被截断可检出。</li>
 * </ol>
 *
 * <p><b>校验怎么判</b>（{@link #verify}）：
 * <ul>
 *   <li>锚点**指纹不自洽** ⇒ 锚点行自身被改过 ⇒ <b>检出</b>（锚点不可信）；</li>
 *   <li>实际链条数 < 锚点条数 ⇒ <b>尾部被截断</b>（链比锚点还短）⇒ 检出；</li>
 *   <li>实际链条数 == 锚点条数 但链尾 hash 不等 ⇒ 链被改过 ⇒ 检出；</li>
 *   <li>实际链条数 > 锚点条数 ⇒ 锚点之后又写了新事件（正常），
 *       此时校验**从锚点位置往后**的链是否连续 ⇒ 断口即检出。</li>
 * </ul>
 *
 * <p><b>为什么锚点表必须独立于链</b>：锚点若也是链上的一条事件，
 * 删台账的人把锚点那条一起删了就白锚了。本实现把锚点存在**独立的锚点表**里
 * （{@code z_audit_chain_anchor}）⇒ 删台账的人未必删得掉它。
 *
 * <p><b>没有锚点存储时</b>：{@link #anchor} 返回 false、{@link #verify} 返回
 * 「未配置锚点存储，无法验证」的**不可判定**结果（**不是「通过」**）——
 * 宁可说「查不了」，不可说「没问题」。
 *
 * @author zifang
 */
public class ChainAnchorService {

    /** 锚点存储（可空 ⇒ 无锚定能力）。 */
    private final ChainAnchorStore anchorStore;

    /** 查询出口（可空 ⇒ 无法取实际链）。 */
    private AuditQuery query;

    /** 打锚时用的默认锚点键前缀。 */
    private String defaultAnchorKeyPrefix = "auto";

    /**
     * 构造。
     *
     * @param anchorStore 锚点存储（可空 ⇒ 无锚定能力）
     */
    public ChainAnchorService(ChainAnchorStore anchorStore) {
        this.anchorStore = anchorStore;
    }

    /**
     * 设查询出口。
     *
     * @param query 查询（可空）
     */
    public void setQuery(AuditQuery query) {
        this.query = query;
    }

    /**
     * 设默认锚点键前缀。
     *
     * @param prefix 前缀（可空 ⇒ 用默认 {@code auto}）
     */
    public void setDefaultAnchorKeyPrefix(String prefix) {
        if (prefix != null && !prefix.trim().isEmpty()) {
            this.defaultAnchorKeyPrefix = prefix.trim();
        }
    }

    /**
     * 在当前链尾**打一个锚点**。
     *
     * <p>锚点记三样：链尾 hash、当前累计条数、锚点时刻。
     * 之后若有人删掉了链尾若干条，{@link #verify} 就能拿这个快照比对出来。
     *
     * @param tenantId  租户（可空 ⇒ 全域）
     * @param anchorKey 锚点键（可空 ⇒ 自动生成：{@code <prefix>-<时刻>}）
     * @return 锚点；失败（无存储 / 无链）返回 null
     */
    public ChainAnchor anchor(String tenantId, String anchorKey) {
        if (anchorStore == null || query == null) {
            return null;
        }
        AuditEvent tail = query.latest(tenantId);
        if (tail == null) {
            // 链是空的 ⇒ 无链尾可锚 ⇒ 返回 null（不是「锚了个空链」）
            return null;
        }
        long count = countEvents(tenantId);
        String key = (anchorKey == null || anchorKey.trim().isEmpty())
                ? defaultAnchorKeyPrefix + "-" + tail.getOccurredAt()
                : anchorKey.trim();
        ChainAnchor a = ChainAnchor.of(tenantId, key, tail.getHash(), count);
        return anchorStore.save(a) ? a : null;
    }

    /**
     * 拿锚点回查实际链 —— **检出尾部截断**。
     *
     * @param tenantId  租户（可空 ⇒ 全域）
     * @param anchorKey 锚点键（可空 ⇒ 用该租户**最新的**锚点）
     * @return 校验结果
     */
    public AnchorVerifyResult verify(String tenantId, String anchorKey) {
        if (anchorStore == null) {
            return AnchorVerifyResult.indeterminate("未配置锚点存储，无法验证尾部截断");
        }
        if (query == null) {
            return AnchorVerifyResult.indeterminate("未配置查询出口，无法验证尾部截断");
        }
        ChainAnchor anchor = resolveAnchor(tenantId, anchorKey);
        if (anchor == null) {
            return AnchorVerifyResult.indeterminate(
                    (anchorKey == null || anchorKey.trim().isEmpty())
                            ? "该租户没有任何锚点，无法验证尾部截断"
                            : "锚点不存在（key=" + anchorKey + "），无法验证尾部截断");
        }
        // ---- 锚点自身是否被改过 ----
        if (!anchor.verifyStoredFingerprint()) {
            return new AnchorVerifyResult(false, anchor, 0, 0,
                    "锚点自身指纹不匹配（锚点行被绕过应用改过）");
        }
        // ---- 实际链现状 ----
        AuditEvent tail = query.latest(tenantId);
        if (tail == null) {
            // 链空了：锚点说链上曾有 count 条 ⇒ 至少尾部被截断了
            return new AnchorVerifyResult(false, anchor, 0, 0,
                    "链已空，但锚点记录链上有 " + anchor.getEventCount() + " 条 ⇒ 尾部被截断");
        }
        long actualCount = countEvents(tenantId);
        if (actualCount < anchor.getEventCount()) {
            return new AnchorVerifyResult(false, anchor, actualCount, anchor.getEventCount(),
                    "尾部被截断：实际链 " + actualCount + " 条 < 锚点记录的 " + anchor.getEventCount() + " 条");
        }
        if (actualCount == anchor.getEventCount()) {
            if (anchor.getChainHash() != null && anchor.getChainHash().equals(tail.getHash())) {
                return new AnchorVerifyResult(true, anchor, actualCount, anchor.getEventCount(),
                        "链完整（条数与链尾 hash 均与锚点一致）");
            }
            return new AnchorVerifyResult(false, anchor, actualCount, anchor.getEventCount(),
                    "条数与锚点一致但链尾 hash 不同 ⇒ 链被改过");
        }
        // 锚点之后又写了新事件 ⇒ 从锚点位置往后验链是否连续
        String tailHash = tail.getHash();
        List<AuditEvent> events = query.range(tenantId, anchor.getAnchoredAt(), Long.MAX_VALUE);
        if (events == null || events.isEmpty()) {
            return new AnchorVerifyResult(false, anchor, actualCount, anchor.getEventCount(),
                    "锚点之后查不到任何事件（但链条数在增加）⇒ 链有断口");
        }
        // 找到锚点位置：链上 hash 等于锚点 chainHash 的那条
        int anchorIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (anchor.getChainHash() != null && anchor.getChainHash().equals(events.get(i).getHash())) {
                anchorIndex = i;
                break;
            }
        }
        if (anchorIndex < 0) {
            return new AnchorVerifyResult(false, anchor, actualCount, anchor.getEventCount(),
                    "锚点之后的事件里找不到锚点位置的记录 ⇒ 链有断口（锚点前段被删）");
        }
        // 从锚点位置往后逐条验链
        for (int i = anchorIndex + 1; i < events.size(); i++) {
            AuditEvent e = events.get(i);
            String expectedPrev = events.get(i - 1).getHash();
            if (!e.verify(expectedPrev)) {
                return new AnchorVerifyResult(false, anchor, actualCount, anchor.getEventCount(),
                        "锚点之后第 " + (i - anchorIndex) + " 条哈希不匹配（eventId=" + e.getEventId() + "）");
            }
        }
        if (!tailHash.equals(tail.getHash())) {
            return new AnchorVerifyResult(false, anchor, actualCount, anchor.getEventCount(), "链尾 hash 不一致");
        }
        return new AnchorVerifyResult(true, anchor, actualCount, anchor.getEventCount(),
                "链完整（锚点之后 " + (actualCount - anchor.getEventCount()) + " 条连续）");
    }

    /**
     * 取锚点（指定键 ⇒ 精确取；未指定 ⇒ 取最新的一个）。
     *
     * @param tenantId  租户
     * @param anchorKey 锚点键（可空 ⇒ 最新）
     * @return 锚点；无返回 null
     */
    public ChainAnchor resolveAnchor(String tenantId, String anchorKey) {
        if (anchorStore == null) {
            return null;
        }
        if (anchorKey != null && !anchorKey.trim().isEmpty()) {
            return anchorStore.load(tenantId, anchorKey.trim());
        }
        List<ChainAnchor> all = anchorStore.list(tenantId);
        if (all == null || all.isEmpty()) {
            return null;
        }
        ChainAnchor latest = all.get(all.size() - 1);
        return latest;
    }

    /**
     * 列出某租户的全部锚点。
     *
     * @param tenantId 租户
     * @return 锚点列表
     */
    public List<ChainAnchor> list(String tenantId) {
        if (anchorStore == null) {
            return new ArrayList<>();
        }
        List<ChainAnchor> all = anchorStore.list(tenantId);
        return all == null ? new ArrayList<>() : all;
    }

    /**
     * 数链上条数（**全量扫描** —— 锚点逻辑不追求性能，审计链通常不大）。
     *
     * @param tenantId 租户
     * @return 条数
     */
    private long countEvents(String tenantId) {
        List<AuditEvent> all = query.range(tenantId, 0L, Long.MAX_VALUE);
        return all == null ? 0L : all.size();
    }

    /**
     * 锚点校验结果。
     */
    public static class AnchorVerifyResult {

        /** 是否通过（false = 检出问题；**「不可判定」也是 false**，见 message）。 */
        private final boolean intact;
        /** 参与校验的锚点（可能为 null）。 */
        private final ChainAnchor anchor;
        /** 实际链条数。 */
        private final long actualCount;
        /** 锚点记录的条数。 */
        private final long anchoredCount;
        /** 说明。 */
        private final String message;
        /** 是否「不可判定」（没锚点 / 没存储 ⇒ 不是「通过」）。 */
        private final boolean indeterminate;

        public AnchorVerifyResult(boolean intact, ChainAnchor anchor,
                                  long actualCount, long anchoredCount, String message) {
            this(intact, anchor, actualCount, anchoredCount, message, false);
        }

        public AnchorVerifyResult(boolean intact, ChainAnchor anchor,
                                  long actualCount, long anchoredCount,
                                  String message, boolean indeterminate) {
            this.intact = intact;
            this.anchor = anchor;
            this.actualCount = actualCount;
            this.anchoredCount = anchoredCount;
            this.message = message;
            this.indeterminate = indeterminate;
        }

        /** 造一个「不可判定」的结果。 */
        static AnchorVerifyResult indeterminate(String message) {
            return new AnchorVerifyResult(false, null, 0, 0, message, true);
        }

        public boolean isIntact() {
            return intact;
        }

        public ChainAnchor getAnchor() {
            return anchor;
        }

        public long getActualCount() {
            return actualCount;
        }

        public long getAnchoredCount() {
            return anchoredCount;
        }

        public String getMessage() {
            return message;
        }

        /** 是否「不可判定」。 */
        public boolean isIndeterminate() {
            return indeterminate;
        }
    }
}
