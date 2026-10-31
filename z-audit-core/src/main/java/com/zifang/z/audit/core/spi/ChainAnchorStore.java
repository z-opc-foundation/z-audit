package com.zifang.z.audit.core.spi;

import com.zifang.z.audit.core.domain.ChainAnchor;

import java.util.List;

/**
 * 链锚点存储（SPI）。
 *
 * <p><b>为什么是 SPI 而不是直接用 Mapper</b>：审计门面（{@code AuditService}）
 * 在 core 层不该绑死 MyBatis。内存实现给单测 / 本地开发，落库实现给生产。
 *
 * <p><b>不抛异常契约</b>：与 {@link AuditSink} 一致 —— 锚点写失败不外抛
 * （审计是辅链路，业务是主链路）。实现自己记日志。
 *
 * @author zifang
 */
public interface ChainAnchorStore {

    /**
     * 写一个锚点（**同键覆盖写** ⇒ 幂等）。
     *
     * @param anchor 锚点
     * @return true = 写入成功
     */
    boolean save(ChainAnchor anchor);

    /**
     * 按键取锚点。
     *
     * @param tenantId  租户（可空 ⇒ 全域）
     * @param anchorKey 锚点键
     * @return 锚点；无记录返回 null
     */
    ChainAnchor load(String tenantId, String anchorKey);

    /**
     * 列出某租户的**全部锚点**（按锚点时刻正序）。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @return 锚点列表
     */
    List<ChainAnchor> list(String tenantId);
}
