# 票 210：买家页无店铺码时不保留旧店会话

Round: 48 后续（回归修复，冻结线允许）　Status: implemented　Blocked by: 无
契约：[`plans/210-buyer-session-store-guard.md`](../../../plans/210-buyer-session-store-guard.md)

## What to build

票 191 修掉了「地址栏带 `?store=` 但存档属于别家店」的情况，但 `App.vue` 的
`readStoredSession()` 用的是 `if (STORE_CODE && stored.tenantId !== STORE_CODE)`：直接打开
`/buyer/`（没有店铺码）时第一个条件短路，同一标签页里上一家店的 `sessionStorage` 存档被原样恢复，
页面继续用上一家店的身份发请求（真实浏览器已复现）。

本票把登录态的读取 / 校验 / 是否丢弃下沉到 `frontend-buyer/src/lib/session.ts` 的纯函数
`restoreSession(raw, storeCode)`，并固定三条行为：

- 无店铺码（空串或纯空白）：有存档就丢弃（`discard: true`），页面走既有的「缺少店铺码」/游客态；
- 有店铺码：同一租户恢复，不同租户丢弃（票 191 行为不变）；
- 不可解析的 JSON：丢弃并返回 null（行为不变）。

`App.vue` 的 `readStoredSession()` 只调用纯函数，并在 `discard` 时从 `sessionStorage` 删除键。

## 边界

- 只改 `frontend-buyer/src/App.vue`、新增 `src/lib/session.ts` 与 `src/lib/session.test.ts`；
  构建产物仅限 `shoppilot-gateway/src/main/resources/static/buyer/**`。
- 不改请求契约、文案、样式、DOM 结构，不改 workspace / console。
- 不引运行时依赖；Vitest 仍是 devDependency，测试跑 node 环境，不引 jsdom / @vue/test-utils。
- 不碰 knowledge / eval / gold / 阈值 / 指标分母；不改 ADR 结论与历史证据目录。
- 实现方不自宣验收通过，裁决由独立 Verifier 给出。

## Verify

```bash
cd frontend-buyer && npm test && npm run typecheck && npm run build
cd .. && git status --short
bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true
git diff --check
```

活体补充证据由编排会话执行：容器档换镜像后跑 `node .scratch/verify-postdeploy.mjs` 与无参数路径探针。

## 验收项

- 无店铺码 + 旧会话 → 丢弃且从 `sessionStorage` 移除，不用于任何 API 调用；页面进入缺少店铺码 / 游客态。
- 有店铺码：同租户恢复，异租户丢弃（票 191 行为不变）。
- 坏 JSON 丢弃并返回 null（行为不变）。
- 无店铺码时对话存档仍整份丢弃（`restoreChat` 既有行为不退化）。
- 会话读取逻辑为无 DOM 依赖的纯函数，`App.vue` 只调用。
- Vitest 覆盖四类用例：无店铺码丢弃、同租户恢复、异租户丢弃、坏 JSON 丢弃；用 `discard` 标志
  区分「丢弃」与「本来就没有存档」。
- `npm test`、`npm run typecheck`、`npm run build` 通过；重建产物与源码一致；JVM 用例数不变；
  `git diff --check` 干净。

## Handoff notes

**关键决策**

- 纯函数返回 `{ session, discard }` 而不是直接返回 `Session | null`：`null` 同时可能表示
  「没有存档」与「存档被丢弃」，只有后者需要删键；测试也必须能把两者分开断言。
- 函数内部对店铺码再做一次 `trim()`（与 `App.vue` 的 `STORE_CODE` 一致，且幂等），
  空串与纯空白都走「无店铺码」分支，避免调用方传入未归一化的值。
- 有店铺码时只比较 `tenantId`，不额外校验令牌字段：保持票 191 的判定面不变；
  形状不对的 JSON（如 `null`、`{}`）会因取不到匹配的 `tenantId` 自然落入丢弃分支。
- `App.vue` 保留 `readStoredSession()` 这个薄壳（删键是 DOM 副作用），JSDoc 补记 round49
  的无店铺码约定；`restoreChat` 一行未改，无店铺码时 `tenantId` 为空，存档照旧整份丢弃。

**验证落点（本机真实读数）**

- `cd frontend-buyer && npm test`：**3 个文件 / 31 条全绿**（新增 `session.test.ts` 7 条：
  无店铺码、纯空白店铺码、同租户、异租户、坏 JSON、无存档不删键、可解析但非会话对象；
  既有 chat 18 条、sse 6 条不变）。
- `npm run typecheck`（`vue-tsc --noEmit`）：通过，无输出、退出码 0。
- `npm run build`：49 modules transformed，`index.html` 0.53 kB、CSS 27.09 kB、JS 112.60 kB，
  1.46s 完成。`emptyOutDir` 删除旧 `index-BVnvz5p8.css` / `index-Dff-VSBH.js`，
  新增 `index-DdL8u1ts.css` / `index-Dlwee3LQ.js`，`index.html` 引用同步更新。
  CSS 文件名变化来自 `App.vue` 改动导致的 scoped `data-v-*` 哈希变化，样式内容本身未改。
- `git status --short`：除本票源码/票面/计划外，只有 `static/buyer` 的 2 删 1 改 2 增；
  未触碰 workspace / console 产物。
- `git diff --check`：无输出。
- JVM 用例：实现会话按编排要求**未跑 `mvnw`**，由 Verifier 重跑
  `bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true`；**BUILD SUCCESS（2:32），
  执行总数 782**（12 + 92 + 628 + 50，与 `docs/STATUS.md` 基线一致）。
- **独立 Verifier 裁决：PASS**（2026-11-10，证据绑定 HEAD `cce86f7`）。八条验收项逐条 CONFIRMED；
  变异对照实测：把空店铺码分支改回旧行为后 `session.test.ts` **2 failed / 5 passed**（失败项正是
  「无店铺码丢弃」「纯空白店铺码丢弃」），还原后 **7 passed**，`git status --short` 逐行一致。
- **部署级复验（编排会话，容器档 8082，gateway 镜像 `7863e09`）**：
  - `verify-no-store.mjs`：旧店 session 已被移除、页面提示缺少店铺码、无任何请求携带旧令牌——
    **3/3 PASS**；
  - `verify-postdeploy.mjs`：下发 `index-Dlwee3LQ.js`，预埋 T001 session 被丢弃/替换为 T002、
    chat 身份 T002、回答不含 T001 政策——**4/4 PASS**；
  - `verify-switch-flow.mjs`：同标签 T001→T002 换店，请求轨迹 `[T001, T002]`，回答归属正确——
    **4/4 PASS**。
- 未提交：本次改动留在工作树（源码、票面、契约、静态产物），由所有者决定提交与签收。

**现场追问**

1. 无参数 `/buyer/` 打开后仍像已登录：先在 DevTools 确认 `shoppilot.buyer.session` 键是否已删，
   再核对 `readStoredSession()` 是否真的拿到 `discard: true`；不要用「页面有没有登录门」代替存储断言。
2. 带 `?store=` 换店行为异常：确认 URL 值先经 `App.vue` trim、纯函数再 trim（幂等）；异租户仍按
   `tenantId` 全等比较，不要在这里加大小写或前缀宽松匹配——那会重新打开串店的口子。
3. 构建产物比对红：`App.vue` 任何改动都会改变 scoped CSS 的 `data-v-*` 哈希，CSS 文件名随之变化，
   这不是样式改动；提交时必须把旧 chunk 的**删除**与新 chunk 的**新增**一起带上，并确认
   `static/workspace`、`static/console.html` 未被本票重建。
