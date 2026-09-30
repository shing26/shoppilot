# 75 round23 收口：工单域竖切

**Status:** blocked（待 69-74 收口）

## What to build

- **`docs/EVIDENCE.md`**：新读数（工单三来源行数对账、事件对账门禁读数、CI 九步、两档资源读数）；**未达成照登**（真实 ACK/pending 若只在清场日跑过一次、门控缺口、买家端未做）。
- **`docs/CODE_MAP.md`**：新增 `shoppilot-ticket` 模块所有权与请求链路落点；四服务边界一节。
- **`CONTEXT.md`**：工单域新术语（工单、来源、队列、优先级、坐席、领取、SLA）——**只加术语，不改既有结论**。
- **`README.md` / `DELIVERY.md` / `CHANGELOG.md` / `RELEASE.md`**：定位按 ADR 0052 更新（可运行的系统雏形），但**性能与判据数字不得因定位变化而重算**；端口表加 8092。
- **收口审计**：常数换代（`G6_EXPECT` surefire 计数、CI 步数、模块数）。
- **tracker**：票 69-75 状态与 Handoff notes。

## Blocked by

[69](69-unified-ticket-entity.md) … [74](74-verification-tiering.md) 全部收口。

## 口径

- **一条判据都没动**：gold 180、阈值、`judge()`、降级枚举 10 / 降级 9 一个字不改。
- ADR 0024 的「不宣称上线」红线在交付层照写（0052 继承）。
- 未达成项保留实测值、归因与限制，**不摘红**。

## 验收

- 收口审计读数落 `docs/EVIDENCE.md`，本地读数与入仓产物一致（或按本机限定 SKIP 照登）。
- 干净克隆的 CI 九步全绿。
- program 路线图更新：下一轮（身份域 ADR 0056）标为 ready。

## Verify

```powershell
git diff --check && git status --short
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
gh run list --workflow ci-subset.yml --limit 1
```

## Handoff notes

（收口时补。）
