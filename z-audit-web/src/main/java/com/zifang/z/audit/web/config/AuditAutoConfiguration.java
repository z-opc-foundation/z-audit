package com.zifang.z.audit.web.config;

import com.zifang.z.audit.core.mapper.AuditEventMapper;
import com.zifang.z.audit.core.service.AuditService;
import com.zifang.z.audit.core.service.PersistentAuditStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * z-audit自动装配。
 *
 * <p>宿主引用本仓时，只要把 {@code z-audit-web} 放进 classpath，
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 会把本装配挂上 ⇒ 数据源 + 领域服务齐备。
 *
 * <p>⚠️ 本装配**不扫描**包树（无 {@code @ComponentScan}）—— 跨域交互按组织口径走 HTTP 边界，
 * 不把别人的 jar 拖进本仓上下文。
 *
 * @author zifang
 */
@AutoConfiguration
@Import(AuditModuleDataSource.class)
public class AuditAutoConfiguration {

    /**
     * 落库版审计存储（**同时是 Sink 与 Query**）。
     *
     * <p>⚠️ **不用 {@code @ConditionalOnBean(AuditEventMapper.class)} 判 Mapper 存在**：
     * 在自动装配里，{@code @ConditionalOnBean} 的评估时机**早于** {@code @MapperScan}
     * 注册的 Mapper bean ⇒ 条件永远判不成立 ⇒ 本域服务静默缺失（表现为接口 503）。
     * 改成**直接构造器注入**：{@code @MapperScan} 在本仓 {@code AuditModuleDataSource}
     * 上，注册时机早于本装配的 {@code @Bean} 求值 ⇒ 注入必然拿到 Mapper。
     *
     * @param mapper Mapper
     * @return 存储
     */
    @Bean
    @ConditionalOnMissingBean
    public PersistentAuditStore persistentAuditStore(AuditEventMapper mapper) {
        return new PersistentAuditStore(mapper);
    }

    /**
     * 审计门面（本域主入口）。
     *
     * @param store 落库存储
     * @return 审计门面
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditService auditService(PersistentAuditStore store) {
        AuditService service = new AuditService(store);
        service.setQuery(store);
        return service;
    }

    /**
     * 本域健康检查（探活用）。
     *
     * @return 健康标识
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditHealthIndicator auditHealthIndicator() {
        return new AuditHealthIndicator("z-audit");
    }
}
