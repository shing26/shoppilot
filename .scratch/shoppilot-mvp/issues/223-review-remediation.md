# 票 223：票 222 审查整改

Round: 55（回归修复；按 ADR 0031 执行）
ADR: 0030 / 0031（回归与现有门禁修复，不新增功能面）　Status: done（2026-11-23 所有者书面签收）　Claimed by: root　Blocked by: 无
契约：[plans/223-review-remediation.md](../../plans/223-review-remediation.md)（2026-11-22 冻结）

## What to build

1. 恢复买家工单列表的完整工单号可见文本，用 CSS 省略而不是改写文本。
2. 坐席身份断言改为登录响应 `displayName`，同时保留「不是内部账号名」的反向断言；
   领取按钮限定在详情动作区，结单对话框断言移到领取之后。
3. 只保留根入口一条五节点工单链路，删除登录页重复链路与硬编码状态。
4. 增加非安全上下文复制降级，并显示可手动复制的完整工单号。
5. 移动端页面边距、卡片内边距、等宽数位、非药丸计数、单一刷新入口收口。
6. `shortTicketId` 下沉 `frontend-shared`。

## 边界

- 不改 API、SSE、状态机、认证、权限、数据或 Java 代码。
- 不改其他既有测试断言、gold、阈值或覆盖率门槛。
- 不新增依赖、网络字体、图标库或装饰图片。

## Verify

```powershell
cd frontend-buyer; npm run typecheck; npm test; npm run build
cd ..\frontend-workspace; npm run typecheck; npm test; npm run build
cd ..
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
git diff --check
git status --short
```

活体复核：1440×900 与 390×844 截图，覆盖根入口、买家工单列表、坐席身份与详情复制。

## 验收项

- `scripts/verify-buyer.mjs` 的完整工单号断言不再被 UI 改写破坏。
- `scripts/verify-workspace.mjs` 验证 `.identity` 等于登录响应 `displayName` 且不等于账号名；
  领取与结单对话框断言按「先领取再结单」执行。
- 根入口链路为五节点且无硬编码完成态；登录页不再重复链路。
- 非安全上下文可复制或有可手动选择的完整工单号。
- 390px 下页面 24px 边距、卡片至少 20px 内边距、无横向溢出。
- 两端 typecheck / vitest / build 全绿；全仓 verify、文档真值检查通过。

## Handoff notes

**关键决策**

- 买家工单号仍以完整文本渲染，列表短显只用 `text-overflow: ellipsis`；
  `verify-buyer.mjs` 的 `myTicketId` 断言保持原样。
- 坐席身份断言改为登录响应 `displayName`，并保留「不等于内部账号名」的反向断言；
  领取按钮限定在 `.detail-actions`，结单对话框断言移到领取成功之后。
- `shortTicketId` 与 `copyToClipboard` 下沉 `frontend-shared`。复制优先
  `navigator.clipboard`，非安全上下文或权限失败走 textarea + `execCommand`；
  失败时完整单号常驻且 `user-select: all`，直到切换工单，避免 1.6 秒后自动收口来不及选中。
- 根入口只保留一条五节点链路（下单 → 提问 → 转人工 → 领取 → 结单），
  不写死 `done` / `current`；买家与坐席登录页删除重复链路。
- 390px 下买家 `.wrap` 24px、`.side-card` / `.ticket-card` 20px；
  坐席 `.work-area` 24px、`.queue-pane` 20px；计数、时间、金额使用
  `tabular-nums`，计数徽标改小圆角。买家侧栏只保留一个刷新入口。
- 本机 8082 被另一个项目 `NexusCampusApplication` 占用，本轮活体证据在 18082 采集；
  没有停止或修改该外部进程。仓库默认端口与脚本默认值未改。

**验证落点**

- 买家：`typecheck` 通过；`vitest` 4 files / 37 tests；`build` 通过；
  `scripts/verify-buyer.mjs` 13/13，含完整工单号与跨买家隔离。
- 坐席：`typecheck` 通过；`vitest` 3 files / 15 tests（新增 `format.test.ts` 2 条）；
  `build` 通过；`scripts/verify-workspace.mjs` 23/23。WARN：队列只有普通优先级，
  排序断言本轮未覆盖。
- 全仓：`.\mvnw.cmd -B -ntp verify` → BUILD SUCCESS，
  `12 + 92 + 673 + 50 = 827 tests / 136 suites`。
- 文档与脚本：`python scripts/gen_status.py --check` → `STATUS ok`；
  `python scripts/verify_doc_truth.py` → `827 tests / 136 suites`；
  `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` → 37 files / 0 errors；
  `git diff --check` 退出 0。
- 视觉/复制：`.scratch/unslop-223/review.mjs` 23/23，覆盖 1440×900 与 390×844、
  根入口五节点、买家完整工单号 + CSS 省略、移动抽屉、坐席身份、复制成功与失败降级、
  两端无横向溢出、24px 页面边距与 20px 卡片内边距。
  截图在 `.scratch/unslop-223/shots/`（本机证据，未入库）。
- 独立 Verifier 复跑发现一次 22/23：`verify-workspace.mjs` 的发布/驳回断言用全局
  DRAFT/REJECTED 计数，内存库历史残留会让「1 版已驳回 / 1 版待发布」假红。
  已把断言收紧到本次 `feedbackId` 对应的修订卡（DRAFT → PUBLISHED / REJECTED），
  不降低覆盖；修正记录见契约「验证期修正」。
- 同一轮脏状态复跑还抓到：队列首行可能是历史复核工单（已结单），领取/结单会打到它上面。
  已改为按本次转人工返回的 `ticketId` 精确选中队列行，并新增「该行存在」断言；
  动作与结单对话框断言未放宽。

**三个现场追问**

1. 8082 被外部项目占用时，活体门禁是否要正式支持独立网关端口覆盖？
   本轮用 `SHOPPILOT_GATEWAY_BASE` / `SHOPPILOT_WORKSPACE_BASE` 指向 18082 完成，
   脚本默认端口未改。
2. 复制失败提示现在常驻到切换工单；是否需要在移动端再加一句「长按选择」或自动选中文本？
3. 工单短号仍是「首字母 + 末 8 位」；若客服对账依赖时间戳前段，需要另定规则，
   完整号始终可见并可复制。

**所有者签收（2026-11-23）**

- 所有者对话内书面指令「签收」。签收范围：票面 6 项整改、买家 13/13、坐席 23/23、
  视觉/复制 23/23、全仓 827/136，以及验证期把 DRAFT/REJECTED 计数与历史队列行的
  断言收紧到本次 `feedbackId` / `ticketId`（只收紧断言，未降低覆盖）。
- 已知边界按原样保留：坐席队列仅覆盖普通优先级，排序断言未覆盖；活体在 18082
  采集（8082 被外部项目占用），脚本默认端口未改；三项现场追问留作后续产品决策。
