package com.zifang.z.audit.web.config;

/**
 * 本域健康标识（装配层轻量 bean，供探活与日志使用）。
 *
 * @author zifang
 */
public class AuditHealthIndicator {

    /** 所属组件名。 */
    private final String component;

    public AuditHealthIndicator(String component) {
        this.component = component;
    }

    /** 所属组件名。 */
    public String getComponent() {
        return component;
    }

    /** 健康标识（恒为 {@code UP}）。 */
    public String getStatus() {
        return "UP";
    }
}
