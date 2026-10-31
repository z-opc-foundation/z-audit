package com.zifang.z.audit.web.api;

import com.zifang.z.audit.core.domain.ChainAnchor;
import com.zifang.z.audit.core.service.ChainAnchorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链锚点端点 —— **防尾部截断**的操作面。
 *
 * <p><b>路由前缀</b>：{@code /z-team/api/audit/chain/**}。
 *
 * <p><b>典型用法</b>：
 * <ol>
 *   <li>定时任务每天打一个锚点：{@code POST /chain/anchor?anchorKey=2026-10-31}；</li>
 *   <li>内审随时校验：{@code GET /chain/anchor/verify} ⇒
 *       「链完整」或「尾部被截断（实际 N 条 < 锚点 M 条）」。</li>
 * </ol>
 *
 * @author zifang
 */
@RestController
@RequestMapping("/z-team/api/audit/chain")
public class ChainAnchorController {

    /** 锚点服务（可空 ⇒ 未装配数据源）。 */
    private final ObjectProvider<ChainAnchorService> anchorServiceProvider;

    /**
     * 构造。
     *
     * @param anchorServiceProvider 锚点服务提供者
     */
    public ChainAnchorController(ObjectProvider<ChainAnchorService> anchorServiceProvider) {
        this.anchorServiceProvider = anchorServiceProvider;
    }

    /**
     * 打一个锚点（快照当前链尾 hash + 累计条数）。
     *
     * @param tenantId  租户（缺省 ⇒ 全域）
     * @param anchorKey 锚点键（缺省 ⇒ 自动生成）
     * @return 锚点
     */
    @PostMapping("/anchor")
    public ResponseEntity<Map<String, Object>> anchor(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String anchorKey) {
        ChainAnchorService service = anchorServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("锚点服务未装配"));
        }
        ChainAnchor anchor = service.anchor(tenantId, anchorKey);
        if (anchor == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("message", "打锚失败：链为空或锚点存储未就绪");
            return ResponseEntity.status(409).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("anchor", view(anchor));
        return ResponseEntity.ok(body);
    }

    /**
     * 拿锚点回查实际链 —— **检出尾部截断**。
     *
     * @param tenantId  租户（缺省 ⇒ 全域）
     * @param anchorKey 锚点键（缺省 ⇒ 用最新的那个）
     * @return 校验结果
     */
    @GetMapping("/anchor/verify")
    public ResponseEntity<Map<String, Object>> verify(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String anchorKey) {
        ChainAnchorService service = anchorServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("锚点服务未装配"));
        }
        ChainAnchorService.AnchorVerifyResult r = service.verify(tenantId, anchorKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("intact", r.isIntact());
        body.put("indeterminate", r.isIndeterminate());
        body.put("actualCount", r.getActualCount());
        body.put("anchoredCount", r.getAnchoredCount());
        body.put("message", r.getMessage());
        body.put("anchor", r.getAnchor() == null ? null : view(r.getAnchor()));
        return ResponseEntity.ok(body);
    }

    /**
     * 列出某租户的全部锚点。
     *
     * @param tenantId 租户（缺省 ⇒ 全域）
     * @return 锚点列表
     */
    @GetMapping("/anchors")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String tenantId) {
        ChainAnchorService service = anchorServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("锚点服务未装配"));
        }
        List<ChainAnchor> anchors = service.list(tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("total", anchors.size());
        body.put("anchors", anchors.stream().map(ChainAnchorController::view).toList());
        return ResponseEntity.ok(body);
    }

    /** 锚点视图。 */
    private static Map<String, Object> view(ChainAnchor a) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tenantId", a.getTenantId());
        view.put("anchorKey", a.getAnchorKey());
        view.put("chainHash", a.getChainHash());
        view.put("eventCount", a.getEventCount());
        view.put("anchoredAt", a.getAnchoredAt());
        return view;
    }

    /** 错误体。 */
    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }
}
