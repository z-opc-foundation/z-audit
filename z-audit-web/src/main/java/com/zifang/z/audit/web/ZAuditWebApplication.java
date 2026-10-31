package com.zifang.z.audit.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * z-audit 管控面启动器。
 *
 * <p>作为**独立进程**跑本域（开发 / 自测用）；作为**库**被宿主引用时，
 * 由 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 里的 {@code AuditAutoConfiguration} 挂装配，不需要本类。
 *
 * @author zifang
 */
@SpringBootApplication
public class ZAuditWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZAuditWebApplication.class, args);
    }
}
