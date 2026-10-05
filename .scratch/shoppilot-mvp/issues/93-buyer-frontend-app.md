# 93 买家端前端工程

**Status:** implemented（2026-10-04）

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

### 落点

新建 `frontend-buyer/`（Vite + Vue3 + TS，`.nvmrc` 24、lockfile 入库），产物落
`shoppilot-gateway/src/main/resources/static/buyer/`，`base: '/buyer/'`。

- `api.ts`：与另两个前端同一套失败口径（0 网关不可达 / 401 登录失效 / 其余非 2xx），
  `request()` 一处收口；`streamAnswer()` 逐帧读 SSE（`EventSource` 不支持 POST，ADR 0006 的同款理由，
  且**分帧按空行切块、逐块解析**而不是子串匹配——round21 记过一次那处坑）。
- `App.vue`：登录 → SSE 提问（含引用）→「我的工单」（诉求/状态/优先级/SLA/处理人）。
  **降级也是一种结局**：说清发生了什么并给出工单号，而不是一句「稍后」。
- 登录态存 sessionStorage、口令登录完立刻清掉（同工作台口径）。

### 顺带修了一个「同类洞的第二个实例」

`AuthFilter` 的静态入口放行面只列了 `/workspace/`——**买家中心按原样加上去会 401**，
而这正是 round23 清场日抓到的那个洞（`/workspace/` 目录路径没放行）。

现在三个入口写在一起，并加了 `StaticEntryExemptionTest`（4 条）把它们一次钉住：
下一个人加入口时会**先在这条上撞红**，而不是等到活体验收才发现。
**变异对照实测为真**：去掉 `/buyer/` 那两行 → 2 条红。

顺带把 `WorkspaceEntryController` 改名/扩成 `StaticEntryController`（两处入口同一套理由，
分成两个类只会让下一个入口再长出一个类）。

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `10 + 57 + 342 + 37 = 446`（gateway 338 → **342**，+4 放行面用例） |
| 前端 | `npm ci` + `typecheck` + `build` 全绿，产物入库（index.html + 2 asset ≈75 kB） |
| 覆盖率 | 四模块全过（`COVERAGE OK modules=4`） |

**一条判据都没动。** **未达成照登**：浏览器断言本轮未实跑（裁定 A），
所以「页面真的能用」目前只有 typecheck + 构建 + 静态放行面三处证据。

### 现场三问

1. **为什么不复用工作台那套 `api.ts`？** 复用一个「登录 + SSE + 队列」三不像的组件，
   比各写 80 行更难改。薄薄一份 `api.ts` 的重复，比一个含糊的共享模块便宜。
2. **为什么列表里没有 transcript？** 那是坐席看的会话原文（票 92 刻意少那一格）；
   前端留着这一格会让「多一个字段」看起来像无害，而它其实是一条数据可见性的承诺。
3. **降级为什么也给工单号？** 「稍后人工跟进」对买家等于没有下文。给工单号 +
   下方列表里能查到，才是有出口的一句话。