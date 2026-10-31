package com.zifang.z.audit.web.api;

import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.service.AuditService;
import com.zifang.z.audit.core.spi.AuditQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计事件端点。
 *
 * <p><b>路由前缀</b>：{@code /z-team/api/audit/**} —— 走组织口径的 HTTP 边界
 * （跨域交互不直接 import 别人的 jar）。
 *
 * <p><b>为什么记事件走 POST 而查事件走 GET</b>：写是副作用、查是幂等读，
 * 语义不同不该混。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/z-team/api/audit")
public class AuditEventController {

    /** 审计门面（可空 ⇒ 未装配数据源）。 */
    private final ObjectProvider<AuditService> auditServiceProvider;

    /**
     * 构造。
     *
     * @param auditServiceProvider 审计门面提供者
     */
    public AuditEventController(ObjectProvider<AuditService> auditServiceProvider) {
        this.auditServiceProvider = auditServiceProvider;
    }

    /**
     * 记一条审计事件。
     *
     * @param event 事件体
     * @return 落链后的事件（含 hash / prevHash）
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> record(@RequestBody AuditEvent event) {
        AuditService service = auditServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("审计服务未装配"));
        }
        AuditEvent recorded = service.record(event);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("event", recorded == null ? null : recorded.toView());
        return ResponseEntity.ok(body);
    }

    /**
     * 按条件查事件。
     *
     * @param tenantId   租户
     * @param operatorId 操作人
     * @param action     动作
     * @param traceId    追踪 ID
     * @param targetType 对象类型
     * @param targetId   对象 ID
     * @param limit      条数
     * @return 事件列表
     */
    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        AuditService service = auditServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("审计服务未装配"));
        }
        AuditQuery.AuditCriteria criteria = new AuditQuery.AuditCriteria();
        criteria.setTenantId(tenantId);
        criteria.setOperatorId(operatorId);
        criteria.setAction(action);
        criteria.setTraceId(traceId);
        criteria.setTargetType(targetType);
        criteria.setTargetId(targetId);
        List<AuditEvent> events = service.list(criteria, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("total", events.size());
        body.put("events", events.stream().map(AuditEvent::toView).toList());
        return ResponseEntity.ok(body);
    }

    /**
     * 校验哈希链完整性。
     *
     * @param tenantId 租户
     * @param from     起（epoch 毫秒；缺省 0）
     * @param to       止（epoch 毫秒；缺省 Long.MAX_VALUE）
     * @return 校验结果
     */
    @GetMapping("/chain/verify")
    public ResponseEntity<Map<String, Object>> verifyChain(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false, defaultValue = "0") long from,
            @RequestParam(required = false, defaultValue = "9223372036854775807") long to) {
        AuditService service = auditServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("审计服务未装配"));
        }
        AuditService.ChainVerifyResult result = service.verifyChain(tenantId, from, to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("intact", result.isIntact());
        body.put("checked", result.getChecked());
        body.put("total", result.getTotal());
        body.put("message", result.getMessage());
        return ResponseEntity.ok(body);
    }

    /**
     * 取链尾事件（最新一条）。
     *
     * @param tenantId 租户
     * @return 链尾事件
     */
    @GetMapping("/chain/latest")
    public ResponseEntity<Map<String, Object>> latest(
            @RequestParam(required = false) String tenantId) {
        AuditService service = auditServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("审计服务未装配"));
        }
        AuditEvent latest = service.latest(tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("event", latest == null ? null : latest.toView());
        return ResponseEntity.ok(body);
    }

    /** 错误体。 */
    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }
}
