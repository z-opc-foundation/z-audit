package com.zifang.z.audit.starter.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zifang.z.boot.datasource.starter.ModuleDataSourceTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 本域数据源装配（对齐 z-biz / z-trade 各域的写法）。
 *
 * <p>配置键名模式：{@code z.base.db.audit.*}（{@code ModuleDataSourceTemplate} 的权威前缀常量在这），
 * 缺省回落到 {@code z.base.db.default.*}。
 *
 * <p>{@code @MapperScan} 把 {@code com.zifang.z.audit.core.mapper} 下的 Mapper 绑到**本域**的
 * {@code sqlSessionFactoryAudit} 上 —— 不跟别域共用 sqlSessionFactory，避免跨域 Mapper 互相污染。
 *
 * <p>开发 / 测试口径：显式给 {@code z.base.db.audit.url}（JDBC URL）时走**直连模式**，
 * 不套 MySQL 键名模式 —— 本仓验收项（DDL 幂等、接口可路由）就靠它跑在本地 H2 上，
 * **不连生产 RDS**。生产口径仍然是 {@code z.base.db.audit.*} 键名模式。
 *
 * @author zifang
 */
@Configuration
@MapperScan(
        basePackages = "com.zifang.z.audit.core.mapper",
        sqlSessionFactoryRef = "sqlSessionFactoryAudit"
)
public class AuditModuleDataSource extends ModuleDataSourceTemplate {

    /**
     * 本域数据源。
     *
     * @param env 环境
     * @return 数据源
     */
    @Bean(name = "dataSourceAudit")
    public DataSource dataSourceAudit(Environment env) {
        String url = env.getProperty("z.base.db.audit.url");
        if (StringUtils.hasText(url)) {
            return buildDirectDataSource(env, "z.base.db.audit", url.trim());
        }
        return buildDataSource(env, "audit");
    }

    /**
     * 本域 sqlSessionFactory（绑本域数据源）。
     *
     * @param dataSource 本域数据源
     * @return sqlSessionFactory
     * @throws Exception 构建失败
     */
    @Bean(name = "sqlSessionFactoryAudit")
    public SqlSessionFactory sqlSessionFactoryAudit(
            @Qualifier("dataSourceAudit") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml"));
        return factoryBean.getObject();
    }

    /**
     * 直连模式数据源：显式 JDBC URL（开发 / 测试用；生产走 {@code z.base.db.audit.*} 键名模式）。
     *
     * @param env    环境
     * @param prefix 配置键前缀
     * @param url    JDBC URL
     * @return 数据源
     */
    static DataSource buildDirectDataSource(Environment env, String prefix, String url) {
        com.alibaba.druid.pool.DruidDataSource dataSource = new com.alibaba.druid.pool.DruidDataSource();
        dataSource.setUrl(url);
        String username = env.getProperty(prefix + ".username");
        if (StringUtils.hasText(username)) {
            dataSource.setUsername(username.trim());
        }
        String password = env.getProperty(prefix + ".password");
        if (password != null) {
            dataSource.setPassword(password);
        }
        String driverClassName = env.getProperty(prefix + ".driver-class-name");
        if (StringUtils.hasText(driverClassName)) {
            dataSource.setDriverClassName(driverClassName.trim());
        }
        return dataSource;
    }
}
