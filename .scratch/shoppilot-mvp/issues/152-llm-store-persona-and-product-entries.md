# 152 把真实模型接回主链路：店铺人格、一般问答与产品入口

**Status:** implemented（2026-10-20；含 1 处配置缺陷 + 2 处产品行为 + 3 个入口改造）

**Claimed by:** zcode session 2026-10-20（round37 实现）

> 本票是**缺陷修复 + 所有者政策覆盖**的混合票。开轮依据、逐项裁决与边界见
> [ADR 0071](../../../docs/adr/0071-round37-llm-wiring-store-persona-and-product-entries.md)。

## What to build

实机走查暴露的「智障感」有三层，按因果顺序修：

1. **模型根本没被调用**：`.env` 配的是 DashScope `qwen-plus`（直连 200），但 `application.yml` 的
   `shoppilot.llm.mode` 写死 `local` → `OllamaLlmClient` → 本机未 `pull` 的 `qwen2.5:3b` →
   每次对话 `本地模型返回 500` / 超时 → 熔断兜底复读“智能回复暂时不可用”。
2. **一般问答被灌入无关政策**：`UNKNOWN` 也走混合检索，实测「店铺推荐什么商品」召回 5 条
   冷链/死蟹/价保条款，模型照着念或被带偏。
3. **提示词没有店铺上下文**：通用客服基座导致 T001/T002 在无专属条款命中时答同一套话。

同时把三个入口改成产品形态：买家端免密开聊、坐席端一键体验、根路径产品导航。

## Blocked by

—

## 开轮依据（已裁定，2026-10-20）

- 第 1 项（模式写死）归 **配置缺陷**：配了云端却永远走本地，按缺陷修复。
- 第 2/3 项与产品入口未命中 ADR 0030/0031 触发条件，归 **所有者政策覆盖**（同 0066/0067/0069/0070）。
- **政策空召回出口不动**：ADR 0068 的“宁可转人工，不可拿另一个行业的条款回答”仍是红线；
  本轮只让 `UNKNOWN` 不再检索、不再被无关条款污染。

## 范围与验收

### A. LLM 模式与采样参数

- `llm.mode: ${SHOPPILOT_LLM_MODE:local}`、`llm.temperature: ${SHOPPILOT_LLM_TEMPERATURE:0.2}`。
- 新占位符登记 `ConfigValidationTest` 白名单；仓库默认值不变（`local` / `0.2`）。
- `.env.example` 写明 `dev` 的用法与“评测仍用 0.2”的口径。
- 验收：`.env` 设 `SHOPPILOT_LLM_MODE=dev` 后，活体问题返回真实正文、`fallbackReason=null`。

### B. 店铺人格 + 一般问答

- 新增 `shoppilot.store-profiles`（`StoreProfileProperties` / `StoreProfileService`），按已验签
  `tenantId` 追加「店名 / 主营」，再叠风格档位；未登记租户不追加。
  **「本店承诺」不进 Prompt**：配置摘要删掉了条款限定条件，只保留在读面给店铺名片。
- 新增 `GET /api/v1/support/store-profile`（只读、租户只从令牌取）。
- `runModelPath`：只有 `intent.isPolicy()` 才检索条款；`UNKNOWN` 走 `skipped:general-chat`，
  不再注入 5 条无关条款。`composeUserMessage(null)` 不再写“本轮未检索到相关条款”。
- **失败安全网（安全审查 M4）**：`UNKNOWN` 先用 T0 词表做一次零 token 的 `looksPolicyShaped`
  判定；命中政策形状（“售后/保障/退货/理赔/酒店词”等）仍走检索与空召回出口。
  误判成 `UNKNOWN` 的政策诉求因此不会绕过 ADR 0068。
- **泛化政策问句**（命中形状但归不到具体意图，如“有哪些售后保障”）不调模型：返回一句
  确定性追问，要求具体商品/订单与问题类型；避免模型把平台规则与本店规则合并成更宽的承诺。
- **条款上下文带 scope**：`composeUserMessage` 在每条条款前标 `[本店]` / `[平台]`，
  提示词要求同一事项口径不同时以本店为准，只复述原文写出的时限、次数与金额。
- 验收：
  - 同一句「你们店是卖什么的？」在 T001/T002/T003/T004 分别答出星选生活旗舰店 / 鲜果驿站 /
    云裳服饰旗舰店 / 云栖酒店。
  - T004「入住和退房时间是几点？」仍为 `POLICY_HOTEL` + 5 条引用。
  - T001「生鲜坏了怎么赔」仍为 `POLICY_FRESH` + 引用（政策路径零回归）。

### C. 产品入口

- 买家端：`?store=TXXX` 默认 `/auth/mock-token` 以 `C001` 游客进入；显示店铺名片与三张快捷问题；
  **游客不展示「我的工单」**（共享 C001 会串出前一位访客的记录）；`guest=0` 保留口令登录
  （供跨买家隔离门禁）。
- 坐席端：`POST /auth/demo-login {tenantId, role}`，服务端用 `SHOPPILOT_IDENTITY_DEMO_PASSWORD`
  换真账号令牌；只接受 `agent` / `admin`，与 `/auth/mock-token` 同受回环条件约束。
  **请求期再做一次 remoteAddr + Host 回环校验**（挡反代 / SSH 隧道 / DNS rebinding），
  并校验下游账号角色确为员工。
- 根路径：产品导航页（三个入口 + 四个店铺直达 + readiness 状态）；调试台移到 `/console.html`，
  `/admin/debug` 重定向到它。
- 验收：三条 Playwright 门禁实跑；根路径标题与三个入口断言；游客与一键体验各一格。

## 实现要点

1. `LlmGateway` 的选择逻辑一行未改——修的是配置入口，不是绕过熔断或放宽预算。
2. 店铺摘要只放“介绍性事实”，提示词里明写“具体政策/时限/金额以条款与工具返回为准”。
   名称以 `SeedRunner` 与 `knowledge/` 为准；**T002 是鲜果驿站，酒店是 T004 云栖酒店**。
3. `UNKNOWN` 不检索后仍下发动作工具：T0/T1 漏判的“我要退款”仍有机会由模型选工具；
   工具选择本身继续充当意图证据（ADR 0007）。
4. 写缓存资格未动：`Intent.cacheAdmissible()` 仍只允许政策意图，`WriteBackPolicy` 仍要求检索命中，
   所以店铺介绍与开放问答不会进语义缓存。
5. 调试台只换了地址：`verify-console.mjs` 的 45 条判据一条未删；缓存命中那条从固定 `1.5s`
   改成等“命中”meta 出现（入口情绪分类在 dev 模式也是一次真实模型调用，固定 sleep 会误判）。
6. 本机 `.env`（不入库）另设 `temperature=0.6`、`read-timeout=60s`；当日此前评测已用掉
   1,036,188 token，演示前把日预算临时上调到 2,000,000。**仓库默认与 `.env.example` 口径不变。**

## 验证落点

- `bash ./mvnw -B -ntp verify` → 四模块 **`10 + 73 + 466 + 45 = 594`** 全绿（新增 `StoreProfileServiceTest` 3 条）。
- `frontend-buyer` / `frontend-workspace`：`npm run typecheck` + `npm run build` 全绿。
- 活体（网关 `dev` + DashScope `qwen-plus`，重新打包并重启后）：
  - T001/T002/T003/T004 的店名问题分别答出各自店名与主营，`intent=UNKNOWN`、`fallback=null`、0 引用。
  - T004 酒店政策问题 `intent=POLICY_HOTEL` + 5 条引用；T001 生鲜政策 `intent=POLICY_FRESH` + 引用。
  - 店铺资料端点四店均 200。
  - `/` 200 产品导航；`/console.html` 200；`/admin/debug` 302 → `/console.html`；
    `/buyer/`、`/workspace/` 200。
- 浏览器门禁（Playwright，`.tools/node_modules`）：
  - `verify-buyer.mjs` **13/13**；
  - `verify-workspace.mjs` **14/14**；
  - `verify-console.mjs` **45/45**；
  - 新增产品探针 **13/13**（根导航、T002/T004 游客开聊与快捷问题、坐席一键体验与队列）。
- **判据面零触碰**：gold / 阈值 / 分母 / `judge()` / `Intent` / `FallbackReason` 枚举未改；
  调试台门禁只改目标地址与一处等待方式。

## Handoff notes

**关键决策**

1. 修的是**配置入口**（`llm.mode` 可配 + 值域 `@Pattern`），没有改 `LlmGateway` 的熔断/预算逻辑；
   非法 mode 在绑定层拒启，避免“未知值静默走云端且绕过日预算”。
2. 店铺资料只把「店名/主营」放进 Prompt；承诺摘要只给前端名片。原因：摘要删掉了除外类目、
   次数上限、券/现金之别，模型复述会给出比真实政策更宽的承诺。
3. `UNKNOWN` 不再全量检索，但保留 `looksPolicyShaped` 兜底：政策形状仍检索、空召回仍转人工。
   一般问答（“推荐什么商品”）没有政策词，不会被重新污染。
4. 游客是共享 `C001`，所以游客页面不渲染工单；口令登录的真实买家才能看到自己的售后进度。
5. `demo-login` 的防线是三层：Bean 注册期回环条件、请求期 remoteAddr + Host 校验、下游角色复核。
   非本机请求与端点未注册同形（404），不新增可探测面。
6. 泛化政策问句不交给模型自由发挥：命中政策形状但没有具体意图分区时返回确定性追问。
   条款上下文带 `[本店]/[平台]` 标记，模型被要求以本店条款为准、只复述原文数字。

**验证落点**

- `bash ./mvnw -B -ntp verify` → 四模块 **`10 + 73 + 468 + 45 = 596`**（安全审查后新增
  `invalidLlmModeIsRejected`、`StaticEntryExemptionTest` 的 `/admin` 负例与 `/console.html`
  正例，具体条数以本次运行汇总为准）。
- 三个 Playwright 门禁与产品探针重跑；`shot-console.mjs` / `capture-polarity-guard.mjs` 已跟到
  `/console.html`，`docs/console.png` 重拍。
- 安全审查项：M1 请求期回环、M2 mode 值域、M3 `/admin` 精确放行、M4 政策形状兜底、
  S1 承诺不进 Prompt、S2 游客隐藏工单、S3 角色复核、S4 命中等待串、S5 采集脚本、S7 口令回显。
- **判据面零触碰**：gold / 阈值 / 分母 / `judge()` / `Intent` / `FallbackReason` 未改。

**三个现场追问**

1. **为什么不让 `UNKNOWN` 直接转人工，或反过来对空召回也放开模型自由回答？**
   `UNKNOWN` 是“判定不出”，不是“本店没有政策”。前者交给店铺人格 + 模型常识，后者若放开，
   酒店会编出“死蟹全额退款”、生鲜店会编出“15 天无理由”——ADR 0068 的跨行业事故就是这么来的。
   所以两条路分开：一般问答不检索，政策空召回仍确定性转人工。
2. **为什么店铺资料放在网关配置而不是查库？**
   `tenants` 表只有 `id/name/rate_limit_qps`，没有主营与承诺；为一段介绍新增列要动 H2/PG 两套
   schema。当前摘要是**对话层文案**，不是业务事实，放配置并由测试钉住“四店各不相同”更小。
   若将来店铺资料要运营可改，再把它迁到 biz-mock 并让网关读。
3. **一键体验登录为什么不是前端预填口令？**
   预填等于把凭证发给浏览器（ADR 0056 的红线）。`/auth/demo-login` 由服务端持有口令换令牌，
   浏览器只收到令牌；端点只在回环注册，且只认 `agent` / `admin`。买家游客入口复用既有的
   `/auth/mock-token`，没有新增第二套假身份机制。
