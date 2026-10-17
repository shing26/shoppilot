# 120 酒店租户 T004 与酒店语料入库

**Status:** implemented

## What to build

把酒店知识库作为**独立租户的纯咨询语料**入库，并保持检索指纹口径正确。

- `knowledge/hotel-01..06-*.md`：入住 / 退房、取消退改、加床与入住人数、押金与费用、早餐与房型、
  提前退房与续住；frontmatter `tenant_id: T004`、`scope: SHOP`、`rule_type: HOTEL_POLICY`。
  **刻意不挂 `PLATFORM`**——可见范围是 `tenantId + PLATFORM`，挂平台级会污染全部电商租户检索。
- 检索录放门的**指纹口径**由「全量 `knowledge/*.md`」收敛为「该夹具租户可见范围（`tenantId +
  PLATFORM`）」：加 T004 语料不该移动 T001 夹具的指纹。`retrieval_gate.py` 加 `tenant_of` /
  `visible_to` / `corpus_sha256(tenant)` 与五条 `FINGERPRINT_SELFCHECK`（反证：别的租户语料不动本
  租户指纹、平台级与本租户新增会动、内容敏感）；夹具加 `tenant` 字段；`RetrievalFusionReplayTest`
  同步重算并加一条「全量指纹不等于记录值」的反证；CI 的 `RETRIEVAL_FIXTURE_SHA256` 换代。

## Blocked by

[119](119-policy-hotel-four-place-sync.md)。

## 口径

- 隔离红线：酒店语料**不挂 `PLATFORM`**（ADR 0066 决策 4）。
- 指纹口径变更是**让判据正确**，不是放宽：它反而新增了「别的租户语料不得移动本租户指纹」的反证。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/retrieval_gate.py
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：加一份跨行业语料会「移动指纹」，暴露了旧指纹口径（全量语料）对**租户隔离**是错的；
  本轮把它改成租户可见范围口径，并补上反证。
- **验证落点**：`python scripts/retrieval_gate.py` → `GATE SELFCHECK ok=12` + `RETRIEVAL GATE ok
  cases=10`；`RetrievalFusionReplayTest`（含全量指纹反证）；CI `RETRIEVAL_FIXTURE_SHA256` 更新。
- **三个现场追问**：
  1. 指纹口径改成「租户可见范围」后，**平台级语料变更**会同时移动所有租户的指纹——这是否够用，
     还是要给平台级单列一条 sha？
  2. 酒店六份文档是否覆盖了对照所需的最小集（入住 / 退改 / 加床 / 押金 / 早餐 / 延时）？有没有
     明显该有而没写的规则（如发票、宠物、停车）？
  3. 酒店语料是生成内容，`effective_from` 统一 `2026-01-01`——知识库纪元是否要随本轮推进一格？
