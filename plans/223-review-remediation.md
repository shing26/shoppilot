# 票 223 契约：票 222 审查整改

**状态**：2026-11-22 冻结（所有者指令「把当前问题都解决了」）
**判定**：本票只修票 222 的展示层回归与审查发现，不新增业务功能面；按 ADR 0031
「回归、事实错误与现有门禁要求的修复不受冻结期新功能限制」执行。票 222 的原始契约
保持只读，脚本断言变更只允许本票明确列出的这一处。

**基线**：分支 `codex/223-review-remediation`，起点 `f56e719`；票 222 已实现但未推送。

## 目标

1. 修复两个必然变红的既有活体门禁，不删除门禁覆盖点。
2. 收敛重复的「产品时刻」与硬编码状态。
3. 补齐复制降级、移动端间距、等宽数位、非药丸计数与单一刷新入口。
4. 把 `shortTicketId` 下沉共享层，消除两份重复实现。

## Allowed paths

- `frontend-shared/src/**`
- `frontend-buyer/src/**`
- `frontend-workspace/src/**`
- `shoppilot-gateway/src/main/resources/static/index.html`
- `shoppilot-gateway/src/main/resources/static/buyer/**`（重新构建产物）
- `shoppilot-gateway/src/main/resources/static/workspace/**`（重新构建产物）
- `scripts/verify-workspace.mjs`
- `plans/223-review-remediation.md`
- `.scratch/shoppilot-mvp/issues/222-unslop-frontend-refinement.md`
- `.scratch/shoppilot-mvp/issues/223-review-remediation.md`
- `.scratch/shoppilot-mvp/README.md`

## Acceptance criteria

1. 买家端 `.ticket-list` 的可见文本仍包含完整工单号；短显只由 CSS 省略实现，不改写文本。
   `scripts/verify-buyer.mjs` 的既有 `myTicketId` 断言保持原样并可继续通过。
2. 坐席端 `.identity` 显示登录响应里的 `displayName`，不显示内部账号名；
   `scripts/verify-workspace.mjs` 改为断言 `.identity` 文本等于登录响应 `displayName`，
   且不等于账号名。该变更不降低「身份来自令牌」的覆盖强度。
   同一脚本的领取/结单断言按产品规则修正：领取按钮限定在 `.detail-actions` 内，
   避免命中 KPI 的「未领取 / 已领取」；结单对话框断言移到领取成功之后。
3. 根入口保留唯一的工单链路产品时刻，节点为「下单 → 提问 → 转人工 → 领取 → 结案」；
   不再硬编码 `done` / `current` 状态，明确标注为流程示例。买家与坐席登录页不再重复绘制节点链。
4. 复制工单号在非安全上下文（LAN HTTP / 容器 IP）下有降级路径；失败时显示可选中的完整工单号。
5. 390×844 下页面边距为 24px、卡片内边距至少 20px，页面无横向溢出。
6. `shortTicketId` 只在 `frontend-shared` 定义；两端按需导入，不再各存一份。
7. 买家端计数、时间、金额使用 `tabular-nums`；计数徽标与订单 chip 不再使用全圆角药丸。
8. 买家侧栏只保留一个刷新入口，按当前页签刷新订单或工单。
9. 恢复票 222 之外的店铺文案改动：`平台认证商家`、`服务中`；不保留无依据的文案重写。
10. 两端 `typecheck`、`vitest`、`build` 全绿；全仓 `.\mvnw.cmd -B -ntp verify` 绿；
    `python scripts/gen_status.py --check`（必要时先重生成）与
    `python scripts/verify_doc_truth.py` 退出 0；`git diff --check` 退出 0。

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

活体复核：1440×900 与 390×844 截图，覆盖根入口、买家工单列表、坐席身份与详情复制。

## Forbidden

- 不改 API 调用、SSE 协议、状态归属、认证流程、权限判定、Java 代码或生产依赖。
- 不新增后端字段、运营指标、在线人数、响应承诺或伪造业务数据。
- 不删除测试文件、不跳测试、不关 lint/typecheck、不降低覆盖率门槛。
- 除 `scripts/verify-workspace.mjs` 的身份断言与领取/结单断言顺序修正外，
  不改其他既有测试断言、gold 或阈值。
- 不清理、回退或提交本机未跟踪产物、日志、截图、`.env`、token 或历史证据目录。
