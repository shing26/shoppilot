# 95 round27 收口：买家端前端

**Status:** implemented（2026-10-04）

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

### 交付了什么

- **`docs/EVIDENCE.md`**：round27 一行。
- **`docs/CODE_MAP.md`**：买家端入口与「我的工单」两跳链路 + **一行专门写静态放行面**
  （清场日抓到过一次，加一个入口忘了放行 → 401）。
- **`CONTEXT.md`**：**一个字没动**。本轮没有引入新概念，这是它该有的样子，写下来比写一句「本轮无新增」更有用。
- **`README.md`**：架构表加「三个前端入口」一行；已知限制加一条（买家端的能力边界与浏览器断言未实跑）。
- **CI 仍是九步**（前端门禁扩内容不加步数）。

### 读数

| 项 | 值 |
|---|---|
| JVM 四模块 | **`5 + 10 + 57 + 342 + 37 = 452`** 绿（round26 收口 440） |
| 覆盖率 | gateway 64.79% / biz-mock 76.92% / ticket 76.03% / tool-api 45.83%，`COVERAGE OK modules=4` |
| 矩阵 | full 档 **27 → 28 步**（add-only）；**CI 九步未动**，run `37179113368` 绿 |
| 指标名 | **仍 55**（本轮不新增 `shoppilot_*`） |
| 变异对照 | 两条，去掉 `customerId` 过滤 → 2 红；去掉静态放行面的 `/buyer/` → 2 红 |
| 审计 | `PASS 85 / FAIL 2 / SKIP 9 / 共 96 项`（`A2` 工作树未提交、`F1c` 那笔老账） |

### 照登（三条，不摘红）

1. **两个浏览器门禁（`verify-buyer` 与上一轮的 `verify-outbound`）本轮都未实跑**
   （要起四服务栈 ≈6.6 GB vs 可用 1.9 GB，裁定 A）。**「页面真的能用」目前只有
   typecheck + 构建 + 静态放行面三处证据。**
2. `SHOPPILOT_IDENTITY_DEMO_PASSWORD` 不给时买家端门禁 **exit 2**——
   不给就退回 mock 令牌的话，那是在验一个已经不存在的产品形态。
3. 买家端的能力边界照写：没有下单、没有多店切换、没有图片富文本——商品与交易域不在本仓范围。

### 本轮最值得记的一条

**「同类洞的第二个实例」是可以预先钉掉的。** 清场日抓到 `/workspace/` 没放行 → 401，
买家中心按原样加上去就会重演一次。而这次的处置不是「记得加上」，是
`StaticEntryExemptionTest` 把三个入口一次钉住 + 变异对照实测为真——
**下一个人加入口时会先在那条上撞红，而不是等活体验收**。这大概是清场日那次教训
最实在的一次兑现。

### 下一轮的入口

- **91 是决策票**（知识服务化做不做），随时可领，翻案成本是改一张票。
- agent 自主性升级那六决策**仍未锚定**到新结构上（program 裁定顺延）。
- **三笔浏览器门禁欠账**：`verify-workspace`（round25）、`verify-outbound` 与 `verify-buyer`（round26/27），
  一并等清场日。