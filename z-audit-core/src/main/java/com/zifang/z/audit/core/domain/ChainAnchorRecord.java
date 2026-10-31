package com.zifang.z.audit.core.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 链锚点落库实体（表 {@code z_audit_chain_anchor}）。
 *
 * <p><b>实体与领域对象分离</b>：{@link ChainAnchor} 是领域模型（带指纹逻辑），
 * 本类是持久化载体（只管字段映射）⇒ 领域模型不被 ORM 注解污染。
 *
 * <p>⚠️ **为什么必须用实体、不能用 {@code Map<String,Object>}**：
 * H2 在 {@code MODE=MySQL} 下会把未加引号的别名**转成大写**
 * （{@code AS tenantId} ⇒ 列标签 {@code TENANTID}）。
 * MyBatis 映射成 {@code Map} 时**直接用原始列标签做 key** ⇒ {@code map.get("tenantId")} 返回 null；
 * 而映射成实体时 MyBatis 会把列名与属性名都归一化后匹配 ⇒ 正常。
 * M1 的 {@code AuditEventMapper} 用实体（{@code AuditRecord}）所以没踩到，
 * 本表若照抄用 Map 就会踩到 ⇒ **统一用实体**。
 *
 * @author zifang
 */
@TableName("z_audit_chain_anchor")
public class ChainAnchorRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户（**空串表示全域**，不用 NULL —— MySQL 唯一索引里 NULL 不相等）。 */
    private String tenantId;
    /** 锚点键。 */
    private String anchorKey;
    /** 锚点处的链尾哈希。 */
    private String chainHash;
    /** 锚点处的累计条数。 */
    private Long eventCount;
    /** 锚点时刻（epoch 毫秒）。 */
    private Long anchoredAt;
    /** 锚点指纹（内容 SHA-256）。 */
    @TableField("fingerprint")
    private String fingerprint;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getEventCount() {
        return eventCount;
    }

    public void setEventCount(Long eventCount) {
        this.eventCount = eventCount;
    }

    public Long getAnchoredAt() {
        return anchoredAt;
    }

    public void setAnchoredAt(Long anchoredAt) {
        this.anchoredAt = anchoredAt;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }
}
