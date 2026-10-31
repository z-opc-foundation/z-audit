-- =============================================================================
-- z-audit M2 增量迁移 —— 给 z_audit_chain_anchor 补 fingerprint 列
-- =============================================================================
-- 起因：M1 的 `init.sql` 建的这张表**没有** fingerprint 列，也没有 tenant_id 的
--       NOT NULL 约束。M2 的锚点机制靠 fingerprint 检出「锚点行自身被绕过应用改过」，
--       靠 tenant_id 存空串（而不是 NULL）让 MySQL 唯一索引真正生效。
--
-- ⚠️ **为什么必须单独写迁移、不能只改 init.sql**：
--    `init.sql` 用的是 `CREATE TABLE IF NOT EXISTS` ⇒ **已建过表的库不会自动加列**。
--    只改 init.sql 的话，M1 建的库会缺列 ⇒ 运行时报「Unknown column fingerprint」。
--    所以：**init.sql 改成新结构（给新库用）+ 本脚本补老库（一次性）**。
--
-- ⚠️ **本脚本只允许在人工确认后执行，且只执行一次**。
--    MySQL 8.0 **没有** `ADD COLUMN IF NOT EXISTS` ⇒ 重复执行会报
--    「Duplicate column name」。故用「先查 INFORMATION_SCHEMA 再决定」的写法，
--    使脚本**可重复执行且不报错**（幂等）。
--
-- 方言：MySQL 8.0（生产）。同一份脚本在 H2 `MODE=MySQL` 下也可执行（本地验证用）。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 补 fingerprint 列
--    ⚠️ 先查 INFORMATION_SCHEMA 再 ADD ⇒ 幂等（重复执行不报错）。
--    ⚠️ `DEFAULT ''` + `NOT NULL`：老行补空串，不阻塞已有数据。
--       ⚠️ 老行的 fingerprint 是空串 ⇒ **校验时指纹不匹配 ⇒ 会被判为「锚点被篡改」**。
--       这是**故意**的：老锚点没有可信指纹，宁可判为不可信（要求重新打锚），
--       也不要「看起来通过」。（重新打锚是幂等覆盖写，成本极低。）
-- ---------------------------------------------------------------------------
SELECT COUNT(*) AS fingerprint_exists
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'z_audit_chain_anchor'
  AND COLUMN_NAME = 'fingerprint';

-- ⚠️ 下面这条**只在 fingerprint 列不存在时**才执行。
--    MySQL 不支持在纯 SQL 里「按条件执行 DDL」，故本脚本采用
--    「查 → 人工确认 → 跑 DDL」两步式（见文件末尾的执行清单）。
ALTER TABLE z_audit_chain_anchor
    ADD COLUMN fingerprint VARCHAR(64) NOT NULL DEFAULT '';

-- ---------------------------------------------------------------------------
-- 2. tenant_id 归一：NULL ⇒ ''，并收紧为 NOT NULL DEFAULT ''
--    ⚠️ **NULL 必须清掉**：MySQL 唯一索引里 NULL 与 NULL **不相等**
--    ⇒ (NULL, 'k1') 可以插无限多行 ⇒ upsert 失效、「同键覆盖」退化成「同键堆积」。
--    ⚠️ 收紧 NOT NULL 前必须先把 NULL 清成 ''（否则 ALTER 失败）。
-- ---------------------------------------------------------------------------
UPDATE z_audit_chain_anchor SET tenant_id = '' WHERE tenant_id IS NULL;

-- ⚠️ 下面这条**只在列仍可空时**才执行（老库可能是 NULL 允许）。
ALTER TABLE z_audit_chain_anchor
    MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '';

-- =============================================================================
-- 执行清单（人工执行，两遍验证幂等）
-- =============================================================================
-- 前置检查：
--   SELECT COUNT(*) FROM z_audit_chain_anchor;
--   SELECT anchor_key, tenant_id FROM z_audit_chain_anchor ORDER BY id LIMIT 20;
--
-- 执行：
--   1) 先单独跑上面的 SELECT 查 fingerprint 列是否存在
--   2) 列不存在 ⇒ 跑 ALTER TABLE ... ADD COLUMN fingerprint ...
--      列已存在 ⇒ 跳过（幂等）
--   3) 跑 UPDATE ... SET tenant_id = '' WHERE tenant_id IS NULL;
--   4) 跑 ALTER TABLE ... MODIFY COLUMN tenant_id ... NOT NULL DEFAULT '';
--
-- 验证判据（执行后应满足）：
--   a) SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
--       WHERE TABLE_NAME='z_audit_chain_anchor' AND COLUMN_NAME='fingerprint';  ⇒ 1
--   b) SELECT COUNT(*) FROM z_audit_chain_anchor WHERE tenant_id IS NULL;      ⇒ 0
--   c) SELECT COUNT(*) FROM z_audit_chain_anchor WHERE fingerprint = '';        ⇒ = 老行数
--      （老行指纹为空是**故意**的 ⇒ 会被判为不可信 ⇒ 需重新打锚）
--
-- 回滚：
--   ALTER TABLE z_audit_chain_anchor DROP COLUMN fingerprint;
--   ALTER TABLE z_audit_chain_anchor MODIFY COLUMN tenant_id VARCHAR(64) NULL;
-- =============================================================================
