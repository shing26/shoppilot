# 95 round27 收口：买家端前端

**Status:** ready-for-agent

## What to build

- **`docs/EVIDENCE.md`**：round27 一行；**未达成照登**（浏览器断言未实跑是本轮最大的那一格）。
- **`docs/CODE_MAP.md`**：买家端入口与「我的工单」端点的落点。
- **`CONTEXT.md`**：**不加新术语**——本轮没有引入新概念（这是它该有的样子，值得写下来）。
- **`README.md` / `DELIVERY.md` / `CHANGELOG.md`**：定位按 ADR 0052；
  **性能与判据数字不得因多一个前端而重算**。已知限制加一条（买家端的能力边界）。
- **审计常数**：CI 步数**不该变**（仍是九步），如变了要写明为什么。
- **tracker**：票 92-95 状态与 Handoff。

## Blocked by

[92](92-buyer-own-tickets-endpoint.md) … [94](94-buyer-gate-and-ci-two-frontends.md) 全部收口。

## 口径

- **一条判据都没动**：gold 180、阈值、`judge()`、降级枚举 10 / 降级 9。
- ADR 0024 的「不宣称上线」红线照写（0052 继承）。
- 未达成项保留实测值、归因与限制，**不摘红**。

## 验收

- 收口审计读数落 `docs/EVIDENCE.md`；
- 干净克隆的 CI 九步全绿（两个前端都构建且逐字节一致）。

## Verify

```powershell
git diff --check && git status --short
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
gh run list --workflow ci-subset.yml --limit 1
```

## Handoff notes

（收口时补）