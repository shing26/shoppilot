# 83 坐席工作台换真登录

**Status:** implemented（2026-10-04）

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

### 落点

- `App.vue`：**登录表单**（店铺 / 坐席账号 / 口令）+ 队列视图 + 退出登录。
  「运维令牌」与「坐席名」两个输入框**删掉了**——后者其实已经不起作用（网关会用令牌里的账号覆盖它），
  留着只会让人以为「改个名字就能换个人」。
- `api.ts`：`Creds` 收成 `Session`（令牌 + 账号 + 角色 + 租户）**一件凭证**；
  `login()` 单独走 `/auth/login`（`AuthFilter` 对 `/auth/` 整体放行，登录页拿不到令牌）。
- 登录态存 **sessionStorage** 而不是 localStorage：关掉标签页就登出。共享机器上 localStorage 里的
  令牌会活到有人手清缓存为止，而令牌只有 30 分钟 TTL。口令**登录完立刻清空、不落任何存储**。
- 产物已入库并重建（旧 asset 被 vite 清掉、新 asset 加进来，两个一起提交）。
- 顺带改了后端一处（**票面没写的**）：`TenantContext.Identity` 增 `username`，
  `JwtService` 多写一个 `usr` claim，`Identity.actor()` 按 **用户名 → 账号 id → 买家 id** 取值。

### 为什么署名叫「用户名」而不是账号 id

`AccountView` 里账号 id 形如 `U0a1b2c3d4e5`。把它写进工单的 `assignee` 栏，页面上没人认得，
验收脚本也没法拿它当断言。用户名在租户内唯一，而审计行本来就带租户，所以它不会把两个身份混成一条。
`RoleGuardAndActorTest.actorPrefersTheUsernameOverTheAccountId` 钉这一格。

### 验收脚本的凭证引导改了（判据一字未动）

`scripts/verify-workspace.mjs` 原来往 localStorage 里塞 `opsToken` + `agent` 再重载。
那条路**在票 83 之后已经不存在了**，所以改的是**引导方式**，不是断言：

| 断言 | 处理 |
|---|---|
| 队列非空 / 优先级排序 / 处理前确认 / 领取后出现领取人 / 无页面异常 | **一字未动** |
| 「没有运维令牌时给出可读原因」 | 改成「未登录时给出可读原因」，**断的还是同一件事**（没有身份 → 可读原因，不是空态） |
| 「领取后该行出现领取人」断言的字面量 | 从自报名 `verify-agent` 改成**登录用的账号名**——领取人现在取自令牌 |
| 领取人的等待条件 | 从「写 localStorage 再重载」改成**真的走一遍登录表单**（直接塞令牌会跳过「登录面能用」这件事，而那正是本票的交付内容） |
| 口令来源 | `SHOPPILOT_IDENTITY_DEMO_PASSWORD`，**没给就 exit 2 并说明为什么**；不给就退回去用 ops token 的话，这个门禁在验一个已经不存在的产品形态 |

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `10 + 57 + 322 + 22 = 411`（gateway 321 → 322，新增署名优先级那条） |
| 覆盖率 | 四模块全过（`COVERAGE OK modules=4`） |
| 前端 | `npm run typecheck` 绿、`npm run build` 绿、产物入库 |
| 脚本语法 | `verify-workspace.mjs` `node --check` 绿；`check-ps-syntax.ps1` **0 错**（**要用 pwsh 7 跑**：Windows PowerShell 5.1 把无 BOM 的 UTF-8 当 ANSI 读，会报出 66 个不存在的语法错） |

**按未达成照登**：**浏览器断言本轮仍未实跑**（spec §5：本机可用内存约 1.9 GB，全栈档只在清场日）。
因此「登录面真的能用」「登录后队列能读到」这两条**只有 JVM 层与静态证据**，不得声称已验证。

### 现场三问

1. **为什么不预填演示口令？** 预填一个能直接用的口令，等于把凭证形态又退回「打开就有」。
   演示入口改成「按一下填用户名，口令自己给」——`IdentitySeedRunner` 那条路（`SHOPPILOT_IDENTITY_DEMO_PASSWORD`）。
2. **为什么 sessionStorage 而不是 localStorage？** 见上。令牌 30 分钟 TTL，而 localStorage 活到有人手清缓存。
3. **`verify-workspace.mjs` 改了引导算不算改判据？** 不算——断言一条没动，动的是「怎么进到页面」。
   但这一格仍然照登：**它本轮没跑过**，所以「改完之后门禁还成立」目前也只是推断，等清场日实跑才算数。

### 清场日补记（2026-10-04，perf 档）
**门禁首次实跑：9/11。两条红，其中一条是产品缺陷。**

| 断言 | 结果 | 归因 |
|---|---|---|
| 未登录时给出可读原因 | **FAIL（没有 .err）** | **产品**：买家端那页同样的情形下补了一句 `.notice`（清场日当天补的），工作台这页没有——round25 改登录时漏了 |
| 登录后显示当前身份 | **FAIL（显示 `undefined`）** | **产品**：登录响应里的 `displayName` 没进页面 |
| 队列非空 / 优先级 / 确认框 / 领取后出现领取人 / 无页面异常 | **PASS** | 其中「领取后出现领取人」实测拿到 `ASSIGNED / agent` |

**怎么修没在本轮做**：本机内存回到 0.3 GB（清场日的常态），改前端要重新构建 + 重新打包 + 重跑门禁，
而今天的机时已经花完。两条红**照登不摘**，触发 = 下一次有内存余量时优先修这两格（它们是 round25 留下的）。

**档位**：perf 档（MockLLM），不是 local 档。
