package com.zifang.z.audit.starter.config;

import com.zifang.z.audit.core.service.AuditService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * ★ 启动时重载哈希链链尾（M2 #22）。
 *
 * <p><b>问题</b>：哈希链的链尾哈希是 {@code AuditService} 里的一个内存变量。
 * 进程重启后它是链头哨兵（全零）⇒ 下一条新事件的 {@code prevHash} 是全零 ⇒
 * <b>链分叉</b>：
 * <ul>
 *   <li>库里旧链末条的 hash 是 {@code X}，新事件 prevHash 是全零
 *       ⇒ 链校验在新旧交界处报「哈希不匹配」⇒ 误报链断；</li>
 *   <li>更严重：篡改者看到新事件 prevHash 是全零，就知道「这里重启过」，
 *       可以在这个位置接一条伪造事件而不触发链校验失败。</li>
 * </ul>
 *
 * <p><b>解法</b>：启动完成后从库里把链尾捞回来接上。
 *
 * <p><b>⚠️ 装载失败不阻断启动</b>：表没建 / 库连不上 ⇒ 保留当前链尾继续跑
 * ⇒ 审计不能因为台账故障就整个瘫掉（安全组件不得拖垮主链路）。
 *
 * @author zifang
 */
public class AuditChainTailLoader {

    /** 审计门面。 */
    private final AuditService auditService;

    /**
     * 构造。
     *
     * @param auditService 审计门面
     */
    public AuditChainTailLoader(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 应用就绪后重载链尾。
     *
     * @param event 事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        try {
            String tail = auditService.reloadChainTail(null);
            System.out.println("[z-audit] 链尾已重载：" + tail);
        } catch (RuntimeException e) {
            System.err.println("[z-audit] 链尾重载失败（链可能分叉）：" + e.getMessage());
        }
    }
}
