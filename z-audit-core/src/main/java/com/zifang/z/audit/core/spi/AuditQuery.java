package com.zifang.z.audit.core.spi;

import com.zifang.z.audit.core.domain.AuditEvent;

import java.util.List;

/**
 * 审计事件查询（SPI）。
 *
 * <p><b>为什么查询与写入分开</b>：写入是「事件流」，查询是「台账视角」
 * （要分页、要按维度筛、可能要跨月分区）。
 * 落库实现（MyBatis Mapper）与内存实现（测试 / 轻量场景）
 * 都能实现本接口 ⇒ 门面不绑定任何具体存储。
 *
 * @author zifang
 */
public interface AuditQuery {

    /**
     * 按条件查事件（**倒序**，最新的在前）。
     *
     * @param criteria 查询条件
     * @param limit    最多返回条数（≤0 ⇒ 用默认值 100）
     * @return 事件列表
     */
    List<AuditEvent> list(AuditCriteria criteria, int limit);

    /**
     * 取链尾事件（**最新一条**）—— 下一条事件的 {@code prevHash} 取它的 hash。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @return 链尾事件；无记录返回 null
     */
    AuditEvent latest(String tenantId);

    /**
     * 按时间窗取事件（**正序**）—— 校验哈希链完整性用。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @param from     起（epoch 毫秒，含）
     * @param to       止（epoch 毫秒，含）
     * @return 事件列表
     */
    List<AuditEvent> range(String tenantId, long from, long to);

    /**
     * 查询条件。
     */
    class AuditCriteria {

        /** 租户。 */
        private String tenantId;
        /** 操作人。 */
        private String operatorId;
        /** 动作。 */
        private String action;
        /** 追踪 ID。 */
        private String traceId;
        /** 操作对象类型。 */
        private String targetType;
        /** 操作对象 ID。 */
        private String targetId;
        /** 起（epoch 毫秒）。 */
        private Long from;
        /** 止（epoch 毫秒）。 */
        private Long to;

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getOperatorId() {
            return operatorId;
        }

        public void setOperatorId(String operatorId) {
            this.operatorId = operatorId;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public String getTargetType() {
            return targetType;
        }

        public void setTargetType(String targetType) {
            this.targetType = targetType;
        }

        public String getTargetId() {
            return targetId;
        }

        public void setTargetId(String targetId) {
            this.targetId = targetId;
        }

        public Long getFrom() {
            return from;
        }

        public void setFrom(Long from) {
            this.from = from;
        }

        public Long getTo() {
            return to;
        }

        public void setTo(Long to) {
            this.to = to;
        }
    }
}
