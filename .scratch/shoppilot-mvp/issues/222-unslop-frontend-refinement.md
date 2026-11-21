# 票 222：三端 unslop 精修

Round: 54（UI-only；所有者 2026-11-21 确认设计阅览稿）
ADR: 无（不触碰业务逻辑、API、认证、支付、schema、生产依赖）　Status: implemented（待所有者验收）　Claimed by: root　Blocked by: 无
契约：[plans/222-unslop-frontend-refinement.md](../../plans/222-unslop-frontend-refinement.md)（2026-11-21 冻结）

## What to build

在票 219 的产品化结构上完成九轮精修：

1. 共享令牌切到纸白、暖白面板、墨色文字与印章绿；正文不低于 13px，元信息 12px。
2. 根入口改为左侧产品叙事、右侧真实工单链路预览；去掉三张等宽功能卡。
3. 买家端删除装饰编号与 `guest` 文案；提高输入框视觉权重；移动端增加「订单与进度」抽屉。
4. 坐席端 KPI 改为一大三小；队列行收敛为状态点 + 摘要 + 时间；生命周期成为详情主栏；
   工单号短显且可复制；移动端列表 ↔ 详情切换。
5. 统一圆角、留白、状态表达与动效；只保留一个产品时刻与一条追踪链路。

## 边界

- 不改 API、SSE、状态机、认证、权限、数据或 Java 代码。
- 不删除既有选择器、关键文案与测试断言。
- 不新增依赖、网络字体、图标库或装饰图片。
- 不伪造运营指标；所有链路节点只使用现有状态字段派生。

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

活体复核：1440×900 与 390×844 截图，覆盖根入口、买家首页 / 抽屉、坐席队列 / 详情 / 移动切换。

## 验收项

- 三端令牌、字号与圆角一致，正文无 10–11px。
- 根入口是叙事 + 工单链路，不再是等宽功能卡。
- 买家移动端能打开并关闭「订单与进度」抽屉。
- 坐席摘要是一大（当前队列）+ 三小（待处理 / 处理中 / 已超时），退款入口仍在。
- 坐席队列行只有一个状态药丸；详情以生命周期为主结构；短号可复制。
- 坐席移动端进入详情后不显示整列队列，返回可用。
- 既有选择器与关键按钮文案保留。
- 两端 typecheck / vitest / build 全绿；全仓 verify、文档真值检查通过。

## Handoff notes

**关键决策**

- 买家身份展示改为 `displayName` 优先，`guest` 只作为内部账号回退为「访客」；侧栏会话信息与顶栏共用同一展示名。坐席顶栏同理，只显示账号展示名（演示档为「演示坐席」）与「坐席 · 店铺 T001」，内部账号名不上屏。
- 工单短号在两端各自的 `lib/format.ts` 增加 `shortTicketId`：保留首字母与末 8 位（如 `T…990650-5`），完整工单号仍放在 `title` 与复制按钮中。未改共享 `shortId` 的 18 位阈值，避免影响其他调用方；`frontend-shared` 本票只动令牌。
- 买家移动抽屉补了三个真实交互细节：Esc 关闭、打开后焦点进入「关闭」、背景滚动锁；因抽屉用 `visibility` 开合，首帧仍不可聚焦，焦点在两次 `requestAnimationFrame` 后进入。抽屉补 `box-sizing: border-box`，修掉 `width:100% + padding` 导致的左侧 32px 裁切。
- 坐席 KPI 三小卡保留一大三小结构，数字下补「未领取 / 已领取 / 超过 SLA」派生说明，避免高卡片中空；详情「复制完整工单号」补 `flex: none` 与最小行高，修掉纵向 flex 把它压成 0 高的问题。
- 未改 API、SSE、状态机、认证、权限、Java 代码、依赖或共享选择器；所有长链路节点仍由现有字段派生。

**验证落点**

- 买家：`typecheck` 通过；`vitest` 4 files / 37 tests；`build` 通过。
- 坐席：`typecheck` 通过；`vitest` 2 files / 13 tests；`build` 通过。
- 全仓：`.\mvnw.cmd -B -ntp verify` → BUILD SUCCESS，四模块 `12 + 92 + 673 + 50 = 827 tests / 136 suites`。
- 文档真值：`python scripts/gen_status.py --check` → `STATUS ok`；`python scripts/verify_doc_truth.py` → `827 tests / 136 suites` 一致；`git diff --check` 退出 0。
- 活体（Playwright，1440×900 / 390×844）：根入口、买家首页与抽屉、坐席登录 / 队列 / 详情 / 移动列表 ↔ 详情全部断言通过；买家抽屉 390 宽、`x=0`、焦点为「关闭」、Esc 与关闭按钮均可退出；坐席顶栏无裸 `agent`、短号含省略号、复制按钮高度 ≥20px；两端 `scrollWidth == clientWidth`。
- 截图（本机证据，未入库）：`.scratch/unslop-221/shots/` 下 `root-desktop.png`、`buyer-home.png`、`buyer-mobile-drawer.png`、`workspace-gate.png`、`workspace-queue.png`、`workspace-detail.png`、`workspace-mobile.png`、`workspace-mobile-detail.png`。

**三个现场追问**

1. 工单短号目前是「首字母 + 末 8 位」；若客服对账习惯依赖时间戳前段，需要再定一种短号规则，但完整号始终可复制。
2. 移动抽屉焦点在开启动效的前两帧后进入关闭按钮；若产品要求等 220ms 动效完全结束再聚焦，需要改成动效结束事件。
3. KPI 三小卡用了「未领取 / 已领取 / 超过 SLA」作为派生说明；若运营口径把「处理中」定义为包含其他状态，需要按后台定义重写这三个词。
