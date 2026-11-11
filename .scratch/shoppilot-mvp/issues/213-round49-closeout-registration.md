# 票 213：round49 收口登记——TTFT 读数换代与文档同步

Round: 49（spec：[round49-spec-ttft-embedding-review.md](../round49-spec-ttft-embedding-review.md)）
ADR: 0085　Status: implemented（2026-11-11）　Blocked by: 票 212（已收口合并）

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

（2026-11-11 收口）

**关键决策**

- TTFT 改判**达成（sidecar 口径）**：`docs/current-metrics.json` / STATUS（gen_status 重生成）/ README 指标表、归因段、未达成汇总表三处换代；修前红值读数（690–1499 ms、归因拆账 300+311+104）全部原样保留，达成声明写明「判据与 Mock 夹具零改动，系统变快而不是判据变宽」。
- ADR 0001/0011/0058 各追加**一条换代指针**（原文不动）：0001 = bge-m3 结论不变、sidecar 同模型同维；0011 = 「独立 embedding 服务」出口兑现；0058 = 第五进程否决记录针对工单域 JVM，sidecar 单独记账准入（RSS ~1.2 GB / mem_limit 1536m）。
- 票 212 合并签收记录已写入票面：Verifier PASS（HEAD c37f3d6，亲跑 TTFT 416.9 / embedding 30.1 ms、Forbidden 零违例、TUNING_PLAN 越界判定为门禁机械换代），所有者 2026-11-11 书面指令签收，fast-forward 合并至 main（`92e4edc`，含 Verifier 证据 CSV 入库）。

**验证落点**

- `python scripts/gen_status.py && gen_status.py --check`：wrote + STATUS ok（TTFT 行达成口径）。
- `python -I scripts/verify_doc_truth.py`：OK（790/132）。
- `git diff --check`：干净。README 指标表 148 行、指标与口径 430/432 行、归因段 453 行、汇总表 481 行、改判总述 154 行五处换代。

**残余与现场追问**

1. 越界留痕：票 212 的 `docs/TUNING_PLAN.md` 改动超出其契约「仅指标名计数」字面（Verifier 判定为文档真值门禁硬要求的机械换代、round43 先例）——本票确认接受该判定并留痕。
2. `check-ps-syntax.ps1` 需要 pwsh 7（本机缺失）：票 212 以 PS 5.1 等价门（ParseInput）复证 35/0；是否把降级逻辑写进脚本本身，留给 round50 决定。
3. 负载窗口字段：长句 novel 在重负载窗口曾超线（spike 复验 100.6 ms）；判据 sweep 问句全 ≤10 字不受影响——归因产物要不要加「当时负载窗口」固定字段，留给 round50。
