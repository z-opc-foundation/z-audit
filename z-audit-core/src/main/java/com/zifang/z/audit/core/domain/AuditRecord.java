package com.zifang.z.audit.core.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 审计事件落库实体（表 {@code z_audit_event}）。
 *
 * <p><b>实体与领域对象分离</b>：{@link AuditEvent} 是领域模型（带哈希链逻辑），
 * 本类是持久化载体（只管字段映射）⇒ 领域模型不被 ORM 注解污染。
 *
 * @author zifang
 */
@TableName("z_audit_event")
public class AuditRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 事件 ID（追踪码，**唯一**）。 */
    private String eventId;
    /** 业务追踪 ID。 */
    private String traceId;
    /** 操作人 ID。 */
    private String operatorId;
    /** 操作人姓名。 */
    private String operatorName;
    /** 租户。 */
    private String tenantId;
    /** 动作。 */
    private String action;
    /** 结果。 */
    private String result;
    /** 操作对象类型。 */
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
    private Long occurredAt;
    /** 上一条的哈希。 */
    private String prevHash;
    /** 本条的哈希。 */
    private String hash;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
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

    public Long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Long occurredAt) {
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

    /**
     * 领域对象 → 落库实体。
     *
     * @param event 领域事件
     * @return 实体
     */
    public static AuditRecord from(AuditEvent event) {
        AuditRecord r = new AuditRecord();
        r.setEventId(event.getEventId());
        r.setTraceId(event.getTraceId());
        r.setOperatorId(event.getOperatorId());
        r.setOperatorName(event.getOperatorName());
        r.setTenantId(event.getTenantId());
        r.setAction(event.getAction() == null ? null : event.getAction().name());
        r.setResult(event.getResult() == null ? null : event.getResult().name());
        r.setTargetType(event.getTargetType());
        r.setTargetId(event.getTargetId());
        r.setDetail(event.getDetail());
        r.setClientIp(event.getClientIp());
        r.setUserAgent(event.getUserAgent());
        r.setOccurredAt(event.getOccurredAt());
        r.setPrevHash(event.getPrevHash());
        r.setHash(event.getHash());
        return r;
    }

    /**
     * 落库实体 → 领域对象（**保留原 hash，不重算**）。
     *
     * @param record 实体
     * @return 领域事件
     */
    public static AuditEvent toDomain(AuditRecord record) {
        AuditEvent e = new AuditEvent();
        e.setEventId(record.getEventId());
        e.setTraceId(record.getTraceId());
        e.setOperatorId(record.getOperatorId());
        e.setOperatorName(record.getOperatorName());
        e.setTenantId(record.getTenantId());
        e.setAction(AuditAction.fromCode(record.getAction()));
        e.setResult(AuditResult.fromCode(record.getResult()));
        e.setTargetType(record.getTargetType());
        e.setTargetId(record.getTargetId());
        e.setDetail(record.getDetail());
        e.setClientIp(record.getClientIp());
        e.setUserAgent(record.getUserAgent());
        e.setOccurredAt(record.getOccurredAt() == null ? 0L : record.getOccurredAt());
        e.setPrevHash(record.getPrevHash());
        e.setHash(record.getHash());
        return e;
    }
}
