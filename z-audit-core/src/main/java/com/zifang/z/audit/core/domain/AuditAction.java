package com.zifang.z.audit.core.domain;

import java.util.Locale;

/**
 * 审计动作（被记录的操作类型）。
 *
 * <p><b>为什么用枚举而不是自由字符串</b>：动作是<b>合规口径</b> ——
 * 监管与内审要按动作维度统计（谁在什么时候导出过数据），
 * 自由字符串会出现「EXPORT」「export」「导出」三种写法 ⇒ 统计口径失真。
 * 枚举把口径钉死，未覆盖的动作走 {@link #OTHER} + {@code detail} 兜底。
 *
 * @author zifang
 */
public enum AuditAction {

    /** 登录。 */
    LOGIN,
    /** 登出。 */
    LOGOUT,
    /** 认证失败。 */
    AUTH_FAILURE,
    /** 查看（读）。 */
    VIEW,
    /** 新建。 */
    CREATE,
    /** 修改。 */
    UPDATE,
    /** 删除。 */
    DELETE,
    /** 导出（**数据出域的高危动作**）。 */
    EXPORT,
    /** 下载附件。 */
    DOWNLOAD,
    /** 打印。 */
    PRINT,
    /** 上传。 */
    UPLOAD,
    /** 敏感识别（跑过一次识别）。 */
    SENSITIVE_SCAN,
    /** 脱敏处理。 */
    MASK,
    /** 打水印。 */
    WATERMARK,
    /** 授权 / 改权限。 */
    GRANT,
    /** 撤销授权。 */
    REVOKE,
    /** 配置变更。 */
    CONFIG_CHANGE,
    /** 其它（用 detail 兜底）。 */
    OTHER;

    /**
     * 宽松解析（大小写不敏感；未知值归 {@link #OTHER}）。
     *
     * @param code 动作码
     * @return 动作
     */
    public static AuditAction fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return OTHER;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (AuditAction a : values()) {
            if (a.name().equals(normalized)) {
                return a;
            }
        }
        return OTHER;
    }

    /**
     * 是否属于<b>数据出域</b>类动作（这类动作在合规上按高风险处理）。
     *
     * @return true = 出域动作
     */
    public boolean isEgress() {
        return this == EXPORT || this == DOWNLOAD || this == PRINT;
    }
}
