package com.zifang.z.audit.core.support;

import com.zifang.z.audit.core.domain.AuditAction;
import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.spi.AuditQuery;
import com.zifang.z.audit.core.spi.AuditSink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版审计存储（**同时实现 Sink 与 Query**）。
 *
 * <p><b>用途**：
 * <ul>
 *   <li>单测 / 本地开发（不落库也能跑通全链路）；</li>
 *   <li>作为<b>参考实现</b>：给「落库版」一个明确的语义基准（顺序、幂等、过滤规则）。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：用 {@link CopyOnWriteArrayList}（读多写少场景合适）。
 *
 * @author zifang
 */
public class InMemoryAuditStore implements AuditSink, AuditQuery {

    /** 事件列表（按写入顺序）。 */
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public boolean accept(AuditEvent event) {
        if (event == null) {
            return false;
        }
        // 幂等：同 eventId 不重复收
        for (AuditEvent e : events) {
            if (event.getEventId() != null && event.getEventId().equals(e.getEventId())) {
                return false;
            }
        }
        events.add(event);
        return true;
    }

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public List<AuditEvent> list(AuditCriteria criteria, int limit) {
        int max = limit <= 0 ? 100 : limit;
        List<AuditEvent> matched = new ArrayList<>();
        for (AuditEvent e : events) {
            if (matches(e, criteria)) {
                matched.add(e);
            }
        }
        matched.sort(Comparator.comparingLong(AuditEvent::getOccurredAt).reversed());
        return matched.size() > max ? new ArrayList<>(matched.subList(0, max)) : matched;
    }

    @Override
    public AuditEvent latest(String tenantId) {
        AuditEvent best = null;
        for (AuditEvent e : events) {
            if (tenantId != null && !tenantId.equals(e.getTenantId())) {
                continue;
            }
            if (best == null || e.getOccurredAt() >= best.getOccurredAt()) {
                best = e;
            }
        }
        return best;
    }

    @Override
    public List<AuditEvent> range(String tenantId, long from, long to) {
        List<AuditEvent> matched = new ArrayList<>();
        for (AuditEvent e : events) {
            if (tenantId != null && !tenantId.equals(e.getTenantId())) {
                continue;
            }
            if (e.getOccurredAt() < from || e.getOccurredAt() > to) {
                continue;
            }
            matched.add(e);
        }
        matched.sort(Comparator.comparingLong(AuditEvent::getOccurredAt));
        return matched;
    }

    /** 条件匹配。 */
    private static boolean matches(AuditEvent e, AuditCriteria c) {
        if (c == null) {
            return true;
        }
        if (c.getTenantId() != null && !c.getTenantId().equals(e.getTenantId())) {
            return false;
        }
        if (c.getOperatorId() != null && !c.getOperatorId().equals(e.getOperatorId())) {
            return false;
        }
        if (c.getTraceId() != null && !c.getTraceId().equals(e.getTraceId())) {
            return false;
        }
        if (c.getTargetType() != null && !c.getTargetType().equals(e.getTargetType())) {
            return false;
        }
        if (c.getTargetId() != null && !c.getTargetId().equals(e.getTargetId())) {
            return false;
        }
        if (c.getAction() != null) {
            AuditAction want = AuditAction.fromCode(c.getAction());
            if (e.getAction() != want) {
                return false;
            }
        }
        if (c.getFrom() != null && e.getOccurredAt() < c.getFrom()) {
            return false;
        }
        return c.getTo() == null || e.getOccurredAt() <= c.getTo();
    }

    /**
     * 全部事件（**返回副本** —— 防外部误改内部状态）。
     *
     * @return 事件列表
     */
    public List<AuditEvent> all() {
        return new ArrayList<>(events);
    }

    /**
     * 按事件 ID 删除一条（**仅测试用**：模拟「有人绕过应用直接删台账」）。
     *
     * @param eventId 事件 ID
     * @return true = 删掉了
     */
    public boolean removeByEventId(String eventId) {
        for (int i = 0; i < events.size(); i++) {
            AuditEvent e = events.get(i);
            if (e.getEventId() != null && e.getEventId().equals(eventId)) {
                events.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * 条数。
     *
     * @return 数量
     */
    public int size() {
        return events.size();
    }
}
