package com.zifang.z.audit.core.domain;

import java.util.Locale;

/**
 * 审计结果（动作的成败）。
 *
 * <p><b>为什么区分「失败」和「被拒」</b>：两者都是「没做成」，但含义完全不同 ——
 * <ul>
 *   <li>{@link #FAILURE}：系统尝试了，但执行出错（如下游超时）⇒ 要查稳定性；</li>
 *   <li>{@link #DENIED}：权限校验没通过，<b>根本没执行</b> ⇒ 要查越权行为。</li>
 * </ul>
 * 合规内审最关心的往往是 {@code DENIED}（谁在试探越权）。
 *
 * @author zifang
 */
public enum AuditResult {

    /** 成功。 */
    SUCCESS,
    /** 失败（执行出错）。 */
    FAILURE,
    /** 被拒绝（权限不足，未执行）。 */
    DENIED;

    /**
     * 宽松解析（未知值归 {@link #FAILURE} —— 解析不了宁可当失败，不当成功）。
     *
     * @param code 结果码
     * @return 结果
     */
    public static AuditResult fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return FAILURE;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (AuditResult r : values()) {
            if (r.name().equals(normalized)) {
                return r;
            }
        }
        return FAILURE;
    }

    /**
     * 是否算「成功」。
     *
     * @return true = 成功
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
