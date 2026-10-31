package com.zifang.z.audit.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计事件（一条留痕）。
 *
 * <p><b>核心设计：哈希链（hash chain）</b>。每条事件带两个哈希字段：
 * <ul>
 *   <li>{@code prevHash}：上一条事件的哈希；</li>
 *   <li>{@code hash}：本条事件内容 + {@code prevHash} 的 SHA-256。</li>
 * </ul>
 * 任何一条被改（时间改了 / 操作人改了 / 内容删了），
 * 其后所有 {@code hash} 都对不上 ⇒ <b>篡改可检出</b>。
 * 这正是「合规留痕」区别于「普通流水」的地方：
 * 普通流水只能证明「记过」，哈希链能证明「没被改过」。
 *
 * <p><b>为什么哈希字段放在事件里而不是另表</b>：链必须**逐条可算**，
 * 放一起才能在写入时同步算好；若放另表则链断（另表可被单独删改）。
 *
 * <p><b>链的起点</b>：第一条事件的 {@code prevHash} = 全零（32 个 0 字符），
 * 表示「链头」。查链时从链头逐条比对。
 *
 * <p><b>已知边界：尾部截断查不出来</b>。哈希链能查出「中间被改 / 被删」，
 * 但查不出「最后几条被删」—— 因为没有后继记录能证明「本该有第 N 条」。
 * 防这个要靠<b>外部锚定</b>：把链头 hash 定期快照到独立存储（如配置中心 / 另一张表），
 * 或把链头 hash 上链（区块链存证）。**这是设计取舍，不是 bug**，
 * 文档与验收脚本里都要认这个口径。
 *
 * @author zifang
 */
public class AuditEvent {

    /** 链头哨兵（首条事件的 prevHash）。 */
    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    /** 时间格式（审计台账的固定口径，避免各处 strftime 不一）。 */
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** 事件唯一 ID（追踪码）。 */
    private String eventId;
    /** 业务追踪 ID（一次请求内多个动作共享同一个 traceId）。 */
    private String traceId;
    /** 操作人 ID。 */
    private String operatorId;
    /** 操作人姓名（留展示用，操作人改名不影响历史归因）。 */
    private String operatorName;
    /** 租户 / 组织。 */
    private String tenantId;
    /** 动作。 */
    private AuditAction action;
    /** 结果。 */
    private AuditResult result;
    /** 操作对象类型（如 {@code CONTRACT}）。 */
    private String targetType;
    /** 操作对象 ID。 */
    private String targetId;
    /** 补充说明。 */
    private String detail;
    /** 客户端 IP。 */
    private String clientIp;
    /** User-Agent。 */
    private String userAgent;
    /** 发生时刻（epoch 毫秒）。 */
    private long occurredAt;
    /** 上一条的哈希。 */
    private String prevHash;
    /** 本条的哈希。 */
    private String hash;

    public AuditEvent() {
    }

    /**
     * 计算本条事件的哈希（**含 prevHash** ⇒ 链式绑定）。
     *
     * <p>参与哈希的字段用**固定顺序 + 明确分隔符**拼接，
     * 避免「字段拼接歧义」（如 {@code a="x|b", c="y"} 与 {@code a="x", b="|y"} 拼出同一串）。
     *
     * @param prevHash 上一条事件的哈希（链头传 {@link #GENESIS_HASH}）
     * @return 本条事件的哈希（64 位十六进制）
     */
    public String computeHash(String prevHash) {
        StringBuilder sb = new StringBuilder();
        sb.append(nvl(eventId)).append('\u0001');
        sb.append(nvl(traceId)).append('\u0001');
        sb.append(nvl(operatorId)).append('\u0001');
        sb.append(nvl(tenantId)).append('\u0001');
        sb.append(action == null ? "" : action.name()).append('\u0001');
        sb.append(result == null ? "" : result.name()).append('\u0001');
        sb.append(nvl(targetType)).append('\u0001');
        sb.append(nvl(targetId)).append('\u0001');
        sb.append(nvl(detail)).append('\u0001');
        sb.append(nvl(clientIp)).append('\u0001');
        sb.append(nvl(userAgent)).append('\u0001');
        sb.append(occurredAt).append('\u0001');
        sb.append(nvl(prevHash));
        return sha256(sb.toString());
    }

    /**
     * 校验本条事件与其 {@code prevHash} 是否自洽。
     *
     * @param expectedPrevHash 期望的上一条哈希
     * @return true = 自洽
     */
    public boolean verify(String expectedPrevHash) {
        return hash != null && hash.equals(computeHash(expectedPrevHash));
    }

    /**
     * 格式化发生时刻（固定口径字符串）。
     *
     * @return 时间串
     */
    public String occurredAtText() {
        return TS_FORMAT.format(Instant.ofEpochMilli(occurredAt));
    }

    /** SHA-256 十六进制。 */
    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明 JRE 残缺 —— 退化用朴素串哈希（口径降级但不崩）
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 空值归一。 */
    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /**
     * 汇总视图（对外查询 / 日志输出用）。
     *
     * @return 键值视图
     */
    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("eventId", eventId);
        view.put("traceId", traceId);
        view.put("operatorId", operatorId);
        view.put("operatorName", operatorName);
        view.put("tenantId", tenantId);
        view.put("action", action == null ? null : action.name());
        view.put("result", result == null ? null : result.name());
        view.put("targetType", targetType);
        view.put("targetId", targetId);
        view.put("detail", detail);
        view.put("clientIp", clientIp);
        view.put("occurredAt", occurredAt);
        view.put("occurredAtText", occurredAtText());
        view.put("hash", hash);
        return view;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public AuditAction getAction() {
        return action;
    }

    public void setAction(AuditAction action) {
        this.action = action;
    }

    public AuditResult getResult() {
        return result;
    }

    public void setResult(AuditResult result) {
        this.result = result;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(long occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    @Override
    public String toString() {
        return "AuditEvent{" + toView() + "}";
    }
}
