package com.zifang.z.audit.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希链**锚点**（防尾部截断）。
 *
 * <p><b>要解决的问题</b>：哈希链能查出「中间被改 / 中间被删」，
 * 但查不出「最后几条被删」—— 因为没有后继记录能证明「本该有第 N 条」。
 * 这是哈希链的固有边界，<b>不是 bug</b>。解决办法就是**外部锚定</b>：
 * 定期把链上某个已知点（某个 eventId + 它的 hash + 当时的条数）快照到
 * <b>链外</b>的存储里（这里是 {@code z_audit_chain_anchor} 表），
 * 之后拿快照回查实际链 ⇒ 尾部被截断能被发现。
 *
 * <p><b>为什么锚点必须独立于链</b>：如果锚点自己也在链上（作为一条审计事件），
 * 那删台账的人把锚点那条一起删了就白锚了 ⇒ 锚点必须存在**独立存储**里，
 * 删台账的人未必知道/删得掉它。这就是「外部锚定」四个字的含义。
 *
 * <p><b>怎么用它检出尾部截断</b>：
 * <ol>
 *   <li>锚点记着某时刻的 {@code eventCount}（链上累计条数）与 {@code chainHash}（当时链尾 hash）；</li>
 *   <li>校验时取实际链尾，与锚点比对：
 *       <ul>
 *         <li>实际条数 < 锚点条数 ⇒ <b>尾部被截断</b>（链比锚点还短）；</li>
 *         <li>实际条数 == 锚点条数 但 hash 不同 ⇒ 链被改过；</li>
 *         <li>实际条数 > 锚点条数 ⇒ 正常（锚点之后又写了新事件），
 *             此时从锚点位置往后验链，确认锚点之后没断口。</li>
 *       </ul></li>
 * </ol>
 *
 * <p><b>锚点键（anchorKey）**：同一租户下可存多个锚点（如每日一个）。
 * 键相同 ⇒ 覆盖写（幂等 upsert 语义）⇒ 定时任务可以反复跑同一天同一个键。
 *
 * @author zifang
 */
public class ChainAnchor {

    /** 租户（可空 ⇒ 全域）。 */
    private String tenantId;

    /** 锚点键（同租户下唯一；同键覆盖写）。 */
    private String anchorKey;

    /**
     * 锚点处的链尾哈希。
     *
     * <p>快照时链尾那条事件的 hash。校验时拿实际链尾跟它比。
     */
    private String chainHash;

    /** 锚点处的链上累计条数（快照时刻的总条数）。 */
    private long eventCount;

    /** 锚点时刻（epoch 毫秒）。 */
    private long anchoredAt;

    /**
     * 锚点指纹（**从存储读回时填入**，新建时不填）。
     *
     * <p>作用：读回一个锚点时，用 {@link #computeFingerprint()} 重算一遍，
     * 与存储里的指纹比对 ⇒ <b>锚点行本身被绕过应用改过也能检出</b>。
     * 没有它的话，篡改者只要同时改 {@code chainHash} 与 {@code eventCount}
     * 就能让校验「看起来正常」。
     *
     * <p>⚠️ 这是**领域对象上的瞬时字段**，不参与 {@link #computeFingerprint()} 的计算
     * （否则自己算自己永远相等，校验就成摆设）。
     */
    private String storedFingerprint;

    public ChainAnchor() {
    }

    /**
     * 构造。
     *
     * @param tenantId  租户
     * @param anchorKey 锚点键
     * @param chainHash 链尾哈希
     * @param eventCount 累计条数
     * @param anchoredAt 锚点时刻
     */
    public ChainAnchor(String tenantId, String anchorKey, String chainHash,
                       long eventCount, long anchoredAt) {
        this.tenantId = tenantId;
        this.anchorKey = anchorKey;
        this.chainHash = chainHash;
        this.eventCount = eventCount;
        this.anchoredAt = anchoredAt;
    }

    /**
     * 造一个锚点（**当前时刻**）。
     *
     * @param tenantId   租户
     * @param anchorKey  锚点键
     * @param chainHash  链尾哈希
     * @param eventCount 累计条数
     * @return 锚点
     */
    public static ChainAnchor of(String tenantId, String anchorKey, String chainHash, long eventCount) {
        return new ChainAnchor(tenantId, anchorKey, chainHash, eventCount, System.currentTimeMillis());
    }

    /**
     * 算锚点自身的指纹（**锚点的幂等/一致性键**）。
     *
     * <p>为什么要指纹：锚点行本身也可能被改（改 chainHash 或 eventCount ⇒
     * 校验时会被骗过）。用指纹把「锚点内容」绑成一个不可分割的整体，
     * 校验时同时验指纹 ⇒ 改一个字段就露馅。
     *
     * <p>⚠️ 这**不是**密码学签名（同 hash 链一样，只是「改了能发现」，
     * 不是「改不了」）—— 防的是绕过应用直接改库，不是防有库写权限的攻击者。
     *
     * @return 指纹（SHA-256 十六进制）
     */
    public String computeFingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(nvl(tenantId)).append('\u0001');
        sb.append(nvl(anchorKey)).append('\u0001');
        sb.append(nvl(chainHash)).append('\u0001');
        sb.append(eventCount).append('\u0001');
        sb.append(anchoredAt);
        return sha256(sb.toString());
    }

    /**
     * 校验锚点自身的指纹是否与内容自洽。
     *
     * @param expectedFingerprint 期望指纹
     * @return true = 自洽（内容没被改过）
     */
    public boolean verifyFingerprint(String expectedFingerprint) {
        return expectedFingerprint != null && expectedFingerprint.equals(computeFingerprint());
    }

    /**
     * 校验锚点自身的指纹是否与**读回时存下的**指纹一致。
     *
     * <p>用于「从存储读回 ⇒ 验指纹」这条路径：读回时把存储里的指纹存进
     * {@link #storedFingerprint}，再调本方法比对。
     *
     * @return true = 自洽；**没有存储指纹时返回 false**（无从验证 ⇒ 不算通过）
     */
    public boolean verifyStoredFingerprint() {
        return verifyFingerprint(storedFingerprint);
    }

    /** SHA-256 十六进制。 */
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用（不可能发生）", e);
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getAnchorKey() {
        return anchorKey;
    }

    public void setAnchorKey(String anchorKey) {
        this.anchorKey = anchorKey;
    }

    public String getChainHash() {
        return chainHash;
    }

    public void setChainHash(String chainHash) {
        this.chainHash = chainHash;
    }

    public long getEventCount() {
        return eventCount;
    }

    public void setEventCount(long eventCount) {
        this.eventCount = eventCount;
    }

    public long getAnchoredAt() {
        return anchoredAt;
    }

    public void setAnchoredAt(long anchoredAt) {
        this.anchoredAt = anchoredAt;
    }

    public String getStoredFingerprint() {
        return storedFingerprint;
    }

    public void setStoredFingerprint(String storedFingerprint) {
        this.storedFingerprint = storedFingerprint;
    }

    @Override
    public String toString() {
        return "ChainAnchor{tenant=" + tenantId + ", key=" + anchorKey
                + ", count=" + eventCount + "}";
    }
}
