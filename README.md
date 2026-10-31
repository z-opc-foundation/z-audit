# z-audit

> **审计与合规留痕** —— 事件模型 + **哈希链** + 事件出口 SPI + 留存期策略。
> 边界一句话：**只证明「发生过什么、没被改过」，不做业务判断、不存业务数据。**

安全线四层能力栈的合规层。同族：`z-sense` · `z-mask` · `z-watermark` · `z-biz-security`（编排）。

立项文档：lead `002_项目需求/001_特性需求/015_z-biz-security_数据安全线`。

## 📋 基本信息

| 项 | 值 |
|---|---|
| 层 | L3 能力仓（可被 Maven Central 口径消费；**本轮不发制品**） |
| 坐标 | `io.github.yuku123:z-audit`，版本 `1.0.0` |
| parent | `io.github.yuku123:z-boot-parent:1.2.0`（Java 17 / Spring Boot 3.3.13 火车） |
| 包名 | `com.zifang.z.audit.core.*` |
| 端口 | 8097 |
| 库 | 共用单库 `oc`（同 z-biz / z-trade） |
| 模块 | `z-audit-core` · `z-audit-web` |

## 🎯 能力清单

| 能力 | 状态 | 代码位置 |
|---|---|---|
| 审计事件模型（17 个字段） | ✅ | `domain/AuditEvent` |
| **哈希链**（prevHash + hash，篡改可检出） | ✅ | `domain/AuditEvent#computeHash` / `#verify` |
| 动作枚举（18 种，含出域标记） | ✅ | `domain/AuditAction` |
| 结果枚举（SUCCESS / FAILURE / DENIED） | ✅ | `domain/AuditResult` |
| 事件出口 SPI（落库 / 日志 / 外部，多路） | ✅ | `spi/AuditSink` |
| 查询 SPI（写与读分离） | ✅ | `spi/AuditQuery` |
| 留存期策略（按动作分档） | ✅ | `domain/RetentionPolicy` |
| 落库实现（MyBatis-Plus Mapper） | ✅ | `service/PersistentAuditStore` · `mapper/AuditEventMapper` |
| 内存实现（测试 / 轻量场景） | ✅ | `support/InMemoryAuditStore` |
| REST 端点（记 / 查 / 验链 / 取链尾） | ✅ | `web/api/AuditEventController` |
| **链头锚定**（防尾部截断） | ❌ M2 | 表已建 `z_audit_chain_anchor`，逻辑待落地 |

## 🔑 核心口径

### 哈希链 —— 这才叫「合规留痕」

普通流水只能证明「记过」；哈希链能证明「**没被改过**」。

每条事件带两个哈希字段：

```
prevHash = 上一条事件的 hash
hash     = SHA-256(本条内容 + prevHash)
```

任何一条被改（时间改了 / 操作人改了 / 内容删了），其后所有 `hash` 都对不上 ⇒ **篡改可检出**。

- 参与哈希的字段用**固定顺序 + 明确分隔符**（`\u0001`）拼接
  ⇒ 避免字段拼接歧义（`a="x|b", c="y"` 与 `a="x", b="|y"` 拼出同一串）
- 链头哨兵 = 全零 64 位（`GENESIS_HASH`）

### ⚠ 已知边界：尾部截断查不出来

哈希链能查出「中间被改 / 被删」，**查不出「最后几条被删」** ——
因为没有后继记录能证明「本该有第 N 条」。

**防这个要靠外部锚定**：把链头 hash 定期快照到独立存储（`z_audit_chain_anchor` 表已建好），
或把链头 hash 上链（区块链存证）。**这是设计取舍，不是 bug**，文档与验收脚本都要认这个口径。

### 动作枚举 vs 自由字符串

动作是**合规口径**（内审要按动作维度统计「谁在什么时候导出过数据」）。
自由字符串会出现「EXPORT」「export」「导出」三种写法 ⇒ 统计口径失真。
枚举把口径钉死，未覆盖的动作走 `OTHER` + `detail` 兜底。

`AuditAction#isEgress()` 标出**数据出域**动作（EXPORT / DOWNLOAD / PRINT）——
这类动作留存期最长（3 年）。

### FAILURE 与 DENIED 分开

| 结果 | 含义 | 内审关注点 |
|---|---|---|
| `FAILURE` | 系统尝试了，但执行出错（如下游超时） | 查稳定性 |
| `DENIED` | 权限校验没通过，**根本没执行** | **查越权行为**（合规内审最常看这个） |

### 留存期按动作分档

| 动作 | 留存天数 | 理由 |
|---|---|---|
| EXPORT / DOWNLOAD / PRINT | **1095**（3 年） | 合规基线（数据出域） |
| AUTH_FAILURE / GRANT / REVOKE / CONFIG_CHANGE | 730（2 年） | 高危动作 |
| 其余 | **180**（半年） | 默认档 |

> 按动作分档不只是合规要求，也是**存储成本控制**（审计流水量最大的是读操作）。

### 审计不得拖垮主链路

出口抛异常 ⇒ **吞掉记日志**，审计失败不影响业务。`AuditService#record` 写链三步
（取链尾 ⇒ 算 hash ⇒ 落库）用 `synchronized` 串行化 —— 并发下两条事件拿到同一个
`prevHash` 会**链分叉**（正确性比并发度重要）。

## 🏗️ 项目结构

```
z-audit/
├── pom.xml
├── z-audit-core/
│   └── src/main/java/com/zifang/z/audit/core/
│       ├── domain/    AuditEvent · AuditAction · AuditResult · RetentionPolicy · AuditRecord
│       ├── spi/       AuditSink（出口）· AuditQuery（查询）
│       ├── service/   AuditService（门面）· PersistentAuditStore（落库实现）
│       ├── mapper/    AuditEventMapper
│       └── support/   InMemoryAuditStore（内存实现，同时是 Sink 与 Query）
├── z-audit-web/
│   └── src/main/java/com/zifang/z/audit/web/
│       ├── config/    AuditAutoConfiguration · AuditModuleDataSource · AuditHealthIndicator
│       └── api/       AuditEventController · ZAuditWebApplication
└── _doc/002_deploy/init.sql    # 2 张表（幂等）
```

## 📡 REST 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/z-team/api/audit/events` | 记一条事件（自动接链 + 算 hash） |
| GET | `/z-team/api/audit/events` | 按条件查（租户/操作人/动作/追踪 ID/对象） |
| GET | `/z-team/api/audit/chain/verify` | 校验哈希链完整性 |
| GET | `/z-team/api/audit/chain/latest` | 取链尾事件 |
| GET | `/api/audit/health` | 健康检查（**无 /z-team 前缀**，探活路径不带业务前缀） |

## 🧪 测试

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
mvn -f z-audit-core/pom.xml test    # 24 例
mvn -f z-audit-web/pom.xml test     # 5 例（H2 真落库）
```

**core 24 例**：默认值补全、null 安全、出口异常不外抛、链头哨兵、链式相连、
篡改检出、删除检出、尾部截断（已知边界，断言为「不可检出」）、空链完整、
无查询出口降级、hash 依赖 prevHash、verify 自洽、按操作人/动作查询、取链尾、
留存期各档、过期判定、可覆盖、枚举解析、出域标记。

**web 5 例**：DDL 幂等（建三遍不报错）、POST 真落库 + 链式 prevHash、
GET 过滤、**绕过应用直接改库 ⇒ 检出链断**、取链尾。

> ⚠ web 层测试**从生产 DDL 脚本读表结构**（`AuditTestSchema`），
> 不内嵌第二份 DDL —— 内嵌一份必然漂移 ⇒ 测试全绿但生产炸。

## 📌 踩坑留档

| 坑 | 现象 | 修法 |
|---|---|---|
| **`@ConditionalOnBean` 早于 `@MapperScan`** | 自动装配里判 Mapper 存在 ⇒ 条件恒不成立 ⇒ 服务静默缺失（接口 503） | 改直接构造器注入（`@MapperScan` 注册时机早于本装配的 `@Bean` 求值） |
| **测试删副本没生效** | `store.all()` 返回副本 ⇒ `remove` 没删到真数据 ⇒ 「删除必须被检出」用例假绿 | 加 `removeByEventId()` 专用方法；顺带把「尾部截断不可检出」显式写成用例 |
