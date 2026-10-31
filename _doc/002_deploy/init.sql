-- =============================================================================
-- z-audit 审计与合规留痕 DDL —— 2 张表
-- =============================================================================
-- 目标库：共用单库 `oc`（与 z-biz / z-trade 同库；库名改动只动本文件与配置两处）
-- 方言  ：MySQL 8.0（生产）；同一份脚本在 H2 `MODE=MySQL` 下也可直接执行（本地验收用）
--
-- ⚠️ 幂等口径（**本脚本可重复执行，第二遍不建表、不报错、不改结构**）：
--    全部语句都是 `CREATE TABLE IF NOT EXISTS` + 表级 `CONSTRAINT`（无 `CREATE INDEX`）。
--    之所以不写独立的 `CREATE INDEX`：MySQL 8.0 **没有** `CREATE INDEX IF NOT EXISTS`，
--    重复执行会报「Duplicate key name」⇒ 破坏幂等。二级索引需求请另开
--    `_doc/002_deploy/init/002_*.sql` 并**只执行一次**（或改用 UNIQUE 约束自带索引）。
--
-- ⚠️ 故意**不写**的东西（都是为了让同一份脚本在 MySQL 8.0 与 H2 上都能跑）：
--    - `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` / `COLLATE`：库级默认字符集口径，
--      执行前请确认目标库默认字符集是 `utf8mb4`（`oc` 库已是）。
--    - 列级 / 表级 `COMMENT`：MySQL 支持、H2 不支持，改为本文件里的 `--` 注释。
--    - 外键约束：`oc` 库是多工作负载共用库，本域**不建外键**（跨域引用只存 id）。
--
-- ⚠️ 本脚本**只允许在人工确认后**对生产 `oc` 库执行；自动化流水线不执行本文件。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. z_audit_event —— 审计事件台账（**哈希链**）
--    幂等键：event_id（唯一）。同一事件重复投递不产生第二条。
--
--    ⚠️ **哈希链的关键列**：
--      prev_hash = 上一条的 hash（链式绑定）
--      hash     = SHA-256(本条内容 + prev_hash)
--    ⇒ 中间任意一条被改 / 被删，后续所有 hash 都对不上 ⇒ 篡改可检出。
--
--    ⚠️ **已知边界**：链**尾**被截断（最后几条被删）查不出来 ——
--      没有后继记录能证明「本该有第 N 条」。防这个要靠**外部锚定**：
--      把链头 hash 定期快照到独立存储，或上链存证。
--
--    ⚠️ occurred_at 用 **BIGINT 存 epoch 毫秒**（不用 DATETIME）：
--      台账要按毫秒排序、要做哈希链比对，用 BIGINT 避免时区/精度歧义。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS z_audit_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      VARCHAR(64)  NOT NULL,
    trace_id      VARCHAR(64)  NULL,
    operator_id   VARCHAR(64)  NULL,
    operator_name VARCHAR(64)  NULL,
    tenant_id     VARCHAR(64)  NULL,
    action        VARCHAR(32)  NOT NULL,
    result        VARCHAR(16)  NOT NULL,
    target_type   VARCHAR(64)  NULL,
    target_id     VARCHAR(128) NULL,
    detail        VARCHAR(1024) NULL,
    client_ip     VARCHAR(64)  NULL,
    user_agent    VARCHAR(512) NULL,
    occurred_at   BIGINT       NOT NULL,
    prev_hash     VARCHAR(64)  NOT NULL,
    hash          VARCHAR(64)  NOT NULL,
    CONSTRAINT pk_z_audit_event PRIMARY KEY (id),
    CONSTRAINT uk_z_audit_event_event_id UNIQUE (event_id)
);

-- ---------------------------------------------------------------------------
-- 2. z_audit_chain_anchor —— 哈希链**锚点表**（防尾部截断）
--    定期把「某个时间点的链头/链尾 hash + 事件条数」快照到这里。
--    校验时拿快照的 hash 与实际链比对 ⇒ 尾部被删也能发现。
--
--    幂等键：(tenant_id, anchor_key) 唯一 —— 同键覆盖写（幂等 upsert 语义）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS z_audit_chain_anchor (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     VARCHAR(64) NULL,
    anchor_key    VARCHAR(64) NOT NULL,
    chain_hash    VARCHAR(64) NOT NULL,
    event_count   BIGINT      NOT NULL,
    anchored_at   BIGINT      NOT NULL,
    CONSTRAINT pk_z_audit_chain_anchor PRIMARY KEY (id),
    CONSTRAINT uk_z_audit_chain_anchor_key UNIQUE (tenant_id, anchor_key)
);
