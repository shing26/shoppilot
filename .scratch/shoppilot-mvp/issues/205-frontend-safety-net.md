# 票 205：前端安全网（Vitest + Playwright 三条冒烟 + App.vue 状态下沉）

Round: 48（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 无

## What to build

两个产品入口（`frontend-buyer` / `frontend-workspace`）目前只有 `typecheck` + 构建 + 产物比对，
组件内的纯逻辑只能靠三条 Playwright 活体脚本事后验。本票补一层**每次提交都能跑、0 token、
不依赖活体栈**的单元测试，并把最容易回归的逻辑从 `App.vue` 下沉到可测模块：

1. **Vitest（两个工程各一份，dev-only）**
   - 各加 `vitest` devDependency、`npm test`（`vitest run`）与一个 `*.test.ts` 目录约定；
   - **不引 @vue/test-utils / jsdom**：被测对象是下沉后的纯函数，不是组件树；
   - CI 第 10 步跑两个工程的 `npm test`（round44 spec §4 登记的第 10 步触发条件）。
2. **状态下沉（行为零变化）**
   - 买家端：SSE 分帧解析从 `api.ts` 下沉到 `lib/sse.ts`（按 `\n\n` 切帧、`event:`/`data:` 解析、
     跨 chunk 缓冲）；对话存档的读取/归一化与一次回答的收尾状态从 `App.vue` 下沉到 `lib/chat.ts`。
   - 工作台：队列筛选/排序与 KPI 计数从 `App.vue` 下沉到 `lib/queue.ts`。
   - 组件模板、样式、请求路径、文案、SSE 事件处理结果逐字不变。
3. **Playwright 三条冒烟**
   - 既有 `scripts/verify-buyer.mjs` / `verify-workspace.mjs` / `verify-console.mjs` 作为冒烟集，
     新增 `scripts/run-frontend-smokes.mjs` 作为唯一入口（顺序执行、汇总退出码）；
   - 三条冒烟**仍需活体栈与演示口令**，不进 ci-subset；本票只保证入口一致、文档写清前置。

## 边界

- 不引路由库 / 状态库 / 组件库 / @vue/test-utils / jsdom；不改 ADR 0079 的组件拆分结论。
- 不改任何页面的 DOM 结构、样式、文案与请求契约；不新增运行时依赖（`vitest` 只在 devDependencies）。
- 不改构建产物行为：构建后仍应逐字节复现入库产物（refactor 后必须重建并核对 `git diff`）。
- 活体冒烟不在本票的必跑项内（需要本机栈与口令）；未跑就照登，不用 `typecheck` 冒充。
- 不做票外重构：`App.vue` 只搬本票列出的三块逻辑，其余状态仍留在根组件。

## Verify

```bash
for app in frontend-buyer frontend-workspace; do (cd $app && npm ci && npm run typecheck && npm test && npm run build); done
node scripts/run-frontend-smokes.mjs   # 需要活体栈 + SHOPPILOT_IDENTITY_DEMO_PASSWORD
bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true
git diff --check
```

## 验收项

- 两个工程 `npm test` 全绿；用例覆盖 SSE 跨 chunk 分帧、对话存档归一化、一次回答的 499/失败/成功收尾、
  队列筛选与优先级排序。
- `npm run typecheck`、`npm run build` 通过；重建产物与入库内容一致（无未跟踪新文件）。
- 三条 Playwright 冒烟的入口脚本存在、`node --check` 通过；未实跑时 Handoff 如实登记。
- CI 新增第 10 步，命令与本地一致。
- JVM 用例数不因本票变化；`docs/EVIDENCE.md` 记录 vitest 读数、构建结果与活体冒烟边界。

## Handoff notes

**关键决策**

- 两个工程各装一份 `vitest@3.2.7`（devDependency），**不引 @vue/test-utils / jsdom**：被测对象是
  下沉后的纯函数，不是组件树；`environment` 用默认的 node，测试耗时 < 1s。
- 状态下沉只搬三块，其余状态仍留根组件（ADR 0079 的「根组件持有状态」结论不变）：
  买家 `lib/sse.ts`（按 `\n\n` 分帧、跨 chunk 缓冲）、`lib/chat.ts`（存档归一化 + 一次回答收尾）、
  工作台 `lib/queue.ts`（状态筛选 + 优先级/时间排序 + 三个 KPI）。
- 三个 `*IT` / `*Test` 命名按各自 runner 的默认 include；Vitest 走 `src/**/*.test.ts`。
- Playwright 三条冒烟**不重写**：新增 `scripts/run-frontend-smokes.mjs` 顺序调用既有三个脚本，
  汇总退出码（0 全过 / 1 断言失败 / 2 缺前置）。它需要活体栈与演示口令，仍不进 CI。
- CI 新增第 10 步（`Frontend unit tests (vitest, 0-token)`），接在既有两个前端构建之后；
  轮 44 spec §4 登记项的触发条件（组件内非平凡纯逻辑）由本轮三块下沉满足。

**验证落点**

- `frontend-buyer`：`npm test` **24 条 / 2 文件全绿**（sse 6、chat 18）。
- `frontend-workspace`：`npm test` **6 条 / 1 文件全绿**（queue 6）。
- 两端 `npm run typecheck` 通过；`npm run build` 通过，产物重建（买家 112.44 kB JS / 27.09 kB CSS，
  工作台 140.57 kB JS / 29.03 kB CSS）。**注意**：产物哈希与旧文件不同，提交时必须把
  `shoppilot-gateway/src/main/resources/static/{buyer,workspace}` 的删除与新增一起提交，
  否则 CI 的逐字节比对与未跟踪产物检查会红。
- `node --check` 覆盖 `scripts/run-frontend-smokes.mjs` 与三条既有冒烟脚本，全部通过。
- 根目录 `./mvnw.cmd -B -ntp verify -Dspring-boot.repackage.skip=true` **BUILD SUCCESS**，
  四模块 **12 / 92 / 626 / 50 = 780 tests / 131 suites**、0 失败（与票 204 基线一致）。
- **三条 Playwright 活体冒烟未实跑**：网关 8082 未起（`curl` 返回 000），也没有演示口令。
  本票只证明入口脚本可解析；`verify-buyer/workspace/console` 的断言与前置条件零改动。

**现场追问**

1. 若 CI 第 10 步在干净 runner 上红而本地绿：先看 `frontend-shared` 的 file 依赖是否被 npm ci 装好；
   Vitest 直接 import 它的 TS 源码，不做预构建。
2. 若构建产物比对红：确认两端都跑了 `npm run build`，并把 static 目录的**删除项**与新 chunk 一起提交；
   只提交新增不提交删除会留下孤儿文件。
3. 若活体冒烟在新栈上红：先按各脚本文件头的前置检查（`NODE_PATH` 指向 `.tools/node_modules`、
   `SHOPPILOT_IDENTITY_DEMO_PASSWORD`），不要改断言；三条脚本的失败项各自带明细。
