package com.zifang.z.audit.web.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * z-audit <b>管控面（web）自动装配</b>。
 *
 * <p><b>只管部署关切</b>：健康检查。
 * <b>引擎装配 + 本域数据源已拆到 {@code z-audit-starter}</b>（M2 #23）——
 * 审计门面、溯源反查、链锚点、链尾重载器、{@code AuditModuleDataSource}（含 {@code @MapperScan}）
 * 都由 {@code com.zifang.z.audit.starter.config.AuditStarterAutoConfiguration} 提供，
 * 本类不再重复定义（重复定义会靠 {@code @ConditionalOnMissingBean} 侥幸让位，
 * 但读代码的人会以为有两处真装配）。
 *
 * <p>本仓 starter 与三个能力仓不同：<b>自足带走本域数据源</b> ——
 * 引擎侧存储要构造器注入 Mapper，Mapper 没有 sqlSessionFactory 就是空的
 * ⇒ 数据源必须与引擎装配同生共死，拆不进 web。
 *
 * <p>宿主引用本仓时：只想嵌入审计能力 ⇒ 依赖 {@code z-audit-starter}（轻，无 web 面）；
 * 想跑完整管控面 ⇒ 依赖 {@code z-audit-web}（它传递带上 starter）。
 *
 * <p>⚠️ 本装配**不扫描**包树（无 {@code @ComponentScan}）—— 跨域交互按组织口径走 HTTP 边界，
 * 不把别人的 jar 拖进本仓上下文。
 *
 * @author zifang
 */
@AutoConfiguration
public class AuditAutoConfiguration {

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
