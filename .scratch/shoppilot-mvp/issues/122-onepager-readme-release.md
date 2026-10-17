# 122 业务一页纸 + README 首屏 + RELEASE 指针

**Status:** implemented

## What to build

把叙事载体换成一页纸，README 首屏降级为指路牌。

- `docs/round32-two-runnable-scenarios.md`：业务侧一页纸——谁 → 遇到什么 → 走哪几步 → 哪一步转人
  → 什么办不了 → 哪个域换知识库就换、哪个域换不了 → 哪段没通；含「五个问题速答」与
  「换一个行业要改哪六处」。**不复述指标**（数字只在 README / EVIDENCE）。
- `README.md` 首屏：加「🧭 先看这里」指路牌（业务侧先读一页纸、工程侧走索引）；徽章与
  CI 段的 JVM 读数按实测换代（505 → 515、ADR 64 → 65）。
- `RELEASE.md`：在「面试作品」那句加换代指针（**只加指针、不改正文**），指向一页纸。
- `scripts/verify_onepager.py`：一页纸防腐化门禁（五个问题锚点 + 文档事实与代码/语料一致 +
  变异反证），并入既有 0 token 步骤（**九步不变**）。

## Blocked by

[120](120-hotel-tenant-and-corpus.md)。

## 口径

- **一页纸面向业务侧、不复述指标**；数字不做第二份。
- README 首屏只做降级与指路，不删工程索引。
- 门禁并入既有步骤，**不新增 CI 步骤**（round15 纪律）。

## Verify

```powershell
python scripts/verify_onepager.py
python scripts/eval_suites.py
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：一页纸承担完整叙事，README 首屏从「架构 + 指标」降级为「业务先读哪、工程走哪」；
  防腐化门禁做成**结构 + 事实一致性**（不是语义理解），与真人 cold-read 分工写明、不互相冒充。
- **验证落点**：`python scripts/verify_onepager.py` → `ONEPAGER GATE OK`（结构 16 + 代码/语料 9 +
  变异反证 4/4）；CI 九步不变（一页纸门禁并入 Suite 步）。
- **三个现场追问**：
  1. 一页纸放 `docs/` 是否够显眼，还是要在仓根再放一个入口？
  2. README 首屏徽章与 CI 段读数换代后，其余章节仍有历史读数（如「28 步验收」）——要不要一次性清扫？
  3. 一页纸的「五个问题速答」是脚本锚点，将来改措辞会不会同时要改脚本，成本是否可接受？
