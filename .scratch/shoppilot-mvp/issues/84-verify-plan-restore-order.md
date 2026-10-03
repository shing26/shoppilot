# 84 验收脚本 `plan` 步的恢复顺序（票 75 登记的脚本脆弱性）

**Status:** implemented（2026-10-04；**代码改动已落地，但收口要求的修前红/修后绿两组活体读数仍缺**，见 Handoff 第一行）

## What to build

票 75 的收口登记了一条**验收脚本自身**的缺陷，不属于被测系统，本轮单开一张处理。

**登记原文**：`-WithRestarts` 的 ticket 14 分支把真 `.env` 挪走、只留一个 `SHOPPILOT_OLLAMA_URL` 占位，
结束时恢复并重启网关；跑完之后「后续 ops 调用全被 biz-mock 拒（`missing or invalid internal token`）」。

## 第一件事：复现，再改

登记里写的根因是「**重启前没有先停掉用占位配置起的那一个**，端口被占，新进程起不来，旧网关带着空
internal token 继续服务」。**这条根因登记在本轮开始时尚未复核**，所以本票的第一格是复现，不是修复。

读代码时看到的事实（供复现时对照，不当结论）：

- `try` 块（`scripts/verify-plan-actions.ps1:308-325`）用占位 `.env` 重起网关；
- `finally` 块（`:326-334`）的顺序是**恢复 `.env` → `stop.ps1 -Ports '8082'` → `start-gateway.ps1`**，
  表面上「先停后起」是对的；
- 真正可疑的是 `Wait-ServiceUp` **分不出新旧进程**：只要端口上有东西在应答 readiness 就返回 true。
  如果 `stop.ps1` 没杀干净（或新进程尚未抢到端口、旧进程还在应答），这一步会**判绿**——
  而此时服务的是旧配置。这与 round23 清场日抓到的「队列空也判过」是同一种假绿。

## 若复现不出来

按仓内纪律**更正登记**，不为了「修一个东西」而制造一个修复：把真实根因写进本票的 Handoff，
并在 tracker 与 `docs/EVIDENCE.md` 里把票 75 那条登记改成更正后的口径。

## Blocked by

无。

## 口径

- **不动被测代码**：这是事实性修正，验收脚本的缺陷不授权去改产品行为。
- **不改判据**：ticket 14 的判据（模型端点不通时以 `fallback` 收尾、原因是 `LLM_CIRCUIT_OPEN`、工单能查回）一字不动。
- 若根因确实是「网关与 biz-mock 的 internal-token 默认值不一致」，那属于**配置面缺陷**，
  要么在本票里一并对齐（并记 ADR），要么单开一张——**不在本票顺手改**。

## 验收

- 修复前红 / 修复后绿**两组读数**（round20 起对验收脚本的硬要求：门禁变绿不等于系统变好）；
- `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` 绿；
- 修复后跑一次 `run-acceptance.ps1 -Only plan -WithRestarts`，其**后续步骤的 ops 调用不再被拒**。

## Verify

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/run-acceptance.ps1 -SkipBuild -SkipStack -Only plan
```

## Handoff notes

### ⚠ 收口要求的「修前红 / 修后绿两组活体读数」**本轮仍缺**

本机现在跑的是**容器档**（round24 的四个服务 + 中间件），而这个脚本的 `-WithRestarts` 分支
要在**本机 JVM 档**上跑。复现它意味着拆掉容器档、起本机 JVM（全栈约 6.6 GB vs 可用约 1.9 GB）——
按 spec §5 与 round23 裁定 A，那要占用者安排清场日。所以：代码改动落地了，**活体读数按未达成照登**，
票 85 收口时不得声称这条门禁已验证。

### 归因更正：票 75 记的根因**读代码站不住**

票 75 的原话是「重启前没有先停掉用占位配置起的那一个，端口被占，新进程起不来，旧网关带着空
internal token 继续服务」。逐条对代码：

1. **「端口被占」站不住**：`stop.ps1` 停完会**等端口释放**（默认 40 s ≥ 网关 35 s 的停机预算），
   没释放就 `Write-Warning` 明说「不许当已停」。所以「先停后起」这个顺序本来就是对的。
2. **「空 internal token」更站不住**：`DevDefaultsEnvironmentPostProcessor` 在**回环绑定上无条件**
   把 `shoppilot.bizmock.internal-token` 兜成 `DevDefaultsPolicy.INTERNAL_TOKEN`，与 `.env` 里有没有这一项无关。
   所以「占位 .env 缺这个键 → 网关拿着空令牌」这件事在回环上产生不了。

**真正的机制**（读代码得到的，与现场症状一致）：占位 `.env` 里没有 `SHOPPILOT_INTERNAL_TOKEN`，
于是**新起的网关退回 dev 默认值**，而**仍在运行的 biz-mock 用的是真 `.env` 里那个值**——
两端不一致，于是后续 ops 调用被拒。这解释了它为什么不是每次都出现：
**只有设过自定义 `SHOPPILOT_INTERNAL_TOKEN` 的机器（也就是「填过 .env 的机器」）才会不一致。**

这与本仓已有的两次更正同族（`OLLAMA_MAX_LOADED_MODELS` 那次、工单数恒 0 那次）：
**归因要落到代码上，不能落到一个听起来顺的故事上。**

### 改了什么

`verify-plan-actions.ps1` 的 ticket 14 分支：写占位 `.env` 时**把真 `.env` 里的三处凭证一并带过去**
（`SHOPPILOT_INTERNAL_TOKEN` / `SHOPPILOT_JWT_SECRET` / `SHOPPILOT_OPS_TOKEN`）。
这一步的目的只是打断模型端点，凭证不是目标，所以带着走是对的；带过去之后两侧就仍然一致。

**ticket 14 的判据一个字未动**（模型端点不通时以 `fallback` 收尾、原因是 `LLM_CIRCUIT_OPEN`、工单能查回）。

### 新登记一项（本票不修，也不混进来）

**`verify-plan-actions.ps1 -WithRestarts` 会 `stop.ps1 -Ports '8082`，而在容器档下那个端口属于
Docker 的端口代理**（`Get-NetTCPConnection` 看到的 OwningProcess 是 docker/wslrelay，不是容器里的 JVM）。
在容器档跑本机档的验收脚本，会打到 Docker 的转发进程上。
触发 = 下次要在容器档上跑 `run-acceptance -WithRestarts`；那时要么先 `up.ps1 -Containerized` 之前
把档位对齐，要么让 `stop.ps1` 认得「这个端口是容器发布的，别杀」。

### 现场三问

1. **为什么不直接删掉 ticket 14 的重启分支？** 它的判据是 PLAN 第 14 行要求的（「模型端点指向不存在地址
   时服务起得来、降级语义正确」），删掉就是删判据。
2. **为什么先改归因再改代码？** 因为按原归因去「修端口释放顺序」是修一个不存在的问题；
   那种修法在门禁上同样会绿，账却留在错误的地方。
3. **为什么不在本机档上补那两组读数？** 见第一段：全栈约 6.6 GB vs 可用约 1.9 GB，
   按裁定 A 不为本轮验收去停别人的容器。照登，不假装。