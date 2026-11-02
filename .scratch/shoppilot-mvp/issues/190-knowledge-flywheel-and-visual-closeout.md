# 票 190：知识飞轮可视化、三入口视觉收口与收口

Round: 44（spec：[round44-spec-product-surface.md](../round44-spec-product-surface.md)）
ADR: 0079　Status: ready-for-agent　Blocked by: 189

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

（收口时补：关键决策 / 验证落点 / 三个现场追问）
