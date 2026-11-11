# round47 开轮依据：真实用户走查的十二项 P2 缺陷修复

Date: 2026-11-03　Status: **Accepted**（2026-11-03 所有者指令「把 P2 按同一套流程收口」）

Owner basis: **缺陷修复，不是新功能**（与 ADR 0080 / ADR 0081 同一依据，走查报告
[`ux-probe/REPORT.md`](../../.scratch/ux-probe/REPORT.md) 的 P2 清单）。报告列了 **12 项 P2**：
其中 11 项是本轮要修的产品面缺陷，**第 12 项「游客转人工后的死路」在 round46 票 192 删除
`isGuest` 短路后已不成立**——本轮复核代码与文案后按「复核后不成立」登记，不为了凑数改一行。
P2 里唯一需要后端改动的是质量报表的「最大 0.00ms」（`QualitySidecarService`），
其余全部落在两个产品入口与共享层。

Tickets: [195](../../.scratch/shoppilot-mvp/issues/195-shared-labels-401-and-contrast.md) /
[196](../../.scratch/shoppilot-mvp/issues/196-buyer-mobile-and-aria.md) /
[197](../../.scratch/shoppilot-mvp/issues/197-workspace-queue-and-mobile.md) /
[198](../../.scratch/shoppilot-mvp/issues/198-quality-decision-max.md) /
[199](../../.scratch/shoppilot-mvp/issues/199-round47-closeout.md)。
Spec: [round47-spec-ux-p2-fixes.md](../../.scratch/shoppilot-mvp/round47-spec-ux-p2-fixes.md)。

## Context

P2 与 P0/P1 形态不同：**路径是通的，读起来不对**。用户能完成操作，但界面上出现
原始枚举、自相矛盾的读数、看得见点不动的目标，或一条把人留在原地的提示。

1. **原始枚举直接进 UI**：`DOWN`（知识视图）、`PENDING_REVIEW`（退款决策路径）、
   `BOT`/`AGENT`（渠道会话）、`SEVEN_DAY_RETURN`（买家订单服务标）四个位置把存储字面量
   当成产品文案。
2. **筛选与详情打架**：队列筛到「已超时」且结果为 0 时，右侧详情仍显示上一张单
   （`App.vue` 的 `selected` 从 `tickets` 取，不看 `filtered`）。
3. **结单可以跳过领取**：未领取的工单「处理完成」按钮仍可用；后端状态机允许
   `OPEN → RESOLVED`，于是「已处理」的数字可以由任何人改写。
4. **质量报表「最大 0.00ms」**：均值 0.06ms 而最大 0.00ms。`Timer.max()` 走的是
   `TimeWindowMax`（时间窗），空闲一段时间后归零，而均值是进程累计值。
5. **对比度不足**：`tab-count` 3.72:1（11px）、未选中的 `side-tab` 与顶栏 `small`
   4.35:1，均低于 WCAG AA 普通文本的 4.5:1；根因是 `--ink-3`（`#6b7280`）。
6. **移动端点击目标过小**：「退出」「刷新」21px、「问问这单」19px、侧栏 tab 35px。
7. **移动端导航吃掉首屏**：工作台 `SideNav` 在 ≤640px 变单列，6 个导航项约 400px
   才见到队列。
8. **移动端点「问问这单」后不回到对话**：新回答在侧栏上方（单列布局先聊天后侧栏），
   视口停在按钮处。
9. **401 不自动登出**：共享 `request()` 只透出状态；买家/坐席只看到「登录已失效」，
   仍停在已登录界面，要先点「退出」。
10. **整段对话一个 `aria-live="polite"`**：流式时读屏器可能反复播报整段历史，
    应改成 `role="log"` 的增量语义。
11. **知识五步条的「人工复核」恒为 0**：统计的是「已复核且可附稿」的列表；
    附过稿后从列表移除，刷新后归零，读起来像「没人复核」。
12. **游客转人工后的死路**：reporter 看到「工单号 T…（游客模式不展示工单）」。
    round46 票 192 已删 `isGuest` 短路与这句文案，侧栏工单页签会真实回填；
    本轮复核确认**不成立**。

## Decision

1. **枚举可读名放共享层**（`frontend-shared`）：`FeedbackVerdict` / `RefundStatus` /
   `ConversationStatus` / `ServiceFlag` 四张映射表与既有 `STATUS_LABEL` / `PRIORITY_LABEL`
   同源。**未知值原样透出**，不吞、不猜——这是本仓既有的标签约定。
2. **401 自动登出做成共享钩子**：`request()` 在**带令牌**的请求收到 401 时调用
   `setUnauthorizedHandler` 注册的回调；两个入口各自注册「中止流 / 清会话 / 回登录面」。
   登录请求本身不带令牌，不会误触发。
   - 买家：保留本标签页对话与工单数据不删（令牌过期不该等于丢对话），回到登录门并写明原因。
   - 坐席：停掉渠道 SSE、清空会话与队列，回到登录卡并写明原因。
3. **对比度修根因**：`--ink-3` 从 `#6b7280` 调到 `#5c636e`。对 `--bg` 5.46:1、
   对 `--line` 4.67:1、对白底 6.06:1，三处被点名的读数一次过线；不逐处改字号或加粗。
4. **移动端点击目标统一到 ≥40px**：买家顶栏「退出」、侧栏「刷新」、订单「问问这单」、
   订单/工单 tab；工作台顶栏「刷新队列」「退出」与侧栏导航项。只在触屏宽度
   （≤640px）放宽，桌面密度不变。
5. **工作台队列的详情跟着筛选走**：`selected` 从 `filtered` 取；筛选变化后若当前选中
   不在结果里，自动选中第一条，结果为空则清空详情。
6. **结单前必须领取（产品面）**：「处理完成」在 `assignee` 为空时禁用并给出
   「先领取」的说明；动作入口再做一次守卫。
   **后端状态机不改**（`OPEN → RESOLVED` 仍是合法迁移）——这条是产品规则，
   不是数据不变量；理由见 Consequences。
7. **移动端导航改三列两行**（≤640px），与 641-960px 的布局一致，不再单列堆叠；
   导航项最小高度 40px。
8. **「问问这单」在窄屏滚回对话**：点击后把聊天主区滚到视口顶部；宽屏不动。
9. **对话容器改 `role="log"` + `aria-relevant="additions text"` + `aria-atomic="false"`**：
   读屏器只播报新增内容，不重播整段历史。
10. **五步条「人工复核」改用服务端 REVIEWED 总数**：工作台保留 `reviewedTotal`
    （`status=REVIEWED` 的完整列表长度），与「已复核 · 可附稿」列表分开；附稿会让后者
    减少，但不该让「人工复核」这一步归零。
11. **质量报表读累计最大**：`AgentStateMachine` 在记录 timer 的同时维护
    `AtomicLong` 累计最大值，并以 Gauge `shoppilot_handoff_decision_seconds_max` 暴露；
    `QualitySidecarService` 优先读这个 Gauge，读不到才回退 `Timer.max()`。
    均值/计数口径不变。

## Considered Options

- **把「最大」从页面拿掉，只显示均值**：否决。报表的价值就在「最慢一次」；删掉读数
  等于承认这条观测做不了，而累计最大是低成本可做的。
- **把 `Timer.max()` 的时间窗调大**：否决。仍是时间窗，空闲后照样衰减；只是把
  「永远显示 0」推迟成「偶尔显示 0」，没有修掉根因。
- **让后端 `resolve` 拒绝未领取工单**：本轮否决。状态机 `OPEN → RESOLVED` 是既有
  契约（脚本与流程都可能直接结单），改它会动验收面；P2 的落点是产品面按钮。
  后端语义登记在票 197 的现场追问里。
- **在 401 时静默刷新令牌**：否决。仓库没有刷新令牌端点；P2 要的是「回到登录面」，
  不是发明一套会话续期。
- **逐处把 11px 字号加大**：否决。3.72:1 与 4.35:1 的根因是同一个色值；加字号只能
  让其中一处过线，且改变密度。
- **把「游客转人工」也改一遍**：否决。round46 已删除短路与文案，本轮复核结论是
  「不成立」；为凑满 12 项而改代码会制造假修复。

## Consequences

- **指标名 +1**：`shoppilot_handoff_decision_seconds_max`（Gauge）。README 的源码口径
  从 **72 / 74** 变为 **73 / 75**（三模块 / 四模块），随本轮登记换代。
  既有指标名、标签与分母一个不变。
- `frontend-shared` 新增四张标签映射与 `setUnauthorizedHandler`；两端各注册一次，
  未注册时 `request()` 行为与之前完全一致（401 仍原样返回）。
- **SSE 入口同样接钩子**：买家 `streamAnswer` 是裸 `fetch`，不走 `request()`；它在
  带令牌且响应 401 时调用同一个内部通知函数，避免「轮询请求会登出、流式问答不会」
  的半截行为。钩子内部复用同一个回调，不新增公共 API。
- 对比度调整会让两个入口的次要文字整体略深；这是可访问性修复的预期副作用。
  `--ink-3` 因此与 `--ink-2` 同值（`#5c636e`），二级/三级文字不再有色阶差。要恢复层次
  只能整体下调 `--ink-2`，不能把 `--ink-3` 调浅——`#5f6672` 对 `--line` 只有 4.45:1。
- 移动端点击目标放宽只影响 ≤640px；桌面 1440×900 的既有截图口径不变。
- P2-12 按「复核后不成立」收口，不产生代码改动；证据写在票 196 的 Handoff 里。
- **不做**：后端 `resolve` 的领取前置、`aria-live` 之外的完整无障碍审计、
  移动端导航的折叠式重设计。

> **round50 票 218 换代指针**：本 ADR 正文中的 Gauge `shoppilot_handoff_decision_seconds_max`
> 已改名为 `shoppilot_handoff_decision_max_seconds`。原名与 Timer 自动导出的 Prometheus
> `_seconds_max` 重名，会让整个 `/actuator/prometheus` 抓取 500；这是改名不是新增，指标名计数不变。
> 正文保留 round47 当时的决策记录，不改写。
