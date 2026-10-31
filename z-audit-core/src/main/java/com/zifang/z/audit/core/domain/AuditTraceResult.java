package com.zifang.z.audit.core.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★ 按追踪码反查的结果（**三态**）。
 *
 * <p><b>★ 三态口径（本类的存在理由）</b>：
 * <ul>
 *   <li>{@link #isFound()} = true ⇒ <b>查到了</b>：台账里有这条追踪码的记录；</li>
 *   <li>{@link #isFound()} = false 且 {@link #isUnavailable()} = false
 *       ⇒ <b>台账里没有这条</b>（合规缺口：要能区分「本来就没打」与「记录被删了」）；</li>
 *   <li>{@link #isUnavailable()} = true ⇒ <b>台账不可用</b>
 *       （基础设施故障：库连不上 / SQL 报错；与上一条处置动作完全不同）。</li>
 * </ul>
 *
 * <p>⚠️ 若把这三种情况都表达成「空列表」，运维会误判成「没发生过」⇒
 * 漏报合规缺口。本类强制把三态摆到类型层面。
 *
 * @author zifang
 */
public class AuditTraceResult {

    /** 是否查到。 */
    private final boolean found;
    /** 台账是否不可用。 */
    private final boolean unavailable;
    /** 说明。 */
    private final String message;
    /** 事件列表（**按时间正序**）。 */
    private final List<AuditEvent> events;

    private AuditTraceResult(boolean found, boolean unavailable, String message, List<AuditEvent> events) {
        this.found = found;
        this.unavailable = unavailable;
        this.message = message;
        this.events = events == null ? Collections.emptyList() : Collections.unmodifiableList(events);
    }

    /**
     * 查到了。
     *
     * @param events 事件列表（**正序**）
     * @return 结果
     */
    public static AuditTraceResult found(List<AuditEvent> events) {
        return new AuditTraceResult(true, false, "已找到溯源记录", events);
    }

    /**
     * 台账里没有这条（**合规缺口**，不是故障）。
     *
     * @param message 说明
     * @return 结果
     */
    public static AuditTraceResult notFound(String message) {
        return new AuditTraceResult(false, false, message, new ArrayList<>());
    }

    /**
     * 台账不可用（**基础设施故障**）。
     *
     * @param message 说明
     * @return 结果
     */
    public static AuditTraceResult unavailable(String message) {
        return new AuditTraceResult(false, true, message, new ArrayList<>());
    }

    public boolean isFound() {
        return found;
    }

    public boolean isUnavailable() {
        return unavailable;
    }

    public String getMessage() {
        return message;
    }

    /** 事件列表（正序；只读）。 */
    public List<AuditEvent> getEvents() {
        return events;
    }

    /**
     * 汇总视图（**给溯源人看的那一张**）。
     *
     * <p><b>★ 为什么要多一个 {@code trace} 单条视图</b>：z-watermark 域的
     * HTTP 客户端读的是 {@code trace.traceId} / {@code trace.bizNo} 这类**单条**字段
     * （盲水印溯源只关心「这一条」）。早先本视图只给 {@code events} 数组
     * ⇒ 客户端读不到 {@code trace} ⇒ **真实环境反查永远「查不到」**
     * （而单元测试用内存台账，压根不过 HTTP ⇒ 测不出来）。
     * ⇒ 这里补一个 {@code trace} 单条视图，两边契约才对得上。
     *
     * @return 键值视图
     */
    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("found", found);
        view.put("unavailable", unavailable);
        view.put("message", message);
        view.put("total", events.size());
        view.put("summary", summarize());
        List<Map<String, Object>> list = new ArrayList<>();
        for (AuditEvent e : events) {
            list.add(e.toView());
        }
        view.put("events", list);
        view.put("trace", traceView());
        return view;
    }

    /**
     * ★ 单条溯源视图（**水印域反查读的就是这个**）。
     *
     * <p>同一追踪码下可能有**两条**行：水印溯源行（{@code eventId} 以 {@code WM:} 开头）
     * 与处理审计行（{@code SEC:} 开头）⇒ 这里**优先取水印行**，
     * 取不到再退回第一条。
     *
     * <p><b>字段口径</b>（与水印域的 {@code WatermarkTrace} 对齐）：
     * <ul>
     *   <li>{@code bizNo} ← 事件的 {@code targetId}（业务单号）；</li>
     *   <li>{@code watermarkType} ← 事件的 {@code targetType}（水印类型）。</li>
     * </ul>
     *
     * @return 单条视图；没有记录返回 null
     */
    public Map<String, Object> traceView() {
        AuditEvent primary = primaryEvent();
        if (primary == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("traceId", primary.getTraceId());
        view.put("eventId", primary.getEventId());
        view.put("operatorId", primary.getOperatorId());
        view.put("operatorName", primary.getOperatorName());
        view.put("tenantId", primary.getTenantId());
        view.put("bizNo", primary.getTargetId());
        view.put("watermarkType", primary.getTargetType());
        view.put("remark", primary.getDetail());
        view.put("occurredAt", primary.getOccurredAt());
        return view;
    }

    /**
     * 主事件（**优先水印溯源行**）。
     *
     * @return 主事件；无记录返回 null
     */
    public AuditEvent primaryEvent() {
        if (events == null || events.isEmpty()) {
            return null;
        }
        for (AuditEvent e : events) {
            if (e.getEventId() != null && e.getEventId().startsWith("WM:")) {
                return e;
            }
        }
        return events.get(0);
    }

    /**
     * 汇总（**谁 / 哪个租户 / 做了什么 / 什么时候**）。
     *
     * <p>⚠️ **操作人可能有多条不同值**（同一追踪码跨动作流转时）
     * ⇒ 汇总给的是**集合**，不是单值 —— 逼调用方看清全貌，不替他做判断。
     *
     * @return 汇总视图
     */
    public Map<String, Object> summarize() {
        Map<String, Object> sum = new LinkedHashMap<>();
        Set<String> operators = new LinkedHashSet<>();
        Set<String> tenants = new LinkedHashSet<>();
        Set<String> actions = new LinkedHashSet<>();
        Set<String> targets = new LinkedHashSet<>();
        long first = 0L;
        long last = 0L;
        for (AuditEvent e : events) {
            if (e.getOperatorId() != null && !e.getOperatorId().isEmpty()) {
                operators.add(e.getOperatorId());
            }
            if (e.getTenantId() != null && !e.getTenantId().isEmpty()) {
                tenants.add(e.getTenantId());
            }
            if (e.getAction() != null) {
                actions.add(e.getAction().name());
            }
            if (e.getTargetId() != null && !e.getTargetId().isEmpty()) {
                targets.add(e.getTargetId());
            }
            long at = e.getOccurredAt();
            if (first == 0L || at < first) {
                first = at;
            }
            if (at > last) {
                last = at;
            }
        }
        sum.put("operatorIds", operators);
        sum.put("tenantIds", tenants);
        sum.put("actions", actions);
        sum.put("targetIds", targets);
        sum.put("firstOccurredAt", first);
        sum.put("lastOccurredAt", last);
        return sum;
    }
}
