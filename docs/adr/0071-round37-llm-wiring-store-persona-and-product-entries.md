# round37 开轮依据：把真实模型接回主链路，并让店铺上下文进入对话

Date: 2026-10-20　Status: **Accepted**（2026-10-20 所有者指令）
Owner basis: 2026-10-20 所有者实机走查后要求「把这个智障项目改造为智能项目」，并点名两件先做的事：
① 修通模型调用，消除 `本地模型返回 500` 与 `LLM_CIRCUIT_OPEN`；② 让 T001/T002 同一句「你们店是卖什么的」
答出各自的主营。随后追加产品面要求：买家端免密开聊、坐席端一键体验、根路径不再默认展示调试台。
Tickets: 实现票 [152](../../.scratch/shoppilot-mvp/issues/152-llm-store-persona-and-product-entries.md)（票面即本轮 spec）。

Context:

2026-10-20 的活体现场同时暴露了三层问题，且**第一层不是模型能力问题**：

1. **模型根本没被调用**。`.env` 里 `SHOPPILOT_LLM_BASE_URL` / `MODEL` / `API_KEY` 指向 DashScope
   `qwen-plus`，直连该端点返回 200；但 `application.yml` 的 `shoppilot.llm.mode` 写死为 `local`，
   `LlmGateway` 据此选择 `OllamaLlmClient`，请求全部打到本机 Ollama 的 `qwen2.5:3b`——而本机并未
   `pull` 这个 tag，于是每次对话以 `本地模型返回 500` / `本地模型调用超时` 收场，再被熔断器统一
   翻译成「智能回复暂时不可用，已为您登记人工处理」。复现记录：同一次走查里
   `intent=UNKNOWN` 的「店铺推荐什么商品」耗时 20.5s 后 `LLM_TIMEOUT`，「你们店是卖什么的？」8.8s 后
   `LLM_CIRCUIT_OPEN`。
2. **一般问答被灌入无关政策**。`runModelPath` 对 `UNKNOWN` 也执行混合检索；实测「店铺推荐什么商品」
   仍召回 5 条条款（冷链、死蟹、价保等），模型要么照着念，要么被带偏。这不是检索坏了，是**入口给错了**。
3. **提示词没有店铺上下文**。系统提示词是一份通用客服基座，`T001`（星选生活旗舰店）与 `T002`
   （鲜果驿站）在没有任何专属条款命中时只能退化成同一套通用话术。

冻结线：`v1.0.0` 已冻结。第 1 项是**配置缺陷**（配了云端却永远走本地），按缺陷修复；第 2/3 项与
产品面（游客入口、一键体验、根路径导航）未命中 ADR 0030 五条，归类为**所有者政策覆盖**
（与 0066 / 0067 / 0069 / 0070 同一机制）。

Decision:

**一、模型模式与采样参数可配，但默认值不变**

- `llm.mode: ${SHOPPILOT_LLM_MODE:local}`；`llm.temperature: ${SHOPPILOT_LLM_TEMPERATURE:0.2}`。
  仓库默认仍是 `local` / `0.2`；本机演示在 `.env` 里设 `dev` / `0.6`（`.env` 不入库）。
- 两个新占位符登记进 `ConfigValidationTest` 的白名单，不绕过启动校验。
- 评测口径提醒：`temperature=0.6` 只用于对话演示，评测与历史读数仍用 `0.2`。

**二、店铺人格进 Prompt，但不成为第二份业务事实**

- 新增 `shoppilot.store-profiles`（`StoreProfileProperties` / `StoreProfileService`），按 `tenantId`
  追加一段「店名 / 主营」，再叠既有风格档位。**「本店承诺」不进 Prompt**：配置摘要删掉了
  条款的限定条件（除外类目、次数上限、券/现金之别），只保留在读面给前端做店铺名片。
- 摘要以 `SeedRunner` 与 `knowledge/` 的店铺级条款为准；提示词里明确写「只用于介绍与引导，
  具体政策、时限、金额必须以【政策条款】或工具返回为准」。未登记租户不追加，避免跨店借资料。
- 新增只读端点 `GET /api/v1/support/store-profile`，租户只从已验签令牌取。

**三、一般问答不再检索政策；政策空召回一字不动，但加一道失败安全网**

- 只有 `intent.isPolicy()` 才执行条款检索；`UNKNOWN` 走 `skipped:general-chat`，由店铺人格 + 模型
  常识作答，不引用条款、不进缓存（`Intent.UNKNOWN.cacheAdmissible()` 本就是 false）。
- **政策形状兜底**：`UNKNOWN` 先用 T0 词表做一次零 token 的 `looksPolicyShaped` 判定；命中
  “售后/保障/退货/理赔/酒店词”等政策语义时仍走检索与空召回出口。分类错误（gold 的 UNKNOWN
  并非 100%）不能成为绕过 ADR 0068 的路。
- **泛化政策问句不交给模型发挥**：命中形状但没有具体意图分区（如“有哪些售后保障”）时，
  返回确定性追问，要求具体商品/订单与问题类型。实测模型会把平台七天无理由与本店生鲜
  不退合并成“水果蔬菜也能七天无理由”，这类答案比不答更糟。
- **条款上下文带 scope**：每条条款前标 `[本店]` / `[平台]`，提示词要求同一事项口径不同时
  以本店为准，只复述原文写出的时限、次数与金额。
- **政策意图的空召回仍按 ADR 0068 确定性转人工并写负标记**。本轮不撤销该决策：宁可转人工，
  不可拿另一个行业的条款回答。用户观察到的「动不动转人工」在修通模型后已由正常回答取代，
  不是靠放宽安全出口换来的。

**四、产品入口**

- 买家端：`?store=TXXX` 默认以 `C001` 游客身份进入（回环调试口 `/auth/mock-token`），页面显示
  店铺名片、三张快捷问题卡；**游客不展示「我的工单」**（共享 C001 会串出前一位访客的记录）；
  `guest=0` 保留口令登录与跨买家隔离门禁。
- 坐席端：`/auth/demo-login` 由服务端用 `SHOPPILOT_IDENTITY_DEMO_PASSWORD` 换真账号令牌，
  浏览器不接触口令；端点只接受 `agent` / `admin`，与 `/auth/mock-token` 同受
  `MockIdentityCondition`（仅回环注册）约束，并在**请求期**再校验 remoteAddr + Host
  （挡反代 / SSH 隧道 / DNS rebinding），以及下游账号角色确为员工。
- 根路径改为产品导航页（三个入口 + 店铺直达）；调试台移到 `/console.html`，并保留
  `/admin/debug` 别名。`verify-console.mjs` 的判据一条未删，只改目标地址。

Consequences:

- 主链路恢复为真实云端模型；本机演示不再复读熔断兜底。
- 店铺差异来自**已验签租户**，不是前端传参；跨店串话术的防线仍在（ADR 0068）。
- 新增 `StoreProfileServiceTest` 3 条；JVM 四模块 **10 + 73 + 466 + 45 = 594** 全绿；三条浏览器门禁
  实跑（买家 13/13、工作台 14/14、调试台 45/45）。
- 本机 `.env` 为演示把日预算从 600k 临时上调（当日此前评测已用掉 1,036,188 token）；仓库默认值与
  `.env.example` 的口径不变，这不是验收阈值变更。
