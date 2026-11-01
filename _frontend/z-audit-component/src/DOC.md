# @yuku123/z-audit-component

z-audit（审计哈希链中间件）可复用前端组件（lead 005 §9 component 共享）。

## 使用

```js
import { configureAudit } from '@yuku123/z-audit-component'
configureAudit({ apiBase: '' })
```

## pages 入口（路由清单）

```js
import { routes, menuItems, appMeta } from '@yuku123/z-audit-component/pages'
```

| 路径 | 页面 | 说明 |
|---|---|---|
| `/z-audit/home` | HomePage | 首页着陆 |
| `/z-audit/events` | EventsPage | 事件查询（GET `/z-team/api/audit/events`，多条件过滤） |
| `/z-audit/chain` | ChainPage | 链头 + 区间核验（GET `/chain/latest`、`/chain/verify`） |
| `/z-audit/docs` | ComponentDocPage | 组件文档 |

## 后端契约

- `GET /z-team/api/audit/events?tenantId&operatorId&action&traceId&targetType&targetId&limit`
- `GET /z-team/api/audit/chain/verify?tenantId&from&to`
- `GET /z-team/api/audit/chain/latest`
- `GET /api/audit/health`（健康检查）

`.` 入口保持 props-only（lead 005 §5）；fetch 只出现在 `./pages` 的默认装配里。
