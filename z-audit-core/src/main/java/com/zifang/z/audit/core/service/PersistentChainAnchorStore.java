package com.zifang.z.audit.core.service;

import com.zifang.z.audit.core.domain.ChainAnchor;
import com.zifang.z.audit.core.domain.ChainAnchorRecord;
import com.zifang.z.audit.core.mapper.ChainAnchorMapper;
import com.zifang.z.audit.core.spi.ChainAnchorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 落库版链锚点存储。
 *
 * <p><b>不抛异常</b>：写锚点失败不外抛（审计是辅链路）。
 *
 * <p><b>tenant_id 归一为空串</b>：MySQL 唯一索引里 NULL 与 NULL 不相等
 * ⇒ NULL 会让「同键覆盖」退化成「同键堆积」。故读写两侧统一用 {@code ""} 表示「全域」。
 *
 * @author zifang
 */
public class PersistentChainAnchorStore implements ChainAnchorStore {

    /** 「全域」租户的存储表示（**不能是 NULL** —— 见类注释）。 */
    public static final String TENANT_ALL = "";

    /** Mapper。 */
    private final ChainAnchorMapper mapper;

    /**
     * 构造。
     *
     * @param mapper Mapper（**不可为 null**）
     */
    public PersistentChainAnchorStore(ChainAnchorMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean save(ChainAnchor anchor) {
        if (anchor == null || anchor.getAnchorKey() == null || anchor.getAnchorKey().isEmpty()) {
            return false;
        }
        try {
            ChainAnchor normalized = normalize(anchor);
            mapper.upsert(normalized.getTenantId(), normalized.getAnchorKey(),
                    normalized.getChainHash(), normalized.getEventCount(),
                    normalized.getAnchoredAt(), normalized.computeFingerprint());
            return true;
        } catch (RuntimeException e) {
            System.err.println("[z-audit] 写链锚点失败（key=" + anchor.getAnchorKey() + "）：" + e.getMessage());
            return false;
        }
    }

    @Override
    public ChainAnchor load(String tenantId, String anchorKey) {
        if (anchorKey == null || anchorKey.isEmpty()) {
            return null;
        }
        try {
            ChainAnchorRecord record = mapper.selectOne(normalizeTenant(tenantId), anchorKey);
            return record == null ? null : toDomain(record);
        } catch (RuntimeException e) {
            System.err.println("[z-audit] 读链锚点失败（key=" + anchorKey + "）：" + e.getMessage());
            return null;
        }
    }

    @Override
    public List<ChainAnchor> list(String tenantId) {
        try {
            List<ChainAnchorRecord> records = mapper.selectList(normalizeTenant(tenantId));
            List<ChainAnchor> anchors = new ArrayList<>();
            if (records != null) {
                for (ChainAnchorRecord r : records) {
                    anchors.add(toDomain(r));
                }
            }
            return anchors;
        } catch (RuntimeException e) {
            System.err.println("[z-audit] 列链锚点失败：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 删一个锚点。
     *
     * @param tenantId  租户
     * @param anchorKey 锚点键
     * @return 影响行数
     */
    public int delete(String tenantId, String anchorKey) {
        try {
            return mapper.deleteByKey(normalizeTenant(tenantId), anchorKey);
        } catch (RuntimeException e) {
            System.err.println("[z-audit] 删链锚点失败（key=" + anchorKey + "）：" + e.getMessage());
            return 0;
        }
    }

    /** 租户归一（null ⇒ 空串）。 */
    private static String normalizeTenant(String tenantId) {
        return tenantId == null ? TENANT_ALL : tenantId;
    }

    /** 锚点归一（租户空串 + 补指纹）。 */
    private static ChainAnchor normalize(ChainAnchor anchor) {
        ChainAnchor a = new ChainAnchor(
                normalizeTenant(anchor.getTenantId()),
                anchor.getAnchorKey(),
                anchor.getChainHash(),
                anchor.getEventCount(),
                anchor.getAnchoredAt() <= 0 ? System.currentTimeMillis() : anchor.getAnchoredAt());
        return a;
    }

    /** 行 → 领域对象。 */
    private static ChainAnchor toDomain(ChainAnchorRecord record) {
        ChainAnchor a = new ChainAnchor();
        a.setTenantId(record.getTenantId());
        a.setAnchorKey(record.getAnchorKey());
        a.setChainHash(record.getChainHash());
        a.setEventCount(record.getEventCount() == null ? 0L : record.getEventCount());
        a.setAnchoredAt(record.getAnchoredAt() == null ? 0L : record.getAnchoredAt());
        // ⚠️ **必须带上存储里的指纹** —— 否则读回后无从验证锚点自身是否被改过。
        a.setStoredFingerprint(record.getFingerprint());
        return a;
    }
}
