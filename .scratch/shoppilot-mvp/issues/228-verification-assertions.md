# 票 228：验收断言补强——activeAppId 与多优先级队列

Round: 58（验收入口补强；按 ADR 0031）　
ADR: 0087（activeAppId 运行态语义；不改结论）　
Status: in-progress（2026-11-23 所有者指令实现）　Claimed by: root　Blocked by: 无

契约：[plans/228-verification-assertions.md](../../plans/228-verification-assertions.md)（2026-11-23 冻结）

## What to build

1. `verify-channel-hot-reload.mjs`：activeAppId 改为与已知候选 appId 的掩码比较，
   PUT 响应与后续 GET 状态都断；旧/新掩码不同时断旧值不再活动；启动/停止日志双计数。
2. `verify-workspace.mjs`：通过工单服务真实创建 high / money 两张种子单，
   断言落单成功、队列可见、三张种子单按 紧急 → 资金 → 普通 排序；
   全局排序断言不再走 WARN 分支。
3. 种子失败必须 FAIL，不得降级为 WARN；既有断言不删除、不弱化。

## 边界

- 只改两个验收脚本；不改 Java、Vue、生产配置、数据库、API、gold、阈值或覆盖率门槛。
- 种子走真实工单服务 `POST /api/tickets`，不直接写库、不伪造 DOM。
- activeAppId 仍是掩码；旧/新掩码相同时用连接启动/停止日志补证，并登记边界。

## Verify

```powershell
node --check scripts/verify-channel-hot-reload.mjs
node --check scripts/verify-workspace.mjs
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体（本机栈可用时）：

```powershell
$env:SHOPPILOT_WORKSPACE_BASE = "http://127.0.0.1:18082"
$env:SHOPPILOT_TICKET_BASE = "http://127.0.0.1:8092"
$env:SHOPPILOT_IDENTITY_DEMO_PASSWORD = "<演示口令>"
$env:SHOPPILOT_INTERNAL_TOKEN = "<内部令牌>"
node scripts/verify-workspace.mjs
node scripts/verify-channel-hot-reload.mjs
```

## Handoff notes

（实现与独立 Verifier 完成后补：关键决策、验证落点、三个现场追问）
