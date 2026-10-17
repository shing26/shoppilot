# 134 调试台坐席登录（R9 裁定 ①：退款面板的读面缺口）

**Status:** implemented

## What to build

按所有者裁定（R9 选项 ①「退款面板加坐席登录」）给调试台补一条坐席账号登录路，
让 `staff-only` 的退款队列读面（B3 / ADR 0063）在运维面上真的可读；面板保持**只读**
（round30 的形态不动，审核仍在坐席工作台）。

- `static/index.html`：footer 加「坐席 账号/口令/登录 + 当前坐席」一格；`loginStaff()` 走
  `/auth/login`（与工作台同一后端契约），令牌只活在页面内存、口令用完即清、不落任何存储。
- `api()` 加 `tokenOverride`：退款队列的读取显式带坐席令牌，不与页面的 buyer 会话令牌混用。
- `loadRefunds()`：未登录时给可读原因（「退款队列要坐席身份才能读……」），不再打注定 403 的请求。
- `scripts/verify-console.mjs`：口令按买家端/工作台同一家法从环境读（缺则 exit 2）；
  退款段重写——① 未登录提示（R9 回归钉子）→ ② 走页面表单登录（含口令清空断言）→
  ③ 队列列出 PENDING_REVIEW + 退款原因 → ④ 只读形态（零 `button[data-decision]` + 指向工作台）；
  造数加确定性回退（直连 biz-mock 工具边界，模型不可用时面板断言仍能被判）；
  移除 dialog 处理器（round30 后控制台已无任何 confirm，是死代码）。
- `ServedConsoleHidesDevDefaultsTest` 扩一条：坐席输入框同样不预填、口令框是 password 型。

## Blocked by

无（所有者裁定先行）。

## 口径

- **只增登录路，不动 staff-only 主判**（ADR 0063）：ops token 仍然不能读退款队列。
- 面板仍只读：放行/驳回的端到端由 `verify-refund-approval.ps1` 与工作台门禁量。
- 断言只换承载方式，不放宽：旧的两条「点放行/驳回」断言换成「按钮一个都不许有 + 指向工作台」。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/run-acceptance.ps1 -Tier full -Only console -SkipBuild
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：坐席登录块放 **footer** 不放 header——header 高度被抽屉 inset 钉死在 49px
  （改它要动几何），而且实测放进 header 后 390px 视口下整页被撑宽到 554，`narrow footer` 断言当场红；
  连「当前坐席」也要**单独成格**（`.ctl` 是 `white-space:nowrap` 的整块，并进去后单行 404 > 390 又红一次）。
  两处都是被门禁逼出来的，不是审美选择。
- **活体读数**：`console` 步 **44/44 PASS**（含四条新断言）；`refund` 步 **exit 0**（21s，
  U9 的显存阻塞随之关闭）；收口审计的 `G8`（打字机 >60 字未放宽）连带红**转绿**
  （`console.log` 里 `chunks/chars` 读数回来了）。期间打字机那条仍按已登记的模型方差间歇
  （实测 53/54 字两跑红、115 字一跑绿）——**判据一字未动**。
- **未达成照登**：坐席令牌仍是 30 分钟 TTL 的页面内存态（刷新即失效是刻意形态，不是缺口）；
  调试台无「退出登录」按钮（关标签页即登出；要不要加留给下一轮裁定）。
- **三个现场追问**：
  1. 调试台现在有两具身份（buyer 会话令牌 + 坐席令牌），要不要在页面上把它们各自标注成
     「这条链路用哪一具」的显式说明，防止下一个维护者再混用？
  2. `#agentWho` 用 `.val`（等宽小字）显示——要不要跟工作台的 `.who` 对齐成同一套样式？
  3. 造数回退路径用了内部令牌（脚本侧）；要不要给「回退造数」加一条计数/日志，
     让「模型路径这次没绿」在门禁输出里更显眼？
