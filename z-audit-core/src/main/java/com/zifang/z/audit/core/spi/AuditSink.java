package com.zifang.z.audit.core.spi;

import com.zifang.z.audit.core.domain.AuditEvent;

/**
 * 审计事件出口（SPI）。
 *
 * <p><b>为什么用 SPI 而不是直接写库</b>：审计台账的落地方式有多种 ——
 * 落库 / 打日志 / 送 Kafka / 送外部合规系统 / 同时多路。
 * 把「记什么」与「记到哪」分开，业务方只管调 {@code AuditService.record}，
 * 出口由宿主装配（可同时挂多个 ⇒ 多路输出）。
 *
 * <p><b>契约**：
 * <ol>
 *   <li><b>不抛异常</b> —— 审计写失败**不能拖垮主业务**（业务是主，审计是辅）；
 *       实现自己吞异常并记日志；</li>
 *   <li><b>顺序保证</b> —— 同一 {@code AuditService} 实例内事件按写入顺序投递
 *       （哈希链本身要求顺序）⇒ 实现若是异步的，需按序入队；</li>
 *   <li><b>幂等</b> —— 同一 {@code eventId} 重复投递不应产生两条记录。</li>
 * </ol>
 *
 * @author zifang
 */
public interface AuditSink {

    /**
     * 投递一条审计事件。
     *
     * @param event 事件（**不可为 null**）
     * @return true = 已被接收
     */
    boolean accept(AuditEvent event);

    /**
     * 出口名（诊断用：多路输出时区分是谁收的）。
     *
     * @return 出口名
     */
    String name();
}
