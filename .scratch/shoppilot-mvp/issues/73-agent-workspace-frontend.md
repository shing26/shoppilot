# 73 坐席工作台：Vite+Vue3 第一入口 + CI 前端构建步

**Status:** implemented（2026-10-02）

## What to build

按 ADR 0057 引前端构建链，做**第一个入口：坐席工作台**。买家端是第二个入口，本轮不做（登记）。

**两处所有者裁定（2026-10-01，见 spec §0 的 E 与 F）**：

- **构建产物入库**到 `shoppilot-ticket/src/main/resources/static/`——**偏离 ADR 0057 的「产物不入库」**，理由：干净克隆 `./mvnw verify` 直接打出带界面的 jar，「干净克隆可复跑」这条更重。
- **门控沿用现有 ops token**，身份域（ADR 0056）是下一轮主体；这个缺口必须写进 EVIDENCE，**不得静默**。

- 仓根加 `frontend-workspace/`（Vite + Vue 3 + TypeScript），`package.json` 与 lockfile **入库**，node 版本 pin 进仓（`.nvmrc` 或 `engines`）。
- 页面：队列列表（按优先级 + SLA 倒计时）、领取、处理（写 payload）、释放；网络失败沿用调试台既有口径「读不到 + 为什么」，**不冻结在空态**。
- CI `.github/workflows/ci-subset.yml` 加一步：`npm ci` + `npm run build` + 校验产物与仓内一致（**八步 → 九步**）。
- 断言：扩展 Playwright 路线（新脚本 `verify-workspace.mjs`，不另立判据体系）。

## Blocked by

[72](72-ticket-agent-service.md)。

## 口径

- 旧调试台**保留为运维页**，不删、不要求能力对齐（ADR 0057 明确它是过渡态）。
- 门控缺口必须在本票的 `docs/EVIDENCE.md` 行里写明"坐席台当前用 ops token，真账号下一轮"，**不得静默**。
- 前端断言沿用既有 `verify-console.mjs` 的做法（Playwright + 既有交互缺陷清单），不引入第二套测试框架。
- **浏览器断言本轮无法实跑**（裁定 A：不起栈）——脚本写好并做语法与静态核对，**实跑按未达成登记**，不得声称「43/43 那样已验证」。

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

### 关键决策

1. **产物落点偏离票面初稿**：初稿写「入库到 `shoppilot-ticket` 的 static」，实落在**网关的
   `static/workspace/`**。理由是纪律而不是偏好：浏览器只经网关访问内部服务（票 15 的 InternalAuthFilter
   纪律），页面若由 :8092 提供，它去调 :8082 的 ops 端点就成跨域——要么给网关的运维面开 CORS
   （为一个页面放宽整面），要么同源提供。选后者。
2. **产物入库**（裁定 E）→ CI 那一步不只是「能构建」，还要 `git diff --exit-code` 校验**与仓里那份逐字节一致**。
   否则「源码改了、产物没重建」会静默通过，而页面跑的是旧产物。
3. **不引 CORS、不引路由框架**：工作台是三个动作加一张表；Vite + Vue 3 已经够了，
   再加 vue-router/pinia 只会增加构建与审计面（ADR 0051 那条「收益吃不到、代价照付」的判断）。
4. **`X-Agent` 是自报身份**（裁定 F）：它只进审计的 actor，不构成权限。凭证形态在页面上写明了。

### 顺带补上的一个守卫缺口（票 72 引入的）

建工作台时发现：票 72 新增的 **claim/release/resolve 以及工单列表，只过了买家 JWT、没有 ops token 门控**。
读队列里是别人的会话原文与诉求——在本仓口径下，那等于「任何登录用户都能翻人工队列」，而动作端点还会改
领取人与状态。现已全部纳入 `guardedTicket(...)`：凭证形态沿用既有 `X-Ops-Token`（真身份域下一轮）。
**这条不是票 73 的范围，是它作为第一个消费者时暴露出来的**，记在此处并回指票 72。

### 验证落点

- `npm ci` + `npm run typecheck`（vue-tsc）**干净**；`npm run build` **成功**（14 modules，1.4 s，
  产物 3 件 ≈74 kB，落在网关 `static/workspace/`）。
- `node --check scripts/verify-workspace.mjs` 通过；`check-ps-syntax.ps1` 32 文件 0 错；
  `\.\mvnw.cmd -B -ntp verify` 四模块绿（网关会把这三个静态文件打进 jar）。
- **CI 八步 → 九步**，新增前端构建门禁（`setup-node` 用 `.nvmrc` 24 + npm cache → `npm ci` → typecheck → build → `git diff --exit-code` 校验产物一致）。

### 未达成（照登不摘红）

- **浏览器断言本轮未实跑**：起栈 ≈7 GB 而本机剩 0.5 GB（裁定 A）。`verify-workspace.mjs` 已写好并过语法核对，
  但**没有任何实跑读数**——页面到底长什么样、按钮点下去会不会按预期，全都没有证据。
  措辞纪律照旧：不得声称「43/43 那样已验证」。
- 坐席身份自报（ADR 0056 下一轮）。

### 现场三问

1. **为什么产物落在网关而不是工单服务？** 浏览器不直连内部服务是票 15 的纪律，落到 :8092 就得给运维面开 CORS。
   同源提供更小。
2. **为什么 CI 要 `git diff` 校验产物？** 因为产物入库，「构建成功」只证明源码能编译；
   不比对的话，改了源码忘了重建就会静默发一个跑旧代码的页面。
3. **为什么不引路由和状态库？** 三个动作一张表；引了只增加构建与审计面，换不到本轮能兑现的东西。
