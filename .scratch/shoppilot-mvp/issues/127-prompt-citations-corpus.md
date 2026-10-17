# 127 提示词换代、citationDetails、语料修正

**Status:** implemented

## What to build

- **提示词换代**：`prompts/agent-system/v1.1.0.md` 改成行业无关底座（「你是商家的在线客服助手」），
  并在无工具分支加「办不了就说清并转人」的约束；`meta.json` 指到 `v1.1.0`；`v1.0.0.md` 保留。
  `PromptCatalogTest` 换代 + 新增行业无关断言；SSE/Sync 的 promptVersion 断言同步换代。
- **引用契约只增**：`AgentResult.CitationDetail(ruleId, heading, doc)` + `citationDetails` 字段；
  `SseEventSink.done` 发 `citationDetails`；`ChatController.ChatResponse` 加 `citationDetails`；
  `EsRestClient.Hit` 带 `headingPath`/`sourceDoc`（命中路径不跑检索，标题必须随命中带出）。
  旧 `citations: List<String>` 原样保留（反馈账本与旧脚本依赖它）。
- **买家端**：`frontend-buyer` 的 `types.ts`/`api.ts` 读 `citationDetails`，`App.vue` 优先展示它——
  否则引用列表退化成一条条「政策条款」（D8）。重新构建产物入库。
- **语料修正**：`return-01-7day-basic.md` 的「十四天内可申请」改回七天；新增
  `fresh-08-shop-t002-dead-crab.md`（T002 店铺级死蟹细则：六小时窗口 / 按只折算 / 订单核验 /
  看照片不能定责必须转人）。`KnowledgeConsistencyTest` 钉住正文与语料事实。

## Blocked by

[124](124-round33-adr-and-spec.md)。

## 口径

- 引用契约**只增兼容**：旧客户端继续读 `citations`，新买家端读 `citationDetails`。
- 不把酒店补丁写进电商人设：底座改成行业无关，酒店由 domain 与语料约束，不靠提示词特判。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
cd frontend-buyer; npm run typecheck; npm run build
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：`citations` 是字符串数组（反馈与旧脚本按它读），改成对象会制造一次性 breaking change；
  只增 `citationDetails` 达到同一目的。买家端优先读详情，旧响应仍可降级显示 ruleId。
- **验证落点**：`PromptCatalogTest`、`SseEventSinkTest`、`GatewayMainPathJvmTest`/`ChannelFlowJvmTest` 的
  promptVersion 断言、`KnowledgeConsistencyTest`、`MarkdownChunkerTest`；`frontend-buyer` typecheck + build
  绿且产物与仓内一致。
- **活体**：T004 问「退房时间是几点」→ `citationDetails` 返回
  `{ruleId, heading: "入住的统一时间与提前入住", doc: "hotel-01-checkin-checkout"}` 等 5 条；
  买家端 `/buyer/` 服务的新 bundle 与仓内构建产物同哈希。
- **三个现场追问**：
  1. `citationDetails` 现在只带 `ruleId/heading/doc`——买家端是否需要「点开看条款原文」？那要再带 `text`
     或一个按 ruleId 取正文的端点。
  2. `return-01` 的正文改了会移动 T001 的语料指纹，本轮只**重钉了指纹**、没有重录 `dense/lexical/fused`
     （需要活体 embedding）——是否要在下次清场日重录整份夹具？
  3. 提示词 `v1.1.0` 是行业无关底座，但**没有任何 per-domain 附加约束**——酒店「纯咨询」这条目前靠
     domain 与零工具实现，是否要在提示词里也显式声明酒店是纯咨询？
