# 90 round26 收口：结果回流买家

**Status:** ready-for-agent

## What to build

- **`docs/EVIDENCE.md`**：新读数（投递门禁读数、三个计数、指标名换代、CI 步数）；**未达成照登**。
- **`docs/CODE_MAP.md`**：`channel.outbound` 的生产端 / 消费端 / 投递口三处落点。
- **`CONTEXT.md`**：新术语（**投递目标 / 回流**）——**只加术语，不改既有结论**。
- **`README.md` / `DELIVERY.md` / `CHANGELOG.md`**：定位按 ADR 0052；
  **性能与判据数字不得因回流而重算**。已知限制加两条（email 不真发；web 渠道靠买家端自读）。
- **审计常数**：G6 surefire 计数、CI 步数按实读数换代。
- **tracker**：票 86-90 状态与 Handoff。

## Blocked by

[86](86-ticket-channel-and-contact-columns.md) … [89](89-outbound-gate-and-metrics.md) 全部收口。

## 口径

- **一条判据都没动**：gold 180、阈值、`judge()`、降级枚举 10 / 降级 9。
- ADR 0024 的「不宣称上线」红线在交付层照写（0052 继承）。
- **ADR 0059 第 7 条必须写进交付材料**：email 出站只落投递口，**从未真发过邮件**；
  回流的可见性是「买家侧可查」，不是「买家一定收到」。
- 未达成项保留实测值、归因与限制，**不摘红**。

## 验收

- 收口审计读数落 `docs/EVIDENCE.md`；
- 干净克隆的 CI 十步全绿。

## Verify

```powershell
git diff --check && git status --short
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
gh run list --workflow ci-subset.yml --limit 1
```

## Handoff notes

（收口时补）