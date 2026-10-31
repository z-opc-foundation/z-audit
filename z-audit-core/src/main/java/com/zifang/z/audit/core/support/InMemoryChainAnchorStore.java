package com.zifang.z.audit.core.support;

import com.zifang.z.audit.core.domain.ChainAnchor;
import com.zifang.z.audit.core.spi.ChainAnchorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版链锚点存储。
 *
 * <p>单测 / 本地开发用。**同键覆盖写**（与落库版语义一致）。
 *
 * <p>⚠️ 读回时把**存储里的指纹**填进 {@link ChainAnchor#setStoredFingerprint}，
 * 让「锚点自身被改过」这条判据在内存实现里也能生效（否则测试测不到这条路径）。
 *
 * @author zifang
 */
public class InMemoryChainAnchorStore implements ChainAnchorStore {

    /** 锚点表：{@code tenantId + '\u0001' + anchorKey} → 锚点。 */
    private final ConcurrentHashMap<String, ChainAnchor> anchors = new ConcurrentHashMap<>();

    @Override
    public boolean save(ChainAnchor anchor) {
        if (anchor == null || anchor.getAnchorKey() == null || anchor.getAnchorKey().isEmpty()) {
            return false;
        }
        ChainAnchor stored = new ChainAnchor(
                anchor.getTenantId() == null ? "" : anchor.getTenantId(),
                anchor.getAnchorKey(),
                anchor.getChainHash(),
                anchor.getEventCount(),
                anchor.getAnchoredAt());
        // 指纹由内容算出 —— 存储的是「内容 + 指纹」不可分割的整体。
        stored.setStoredFingerprint(stored.computeFingerprint());
        anchors.put(key(stored.getTenantId(), stored.getAnchorKey()), stored);
        return true;
    }

    @Override
    public ChainAnchor load(String tenantId, String anchorKey) {
        if (anchorKey == null || anchorKey.isEmpty()) {
            return null;
        }
        ChainAnchor a = anchors.get(key(tenantId == null ? "" : tenantId, anchorKey));
        if (a == null) {
            return null;
        }
        // ⚠️ 返回**副本** —— 防外部改内部状态（与 InMemoryAuditStore.all() 同纪律）。
        ChainAnchor copy = new ChainAnchor(a.getTenantId(), a.getAnchorKey(),
                a.getChainHash(), a.getEventCount(), a.getAnchoredAt());
        copy.setStoredFingerprint(a.getStoredFingerprint());
        return copy;
    }

    @Override
    public List<ChainAnchor> list(String tenantId) {
        String t = tenantId == null ? "" : tenantId;
        List<ChainAnchor> matched = new ArrayList<>();
        for (ChainAnchor a : anchors.values()) {
            if (t.isEmpty() || t.equals(a.getTenantId())) {
                ChainAnchor copy = new ChainAnchor(a.getTenantId(), a.getAnchorKey(),
                        a.getChainHash(), a.getEventCount(), a.getAnchoredAt());
                copy.setStoredFingerprint(a.getStoredFingerprint());
                matched.add(copy);
            }
        }
        matched.sort(Comparator.comparingLong(ChainAnchor::getAnchoredAt));
        return matched;
    }

    /**
     * 删一个锚点。
     *
     * @param tenantId  租户
     * @param anchorKey 锚点键
     * @return true = 删掉了
     */
    public boolean remove(String tenantId, String anchorKey) {
        return anchors.remove(key(tenantId == null ? "" : tenantId, anchorKey)) != null;
    }

    /**
     * 全部锚点（**返回副本**）。
     *
     * @return 锚点列表
     */
    public List<ChainAnchor> all() {
        List<ChainAnchor> all = new ArrayList<>();
        for (ChainAnchor a : anchors.values()) {
            ChainAnchor copy = new ChainAnchor(a.getTenantId(), a.getAnchorKey(),
                    a.getChainHash(), a.getEventCount(), a.getAnchoredAt());
            copy.setStoredFingerprint(a.getStoredFingerprint());
            all.add(copy);
        }
        all.sort(Comparator.comparingLong(ChainAnchor::getAnchoredAt));
        return all;
    }

    /** 复合键。 */
    private static String key(String tenantId, String anchorKey) {
        return tenantId + '\u0001' + anchorKey;
    }
}
