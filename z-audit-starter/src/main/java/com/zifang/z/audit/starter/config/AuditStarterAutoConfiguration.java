package com.zifang.z.audit.starter.config;

import com.zifang.z.audit.core.mapper.AuditEventMapper;
import com.zifang.z.audit.core.mapper.ChainAnchorMapper;
import com.zifang.z.audit.core.service.AuditService;
import com.zifang.z.audit.core.service.AuditTraceService;
import com.zifang.z.audit.core.service.ChainAnchorService;
import com.zifang.z.audit.core.service.PersistentAuditStore;
import com.zifang.z.audit.core.service.PersistentChainAnchorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * z-audit <b>引擎装配</b>（M2 #23 —— 引擎装配只有一处，在 starter，不在 web）。
 *
 * <p>与三个能力仓（sense / mask / watermark）的 engine-only starter 不同，本仓
 * <b>starter 自足带走本域数据源</b>（{@link AuditModuleDataSource}，含 {@code @MapperScan}）：
 * 引擎侧的 {@code PersistentAuditStore} 要构造器注入 {@code AuditEventMapper}，
 * Mapper 没有 sqlSessionFactory 就是空的 ⇒ 数据源必须与引擎装配同生共死。
 *
 * <p>宿主引用本仓时：只想嵌入审计能力 ⇒ 依赖 {@code z-audit-starter}（无 web 面）；
 * 想跑完整管控面 ⇒ 依赖 {@code z-audit-web}（它传递带上 starter，只补 REST + 健康检查）。
 *
 * <p>⚠️ 本装配**不扫描**包树（无 {@code @ComponentScan}）—— 跨域交互按组织口径走 HTTP 边界，
 * 不把别人的 jar 拖进本仓上下文。
 *
 * @author zifang
 */
@AutoConfiguration
@Import(AuditModuleDataSource.class)
public class AuditStarterAutoConfiguration {

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
     * ★ 溯源反查服务（M2 #22 —— 盲水印溯源的查询侧）。
     *
     * <p>查询出口直接给 {@link PersistentAuditStore}（它同时是 Sink 与 Query）——
     * ⚠️ {@code AuditService} 是门面，**不实现** {@code AuditQuery}，不能当查询出口传。
     *
     * @param auditStore 落库存储（兼查询出口）
     * @return 反查服务
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditTraceService auditTraceService(PersistentAuditStore auditStore) {
        return new AuditTraceService(auditStore);
    }

    /**
     * ★ 启动时重载链尾哈希（M2 #22 —— 重启后链不能分叉）。
     *
     * <p><b>为什么必须有</b>：链尾哈希是内存变量，重启后变成链头哨兵 ⇒
     * 下一条新事件的 {@code prevHash} 是全零 ⇒ <b>链分叉</b>：
     * 旧链末条的 hash 对不上，且篡改者能看出「这里重启过」并在此接入伪造事件。
     *
     * <p><b>⚠️ 装载失败不阻断启动</b>：链尾取不到（表没建 / 库连不上）
     * ⇒ 保留当前链尾继续跑 ⇒ 审计不能因为台账故障就整个瘫掉。
     *
     * @param auditService 审计门面
     * @return 链尾重载器（启动后执行一次）
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditChainTailLoader auditChainTailLoader(AuditService auditService) {
        return new AuditChainTailLoader(auditService);
    }

    /**
     * 落库版链锚点存储。
     *
     * <p>⚠️ 同样**不用 {@code @ConditionalOnBean(ChainAnchorMapper.class)}** ——
     * 理由同 {@link #persistentAuditStore}：自动装配里条件评估早于 {@code @MapperScan}。
     *
     * @param mapper 锚点 Mapper
     * @return 锚点存储
     */
    @Bean
    @ConditionalOnMissingBean
    public PersistentChainAnchorStore persistentChainAnchorStore(ChainAnchorMapper mapper) {
        return new PersistentChainAnchorStore(mapper);
    }

    /**
     * 链锚点服务（**补上「尾部截断不可检出」这条边界**）。
     *
     * <p>查询出口直接给 {@link PersistentAuditStore}（它同时是 Sink 与 Query）——
     * ⚠️ {@code AuditService} 是门面，**不实现** {@code AuditQuery}，不能当查询出口传。
     *
     * @param anchorStore 锚点存储
     * @param auditStore  落库存储（兼查询出口）
     * @return 锚点服务
     */
    @Bean
    @ConditionalOnMissingBean
    public ChainAnchorService chainAnchorService(PersistentChainAnchorStore anchorStore,
                                                 PersistentAuditStore auditStore) {
        ChainAnchorService service = new ChainAnchorService(anchorStore);
        service.setQuery(auditStore);
        return service;
    }
}
