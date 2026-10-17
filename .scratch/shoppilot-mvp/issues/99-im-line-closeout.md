# 99 IM 主线收口 / B 段裁定

**Status:** implemented

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

- **关键决策（本票的全部产出：对外措辞定死）**：
  - A 段只能说「**适配器契约可复现**」（两格派生 + 飞书录放门，0 token，进 CI 既有 test 步）；
  - B 段只能说「**飞书真连已验证**」并带证据指针（wss 握手 / 事件收发全链路 / 重启重建 / 开发者后台验证，四格都有落点）；
  - **不说「已接入飞书」、更不说「已接入各 IM 平台」**；跨平台身份统一（B5）明确不交付、多平台不做。
  - 票面第 19-20 行那句「不许出现『已接入 XX 平台』」的理由是**无法核对**；对飞书它今天可核对，但收口仍取更保守的措辞
    （「真连已验证」），因为「接入」隐含产品化（引导、多租户、运维面），而那不在本仓承诺里。
- **验证落点**：`README.md` 的已知限制段换代（见下）；EVIDENCE 新增「IM 主线收口」行；tracker 的 IM 主线行 → done、frontier 清空；
  `git diff --check` 干净；收口审计复跑（文档改动，判据零触碰）。
- **顺带修掉一处文档漂移（本票职责内）**：README 飞书段仍写着「事件收发全链路未实测；重启自动重连未实测」，
  而同日更晚的 round31 补测 1/2 已实测通过（含用户确认收到回复、restart4/5 两个新 conn_id）——按收口职责换代，
  仍未实测的两格（SDK 同进程 `autoReconnect`、活体重复 message id 幂等）继续照登。
- **未达成照登**：SDK `autoReconnect` 语义未实测（验的是应用层重启重建）；重复 message id 的活体幂等未实测；
  只接飞书一家；隧道/公网形态从未使用（长连接本来就不需要，零暴露红线因此天然成立）。
- **三个现场追问**：
  1. 「真连已验证」与「已接入」的边界要不要写进 `CONTEXT.md` 成术语？（本轮按「无新概念不改 CONTEXT」没写。）
  2. 若将来要接第二家平台，A 段的录放门模式是否直接复用（夹具 + 0 token），还是先补一次「多平台选择」的裁定？
  3. 飞书凭据在 `.env`（不入库）——轮换/失效时的运维动作要不要写进 README 的快速开始？
