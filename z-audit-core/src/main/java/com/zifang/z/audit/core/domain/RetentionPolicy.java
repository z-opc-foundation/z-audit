package com.zifang.z.audit.core.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 留存期策略（审计台账的保存期限口径）。
 *
 * <p><b>为什么按动作分档</b>：合规要求对不同动作的保存年限不同 ——
 * 出域动作（导出 / 下载 / 打印）要留得最久（通常 3 年），
 * 日常读操作留 6 个月就够。按动作分档而不是一刀切，
 * 既是合规要求，也是**存储成本控制**（审计流水量最大的是读操作）。
 *
 * <p><b>默认档</b>：{@link #DEFAULT_RETENTION_DAYS} 天（180 天）。
 * 未登记的动作落默认档。
 *
 * @author zifang
 */
public class RetentionPolicy {

    /** 默认留存天数（180 天 ≈ 半年）。 */
    public static final int DEFAULT_RETENTION_DAYS = 180;

    /** 出域动作的留存天数（3 年，合规基线）。 */
    public static final int EGRESS_RETENTION_DAYS = 1095;

    /** 动作 → 留存天数。 */
    private final Map<AuditAction, Integer> retentionDays = new LinkedHashMap<>();

    /** 默认留存天数（可覆盖）。 */
    private int defaultRetentionDays = DEFAULT_RETENTION_DAYS;

    /**
     * 默认构造：装载标准档。
     *
     * <p>标准档口径：
     * <ul>
     *   <li>出域动作（EXPORT / DOWNLOAD / PRINT）⇒ {@link #EGRESS_RETENTION_DAYS}；</li>
     *   <li>高危动作（AUTH_FAILURE / GRANT / REVOKE / CONFIG_CHANGE）⇒ 2 年；</li>
     *   <li>其余 ⇒ {@link #DEFAULT_RETENTION_DAYS}。</li>
     * </ul>
     */
    public RetentionPolicy() {
        for (AuditAction action : new AuditAction[]{AuditAction.EXPORT, AuditAction.DOWNLOAD, AuditAction.PRINT}) {
            retentionDays.put(action, EGRESS_RETENTION_DAYS);
        }
        for (AuditAction action : new AuditAction[]{AuditAction.AUTH_FAILURE, AuditAction.GRANT,
                AuditAction.REVOKE, AuditAction.CONFIG_CHANGE}) {
            retentionDays.put(action, 730);
        }
    }

    /**
     * 取某动作的留存天数。
     *
     * @param action 动作
     * @return 留存天数（未登记 ⇒ 默认档）
     */
    public int retentionDaysOf(AuditAction action) {
        if (action == null) {
            return defaultRetentionDays;
        }
        Integer days = retentionDays.get(action);
        return days == null ? defaultRetentionDays : days;
    }

    /**
     * 判断某条事件是否已过留存期（**可清理**）。
     *
     * @param event 事件
     * @param now   当前时刻（epoch 毫秒）
     * @return true = 已过期，可清理
     */
    public boolean isExpired(AuditEvent event, long now) {
        if (event == null) {
            return true;
        }
        long expireAt = event.getOccurredAt()
                + retentionDaysOf(event.getAction()) * 24L * 60L * 60L * 1000L;
        return now > expireAt;
    }

    /**
     * 覆盖某动作的留存天数。
     *
     * @param action 动作
     * @param days   天数（负数 ⇒ 忽略）
     */
    public void put(AuditAction action, int days) {
        if (action != null && days >= 0) {
            retentionDays.put(action, days);
        }
    }

    /**
     * 设置默认留存天数。
     *
     * @param days 天数（负数 ⇒ 忽略）
     */
    public void setDefaultRetentionDays(int days) {
        if (days >= 0) {
            this.defaultRetentionDays = days;
        }
    }

    public int getDefaultRetentionDays() {
        return defaultRetentionDays;
    }

    /**
     * 全部档位（只读视图）。
     *
     * @return 动作 → 天数
     */
    public Map<AuditAction, Integer> view() {
        return Collections.unmodifiableMap(retentionDays);
    }
}
