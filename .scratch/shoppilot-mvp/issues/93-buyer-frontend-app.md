# 93 买家端前端工程

**Status:** ready-for-agent

## What to build

第三个 Vite+Vue3 入口，落 **`/buyer/`**（产物进 `shoppilot-gateway/src/main/resources/static/buyer/`）。

- **登录**：店铺 + 用户名 + 口令 → `/auth/login`（round25 已有）。登录态存 **sessionStorage**（同工作台的口径）。
- **会话页**：SSE 对话（`/api/v1/support/chat/stream`），带引用、追问照旧。
- **我的工单**：调 `GET /api/v1/support/tickets`（票 92），显示诉求、状态、优先级、SLA 与处理结论。

## Blocked by

[92](92-buyer-own-tickets-endpoint.md)。

## 口径

- **失败口径三件套**与工作台同一套（`status: 0` 网络不可达 / 401 / 其余非 2xx），
  `api()` 一处收口，不在页面各处另写一遍（ticket 83 那条纪律）。
- **产物入库**（裁定 E），CI 逐字节校验那一格不许因为变成两段前端而被丢掉。
- **独立工程，不复用工作台那套凭证代码**：`frontend-buyer/` 自带一份很薄的 `api.ts`。
  复用一个「登录 + SSE + 队列」三不像的组件，比各写 80 行更难改。
- `.nvmrc` 与 lockfile 入库（同工作台）。

## 验收

- `npm ci` + `npm run typecheck` + `npm run build` 全绿，产物入库；
- **重新构建后 `git diff` 为空**（CI 的逐字节校验那一格）；
- 页面**无未捕获异常**、失败时不冻在空态（工作台那两条纪律）。

## Verify

```bash
cd frontend-buyer && npm ci && npm run typecheck && npm run build
cd .. && git diff --exit-code
```

## Handoff notes

（收口时补）