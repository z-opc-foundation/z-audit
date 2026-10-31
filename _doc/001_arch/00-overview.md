# 总体设计

> 口径截至 2026-10-31 的 `main`（M1 哈希链 + M2 链头锚定已落地）。README 的
> 「能力清单 / 核心口径 / 踩坑留档」是逐条细节的权威位置，本文只讲**架构切分与不变式**。

## 1. 这仓解决什么问题

审计的意义不是「记过」，而是「**记过之后可证明没被改过**」。z-audit 用哈希链 + 外部锚点做到这点：

```
record(事件) ──▶ prevHash = 上一条 hash，hash = SHA-256(本条内容 + prevHash) ──▶ Sink 落库/外发
verify()     ──▶ 逐条重算比对 ⇒ 中间改/删可检出
anchor()     ──▶ 记链尾 hash + 累计条数 + 时刻 + fingerprint ⇒ 尾部截断可检出
```

边界一句话：**只证明「发生过什么、没被改过」**，不做业务判断、不存业务数据、不替调用方决定要不要记。

## 2. 模块与依赖方向

```
z-audit/
├── z-audit-core/                 纯领域层，不依赖 Spring，可脱离数据库单测
│   ├── domain/    AuditEvent · AuditAction(18 值) · AuditResult · RetentionPolicy · AuditRecord
│   │              ChainAnchor · ChainAnchorRecord
│   ├── spi/       AuditSink（写出口）· AuditQuery（读出口）· ChainAnchorStore
│   ├── service/   AuditService（门面）· PersistentAuditStore（MyBatis-Plus 落库）
│   │              ChainAnchorService · PersistentChainAnchorStore
│   ├── mapper/    AuditEventMapper · ChainAnchorMapper
│   └── support/   InMemoryAuditStore · InMemoryChainAnchorStore（测试 / 轻量场景）
├── z-audit-starter/              引擎装配（仅此一处）+ 本域数据源（自足带走，3 例）
│   └── config/    AuditStarterAutoConfiguration · AuditModuleDataSource（@MapperScan）
│                  · AuditChainTailLoader（启动重载链尾）
└── z-audit-web/                  管控面部署：依赖 starter，只管 REST + 健康检查（20 例）
    ├── config/    AuditAutoConfiguration（健康检查）· AuditHealthIndicator
    └── api/       AuditEventController · ChainAnchorController · AuditHealthController
```

三条结构约束：

1. **写读分离**：`AuditSink`（写）与 `AuditQuery`（读）是两个口，`PersistentAuditStore` 同时实现两者；
   门面 `AuditService` **不实现** `AuditQuery`（`setQuery(AuditService)` 编译不过，这是刻意的）。
2. **链与锚分表**：`z_audit_event` 与 `z_audit_chain_anchor` 分开存 —— 锚点若与链同表，
   删表尾就能顺手改锚，外部锚定失去意义。
3. core 不依赖同族任何仓 —— 合规层被编排层调用，反向不依赖。
4. **引擎装配只有一处**（starter，M2 #23）：web 不重复定义任何引擎 bean；
   本仓 starter 与三个能力仓不同——**自足带走本域数据源**
   （引擎侧存储要构造器注入 Mapper，数据源与引擎装配同生共死，拆不进 web）。

## 3. 不变式（改代码必须守住）

| 不变式 | 实现位置 | 破掉的后果 |
|---|---|---|
| 参与哈希的字段用**固定顺序 + `\u0001` 分隔符** | `AuditEvent#computeHash` | 字段拼接歧义 ⇒ 两条不同事件算出同一 hash |
| 链头哨兵 = 全零 64 位 `GENESIS_HASH` | `AuditEvent` | 首条 `prevHash` 无定义 ⇒ 链起点不可验证 |
| 写链三步（取链尾 ⇒ 算 hash ⇒ 落库）**串行化** | `AuditService#record` 的 `synchronized` | 并发取到同一 `prevHash` ⇒ **链分叉** |
| 出口异常**吞掉记日志**，不外抛 | `AuditService#record` | 审计抖动拖垮主链路 |
| `FAILURE`（执行出错）与 `DENIED`（未执行）分开 | `domain/AuditResult` | 内审查不出越权尝试 |
| 出域动作（EXPORT/DOWNLOAD/PRINT）留存 1095 天 | `domain/RetentionPolicy` | 违反合规基线 |
| 锚点 `fingerprint` = 锚点内容的 SHA-256 | `ChainAnchorService` | 同时改 `chainHash` 与 `eventCount` 就能伪装一致 |
| **不可判定 ≠ 通过**（无锚 / 无存储 / 无查询 ⇒ `indeterminate=true`） | `ChainAnchorService#verify` | 「忘了打锚」被读成「审计完整」 |
| `tenant_id` 用空串表示「全域」，**禁止 NULL** | 表结构 + Mapper | MySQL 唯一索引里 NULL≠NULL ⇒ 同键堆积、upsert 退化 |
| Mapper **返回实体不返回 Map** | `ChainAnchorMapper` | H2 `MODE=MySQL` 把别名转大写 ⇒ `map.get("tenantId")` 恒 null ⇒ 指纹必然不匹配 |

校验判据全表（`ChainAnchorService#verify`）与 REST 端点清单见
[README](../../README.md) 的「链头锚定」「REST 端点」两节。

## 4. 已知边界（不是 bug）

| 边界 | 影响 | 状态 |
|---|---|---|
| 未打锚的区间无法判尾部截断 | 锚点之间的窗口靠纪律 | M2 已提供锚点，需定期打 |
| 老行 `fingerprint` 为空 ⇒ 升级后判「不可信」 | 要求重新打锚 | **故意如此**，比默认可信安全 |
| `CREATE TABLE IF NOT EXISTS` 不给已建表加列 | 只改 `init.sql` 对新库生效 | 一次性增量迁移 `init/002_anchor_fingerprint.sql` |

## 5. 相关文档

- 部署与 DDL：[`_doc/002_deploy/init.sql`](_doc/002_deploy/init.sql)（2 张表，幂等）·
  [`_doc/002_deploy/init/`](_doc/002_deploy/init/)（一次性增量迁移，只执行一次）
- 复跑与实测读数：[`_doc/005_testing/001_验收复跑.md`](_doc/005_testing/001_验收复跑.md)
- 用例数与逐条读数不在本文维护（会随每次加用例过期），权威位置是 README「测试」节与验收复跑文档。
- web 层测试**从生产 DDL 脚本读表结构**（`AuditTestSchema`），不内嵌第二份 DDL —— 内嵌必漂移。
