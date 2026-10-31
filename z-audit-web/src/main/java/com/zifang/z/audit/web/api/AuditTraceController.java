package com.zifang.z.audit.web.api;

import com.zifang.z.audit.core.domain.AuditAction;
import com.zifang.z.audit.core.domain.AuditEvent;
import com.zifang.z.audit.core.domain.AuditResult;
import com.zifang.z.audit.core.domain.AuditTraceResult;
import com.zifang.z.audit.core.service.AuditService;
import com.zifang.z.audit.core.service.AuditTraceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ 溯源闭环端点（M2 #22）。
 *
 * <p><b>路由前缀</b>：{@code /z-team/api/audit/trace/**}。
 *
 * <p><b>为什么审计域要开这两个口</b>：
 * <ul>
 *   <li>{@code POST /trace} —— <b>写入</b>：z-watermark 打完水印后把
 *       「谁 / 什么单号 / 哪条追踪码」交过来落台账。
 *       ⚠️ <b>本域是 {@code z_audit_event} 表的唯一写者</b> ——
 *       哈希链要求单写者（多写者各持链尾 ⇒ 链分叉）。</li>
 *   <li>{@code GET /trace/{traceId}} —— <b>反查</b>：拿到泄密内容的人提取出
 *       盲水印里的追踪码 ⇒ 调这个口 ⇒ 还原完整上下文。</li>
 * </ul>
 *
 * <p><b>★ 三态 HTTP 口径</b>（这是本控制器最重要的设计）：
 * <ul>
 *   <li><b>200</b> ⇒ 查到了（或写入成功）；</li>
 *   <li><b>404</b> ⇒ <b>台账里没有这条</b> —— 合规缺口，要报警；</li>
 *   <li><b>503</b> ⇒ <b>台账不可用</b> —— 基础设施故障，要修库。</li>
 * </ul>
 * ⚠️ 404 与 503 混为一谈 ⇒ 运维会以为「没打过水印」，实际是库挂了 ⇒ 漏报。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/z-team/api/audit/trace")
public class AuditTraceController {

    /** 审计门面（可空 ⇒ 未装配数据源）。 */
    private final ObjectProvider<AuditService> auditServiceProvider;
    /** 反查服务（可空 ⇒ 未装配）。 */
    private final ObjectProvider<AuditTraceService> traceServiceProvider;

    /**
     * 构造。
     *
     * @param auditServiceProvider 审计门面提供者
     * @param traceServiceProvider 反查服务提供者
     */
    public AuditTraceController(ObjectProvider<AuditService> auditServiceProvider,
                                ObjectProvider<AuditTraceService> traceServiceProvider) {
        this.auditServiceProvider = auditServiceProvider;
        this.traceServiceProvider = traceServiceProvider;
    }

    /**
     * ★ 写一条溯源记录（**幂等**：同一 eventId 重复提交只留一条）。
     *
     * <p>这是 z-watermark 打完水印后**唯一的落台账动作** ——
     * 编排层（z-biz-security）也走同一个口径 ⇒ 同一份内容不会被记两次。
     *
     * @param request 请求体
     * @return 写入结果
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> record(@RequestBody TraceRecordRequest request) {
        AuditService service = auditServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("审计服务未装配"));
        }
        if (request == null || request.getTraceId() == null || request.getTraceId().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(error("traceId 不能为空"));
        }
        AuditEvent event = new AuditEvent();
        // ★ 幂等键：eventId 由调用方给（默认 WM:<traceId>）⇒ 重放不产生第二条。
        event.setEventId(request.getEventId() == null || request.getEventId().isEmpty()
                ? "WM:" + request.getTraceId()
                : request.getEventId());
        event.setTraceId(request.getTraceId());
        event.setOperatorId(request.getOperatorId());
        event.setOperatorName(request.getOperatorName());
        event.setTenantId(request.getTenantId());
        event.setAction(AuditAction.WATERMARK);
        event.setResult(AuditResult.SUCCESS);
        event.setTargetType(request.getTargetType() == null ? "WATERMARK" : request.getTargetType());
        // ★ targetId 存业务单号 ⇒ 溯源时能直接看到「导的是哪份单子」。
        event.setTargetId(request.getBizNo());
        event.setDetail(request.getRemark());
        if (request.getOccurredAt() != null && request.getOccurredAt() > 0) {
            event.setOccurredAt(request.getOccurredAt());
        }
        event.setClientIp(request.getClientIp());
        event.setUserAgent(request.getUserAgent());

        AuditEvent recorded = service.record(event);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("eventId", recorded == null ? null : recorded.getEventId());
        body.put("traceId", request.getTraceId());
        body.put("hash", recorded == null ? null : recorded.getHash());
        return ResponseEntity.ok(body);
    }

    /**
     * ★ 按追踪码反查（**三态**）。
     *
     * @param traceId 追踪码
     * @return 反查结果
     */
    @GetMapping("/{traceId}")
    public ResponseEntity<Map<String, Object>> lookup(@PathVariable String traceId) {
        AuditTraceService traceService = traceServiceProvider.getIfAvailable();
        if (traceService == null) {
            return ResponseEntity.status(503).body(error("审计反查服务未装配"));
        }
        AuditTraceResult result = traceService.lookup(traceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", !result.isUnavailable());
        body.putAll(result.toView());
        if (result.isUnavailable()) {
            return ResponseEntity.status(503).body(body);
        }
        if (!result.isFound()) {
            return ResponseEntity.status(404).body(body);
        }
        return ResponseEntity.ok(body);
    }

    /** 错误体。 */
    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }

    /**
     * 溯源写入请求体。
     */
    public static class TraceRecordRequest {

        /** 追踪码（必填）。 */
        private String traceId;
        /** 事件 ID（幂等键；缺省 {@code WM:<traceId>}）。 */
        private String eventId;
        /** 操作人。 */
        private String operatorId;
        /** 操作人姓名。 */
        private String operatorName;
        /** 租户。 */
        private String tenantId;
        /** 业务单号（落 targetId）。 */
        private String bizNo;
        /** 对象类型（缺省 {@code WATERMARK}）。 */
        private String targetType;
        /** 备注。 */
        private String remark;
        /** 发生时刻（epoch 毫秒；缺省取当前）。 */
        private Long occurredAt;
        /** 客户端 IP。 */
        private String clientIp;
        /** User-Agent。 */
        private String userAgent;

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getOperatorId() {
            return operatorId;
        }

        public void setOperatorId(String operatorId) {
            this.operatorId = operatorId;
        }

        public String getOperatorName() {
            return operatorName;
        }

        public void setOperatorName(String operatorName) {
            this.operatorName = operatorName;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getBizNo() {
            return bizNo;
        }

        public void setBizNo(String bizNo) {
            this.bizNo = bizNo;
        }

        public String getTargetType() {
            return targetType;
        }

        public void setTargetType(String targetType) {
            this.targetType = targetType;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public Long getOccurredAt() {
            return occurredAt;
        }

        public void setOccurredAt(Long occurredAt) {
            this.occurredAt = occurredAt;
        }

        public String getClientIp() {
            return clientIp;
        }

        public void setClientIp(String clientIp) {
            this.clientIp = clientIp;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }
}
