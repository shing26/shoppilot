# 117 round32 ADR 与 spec

**Status:** implemented

## What to build

round32 开轮的记录票：ADR 0066 + round32 spec。

- `docs/adr/0066-round32-generic-customer-service-two-runnable-scenarios.md`：开轮决策（所有者政策
  覆盖，不自动续期）+ **授权 `CONTEXT.md` 的租户术语收敛** + 酒店纯咨询对照 + 酒店语料不挂
  `PLATFORM` + 酒店评测不并入 gold + 交付面以业务一页纸为主。
- `.scratch/shoppilot-mvp/round32-spec-generic-customer-service.md`：本轮一句话、范围、硬约束、
  验证形态、票序（117-123）、登记节。

## Blocked by

无（本轮入口票）。

## 口径

- 按 `AGENTS.md`，`CONTEXT.md` 术语变更由本 ADR 明文覆盖，实现者不自行改写。
- 判据面零改动；CI 九步不变。

## Verify

```powershell
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：round32 性质是**所有者政策覆盖**（`RELEASE.md` Freeze policy 第 4 条），
  **不自动续期**；术语收敛（租户唯一、店铺降为电商域业务叫法）由 ADR 0066 决策 2 逐字授权。
- **与 ADR 0007 的两处冲突照登**（ADR 0066 决策 8，不改既有 ADR 结论）：① 意图数由「封顶 10」
  越过到 **11**，是所有者政策覆盖下的有意偏离；② ADR 0007 那句「限流统计」在本仓不可观测
  （`RateLimitService` 键是租户 + 买家/IP，无意图维度，也找不到 `intent` tag 的 meter）——本轮
  **不声称限流统计自动生效**，照登为既有文档缺口。实际自动生效的是缓存分区与工具裁剪，另加检索
  过滤与评测集。
- **验证落点**：ADR 0066 与 spec 均入库；后续票 118-123 逐票引用。
- **三个现场追问**：
  1. 酒店若将来要做**办理域**，「改四处」结论是否要改写为「改六处（+工具契约 +状态机分支）」？
  2. 业务一页纸放在 `docs/` 与放在仓根，哪个更利于非工程读者第一时间找到？
  3. 术语收敛后，历史文档里「店铺 / 租户」并列的旧表述要不要做一次统一清扫，还是只在引用处纠正？
