# 部署三模式 —— 审计域视角（M2 #34）

> 本域是 `z_audit_event` 哈希链台账的**候选唯一写者**。三模式的完整对照表在编排层仓
> `z-biz-security/_doc/002_deploy/deploy-modes.md`，本文只写**审计域自己**在各模式下的角色与红线。

## 本域在三模式下的角色

| 模式 | z-audit 服务的角色 | 本域红线 |
|---|---|---|
| A：编排层同进程直写（默认） | **不部署，或只做查询 / 打锚点 / 校验面** | ⛔ **绝不能开写**（POST `/z-team/api/audit/events` 落库 = 第二个写者 ⇒ 链分叉） |
| B：分进程 HTTP | `z_audit_event` **唯一写者**（编排层 / watermark 走 HTTP 交过来） | ✅ 写入口只有本域开；`z.base.db.audit.*` 指向权威台账库 |
| C：独立审计域（台账中心） | 唯一写者 + 中心化治理（锚点集中、独立权限、独立扩容） | 同 B；多业务面共用一张链 ⇒ **更**不能有任何旁路直写 |

## 本域各模式通用配置

- 开发 / 测试直连：`z.base.db.audit.url=jdbc:h2:...`（不套 MySQL 键名模式）；
- 生产口径：`z.base.db.audit.*` 键名模式（`ModuleDataSourceTemplate`）；
- DDL：`_doc/002_deploy/init.sql`（幂等；`z_audit_event` + `z_audit_chain_anchor` 两表）。

## 审计域不变式（部署形态怎么换都不许破）

1. **单写者**：任何时刻写 `z_audit_event` 的进程只有一个（模式 A = 编排层进程，B/C = 本域服务进程）；
2. **链尾重启必重载**：`AuditChainTailLoader`（starter 提供）—— 装载失败不阻断启动，但
   没重载就写 = 新事件 `prevHash` 全零 = 链分叉；
3. **锚点外部化**：链锚点（`z_audit_chain_anchor`）与链分表 —— 锚点与链同表 ⇒ 删表尾顺手改锚 ⇒
   外部锚定失去意义；
4. **尾部截断不可单独依赖链校验**：截断后剩余链自身「完整」⇒ 必须靠锚点校验
   （`GET /z-team/api/audit/chain/anchor/verify`）才能检出。

## 验收（防链分叉）

- `GET /z-team/api/audit/chain/verify` 完整；
- 打锚点后 `GET /z-team/api/audit/chain/anchor/verify` 通过；
- 同一 `traceId` 反查：`WM:` 与 `SEC:` 行 `prevHash` 相连（同一条链）。
