package com.zifang.z.audit.core.mapper;

import com.zifang.z.audit.core.domain.ChainAnchorRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 链锚点 Mapper（表 {@code z_audit_chain_anchor}）。
 *
 * <p>由 web 层 {@code @MapperScan} 绑到**本域** sqlSessionFactory 上。
 *
 * <p><b>为什么用实体而不是 {@code Map}</b>：H2 在 {@code MODE=MySQL} 下会把未加引号的
 * 别名转成大写（{@code AS tenantId} ⇒ {@code TENANTID}），
 * MyBatis 映射成 Map 时直接用原始列标签做 key ⇒ 取不到值；映射成实体则正常。
 * 故本 Mapper 统一返回 {@link ChainAnchorRecord} 实体。
 *
 * <p>⚠️ **锚点表不在哈希链上** —— 它是「链外的独立存储」，
 * 删台账的人未必知道/删得掉它 ⇒ 这正是它能检出尾部截断的原因。
 *
 * @author zifang
 */
@Mapper
public interface ChainAnchorMapper {

    /** 列清单（别名 → 实体字段名）。 */
    String COLUMNS = "id, tenant_id AS tenantId, anchor_key AS anchorKey, "
            + "chain_hash AS chainHash, event_count AS eventCount, "
            + "anchored_at AS anchoredAt, fingerprint";

    /**
     * 按键取锚点。
     *
     * @param tenantId  租户（可空 ⇒ 全域）
     * @param anchorKey 锚点键
     * @return 锚点行；无记录返回 null
     */
    @Select("<script>SELECT " + COLUMNS + " FROM z_audit_chain_anchor "
            + "<where> <if test='tenantId != null and tenantId != \"\"'> tenant_id = #{tenantId} </if> "
            + "<if test='tenantId == null or tenantId == \"\"'> tenant_id = '' </if> "
            + " AND anchor_key = #{anchorKey} </where> LIMIT 1</script>")
    ChainAnchorRecord selectOne(@Param("tenantId") String tenantId,
                                 @Param("anchorKey") String anchorKey);

    /**
     * 列出某租户的全部锚点（**按锚点时刻正序**）。
     *
     * @param tenantId 租户（可空 ⇒ 全域）
     * @return 锚点行列表
     */
    @Select("<script>SELECT " + COLUMNS + " FROM z_audit_chain_anchor "
            + "<where> <if test='tenantId != null and tenantId != \"\"'> tenant_id = #{tenantId} </if> "
            + "<if test='tenantId == null or tenantId == \"\"'> tenant_id = '' </if> "
            + "</where> ORDER BY anchored_at ASC, id ASC</script>")
    List<ChainAnchorRecord> selectList(@Param("tenantId") String tenantId);

    /**
     * 按键**覆盖写**锚点（**幂等 upsert 语义** —— 同键覆盖，不是插入新行）。
     *
     * <p>⚠️ MySQL 8.0 与 H2 的 {@code ON DUPLICATE KEY UPDATE} 语法**兼容**，
     * 但 H2 在 {@code MODE=MySQL} 下对 {@code ON CONFLICT ... DO UPDATE} 支持更好；
     * 这里用 MySQL 口径的 {@code ON DUPLICATE KEY UPDATE}，H2 的 MySQL 兼容模式能跑。
     *
     * @param tenantId    租户
     * @param anchorKey   锚点键
     * @param chainHash   链尾哈希
     * @param eventCount  累计条数
     * @param anchoredAt  锚点时刻
     * @param fingerprint 锚点指纹
     * @return 影响行数
     */
    @Update("INSERT INTO z_audit_chain_anchor "
            + "(tenant_id, anchor_key, chain_hash, event_count, anchored_at, fingerprint) "
            + "VALUES (#{tenantId}, #{anchorKey}, #{chainHash}, #{eventCount}, #{anchoredAt}, #{fingerprint}) "
            + "ON DUPLICATE KEY UPDATE chain_hash = #{chainHash}, event_count = #{eventCount}, "
            + "anchored_at = #{anchoredAt}, fingerprint = #{fingerprint}")
    int upsert(@Param("tenantId") String tenantId,
               @Param("anchorKey") String anchorKey,
               @Param("chainHash") String chainHash,
               @Param("eventCount") long eventCount,
               @Param("anchoredAt") long anchoredAt,
               @Param("fingerprint") String fingerprint);

    /**
     * 删一个锚点（按键）。
     *
     * @param tenantId  租户
     * @param anchorKey 锚点键
     * @return 影响行数
     */
    @Delete("<script>DELETE FROM z_audit_chain_anchor "
            + "<where> <if test='tenantId != null and tenantId != \"\"'> tenant_id = #{tenantId} </if> "
            + "<if test='tenantId == null or tenantId == \"\"'> tenant_id = '' </if> "
            + " AND anchor_key = #{anchorKey} </where></script>")
    int deleteByKey(@Param("tenantId") String tenantId, @Param("anchorKey") String anchorKey);
}
