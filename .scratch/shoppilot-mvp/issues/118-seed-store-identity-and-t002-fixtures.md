# 118 种子店铺身份对齐 + T002 生鲜演示单

**Status:** implemented

## What to build

让种子数据里「店铺是谁」与 `knowledge/` 语料一致，并补上 T002 的死蟹理赔演示单。

- `SeedRunner`：`TENANTS` 改名以语料为准——T001 **星选生活旗舰店**、T002 **鲜果驿站**、
  T003 **云裳服饰旗舰店**；新增 `NON_SHOP_TENANTS`（T004 云栖酒店，只播租户行）。
- `seedTenantsIfEmpty()` 由「表非空即整段跳过」改为**逐租户缺哪补哪**——否则既有库永远看不到 T004，
  酒店语料进去了却因租户不可见查不到。
- 演示固定单 `DEMO_ORDER_NOS` 扩到 `90001-90004, 91001, 91002`；`Fixture` 加 `tenantId` /
  `shippedAt` / `deliveredAt`；新增 T002 两单——`91001` 签收 2 小时（六小时理赔窗口内）、
  `91002` 签收 28 小时（已超窗），时间相对 `now` 计算。
- 同步改名：`scripts/build_eval_set.py` 的 `SHOP_NAMES`、`scripts/verify_eval_judge.py` 的防呆
  标记、`scripts/verify-console.mjs` / `verify-action-loop.ps1` / 调试台 `index.html` 的租户列表、
  `scripts/run_tool_eval.py` 的复位提示。
- `SeedSegmentationTest`：租户数 3 → 4 + 两条新用例（非电商租户只播租户行 / 店铺名跟语料一致）。

## Blocked by

[117](117-round32-adr-and-spec.md)。

## 口径

- **以语料为准改种子，不改语料**；判据面零改动。
- 演示单时间相对 `now`，任何时刻起栈都成立。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/verify_eval_judge.py
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：店铺名以 `knowledge/` 语料为准对齐（「同两个租户 ID 在同一个仓里有两个身份」是
  demo 感最机械的那一层）；T004 只播租户行、不进压测订单池，避免四分之一订单摊给酒店。
- **验证落点**：`SeedSegmentationTest`（`nonShopTenantIsATenantRowAndNothingElse`、
  `shopNamesFollowTheKnowledgeCorpus`）；`verify_eval_judge.py` 的 `SHOP_NAMES` 一字不差断言；
  `.\mvnw.cmd -B -ntp verify` 绿。
- **三个现场追问**：
  1. 既有库逐租户补行会不会在某些持久档残留脏租户上产生冲突（当前按 id 判存在，`Tenant` 无唯一
     约束冲突面）？
  2. `91001 / 91002` 是否该进 `verify-action-loop.ps1` 的复位断言，而不只是 `run_tool_eval.py`？
  3. T003 的店名「云裳服饰旗舰店」在语料里出现次数很少——要不要补一条 T003 店铺级政策让三家店
     都有店铺级语料？
