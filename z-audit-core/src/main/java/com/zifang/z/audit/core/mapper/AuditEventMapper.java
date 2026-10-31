package com.zifang.z.audit.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zifang.z.audit.core.domain.AuditRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 审计事件 Mapper（表 {@code z_audit_event}）。
 *
 * <p>由 web 层 {@code @MapperScan(basePackages = "com.zifang.z.audit.core.mapper",
 * sqlSessionFactoryRef = "sqlSessionFactoryAudit")} 绑到**本域** sqlSessionFactory 上。
 *
 * <p>查询用**显式 SQL**（列名带 {@code AS} 别名 ⇒ 不依赖
 * {@code map-underscore-to-camel-case} 配置，MySQL 8.0 与 H2（{@code MODE=MySQL}）行为一致）。
 *
 * @author zifang
 */
@Mapper
public interface AuditEventMapper extends BaseMapper<AuditRecord> {

    /** 列清单（别名 → 实体字段名）。 */
    String COLUMNS = "id, event_id AS eventId, trace_id AS traceId, operator_id AS operatorId, "
            + "operator_name AS operatorName, tenant_id AS tenantId, action, result, "
            + "target_type AS targetType, target_id AS targetId, detail, client_ip AS clientIp, "
            + "user_agent AS userAgent, occurred_at AS occurredAt, prev_hash AS prevHash, hash";

    /**
     * 取链尾事件（最新一条）。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @return 链尾事件；无记录返回 null
     */
    @Select("<script>SELECT " + COLUMNS + " FROM z_audit_event "
            + "<where> <if test='tenantId != null and tenantId != \"\"'> tenant_id = #{tenantId} </if> </where>"
            + " ORDER BY occurred_at DESC, id DESC LIMIT 1</script>")
    AuditRecord selectLatest(@Param("tenantId") String tenantId);

    /**
     * 按时间窗取事件（**正序**）—— 校验哈希链完整性用。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @param from     起（epoch 毫秒）
     * @param to       止（epoch 毫秒）
     * @param limit    上限
     * @return 事件列表
     */
    @Select("<script>SELECT " + COLUMNS + " FROM z_audit_event "
            + "<where> <if test='tenantId != null and tenantId != \"\"'> tenant_id = #{tenantId} </if> "
            + " AND occurred_at &gt;= #{from} AND occurred_at &lt;= #{to} </where>"
            + " ORDER BY occurred_at ASC, id ASC LIMIT #{limit}</script>")
    java.util.List<AuditRecord> selectRange(@Param("tenantId") String tenantId,
                                            @Param("from") long from,
                                            @Param("to") long to,
                                            @Param("limit") int limit);

    /**
     * 按条件查事件（**倒序**）。
     *
     * @param tenantId   租户
     * @param operatorId 操作人
     * @param action     动作
     * @param traceId    追踪 ID
     * @param targetType 对象类型
     * @param targetId   对象 ID
     * @param from       起
     * @param to         止
     * @param limit      上限
     * @return 事件列表
     */
    @Select("<script>SELECT " + COLUMNS + " FROM z_audit_event "
            + "<where> <if test='tenantId != null and tenantId != \"\"'> tenant_id = #{tenantId} </if> "
            + "<if test='operatorId != null and operatorId != \"\"'> AND operator_id = #{operatorId} </if> "
            + "<if test='action != null and action != \"\"'> AND action = #{action} </if> "
            + "<if test='traceId != null and traceId != \"\"'> AND trace_id = #{traceId} </if> "
            + "<if test='targetType != null and targetType != \"\"'> AND target_type = #{targetType} </if> "
            + "<if test='targetId != null and targetId != \"\"'> AND target_id = #{targetId} </if> "
            + "<if test='from != null'> AND occurred_at &gt;= #{from} </if> "
            + "<if test='to != null'> AND occurred_at &lt;= #{to} </if> </where>"
            + " ORDER BY occurred_at DESC, id DESC LIMIT #{limit}</script>")
    java.util.List<AuditRecord> selectByCriteria(@Param("tenantId") String tenantId,
                                                  @Param("operatorId") String operatorId,
                                                  @Param("action") String action,
                                                  @Param("traceId") String traceId,
                                                  @Param("targetType") String targetType,
                                                  @Param("targetId") String targetId,
                                                  @Param("from") Long from,
                                                  @Param("to") Long to,
                                                  @Param("limit") int limit);

    /**
     * 清理过期事件（按留存期策略）—— 返回删除条数。
     *
     * @param expireBefore 早于该时刻的删掉
     * @return 删除条数
     */
    @Delete("DELETE FROM z_audit_event WHERE occurred_at &lt; #{expireBefore}")
    int deleteExpired(@Param("expireBefore") long expireBefore);
}
