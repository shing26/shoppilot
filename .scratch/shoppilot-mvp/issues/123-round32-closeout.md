# 123 round32 收口与真人 cold-read

**Status:** implemented

## What to build

round32 收口登记 + 真人 cold-read（本轮主判）。

- `docs/EVIDENCE.md`：round32 行——JVM 读数换代（`10 + 69 + 391 + 45 = 515`）、180 条标定重跑、
  检索指纹口径变更与 CI `RETRIEVAL_FIXTURE_SHA256` 换代、酒店 `kind: hotel` 12 条。
- `docs/CODE_MAP.md`：酒店意图 / 语料 / 种子的首改位置。
- tracker：round32 条目 + Round 表 + 票索引 + 当前交付层读数换代（**以本轮实测为准**）。
- `round3-closeout-audit.py`：`G6_EXPECT` 按新鲜 JVM 实测换代（**不为凑绿改判据**）。
- **真人 cold-read（主判）**：找一位真实业务侧的人，只看 `docs/round32-two-runnable-scenarios.md`
  3 分钟，回答五个问题；结论照实记，**不许用脚本门禁冒充**。

## Blocked by

[118](118-seed-store-identity-and-t002-fixtures.md)、[119](119-policy-hotel-four-place-sync.md)、
[120](120-hotel-tenant-and-corpus.md)、[121](121-intent-calibration-and-hotel-eval.md)、
[122](122-onepager-readme-release.md)。

## 口径

- 读数以实测为准；未达成项、未执行项照登。
- 真人 cold-read 是主判，`verify_onepager.py` 是辅证，**两者不互相冒充**。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/verify_eval_judge.py
python scripts/eval_suites.py
python scripts/verify_onepager.py
python scripts/retrieval_gate.py
python scripts/run_tool_eval.py --rescore eval/results/tool-eval-20260910-080638-dev-budgetfix.csv eval/results/tool-eval-20260910-080732-dev-budgetfix.csv eval/results/tool-eval-20260910-080830-dev-budgetfix.csv eval/results/tool-eval-20260910-080926-dev-budgetfix.csv eval/results/tool-eval-20260910-080942-dev-budgetfix.csv eval/results/tool-eval-20260910-075747-dev.csv --rescore-expected-diff ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：round32 是**所有者政策覆盖**（ADR 0066），**不自动续期**。两个可跑场景
  （鲜果驿站 T002 死蟹理赔 + 云栖酒店 T004 入住退改）落地；术语收敛「租户唯一、店铺为电商域
  业务叫法」由 ADR 0066 授权。
- **验证落点**：
  - JVM：`.\mvnw.cmd -B -ntp verify` 四模块 **`10 + 69 + 391 + 45 = 515`** 全绿
    （biz-mock 67 → 69：票 118 的 `SeedSegmentationTest` 两条；gateway 383 → 391：票 119/120
    的 T0 / Chunker / 检索融合用例）。
  - 0 token 门禁：判据自检 40/40、离线 rescore `tool_diff=4`、套件夹具 `ok=30`、
    一页纸门禁 `ONEPAGER GATE OK`、检索录放门 `ok=12 / cases=10`、覆盖率棘轮四模块 OK、
    task 夹具 `ok=14`——**CI 九步不变**。
  - 标定：`python scripts/calibrate_intent.py` 重跑，工作点仍 `conf=0.70 / margin=0.02`，
    准入率 72.7%、判错桶 1/24、动作误判为政策 0 条；**加酒店质心未移动 180 条 gold 的任何读数**。
  - 检索指纹：口径由「全量语料」改为「租户可见范围（tenantId + PLATFORM）」，夹具 sha
    `71e94f85…`（CI env 同步）。
- **真人 cold-read（主判）——尚未执行，照登**：需要一位**真实业务侧的人**（客服主管 / 运营 /
  产品）。执行协议：把 `docs/round32-two-runnable-scenarios.md` 单独发给该人，**只给 3 分钟**、
  不给任何口头补充，然后记录其对五个问题（一进 / 一出 / 哪一步转人 / 什么情况办不了 / 现在哪段没通）
  的复述。**本票不以 `verify_onepager.py` 的绿冒充这一格**——脚本只证明文档没腐化，不证明有人读懂了。
  执行结论（读懂了哪几个、卡在哪）由所有者补记在本行。
- **活体套件跑批未执行，照登**：`python scripts/run_tool_eval.py --suite hotel`（酒店 12 条活体、
  0 token 命中那一格）本轮**没跑**——它要本机活体栈（网关 + Ollama + ES/Qdrant/Redis）。本轮只跑通
  它的 **0 token 夹具（`SUITE SELFCHECK ok=30`）与 JVM 覆盖**（T0/T1 路由、入库映射、检索指纹）。
  这一格与真人 cold-read 一起，是本轮**两处照登未执行**；都不许写成「已验证」。
- **与收口审计 B7 禁面的冲突，照登**：`round3-closeout-audit.py` 的 B7 把 `knowledge/` 列为内容级
  禁面（round3 纯文档轮），而 ADR 0066 决策 3/4 要求本轮**新增** `knowledge/hotel-*.md`。**处理口径**：
  这是 ADR 0066 的显式授权，**不删知识库、不改 B7 判据**；审计重跑由所有者按 ADR 0023 重锚 B7 的
  `knowledge/` 禁面（或单列豁免并写明理由）。
- **收口审计的 `G6_EXPECT` 换代与随之而来的那条红，照登**：常数已按本机 JVM verify 实测换成
  `[10, 69, 391, 45] = 515`。但它读的 `logs/acceptance/{build,unit}.log` 仍是 **2026-10-05** 的清场日
  落点（`448`），所以现在跑审计会得到一条「常数 515 ≠ 日志 448」的红——**那不是防线失效，是本轮
  没重跑带 `build/unit` 的活体矩阵**（与审计里「中途加票时不要动它，跑了再换」是同一回事的另一面）。
  **不许为消这条红把常数改回 448**；日志追上 515 是矩阵下次重跑时的事。另：审计的
  `PASS/FAIL` 读数仍是 round22 的旧落点，本轮**未重跑审计**（需活体栈），照登。
- **三个现场追问**：
  1. 真人 cold-read 结果若显示「哪段没通」没被记住，是否要在一页纸里把它提到更前？
  2. 酒店「改四处」结论只对咨询域成立——办理域的成本要不要单列一页纸说明？
  3. 检索指纹换成租户可见范围后，平台级语料仍会移动所有租户指纹——要不要给平台级单列 sha？
