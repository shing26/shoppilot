# 73 坐席工作台：Vite+Vue3 第一入口 + CI 前端构建步

**Status:** ready-for-agent

## What to build

按 ADR 0057 引前端构建链，做**第一个入口：坐席工作台**。买家端是第二个入口，本轮不做（登记）。

- 仓根加 `frontend-workspace/`（Vite + Vue 3 + TypeScript），`package.json` 与 lockfile **入库**，构建产物**入库**到 `shoppilot-ticket/src/main/resources/static/`（干净克隆要能直接起，不依赖本机跑过 build）。
- 页面：队列列表（按优先级 + SLA 倒计时）、领取、处理（写 payload）、释放；网络失败沿用调试台既有口径「读不到 + 为什么」，**不冻结在空态**。
- 门控：本轮沿用现有 ops token（**身份域是下一轮，缺口照登**）。
- CI `.github/workflows/ci-subset.yml` 加一步：`npm ci` + `npm run build` + 校验产物与仓内一致（**八步 → 九步**）。
- 断言：扩展 Playwright 路线（新脚本 `verify-workspace.mjs`，不另立判据体系）。

## Blocked by

[72](72-ticket-agent-service.md)。

## 口径

- 旧调试台**保留为运维页**，不删、不要求能力对齐（0057 明确它是过渡态）。
- 门控缺口必须在本票的 `docs/EVIDENCE.md` 行里写明"坐席台当前用 ops token，真账号下一轮"，**不得静默**。
- 前端断言沿用既有 `verify-console.mjs` 的做法（Playwright + 既有交互缺陷清单），不引入第二套测试框架。

## 验收

- 干净克隆 `npm ci && npm run build` 产物与入库产物字节一致（CI 步钉这条）。
- 队列列表在有工单时非空、优先级排序正确、SLA 倒计时在走。
- 领取按钮防连点（复用 ticket 61 的确认 + in-flight 禁用模式）。
- 网络级失败有可读原因，不抛未捕获 Promise。

## Verify

```bash
npm ci --prefix frontend-workspace && npm run build --prefix frontend-workspace
node scripts/verify-workspace.mjs        # 需起栈（清场日/日常档按 74 的分档）
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

（收口时补；门控缺口与 CI 步数换代记在此。）
