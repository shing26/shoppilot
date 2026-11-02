# 票 190：知识飞轮可视化、三入口视觉收口与收口

Round: 44（spec：[round44-spec-product-surface.md](../round44-spec-product-surface.md)）
ADR: 0079　Status: implemented　Blocked by: 189

## What to build

1. **工作台「知识」视图**（`frontend-workspace/src/api.ts` + 新视图组件）：
   - 接 round43 票 186 已开好的代理口：复核完成（`PATCH`）、附稿（`POST`）、列表（`GET`）、
     发布 / 驳回（`POST`）。**全路径开工第一步按票 186 的 `@RequestMapping` 核对**
     （预期在 `/api/v1/support/ops` 前缀下），不照抄本票的简写。
   - 链路呈现：复核队列 PENDING → 复核完成 REVIEWED → 附稿 DRAFT → 发布 PUBLISHED（/ 驳回
     REJECTED），每一步的状态与操作人可见。
   - 发布成功后给**显式提示**：「发布不立即生效，需重跑 ingest 才会进入语料并推进纪元」。
     不伪造"发布即生效"，不提供进度条式的假等待。
   - 附稿表单的前置校验与后端同口径：intent 只收政策意图、scope 只收 `SHOP|PLATFORM`、
     正文必填——前端拦在提交前，但**不复制后端的判定逻辑去"预判"合法与否**。
   - 这条视图执行的是 [round43 spec](../round43-spec-knowledge-backflow.md) §4 登记项 1
     「工作台修订编辑器」——其触发条件（修订面进入产品演示清单）在本轮成立。收口时在
     round43 spec 与 `registered-debt.md` 里同步状态，别让登记项悬空。
2. **买家端依据面板**：答案下方把已有 `citationDetails`（`doc` / `heading` / `ruleId`）分组展开，
   显示"依据 N 条知识"。**不新增后端字段**，不伪造知识版本号或纪元号。
3. **三入口视觉收口**：
   - `static/index.html` 已是标杆，作为视觉基准（径向渐变、hero、卡片网格、状态点），
     **不抄布局**——另外两入口的产品语义不同，统一的是字阶、色板、圆角、阴影、动效时长、
     空状态与错误态的语言。
   - `static/console.html` 只改样式；`id="opsToken"` 必须有 `placeholder` 无 `value`、
     `id="agentPass" size="10" placeholder="口令"`、`id="agentUser"`/`id="agentPass"` 不得预填、
     不得出现 `OPS_TOKEN`/`INTERNAL_TOKEN`/`JWT_SECRET` 字面量——这些被
     `ServedConsoleHidesDevDefaultsTest` 钉着，只可改 CSS。
4. **浏览器断言**：`scripts/verify-buyer.mjs` / `verify-workspace.mjs` / `verify-console.mjs`
   的选择器已与 round36-39 之后的 DOM 漂移，本票统一修好并**实跑**（本机 `.tools/node_modules`
   已装 playwright，走 `NODE_PATH` + `createRequire`）。读数写实测值；跑不过就照登未达成，
   不许写"已验证"。
5. **视觉证据**：桌面宽与 390px 双宽度截图落 `docs/r44-*.png`，在 `docs/EVIDENCE.md` 登记，
   并**明确区分**「脚本实测」与「所有者主观确认」——Playwright 钉得住结构，钉不住好不好看。
6. **文档换代**：
   - `docs/CODE_MAP.md`：前端组件表（两个 `src/components/` 的职责与状态归属）、
     新的 biz-mock 查询口与网关转发口、知识视图落点。
   - `docs/EVIDENCE.md`：round44 条目（读数、截图、证据边界）。
   - `CONTEXT.md`：如需新术语（如「知识飞轮」）——**增词不改旧词**；发现与既有术语冲突时
     停下来说明，不自行改写。
   - `python scripts/gen_status.py` 重生 `docs/STATUS.md`（不手改）。
   - tracker README：Round 表补 round44 行、Ticket 索引补 188-190、三票 `Status` 与 Handoff。

## Blocked by / Verify

189。Verify：

```powershell
node --check scripts/verify-buyer.mjs
node --check scripts/verify-workspace.mjs
node --check scripts/verify-console.mjs
$env:NODE_PATH="D:\ShopPilot\.tools\node_modules"; node scripts/verify-buyer.mjs
$env:NODE_PATH="D:\ShopPilot\.tools\node_modules"; node scripts/verify-workspace.mjs
$env:NODE_PATH="D:\ShopPilot\.tools\node_modules"; node scripts/verify-console.mjs
python scripts/gen_status.py
.\mvnw.cmd -B -ntp verify
git diff --check
git status --short
```

## 验收项

- 知识飞轮五个状态在同一视图里可走通（读、附稿、发布、驳回），发布后提示与后端语义一致。
- 买家依据面板只用已有字段，无新增后端。
- 三脚本实跑读数如实登记；未跑或失败的项目保留未达成说明。
- 截图与 EVIDENCE 条目对齐，主观项标注为所有者确认。
- `docs/STATUS.md` 由脚本生成；tracker 三票全部收口（Status + Handoff notes + 三个现场追问）。
- 无验收判据、阈值、gold、指标分母改动。

## Handoff notes

### 关键决策

1. **知识视图不做成「流水线进度条」，而是五步说明条 + 两块可操作面板。** 后端状态机不允许跳步
   （附稿的前置是 `REVIEWED`），进度条会让人以为可以点「下一步」。真正的动作只有四个：标为已复核、
   附稿、发布、驳回，各自长在对应的卡片上；五步条只回答「现在在哪一格」。
2. **复核后的反馈必须在会话内留一份副本。** `GET /feedback/review-queue` 只回 `PENDING`，复核成功
   这一行就从队列里消失了，而下一步「附稿」要的正是这一行。这里用 `reviewedFeedback` 本地副本承接，
   **不去加一个 REVIEWED 列表口**——那是新接口，票面没批。代价写在现场追问 1。
3. **附稿表单不预判后端合法性。** 下拉只给政策意图 / `SHOP|PLATFORM` / `ECOMMERCE|HOTEL`，标题与
   正文必填在提交前拦；但「这条反馈是不是 REVIEWED」「这稿能不能发」完全由后端 409/400 决定，
   前端只把 400/409 的原话与可读解释透出来（`describeKnowledge`），不复制状态机。
4. **「发布不立即生效」做成已发布卡上的常驻提示，不是一次性 toast。** 这是整条链最容易误读的语义，
   toast 会消失；只要稿子还是 `PUBLISHED`，那句「需重跑 ingest」就该在那儿，并带上命令。
5. **买家依据面板只在真有 `citationDetails` 时渲染，且视图不重排。** `groupCitations` 按
   `doc` 分组、组内保持后端给的相关度序、组间按首次出现——重排等于替检索器重排。
6. **`console.html` 只动样式，字面量一个没改。** 加的是 `--r-*` / `--shadow-*` / `--dur-*` /
   `--ease` / `--ring`、`:focus-visible` 环与 `prefers-reduced-motion`；`id="opsToken"` 的
   `placeholder`、`id="agentPass" size="10" placeholder="口令"`、不预填、无密钥字面量这些被
   `ServedConsoleHidesDevDefaultsTest` 钉住的属性零改动。
7. **`verify-console.mjs` 的两处等待从 `networkidle` 改成 `load` + 有界等待。** 不是页面变慢，是
   `/actuator/health/deps` 在本机（ES/Qdrant 不可达）挂 **40.04 s** 才回 503，`networkidle`
   永远等不到；另把「缓存命中」那条**没有 catch** 的等待改成有界等待 + 照常判红，否则一处超时就让
   整轮脚本崩掉、后面二十多条断言一条都跑不到。**判据没改**：仍是 `hitSeen && hitChips === 1`，
   另外三条检索类断言与两条读数格断言原样留红（见验证落点）。
8. **产物与源码同步入库**（round30 事故的教训）：两端各 `typecheck` + `build` 一次，`static/buyer`
   与 `static/workspace` 的**新增与删除**全部入库；CI 的 `git status --porcelain` 门禁本轮也已在位。

### 验证落点

| 命令 | 结果 |
| --- | --- |
| `node --check scripts/verify-buyer.mjs` / `verify-workspace.mjs` / `verify-console.mjs` / `capture-r44-screens.mjs` | 四个都通过 |
| `node scripts/verify-buyer.mjs`（活体，NODE_PATH=.tools） | **13/13 PASS**；依据面板那格因无 `citationDetails` 打 **INFO**（检索降级，不冒充 PASS） |
| `node scripts/verify-workspace.mjs`（活体） | **23/23 PASS + 1 WARN**；知识飞轮五步、`DRAFT` 卡、发布提示、**驳回终态（REJECTED）** 四格全绿；WARN = 队列只有一种优先级，排序断言未覆盖 |
| `node scripts/verify-console.mjs`（活体） | **39/45**，6 红：`citations rendered` / done 帧 `ruleIds` / `cache hit`（三条依赖检索）、`no failed requests`（503+500+403 同源环境缺口）、两条读数格「另一格保持 live」（`/ops/**` 默认拒绝后前提不成立）。判据未改 |
| `cd frontend-buyer && npm run typecheck && npm run build` | 绿；`index-BVVfRdyG.js` 105.80 kB / `index-B36rZRpY.css` 25.33 kB |
| `cd frontend-workspace && npm run typecheck && npm run build` | 绿；`index-BvbFBYiI.js` 137.63 kB / `index-B0lDk8NQ.css` 28.73 kB |
| `bash ./mvnw -B -ntp verify` | BUILD SUCCESS；四模块 **12 / 89 / 617 / 50 = 768 tests / 129 suites**，0 失败 |
| `python scripts/check_coverage.py` | OK；tool-api 44.70% / biz-mock 83.57% / gateway 68.88% / ticket 78.62% |
| `python scripts/gen_status.py` | 已重生 `docs/STATUS.md`（768 / 129 suites） |
| `node scripts/capture-r44-screens.mjs` | 10 张，桌面 1440×900 与 390×844 各半，无页面异常 |
| `git diff --check` / `git status --short` | 干净；无日志、无崩溃转储、无过期快照 |

### 三个现场追问

1. **已复核但还没附稿的反馈，刷新页面后就从 UI 上消失了。** 复核队列只回 `PENDING`，会话内副本
   只活在这一次打开里。要收口需要一条「按状态列反馈」的读口（或让复核口顺带返回刚复核的条目），
   这是新接口，得单开一票。
2. **票面「每一步的状态与操作人可见」只做到「本会话内当前账号」。** `FeedbackView` 与
   `RevisionView` 都不带 actor 字段，历史操作人无法回显；页面只能把本次会话里自己做的动作标成
   「当前账号」。要全量可见就得在 biz-mock 两张表上补 actor 列并迁移——不是纯前端能补的。
3. **`verify-console.mjs` 里的检索类断言在 ES/Qdrant 不可用时恒红。** 这三条与产品面无关，却是
   同一份门禁的一部分；每次环境降级都会把 console 读成「产品坏了」。要不要给它们一个显式的
   SKIP 状态（像工作台的 WARN 那样），还是坚持「环境不全就是红」，需要所有者裁定——本轮没有改。
