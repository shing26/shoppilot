# 157 坐席工作台控制台化：三视图 + 编排质量产品面

**Status:** implemented（2026-10-25）

**Claimed by:** zcode session 2026-10-25（ADR 0073）

> 依据：[ADR 0073](../../../docs/adr/0073-round38-workspace-console-product-surface.md)、
> [ADR 0070](../../../docs/adr/0070-round36-product-surface-completion-reopen-basis.md)、
> [ADR 0072](../../../docs/adr/0072-agent-handoff-graph-and-role-kernel.md) H4。

## What to build

1. 工作台信息架构升级为三个视图：
   - 工单队列（既有）
   - 退款审核（既有）
   - 编排质量（新增）
2. 编排质量视图读取 `GET /api/v1/support/ops/quality/report`，展示：
   - 请求 / 降级 / fallback 比例
   - Handoff allowed / blocked / blocked ratio / 决策耗时
   - blocked reason 分布
   - tool risk 尝试分布
   - friction：工具成功仍降级、重复追问槽位、Handoff 上下文丢失
   - Redis audit 最近 15 分钟动作计数
3. `QualitySidecarController` 授权对齐既有 ops 面：ops token 或已验签 AGENT/ADMIN。
4. 工作台视觉升级为控制台壳：左侧导航、清晰工作区、保留既有操作与失败口径。

## Blocked by

—

## 范围与验收

- 坐席/管理员登录后可直接读取质量报表，页面不要求另填 ops token。
- 质量视图只读；不出现改路由、处理工单等动作。
- 质量报表读取失败时显示失败原因，不伪装成 0。
- `npm run typecheck`、`npm run build` 通过；产物入库。
- 后端控制器权限测试覆盖 AGENT 放行与买家拒绝。
- 180 条 gold 零改动；JVM verify 全绿。

## Non-goals

- 不改买家端。
- 不引图表库或运行时 UI 依赖。
- 不做实时刷新推送；手动刷新即可。
- 不改 H4 后端口径。

## Verify

- 本机：`cd frontend-workspace && npm run typecheck && npm run build`
- 本机：`./mvnw.cmd -B -ntp -pl shoppilot-gateway -am test`
- 产物：`git status` 必须包含 `static/workspace` 的新构建文件。

## Handoff notes

**关键决策**

1. 坐席台从“一张长页”升级为三视图控制台：工单队列 / 退款审核 / 编排质量。
   队列与退款的既有行为、失败口径、确认对话框原样保留，只换信息架构。
2. 质量视图直接消费 ADR 0072 H4 的只读 `/quality/report`，不新增聚合逻辑、不加 LLM、
   不提供任何干预动作；报表读取失败按失败展示，不伪装成 0。
3. 权限与既有 ops 面统一：`X-Ops-Token` 或已验签 `AGENT/ADMIN`。工作台用登录令牌直接读，
   页面不要求另填运维令牌；买家仍 403。
4. 视觉不引组件库、不加运行时依赖；沿用 `frontend-shared` 令牌，新增左侧导航与质量卡片/分布/表格。
5. 构建产物入库并重建，CI 的逐字节校验继续生效。
6. 根路径产品首页的坐席卡片同步改为“坐席控制台”，描述三视图与 Handoff 指标，入口文案改为“进入控制台”，
   避免产品首页仍指向旧形态。

**验证落点**

- `npm run typecheck`：通过。
- `npm run build`：通过；`static/workspace/` 产物已重建。
- `mvnw verify`：四模块 **681 tests / 116 suites** 全绿。
- 浏览器活体验证 **11/11**：页面加载、一键坐席登录、三视图导航、默认队列、质量视图 4 张 KPI、
  Handoff / 上下文丢失指标、只读说明、退款独立视图、390px 不横向溢出、无页面异常。
- 真实数据验证：先跑四轮 handoff 序列，质量视图显示 `4 请求 / 1 降级 / 1 blocked / 3ms 决策耗时`
  与 `QUERY_LOGISTICS / READ_ONLY / AFTER_SALES` 工具风险行。
- 截图：[质量视图](../../../docs/workspace-console-quality.png)、[工单队列](../../../docs/workspace-console-queue.png)。

**未达成 / 边界**

- 既有 `scripts/verify-workspace.mjs` 的选择器对应旧页面形态，本轮未改它的判据，也未把它当作本轮证据；
  本轮浏览器证据来自独立临时脚本（未入库）。后续若要让该门禁继续作为正式门禁，需要单独更新选择器并活体复跑。
- 买家端本轮未改，仍是下一张产品面票的候选。

**三个现场追问**

1. **为什么质量视图不做自动刷新？** 它是人工排查面，手动刷新足够；自动轮询会把只读旁路变成高频数据源。
2. **为什么允许坐席/管理员读质量报表？** 他们本来就是 ops 面的合法使用者；只读动作名计数不含 actor，
   与既有 `OpsAuthorizationInterceptor` 口径一致。
3. **为什么先做坐席台而不是买家端？** H4 的数据已经存在，只差展示面；先把“后端能力可见”这件事做实，
   比再改一轮聊天外观更能回答“项目到底做出了什么”。
