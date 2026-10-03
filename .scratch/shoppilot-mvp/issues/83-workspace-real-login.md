# 83 坐席工作台换真登录

**Status:** ready-for-agent

## What to build

- 工作台前端加一个登录页：用户名 + 口令 → `/auth/login` → 拿带角色的令牌。
- 领取 / 处理 / 结单时，**actor 取自令牌**，不再由页面上一个「坐席名字」输入框自报。
- **演示入口保留**：口令可显示、可一键填入的演示账号入口继续在（ADR 0014 的调试台正门），
  但它领到的是一个**角色令牌**，不是 ops token + 自报名字。
- CI 仍是九步：前端构建门禁不变，`git diff --exit-code` 校验产物与仓内逐字节一致。

## Blocked by

[82](82-role-guard-and-self-report-retirement.md)。

## 口径

- **凭证不再存在 localStorage**：登录态放内存或 sessionStorage，页面刷新后按需要重登。
  写清理由，不写成「顺手改的」。
- **失败口径与既有三条一致**：网络不可达 / 401-403 / 其余非 2xx 三种失败各有各的处置
  （`api.ts` 的 `describe()` 一处收口，不在登录页另写一遍）。
- **产物入库**（票 73 的裁定 E）：本票改完要重新构建并提交，CI 的逐字节校验才绿。

## 验收

- `npm ci` + `tsc --noEmit` + `vite build` 全绿，产物已入库；
- `git diff --exit-code` 在重新构建后为空；
- **浏览器断言本轮按未达成登记**（spec §5：本机可用内存约 1.9 GB，裁定 A）。

## Verify

```bash
cd frontend-workspace && npm ci && npm run typecheck && npm run build
cd .. && git diff --exit-code
```

## Handoff notes

（收口时补）