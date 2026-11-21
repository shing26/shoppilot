# 票 219 契约：产品化前端重设计（买家端 + 坐席工作台）

**状态**：2026-11-20 冻结
**开轮依据**：所有者 2026-11-20 直接指令「前端页面需要进行重新设计，让项目像可落地项目而不是 demo 可展示项目」。
**判定**：UI-only。不触碰业务逻辑、内部/对外 API、认证授权、支付、数据删除、数据库 schema、生产依赖；不适用验收链路的 Implementer/Verifier 分离，但保留构建、单测与浏览器行为验证。

**第二轮范围（2026-11-20，同一票内）**：参考 `docs/design-samples` 的 V1/V2/V3 方向，落地
「买家帮助中心 + 会话上下文」「坐席队列筛选 + 买家原始诉求 + 责任人」「根入口产品化」。
根入口页从「本机演示栈」改为「售后服务工作台」，工程入口折叠并标注开发者工具；不新增
运行时控制台，不伪造运营指标。

## 目标

把买家端与坐席工作台从「演示卡片 + 一键体验」的观感，收敛为可交付产品的工作界面：

- 深墨色产品外壳（顶栏 / 侧栏），浅灰画布，白色工作面板；
- 单一翡翠强调色，语义色只用于状态；
- 圆角从 14-20px 收到 6-10px，阴影只保留弹层；
- 登录页改分栏：左侧品牌与能力说明，右侧登录表单；
- 演示入口保留但降为次要动作，不再占据首屏主按钮位；
- 买家端首屏改为任务导向的常见问题列表，不再使用营销式 hero；
- 坐席端改全高操作台：KPI 条、队列列表、详情栏、独立滚动。

## Allowed paths

- `frontend-shared/src/tokens.css`
- `frontend-buyer/src/**`
- `frontend-workspace/src/**`
- `shoppilot-gateway/src/main/resources/static/buyer/**`（构建产物）
- `shoppilot-gateway/src/main/resources/static/workspace/**`（构建产物）
- `shoppilot-gateway/src/main/resources/static/index.html`（根入口页）
- `.scratch/shoppilot-mvp/issues/219-*.md`
- `plans/219-*.md`

## Acceptance criteria

1. 买家端与坐席端均采用新外壳：深墨色 chrome、浅灰画布、白色面板、单一翡翠强调色。
2. 未登录页保留既有可读文案与选择器：`.gate` 含「工单 / 转人工 / 账号登录」；`.login-card` 含「登录 / 队列」。
3. 既有 Playwright 选择器与文案不变：`.store-card`、`.side-tab`、`.ticket-card`、`.ticket-list`、`.thread`、`.msg.user`、`.msg.assistant .bubble`、`.composer`、`.cites`、`.cite-group`、`.side-activity`、`.who`、`.login`、`.login-form`、`.brand`、`.identity`、`.side-nav`、`.detail-pane`、`.detail-head h2`、`.priority`、`.dialog`、`.kb`、`.kb-flow .flow-step`、`.review-row`、`.draft-form`、`.revision-card`、`.ingest-note`。
4. 买家端 390px、坐席端 390px 均无横向溢出。
5. 买家端登录、店铺卡、我的工单、跨买家隔离、提问回答；坐席端登录、队列、详情、领取、结单确认均可用。
6. 两端 `typecheck`、`vitest`、`vite build` 全绿；全仓 `mvnw verify` 绿。
7. 根入口页不再自称「本机演示栈」或并列「不用登录也能开聊」；工程入口折叠并标注开发者工具，
   数据留存口径明示为演示档。
8. 买家端帮助首页按真实任务分类；会话头显示店铺、会话号与关联订单；侧栏含本次订单、售后进度、会话信息。
9. 坐席队列含关键词与来源筛选；列表行含优先级色点、来源徽章与 SLA；详情含买家原始诉求、生命周期与责任人。

## Verification commands

```powershell
cd frontend-buyer; npm run typecheck; npm test; npm run build
cd frontend-workspace; npm run typecheck; npm test; npm run build
.\mvnw.cmd -B -ntp verify
git diff --check
git status --short
```

活体页面：Vite dev server（代理到 8082）分别跑买家端与坐席端，截图并跑等价浏览器断言（工单隔离、提问、队列、领取、结单弹窗、390px 溢出）。

## Forbidden

- 改 API 调用、状态归属、SSE 协议、认证流程或任何 Java 代码；
- 改既有 Playwright 断言、gold、判据、阈值或测试文案；
- 删除演示入口或演示能力（只允许降级视觉权重）；
- 改调试台 `console.html`；
- 用 `!important` 全局覆盖绕过组件样式；
- 为「看起来更满」伪造指标、在线人数、响应时长等数据。
