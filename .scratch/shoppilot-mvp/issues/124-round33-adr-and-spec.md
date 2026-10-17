# 124 round33 ADR 与 spec

**Status:** implemented

## What to build

round33 开轮的记录票：ADR 0067 + round33 spec。

- `docs/adr/0067-round33-cross-industry-isolation-and-intent-precedence.md`：所有者政策覆盖 + Domain
  维度 + 缓存/检索隔离 + T0 动作优先 + 提示词/引用/语料修复边界。
- `.scratch/shoppilot-mvp/round33-spec-cross-industry-isolation.md`：九类复现缺陷、范围、硬约束、
  验证形态、票序 124-128、登记节。

## Blocked by

无（本轮入口票）。

## Verify

```powershell
git diff --check; git status --short
```

## Handoff notes

- 本轮性质是所有者政策覆盖，不自动续期；不改 gold、阈值、分母或验收判据。
- 后续票 125-128 逐票引用 ADR 0067。
