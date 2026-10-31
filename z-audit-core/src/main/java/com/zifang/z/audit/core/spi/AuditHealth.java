package com.zifang.z.audit.core.spi;

/**
 * 查询出口的健康自述（**可选能力接口**）。
 *
 * <p><b>为什么需要它</b>：查询失败时，落库实现按 SPI 契约「不抛异常」⇒ 返回空列表。
 * 但**「查不到」与「查不了」必须能区分**：
 * <ul>
 *   <li>查不到 ⇒ 台账里确实没有这条记录（**合规缺口**，要报警）；</li>
 *   <li>查不了 ⇒ 数据库连不上 / SQL 报错（**基础设施故障**，要修库）。</li>
 * </ul>
 * 两者都返回空列表的话，运维会以为「没打过水印」，实际是台账挂了。
 *
 * <p><b>用法</b>：实现类实现本接口后，调用方用 {@code instanceof} 判断，
 * 读取最近一次错误信息。
 *
 * @author zifang
 */
public interface AuditHealth {

    /**
     * 最近一次操作失败的原因（**成功时返回 null**）。
     *
     * @return 错误说明；无错误返回 {@code null}
     */
    String lastError();
}
