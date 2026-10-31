# z-audit

> **审计与合规留痕** —— 事件模型 + **哈希链** + 事件出口 SPI + 留存期策略。
> 边界一句话：**只证明「发生过什么、没被改过」，不做业务判断、不存业务数据。**

安全线四层能力栈的合规层。同族：`z-sense` · `z-mask` · `z-watermark` · `z-security`（编排）。

立项文档：lead `002_项目需求/001_特性需求/015_z-security_数据安全线`。

## 📋 基本信息

| 项 | 值 |
|---|---|
| 层 | L3 能力仓（可被 Maven Central 口径消费；**本轮不发制品**） |
| 坐标 | `io.github.yuku123:z-audit`，版本 `1.0.0` |
| parent | `io.github.yuku123:z-boot-parent:1.2.0`（Java 17 / Spring Boot 3.3.13 火车） |
| 包名 | `com.zifang.z.audit.core.*` |
| 端口 | 8097 |
| 库 | 共用单库 `oc`（同 z-biz / z-trade） |
| 模块 | `z-audit-core` · `z-audit-starter` · `z-audit-web` |

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
| **链头锚定**（防尾部截断） | ✅ | `service/ChainAnchorService` · `domain/ChainAnchor` · `spi/ChainAnchorStore` |
| **★ 按追踪码反查**（盲水印溯源的查询侧，M2 #22） | ✅ | `service/AuditTraceService` · `domain/AuditTraceResult` |
| **★ 三态区分**（查到 / 台账没有 / 台账不可用） | ✅ | `domain/AuditTraceResult` + `spi/AuditHealth` |
| **★ 溯源写入端点**（跨域落台账，幂等） | ✅ | `web/api/AuditTraceController`（`POST /z-team/api/audit/trace`） |
| **★ 重启链尾重载**（不链分叉） | ✅ | `service/AuditService#reloadChainTail` + `web/config/AuditChainTailLoader` |
| **★ 落库结果如实反映** | ✅ | `service/AuditService#recordWithOutcome` → `AuditOutcome` |

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

### ⚠ 尾部截断：靠「链头锚定」查出来（M2 已落地）

哈希链能查出「中间被改 / 被删」，**光看链本身查不出「最后几条被删」** ——
因为没有后继记录能证明「本该有第 N 条」。

M2 补上**外部锚定**（`z_audit_chain_anchor` 表，与链**分表存放**）：

| 锚点记三样 | 作用 |
|---|---|
| 链尾 `chainHash` | 证明「锚定时链长这样」 |
| 累计条数 `eventCount` | 证明「锚定时有这么多条」 |
| 锚点时刻 `anchoredAt` | 证明「什么时候打的锚」 |

**关键设计：锚点自身用 fingerprint（内容 SHA-256）绑成不可分割整体。**
篡改者若同时改 `chainHash` 与 `eventCount` 伪装一致 ⇒ 指纹对不上 ⇒ **照样露馅**。

校验判据（`ChainAnchorService#verify`）：

| 情况 | 判定 |
|---|---|
| 锚点指纹不自洽 | **检出篡改** |
| 实际条数 < 锚点条数 | **检出尾部截断** |
| 条数相等但链尾 hash 不同 | **检出链被改** |
| 条数更多 | 从锚点位置往后逐条验链 |
| 链为空 | **检出链被清空** |

#### ★ 「不可判定」是独立状态，不是「通过」

没打锚点 / 没配存储 / 没给查询出口 ⇒ 判 **`indeterminate=true`**，**绝不算通过**。
理由：**宁可说「查不了」，不可说「没问题」** —— 否则「忘了打锚」会被误读成「审计完整」。

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
│       │              ChainAnchor · ChainAnchorRecord
│       ├── spi/       AuditSink（出口）· AuditQuery（查询）· ChainAnchorStore
│       ├── service/   AuditService（门面）· PersistentAuditStore（落库实现）
│       │              ChainAnchorService · PersistentChainAnchorStore
│       ├── mapper/    AuditEventMapper · ChainAnchorMapper
│       └── support/   InMemoryAuditStore · InMemoryChainAnchorStore
├── z-audit-starter/
│   └── src/main/java/com/zifang/z/audit/starter/config/
│       ├── AuditStarterAutoConfiguration   引擎装配（仅此一处）+ @Import 本域数据源
│       ├── AuditModuleDataSource           本域数据源（含 @MapperScan，与引擎同生共死）
│       └── AuditChainTailLoader            启动重载链尾（重启后链不能分叉）
├── z-audit-web/
│   └── src/main/java/com/zifang/z/audit/web/
│       ├── config/    AuditAutoConfiguration（只管部署关切：健康检查，依赖 starter）
│       │              · AuditHealthIndicator
│       └── api/       AuditEventController · ChainAnchorController · ZAuditWebApplication
└── _doc/002_deploy/init.sql    # 2 张表（幂等）
    └── init/002_anchor_fingerprint.sql   # 一次性增量迁移（M1 老库补 fingerprint 列）
```

> **引擎装配只有一处**：本仓 starter 与三个能力仓不同——**自足带走本域数据源**
> （引擎侧存储要构造器注入 Mapper，Mapper 没有 sqlSessionFactory 就是空的
> ⇒ 数据源必须与引擎装配同生共死，拆不进 web）。web 依赖 starter、不重复定义。

## 📡 REST 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/z-team/api/audit/events` | 记一条事件（自动接链 + 算 hash） |
| GET | `/z-team/api/audit/events` | 按条件查（租户/操作人/动作/追踪 ID/对象） |
| GET | `/z-team/api/audit/chain/verify` | 校验哈希链完整性 |
| GET | `/z-team/api/audit/chain/latest` | 取链尾事件 |
| POST | `/z-team/api/audit/chain/anchor` | **打锚点**（记录链尾 hash + 累计条数） |
| GET | `/z-team/api/audit/chain/anchor/verify` | **校验锚点**（查尾部截断 / 锚点篡改） |
| GET | `/z-team/api/audit/chain/anchors` | 列锚点 |
| POST | `/z-team/api/audit/trace` | **★ 溯源写入**（z-watermark 打完水印后交过来落台账，幂等键 `WM:<traceId>`） |
| GET | `/z-team/api/audit/trace/{traceId}` | **★ 按追踪码反查**（三态：200 查到 / 404 台账没有 / 503 台账不可用） |
| GET | `/api/audit/health` | 健康检查（**无 /z-team 前缀**，探活路径不带业务前缀） |

> ★ **本域是 `z_audit_event` 表的唯一权威写者**（哈希链要求单写者 ——
> 多写者各持链尾 ⇒ 链分叉）。z-watermark 与 z-security 编排层都交给本域写。
> 三模式下这份职责怎么落（谁写、谁只读、怎么防链分叉）见 [`_doc/002_deploy/deploy-modes.md`](_doc/002_deploy/deploy-modes.md)。

## 🧪 测试

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
mvn -f z-audit-core/pom.xml test       # 58 例
mvn -f z-audit-starter/pom.xml test    # 3 例（H2 真落库）
mvn -f z-audit-web/pom.xml test        # 20 例（H2 真落库）
```

> **CI**：push / PR 自动跑 GitHub Actions（`.github/workflows/ci.yml`：JDK 17 + `mvn -B -ntp verify`）。

**core 58 例**（原 24 + 锚点 18 + 溯源 16）：默认值补全、null 安全、出口异常不外抛、链头哨兵、
链式相连、篡改检出、删除检出、空链完整、无查询出口降级、hash 依赖 prevHash、
verify 自洽、按操作人/动作查询、取链尾、留存期各档、过期判定、可覆盖、枚举解析、出域标记；
**锚点 18 例**：打锚记 hash+条数、空链返 null、同键覆盖、自动键、未变通过、
新增事件后通过、**尾部截断检出**、链整条删空检出、锚点后被改检出、
**锚点自身被篡改检出**、无锚点=不可判定、无存储=不可判定、无查询=不可判定、
指纹绑定内容、无存储指纹时校验 false、列锚点排序、未指定键用最新；
**溯源 16 例**：**三态**（查到 / 台账没有 / 台账不可用）、无 `AuditHealth` 的出口只判「查不到」、
汇总给集合不判单值、**链尾重载**（重启后接旧链、空表保持哨兵）、
**幂等拒收不得推进链尾**、幂等后整链仍完整、**无出口（只算链）模式链尾照常推进**、
**落库结果如实反映**（拒收 / 正常 / 出口抛异常 3 例）。

**web 20 例**（原 5 + 锚点 7 + 溯源 8）：DDL 幂等（建三遍不报错）、POST 真落库 + 链式 prevHash、
GET 过滤、**绕过应用直接改库 ⇒ 检出链断**、取链尾；
**绕过应用直接 DELETE 库 ⇒ 检出尾部截断**、直接 UPDATE 锚点 ⇒ 指纹检出篡改、
同键覆盖只 1 行、列锚点、无锚点 indeterminate、fingerprint 列非空 64 位；
**溯源 8 例**：POST **绕过应用直接查表**验证真落库、单号落 `target_id`、幂等重复提交只有一条、
反查 200 + summary、查不到 404、空码 4xx、**新记录 `prev_hash` 接旧链**、
**响应必含 `trace` 单条视图**（跨域契约）。

> ⚠ web 层测试**从生产 DDL 脚本读表结构**（`AuditTestSchema`），
> 不内嵌第二份 DDL —— 内嵌一份必然漂移 ⇒ 测试全绿但生产炸。

**starter 3 例**（嵌入式装配，宿主模拟应用放 `com.example.embedhost` 无关包树 ⇒ 组件扫描不可能兜底）：
纯宿主只靠 `AutoConfiguration.imports` 装出**引擎全家 + 本域数据源**（门面/反查/锚点/链尾重载器/数据源齐备）、
**嵌入的引擎真落库 + 按追踪码反查**（带链哈希，不是空壳）、
**imports 完整性逐行 `Class.forName` 纯机械校验**（正向用例抓不住包名笔误——Spring 侧有容错，这个才判得红）。

## 📌 踩坑留档

| 坑 | 现象 | 修法 |
|---|---|---|
| **`@ConditionalOnBean` 早于 `@MapperScan`** | 自动装配里判 Mapper 存在 ⇒ 条件恒不成立 ⇒ 服务静默缺失（接口 503） | 改直接构造器注入（`@MapperScan` 注册时机早于本装配的 `@Bean` 求值） |
| **测试删副本没生效** | `store.all()` 返回副本 ⇒ `remove` 没删到真数据 ⇒ 「删除必须被检出」用例假绿 | 加 `removeByEventId()` 专用方法；顺带把「尾部截断不可检出」显式写成用例 |
| **H2 MODE=MySQL 把未加引号的列别名转大写** | `AS tenantId` ⇒ `TENANTID` ⇒ MyBatis 映射成 `Map<String,Object>` 时 `map.get("tenantId")` 恒 null ⇒ 锚点指纹重算必然不匹配 | Mapper **返回实体类**（`ChainAnchorRecord`），不返回 Map；实体映射会归一化列名与属性名 |
| **MySQL 唯一索引里 NULL 与 NULL 不相等** | `(NULL, 'k1')` 可插无限多行 ⇒ upsert 退化成「同键堆积」 | `tenant_id` 统一用空串 `''` 表示「全域」，**禁止用 NULL** |
| **`CREATE TABLE IF NOT EXISTS` 不给已建表加列** | 只改 `init.sql` 只对**新库**生效 ⇒ M1 已建的库缺 `fingerprint` 列 ⇒ 运行时报 `Unknown column` | 另写一次性增量迁移 `init/002_anchor_fingerprint.sql`（查 `INFORMATION_SCHEMA` → `ADD COLUMN`） |
| **`AuditService` 不是 `AuditQuery`** | `setQuery(AuditService)` 编译不过 | 门面不实现查询口 ⇒ 注入 `PersistentAuditStore`（它同时是 Sink 与 Query） |
| **老行 `fingerprint` 为空 ⇒ 锚点判「不可信」** | M1 已有锚点行升级后校验失败 | **故意如此**：要求重新打锚（成本极低），比「默认可信」安全 |
| **★ 幂等拒收推进了链尾 ⇒ 误报链断** | 同一 eventId 交两次 ⇒ 出口拒收但内存链尾已推进 ⇒ 下一条 `prevHash` 指向库里不存在的记录 ⇒ 链校验报「哈希不匹配」，**而台账其实没问题** | 出口拒收 ⇒ 链尾**回退**到本条 `prevHash`，再从库里 `resyncChainTail()` 接真实链尾 |
| **★ 「无出口」与「出口拒收」没区分** | 修上一条时引入误伤：无出口（只算链）模式下 `deliver` 返回 false ⇒ 链尾被回退 ⇒ 链尾**永不推进** | 回退条件加 `!sinks.isEmpty()`；纯内存模式链尾照常推进 |
| **★ `record()` 吞异常但返回非 null** | 出口炸了也返回 event ⇒ 调用方以为写成了 ⇒ **合规缺口被静默掩盖**（打了水印却没留痕） | 加 `recordWithOutcome()` 返回 `AuditOutcome`（事件 + `accepted`）；调用方据此如实标缺口 |
| **★ 跨域反查响应缺 `trace` 单条字段** | 反查响应只给 `events` 数组，水印域 HTTP 客户端读的是 `trace` 对象 ⇒ **真实环境反查永远「查不到」**，而单测走内存台账**不过 HTTP** ⇒ 测不出来 | `toView()` 补 `trace` 单条视图（优先取 `WM:` 行）；加真 HTTP 契约测试（`WatermarkAuditHttpContractTest`）钉住字段名 |

## 文档目录

本项目文档统一收口在 `_doc/` 下（lead 002 规范八类固定编号表，桶按需存在）:

- [`_doc/001_arch/`](_doc/001_arch/) — 架构与不变式：
  - [`00-overview.md`](_doc/001_arch/00-overview.md) — 总体设计（模块与依赖方向、写读分离、链与锚分表、哈希/串行化/不可判定等十条不变式、已知边界）。
- [`_doc/002_deploy/`](_doc/002_deploy/) — 部署资产与初始化 SQL：
  - [`init.sql`](_doc/002_deploy/init.sql) — 2 张表（幂等，可重复执行）。
  - [`init/`](_doc/002_deploy/init/) — 一次性增量迁移（`002_anchor_fingerprint.sql`，**只执行一次**）。
  - [`deploy-modes.md`](_doc/002_deploy/deploy-modes.md) — 部署三模式（审计域视角：单写者红线、链尾重载、锚点外部化不变式）。
- [`_doc/005_testing/`](_doc/005_testing/) — 验收复跑：
  - [`001_验收复跑.md`](_doc/005_testing/001_验收复跑.md) — 五仓复跑命令、实测读数、已知边界与生产执行清单。
