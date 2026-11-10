# 票 213：round49 收口登记——TTFT 读数换代与文档同步

Round: 49（spec：[round49-spec-ttft-embedding-review.md](../round49-spec-ttft-embedding-review.md)）
ADR: 0085　Status: ready-for-agent　Blocked by: 票 212

## What to build

按票 212 实测结果做收口换代，判据一字不动：

- 重跑 `run_sse_ttft` / `ttft_attribution`，取**修后**读数落 `loadtest/results/`；
- `docs/STATUS.md`（`gen_status.py` 重生成）、`docs/current-metrics.json`、README 指标表与
  归因段、`docs/EVIDENCE.md`、`docs/TUNING_PLAN.md` 同步；
- 达成 → TTFT 行转绿并记达成口径；未达成 → 红值照登 + 固定项账按 sidecar 实测更新；
- ADR 0001/0011/0058 换代指针登记（原文不改）；tracker README round49 收口。

## 边界

- 不改判据/夹具/gold/`judge()`/分母；不删旧行读数（append-only）。
- 未达成项按纪律写进票面 Handoff，不预填、不摘红。

## Verify

```bash
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
git diff --check && git status --short
```

## 验收项

- 两口径（服务端/探针）修后读数落档并登记 EVIDENCE；STATUS/README/EVIDENCE/TUNING 四处一致。
- ADR 换代指针三条（0001/0011/0058）留痕。
- tracker README round49 行收口（done 或如实登记残余）。

## Handoff notes

（收口时补：关键决策、验证落点、三个现场追问。）
