package com.zifang.z.audit.core.service;

import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.AuditTraceResult;
import com.zifang.z.audit.core.spi.AuditHealth;
import com.zifang.z.audit.core.spi.AuditQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ★ 按追踪码反查服务（M2 #22 —— 溯源闭环的查询侧）。
 *
 * <p><b>它解决什么</b>：盲水印的载荷**只带追踪码**（载体容量是硬约束），
 * 拿到泄密内容的人只能提取出一个短码。要还原「谁在什么时候把哪份单号导出去的」，
 * 必须拿这个短码**回查台账** —— 本服务就是那个回查口。
 *
 * <p><b>★ 三态口径</b>（本类最重要的设计）：
 * <ul>
 *   <li>{@code found=true} ⇒ 查到了；</li>
 *   <li>{@code found=false, unavailable=false} ⇒ <b>台账里没有这条</b>
 *       —— 这是**合规缺口**（「打过水印但没留痕」或「记录被删了」），要报警；</li>
 *   <li>{@code unavailable=true} ⇒ <b>台账不可用</b>
 *       —— 这是**基础设施故障**（库连不上 / SQL 报错），要修库。</li>
 * </ul>
 * 两者都返回「空列表」的话，运维会误判成「没发生过」⇒ 漏报合规缺口。
 *
 * <p><b>判据</b>：查询出口实现了 {@link AuditHealth} ⇒ 读它的 {@code lastError()}
 * 区分「查不了」；没实现 ⇒ 只能判「查不到」（能力降级，但**不误报故障**）。
 *
 * @author zifang
 */
public class AuditTraceService {

    /** 查询出口（可空 ⇒ 无查询能力）。 */
    private AuditQuery query;

    /**
     * 构造。
     *
     * @param query 查询出口（可空）
     */
    public AuditTraceService(AuditQuery query) {
        this.query = query;
    }

    /**
     * 设查询出口。
     *
     * @param query 查询出口（可空 ⇒ 无查询能力）
     */
    public void setQuery(AuditQuery query) {
        this.query = query;
    }

    /**
     * ★ 按追踪码反查（**三态**）。
     *
     * @param traceId 追踪码
     * @return 反查结果（**不返回 null**）
     */
    public AuditTraceResult lookup(String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return AuditTraceResult.notFound("追踪码为空");
        }
        if (query == null) {
            return AuditTraceResult.unavailable("未配置查询出口，无法反查");
        }
        AuditQuery.AuditCriteria criteria = new AuditQuery.AuditCriteria();
        criteria.setTraceId(traceId.trim());
        List<AuditEvent> events;
        try {
            events = query.list(criteria, 200);
        } catch (RuntimeException e) {
            return AuditTraceResult.unavailable("查询异常：" + e.getMessage());
        }
        if (events == null) {
            events = Collections.emptyList();
        }
        // ★ 查询出口报了错 ⇒ 这是「查不了」，不是「查不到」。
        if (query instanceof AuditHealth) {
            String err = ((AuditHealth) query).lastError();
            if (err != null && !err.isEmpty()) {
                return AuditTraceResult.unavailable(err);
            }
        }
        if (events.isEmpty()) {
            return AuditTraceResult.notFound("台账中没有该追踪码的记录");
        }
        List<AuditEvent> ordered = new ArrayList<>(events);
        // 正序（最早在前）⇒ 溯源人按时间线看
        ordered.sort(Comparator.comparingLong(AuditEvent::getOccurredAt));
        return AuditTraceResult.found(ordered);
    }
}
