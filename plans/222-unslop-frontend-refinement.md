# 票 222 契约：三端 unslop 精修

**状态**：2026-11-21 冻结（所有者已确认设计阅览稿）
**判定**：UI-only 展示层改动，不触碰业务逻辑、内部/对外 API、认证授权、支付、数据删除、
数据库 schema 或生产依赖，因此不启用验收链路的 Implementer / Verifier 分离强制项；
仍按契约冻结、独立复跑命令、证据绑定 HEAD 与工作树执行。

**开票依据**：所有者 2026-11-21 指令「确认」设计阅览稿，要求在票 219 的产品化结构上继续
精修，而不是推倒重来。设计稿与审计位于 `.scratch/unslop-221/design-review.html`，方向为
「纸白 + 墨色 + 印章绿」，核心问题是字号、留白、药丸、等宽卡片与开发者文案。

**基线**：HEAD `4fc0309`，票 219/220/221 已统一收口；工作树仅含本机未跟踪产物。

## 目标

按以下九轮顺序完成三端精修：

1. **间距**：统一 4px 网格；页面边 24–32、卡片 20–24、列表行 36–44。
2. **颜色**：纸白画布、暖白面板、墨色文字、印章绿强调；去掉冷灰后台感与浅绿选中底。
3. **图标**：不新增图标集；删除装饰编号与重复状态点，保留必要方向箭头和状态节点。
4. **文案**：`guest` → 「访客」，`agent · T001` → 「坐席 / 店铺 T001」，
   删除重复刷新与解释性噪声；不伪造运营指标。
5. **字型**：正文最低 13px、元信息 12px；标题使用本地衬线回退，数字使用
   `tabular-nums`；不引入网络字体。
6. **布局**：根入口改为「左叙事 + 右工单链路预览」；买家端把输入框提为第二视觉焦点，
   移动端右栏改为「订单与进度」抽屉；坐席端 KPI 改为「一大三小」，生命周期升为详情主栏，
   移动端改列表 ↔ 详情切换。
7. **细节**：控件圆角 8/10、卡片 12、面板 14；药丸只保留状态；工单号短显 + 可复制。
8. **视觉**：只做一个产品时刻——真实工单链路（下单 → 提问 → 转人工 → 领取 → 结案），
   用细线与节点绘制，不生成装饰图片。
9. **动效**：只保留节点点亮、列表选中与弹层进入；尊重 `prefers-reduced-motion`。

## Allowed paths

- `frontend-shared/src/tokens.css`
- `frontend-buyer/src/**`
- `frontend-workspace/src/**`
- `shoppilot-gateway/src/main/resources/static/index.html`
- `shoppilot-gateway/src/main/resources/static/buyer/**`（重新构建产物）
- `shoppilot-gateway/src/main/resources/static/workspace/**`（重新构建产物）
- `plans/222-unslop-frontend-refinement.md`
- `.scratch/shoppilot-mvp/issues/222-unslop-frontend-refinement.md`
- `.scratch/shoppilot-mvp/README.md`

## Acceptance criteria

1. 三端统一纸白 / 墨色 / 印章绿令牌；正文与元信息不再使用 10–11px；数字使用等宽数位。
2. 根入口首屏为「左叙事 + 右可追踪工单链路」，不再由三张等宽功能卡构成；店铺入口、
   工程入口折叠与 readiness 状态保留。
3. 买家端删除帮助分类装饰编号；顶栏不再显示 `guest`；输入框成为第二视觉焦点。
4. 买家端 ≤960px 时提供「订单与进度」抽屉，能打开、关闭并访问订单与工单；
   桌面右栏保持常驻。
5. 坐席端队列摘要为「一大三小」，待审退款仍有入口；队列行改为优先级状态点 +
   问题摘要 + 右侧时间，状态药丸最多一个。
6. 坐席端详情以生命周期为主结构，工单号短显且可复制；`刷新队列` 只保留一个主要入口。
7. 坐席端 ≤960px 时列表与详情可切换，进入详情后无需滚过整列队列；返回入口可用。
8. 既有测试选择器与关键文案保留：`.gate`、`.login-card`、`.store-card`、`.side-tab`、
   `.ticket-card`、`.ticket-list`、`.thread`、`.msg.user`、`.msg.assistant .bubble`、
   `.composer`、`.cites`、`.cite-group`、`.side-activity`、`.who`、`.login`、`.login-form`、
   `.brand`、`.identity`、`.side-nav`、`.nav-item`、`.detail-pane`、`.detail-head h2`、
   `.priority`、`.dialog`、`.kb`、`.kb-flow .flow-step`、`.review-row`、`.draft-form`、
   `.revision-card`、`.ingest-note`、`.access-denied`；按钮文案
   `一键体验买家`、`一键体验坐席`、`管理员体验`、`退出`、`发送`、`刷新队列` 保留。
9. 两端 `typecheck`、`vitest`、`build` 全绿；全仓 `.\mvnw.cmd -B -ntp verify` 绿；
   `python scripts/gen_status.py --check` 与 `python scripts/verify_doc_truth.py` 退出 0。
10. 桌面 1440×900 与移动 390×844 截图复核无横向溢出；`git diff --check` 退出 0。

## Verification commands

```powershell
cd frontend-buyer
$env:NODE_OPTIONS='--max-old-space-size=1024'
npm run typecheck
npm test
npm run build

cd ..\frontend-workspace
$env:NODE_OPTIONS='--max-old-space-size=1024'
npm run typecheck
npm test
npm run build

cd ..
$env:MAVEN_OPTS='-Xmx512m'
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
git diff --check
git status --short
```

活体截图使用现有本机三服务与 Playwright；前端重建后必须重启网关或使用 Vite dev server，
避免截到旧 fat jar 内的静态资源。

## Forbidden

- 不改任何 API 调用、SSE 协议、状态归属、认证流程、权限判定或 Java 代码。
- 不新增后端字段、运营指标、在线人数、响应承诺或伪造业务数据。
- 不删除或弱化既有测试、选择器、文案断言、gold、阈值或迁移。
- 不引入网络字体、图标库、CSS 框架或新的生产依赖。
- 不生成装饰性图片替代真实界面结构。
- 不清理、回退或提交本机未跟踪产物、日志、截图、`.env`、token 或历史证据目录。
- 发现功能缺陷只记录复现；不在本票顺手改业务代码。
