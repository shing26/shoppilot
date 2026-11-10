# 票 210 验收契约：买家页无店铺码时不保留旧店会话

- Task ID: `210-buyer-session-store-guard`
- 冻结日期: 2026-11-10
- 固定点: 当前 HEAD `cce86f7`（`git rev-parse HEAD` 现场核对）
- 性质: 回归修复（票 191 的残余缺口；冻结线允许，不适用 ADR 0031）
- 背景证据: 票 191 已修「有 `?store=` 时丢弃异租户会话」；残余是 `readStoredSession()`
  的 `if (STORE_CODE && stored.tenantId !== STORE_CODE)`——`STORE_CODE` 为空时短路，
  旧店 session 原样保留，页面在 `/buyer/` 无参数时沿用上一家店的身份。

## Allowed paths（实现者仅可改这些）

- `frontend-buyer/src/App.vue`
- `frontend-buyer/src/lib/session.ts`（新建）
- `frontend-buyer/src/lib/session.test.ts`（新建）
- `shoppilot-gateway/src/main/resources/static/buyer/**`（仅 `npm run build` 产物：新增 chunk + 删除旧 chunk）
- `plans/210-buyer-session-store-guard.md`
- `.scratch/shoppilot-mvp/issues/210-buyer-session-store-guard.md`

## Acceptance criteria

1. **无店铺码**（`/buyer/` 或 `?store=` 为空/空白）：`sessionStorage` 里的旧会话一律丢弃
   （从存储移除），不得用于任何 API 调用；页面进入既有的「缺少店铺码」/游客态路径。
2. **有店铺码**：`stored.tenantId === STORE_CODE` 时照旧恢复；不等时照旧丢弃（票 191 行为不变）。
3. **不可解析的 JSON**：照旧移除并返回 null（行为不变）。
4. **对话存档**：`restoreChat` 在无店铺码时继续整份丢弃（既有行为，不得退化）。
5. 会话读取逻辑下沉为纯函数 `lib/session.ts`（可单测、不依赖组件树），`App.vue` 只做调用。
6. Vitest 新增回归断言至少覆盖：无店铺码 + 存在旧会话 → 丢弃；同租户 → 恢复；
   异租户 → 丢弃；坏 JSON → 丢弃。测试用 `sessionStorage` 的注入替身或纯函数入参，不引 jsdom。
7. `npm test`、`npm run typecheck`、`npm run build` 通过；重建产物与源码一致。
8. JVM 用例数不变；`git diff --check` 干净。

## Verification commands（Verifier 亲自重跑）

```bash
git rev-parse HEAD && git status --short
cd frontend-buyer && npm test && npm run typecheck && npm run build
cd .. && git status --short        # 产物应只有 buyer/assets 的新增/删除
bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true
git diff --check
```

活体补充证据（由编排会话执行，Verifier 可复核脚本与产物）：容器档换镜像后，
`node .scratch/verify-postdeploy.mjs` 与无参数路径探针。

## Forbidden

- 不改请求契约、文案、样式、DOM 结构、其他页面（workspace / console）。
- 不改 `frontend-buyer` 以外的前端工程；不引运行时依赖；`vitest` 仅 devDependency。
- 不碰 `knowledge/`、`eval/`、gold、阈值、`judge()`、降级枚举、指标分母。
- 不碰 `.scratch/shoppilot-mvp/README.md` 以外的 tracker 历史（本票只新增 210 票面并补 Status/Handoff）。
- 不改 ADR 结论；不移动/重命名/删除历史证据目录。
- 实现者不得自宣「验收通过」；裁决由独立 Verifier 给出。

## 裁决记录

- **2026-11-10：PASS**（独立 Verifier，证据绑定 HEAD `cce86f7`）。八条验收项逐条 CONFIRMED；
  亲自重跑 `npm test` 31/31、`typecheck` 0、`npm run build` 0、`mvnw verify` **782 tests**（BUILD SUCCESS）、
  `git diff --check` 0；变更全部落在 Allowed paths。
- **变异对照**：临时把空店铺码分支改回旧行为 → `session.test.ts` **2 failed / 5 passed**
  （失败项为「无店铺码丢弃」「纯空白店铺码丢弃」）；还原后 **7 passed**，`git status --short` 与变异前一致。
- **部署级补充证据（编排会话）**：gateway 镜像重建（`7863e09`）后，
  `verify-no-store.mjs` 3/3、`verify-postdeploy.mjs` 4/4、`verify-switch-flow.mjs` 4/4 全 PASS。
- 改动保留在工作树，未提交；由所有者签收后决定提交。
