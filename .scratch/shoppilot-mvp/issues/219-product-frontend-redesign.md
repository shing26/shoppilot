# 票 219：产品化前端重设计（买家端 + 坐席工作台）

Round: 51（UI-only；开轮依据 = 所有者 2026-11-20 指令「前端页面需要进行重新设计，让项目像可落地项目而不是 demo 可展示项目」）
ADR: 无（不触碰业务逻辑、API、认证、支付、schema、生产依赖）　Status: done（2026-11-21，所有者书面签收；与 220/221 统一收口）　Blocked by: 无
契约：[plans/219-product-frontend-redesign.md](../../plans/219-product-frontend-redesign.md)（2026-11-20 冻结）

## What to build

把两个产品入口从「演示卡片」收敛为可交付的工作界面：

1. **共享令牌**：深墨色 chrome、浅灰画布、白色面板、单一翡翠强调色；圆角 6-10px；阴影只保留弹层；控件基线统一 32-40px。
2. **买家端**：白色顶栏 + 紧凑店铺条 + 对话工作区 + 右侧订单/工单栏；首屏从营销 hero 改为任务导向的常见问题列表；登录页改分栏（品牌能力 + 登录表单）；演示入口降为次要动作。
3. **坐席工作台**：深色全高侧栏 + 深色顶栏 + 独立滚动主区；KPI 条合并为一条分栏指标带；队列改「列表 + 详情」双栏操作台；登录页改分栏。
4. **次要视图**：知识飞轮、退款审核、渠道连接、渠道会话、编排质量的圆角与阴影随令牌收敛，不改布局语义。
5. **构建产物**：重新生成 `static/buyer` 与 `static/workspace`，旧哈希资源由 `emptyOutDir` 清理。

## 边界

- 不改任何 API 调用、状态归属、SSE 协议、认证流程或 Java 代码。
- 不改既有 Playwright 选择器、文案断言、gold、判据、阈值。
- 不删除「一键体验」能力，只降低其视觉权重。
- 不改调试台 `console.html`（开发者工具，不是产品面）。
- 不伪造在线人数、响应时长等运营数字。

## Verify

```powershell
cd frontend-buyer; npm run typecheck; npm test; npm run build
cd frontend-workspace; npm run typecheck; npm test; npm run build
.\mvnw.cmd -B -ntp verify
git diff --check && git status --short
```

活体页面：Vite dev server 代理到 `127.0.0.1:8082`，跑等价浏览器断言（买家登录 / 工单隔离 / 提问 / 390px；坐席登录 / 队列 / 详情 / 领取 / 结单弹窗 / 390px）。

## 验收项

- 两端新外壳落地，未登录页与主界面均无「演示卡片」观感。
- 根入口页不再自称「本机演示栈」；工程入口折叠并标注开发者工具，演示档数据留存口径明示。
- 买家帮助首页按真实任务分类，会话头显示关联订单，侧栏含本次订单 / 售后进度 / 会话信息。
- 坐席队列含关键词与来源筛选，列表含优先级色点 / 来源徽章 / SLA，详情含买家原始诉求与责任人。
- `.gate` 仍含「工单 / 转人工 / 账号登录」；`.login-card` 仍含「登录 / 队列」。
- 既有选择器全部保留，浏览器断言 18/18 通过。
- 买家端 390px、坐席端 390px 无横向溢出。
- 两端 `typecheck` + `vitest` + `build` 全绿。
- 全仓 `mvnw verify` BUILD SUCCESS；JVM 807 tests / 136 suites。
- 构建产物与源码同步，`git diff --check` 退出 0。

## Handoff notes

**Status**：done（2026-11-21；所有者书面签收；工作树内完成，与 220/221 统一收口）

### 关键决策

- **设计读法**：B2B 客服产品面（买家对话 + 坐席操作台），观感取「安静、可信、可审计」；不做 landing page 式 hero，不做玻璃拟态，不引入新依赖。
- **第二轮取舍（2026-11-20）**：参考 `docs/design-samples` 后取 **V1 的买家上下文 + V2 的坐席操作台 + V3 的口径诚实**。
  买家端落地帮助分类、会话头关联订单、侧栏三段式；坐席端落地筛选条、优先级色点/来源徽章/SLA、
  买家原始诉求引用块与责任人署名；根入口页从「本机演示栈」改为「售后服务工作台」，工程入口折叠。
  不新建 V3 的 runtime 控制台——当前没有对应的数据装配，硬做会变成新的演示面。
- **单一强调色**：沿用既有 teal 变量名，但把值收敛为深翡翠 `#0e7a5f`；语义色只用于状态。这样既有组件引用零改名，避免全仓变量迁移。
- **深色 chrome 只给坐席端与登录品牌面板**：买家端顶栏保持白色，避免把消费者页面做成运维后台；坐席端用深色侧栏建立「操作台」层级。
- **登录页分栏**：左侧品牌与能力，右侧表单；「账号登录」为主按钮，「一键体验」降为次要按钮。买家端未登录不再显示「本店」店铺条。
- **买家首屏任务化**：移除「你们店是卖什么的？」这类演示问句，改为退换、运费、物流等真实任务；首屏内容顶部对齐，不再垂直居中。
- **买家帮助中心**：按店铺类型给出四类任务（退货退款 / 物流 / 优惠 / 生鲜理赔；酒店为入住 / 变更 / 发票 / 到店），
  入口文案只改导航，不改任何问答链路。
- **会话头与侧栏**：会话头显示店铺、会话号与关联订单 chip；侧栏固定「本次涉及的订单 / 售后进度 / 会话信息」三段，
  订单号从当前展开订单或最近一条消息中提取，不额外发请求。
- **坐席全高布局**：`.shell` 固定 `100dvh`，侧栏与主区各自滚动；队列与详情改为等高双栏，避免「漂浮卡片 + 大片空白」。
- **坐席筛选与详情**：关键词/来源筛选是视图内状态，不改根组件队列；详情把买家原话放进引用块，
  责任人取 `assignee`（登录令牌写入），并明确「页面不能手填」。角色只做顶栏/侧栏标识，不隐藏后端仍允许的入口。
- **选择器零改动**：只改样式、文案与少量结构（登录页分栏），保留全部验收脚本依赖的类名与按钮文本。
- **回答渲染**：模型输出常见 `**粗体**`、列表与步骤，原样显示会像调试输出。新增无依赖的 `renderMarkdown`，
  先整体 HTML 转义再插入固定标签，不支持链接或原始 HTML；流式未闭合标记原样显示。

### 验证落点

- `frontend-buyer`：`npm run typecheck` 通过；`npm test` **37/37**（新增 6 条 Markdown 渲染断言）；`npm run build` 通过。
- `frontend-workspace`：`npm run typecheck` 通过；`npm test` **6/6**；`npm run build` 通过。
- 全仓：`.\mvnw.cmd -B -ntp verify` → **BUILD SUCCESS**，`12 + 92 + 653 + 50 = 807 tests / 136 suites`，0 failures / 0 errors。
- 活体等价断言：`.scratch/verify-redesign.mjs` → **18/18 PASS**（买家工单隔离、提问回答、坐席队列/详情/领取/结单弹窗、两端 390px 无溢出、无页面异常）。
- 第二轮截图（本机未跟踪）：`.scratch/r2-root.png`、`.scratch/r2-buyer-gate.png`、`.scratch/r2-buyer-home.png`、`.scratch/r2-buyer-chat.png`、`.scratch/r2-buyer-mobile.png`、`.scratch/r2-ws-gate.png`、`.scratch/r2-ws-queue.png`、`.scratch/r2-ws-mobile.png`。
- 第一轮截图保留：`.scratch/redesign-buyer-gate2.png`、`.scratch/redesign-buyer-final.png`、`.scratch/redesign-buyer-mobile.png`、`.scratch/redesign-ws-gate2.png`、`.scratch/redesign-ws-in.png`、`.scratch/redesign-ws-mobile.png`。
- `git diff --check` 退出 0。`git status --short` 含本轮前端源码、构建产物与票面文件；另有本轮之前已存在的未跟踪草稿
  （`.scratch/*`、`docs/design-samples/` 等），未清理、未纳入本轮，按仓库纪律保留。

### 三个现场追问

1. **为什么没直接做 V3 的 runtime 控制台？** 设计提案本身也把 V3 列为路线 A 首选，但 V3 需要把回放帧、证据卡、口径标签接成真数据；当前前端没有这层装配，硬做只会多一个「看起来像控制台」的演示面。本轮取 V3 的诚实原则：根入口明示演示档、工程入口折叠、不伪造运营数字。若要做 V3，应另立票先定数据来源与只读边界。
2. **为什么角色只做标识、不隐藏「渠道连接」？** 后端 `OpsAuthorizationInterceptor` 与 `ChannelAdminController` 当前对 AGENT/ADMIN 都放行，前端单方面隐藏会把可用功能变成不可发现，属于没有后端依据的产品承诺。本轮在顶栏/侧栏显示角色，并把「管理员」作为身份而非权限门；真要角色塑造菜单，先补后端角色守卫与 403 契约。
3. **根入口页改了源码，为什么活体栈还是旧页？** 当前 8082 是容器档，静态资源来自镜像构建；本轮只改源码并重建 `static/buyer|workspace` 产物，根页 `static/index.html` 会在下次镜像构建时生效。活体截图里的根页用 `file://` 预览，因此 readiness 显示「网关不可达」是预期；同源部署后脚本会读到真实状态。

### 所有者书面签收（2026-11-21）

- 所有者对话内书面指令：**「签署 219/220 后统一收口」**，与 220/221 一并签收。
- 签收范围：AC1–AC9；以既有验证落点为准（两端 typecheck/vitest/build、全仓 807/136、
  活体等价断言 18/18）。
- 收口说明：README 的产品入口/目录描述、根入口口径与两处架构叙述随本轮更新，属于交付文档
  同步，不改 API、状态归属、SSE 协议、认证流程或任何 Java 代码。原 Allowed paths 未列
  README，此处按收口文档显式登记，不扩大功能面。
- 本机截图、`docs/design-samples/` 与 `.scratch/*` 探针仍按仓库纪律保留为未跟踪本机证据，
  未纳入统一提交。
