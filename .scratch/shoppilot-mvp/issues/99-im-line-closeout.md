# 99 IM 主线收口 / B 段裁定

**Status:** ready-for-agent

> **所有者裁定已下（2026-10-07）：B 段开，第一平台 = 飞书**（长连接形态）。
> 裁定记录见 [ADR 0065](../../../docs/adr/0065-b4-im-feishu-longconnection.md)，
> 执行票见 [round31 spec](../round31-spec-b4-feishu-live.md)（96、97、113-116）。
> 本票的剩余工作（主线收口 + 对外措辞定死）仍等 96/97 收口后执行。

## What to build

按票 98 的结论**裁定 B 段开不开**，并收口本主线。

- **若结论是「可开」**：开一张 ADR 写清暴露面方案与红线，然后另开 round spec 与票；
  **本票只留下那个决定**。
- **若结论是「不可开」**：照登，保留 A 段作为本线的全部交付内容，
  并把「什么条件成立时重开」写成触发条件（同 票 91 的处置）。
- **`docs/EVIDENCE.md` / `docs/CODE_MAP.md` / `CONTEXT.md` / `README.md` / tracker** 收口。
  **对外表述的措辞由本票定死**：A 段只能说「适配器契约可复现」，
  **不许出现「已接入 XX 平台」**（那句无法核对，而本仓吃过「loopback 那次 curl 没打对」的亏）。

## Blocked by

[96](96-inbound-contract-session-and-client-token.md) … [98](98-im-live-feasibility.md) 全部收口。

## 口径

- 一条判据都没动：A 段新增的门禁是 **0 token**，所以它进 CI 不放宽 CI 的任何既有性质。
- ADR 0024 / 0052 的「不宣称上线」红线照写：本线**没有**改写它们，也没有做产品化包装。
- 未达成照登，尤其是「B 段真连未验证」这一格。

## Verify

```powershell
git diff --check && git status --short
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
gh run list --workflow ci-subset.yml --limit 1
```

## Handoff notes

（收口时补）