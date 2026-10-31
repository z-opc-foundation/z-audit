package com.zifang.z.audit.core.service;

import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.AuditRecord;
import com.zifang.z.audit.core.mapper.AuditEventMapper;
import com.zifang.z.audit.core.spi.AuditHealth;
import com.zifang.z.audit.core.spi.AuditQuery;
import com.zifang.z.audit.core.spi.AuditSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 落库版审计存储（**同时实现 Sink 与 Query**）。
 *
 * <p>把 {@link AuditEvent}（领域）↔ {@link AuditRecord}（实体）互转。
 *
 * <p><b>不抛异常</b>：写库失败不外抛（与 SPI 契约一致），
 * 失败时记 stderr 并返回 false —— 业务链路不受影响。
 *
 * <p><b>★ 但「查不到」与「查不了」必须能区分（M2 #22）</b>：
 * 本类额外实现 {@link AuditHealth}，把最近一次失败原因留在 {@link #lastError()}。
 * 查询失败时仍返回空列表（不抛异常，遵 SPI 契约），
 * 但调用方能通过 {@code instanceof AuditHealth} 判出「这是故障不是空」⇒
 * 返回 503 而不是 404 ⇒ 运维处置动作不同。
 *
 * @author zifang
 */
public class PersistentAuditStore implements AuditSink, AuditQuery, AuditHealth {

    /** 默认查询上限。 */
    private static final int DEFAULT_LIMIT = 100;

    /** 链校验时的取数上限（一次最多校验多少条）。 */
    private static final int CHAIN_SCAN_LIMIT = 10000;

    /** Mapper。 */
    private final AuditEventMapper mapper;

    /** 最近一次失败原因（成功时清空）。 */
    private final AtomicReference<String> lastError = new AtomicReference<>(null);

    /**
     * 构造。
     *
     * @param mapper Mapper（**不可为 null**）
     */
    public PersistentAuditStore(AuditEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean accept(AuditEvent event) {
        if (event == null) {
            return false;
        }
        try {
            boolean ok = mapper.insert(AuditRecord.from(event)) > 0;
            lastError.set(null);
            return ok;
        } catch (RuntimeException e) {
            lastError.set("落库失败：" + e.getMessage());
            System.err.println("[z-audit] 落库失败（eventId=" + event.getEventId() + "）：" + e.getMessage());
            return false;
        }
    }

    @Override
    public String name() {
        return "persistent-db";
    }

    @Override
    public List<AuditEvent> list(AuditCriteria criteria, int limit) {
        int max = limit <= 0 ? DEFAULT_LIMIT : limit;
        try {
            AuditCriteria c = criteria == null ? new AuditCriteria() : criteria;
            List<AuditRecord> records = mapper.selectByCriteria(
                    c.getTenantId(), c.getOperatorId(), c.getAction(), c.getTraceId(),
                    c.getTargetType(), c.getTargetId(), c.getFrom(), c.getTo(), max);
            lastError.set(null);
            return toDomain(records);
        } catch (RuntimeException e) {
            lastError.set("查询失败：" + e.getMessage());
            System.err.println("[z-audit] 查询失败：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public AuditEvent latest(String tenantId) {
        try {
            AuditRecord record = mapper.selectLatest(tenantId);
            lastError.set(null);
            return record == null ? null : AuditRecord.toDomain(record);
        } catch (RuntimeException e) {
            lastError.set("取链尾失败：" + e.getMessage());
            System.err.println("[z-audit] 取链尾失败：" + e.getMessage());
            return null;
        }
    }

    @Override
    public List<AuditEvent> range(String tenantId, long from, long to) {
        try {
            List<AuditRecord> records = mapper.selectRange(tenantId, from, to, CHAIN_SCAN_LIMIT);
            lastError.set(null);
            return toDomain(records);
        } catch (RuntimeException e) {
            lastError.set("取时间窗失败：" + e.getMessage());
            System.err.println("[z-audit] 取时间窗失败：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public String lastError() {
        return lastError.get();
    }

    /**
     * 清理过期事件（早于 {@code expireBefore} 的都删）。
     *
     * @param expireBefore 过期时刻（epoch 毫秒）
     * @return 删除条数
     */
    public int purgeExpired(long expireBefore) {
        try {
            return mapper.deleteExpired(expireBefore);
        } catch (RuntimeException e) {
            System.err.println("[z-audit] 清理过期事件失败：" + e.getMessage());
            return 0;
        }
    }

    /** 实体列表 → 领域列表。 */
    private static List<AuditEvent> toDomain(List<AuditRecord> records) {
        List<AuditEvent> events = new ArrayList<>();
        if (records != null) {
            for (AuditRecord r : records) {
                events.add(AuditRecord.toDomain(r));
            }
        }
        return events;
    }
}
