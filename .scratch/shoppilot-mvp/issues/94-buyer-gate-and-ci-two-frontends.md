# 94 买家端浏览器门禁 + CI 前端门禁扩到两个前端

**Status:** ready-for-agent

## What to build

- **`scripts/verify-buyer.mjs`**：登录 → 会话页能问一句 → 「我的工单」里**能看到那张单** →
  **断言同租户另一买家的工单号不出现** → 未登录时给可读原因而不是空态。
- **CI 前端门禁扩到两个前端**：`npm ci` + typecheck + build 跑两个工程，
  `git diff --exit-code` **两段都要**。
  门禁**仍是九步**（一步里做两件事，不是加一步）——步数是 CI 成本口径，不该顺手变。
- **接进验收矩阵**：full 档 add-only 一步。

## Blocked by

[93](93-buyer-frontend-app.md)。

## 口径

- **门禁自己造那张工单**（走 buyer 会话 → 显式转人工），不依赖前一步的残留（round23 票 73 的纪律）。
- **跨买家那条断言是本票的承重格**：它必须去查「另一买家」的工单号确实存在过，
  否则「A 的页面上没有 B 的单」可能只是因为 B 压根没有单。
- 判据面零改动。

## 验收

- `node --check` 绿；
- **浏览器断言本轮按未达成登记**（裁定 A），不得声称已验证；
- CI 九步全绿（门禁扩到两个前端之后逐字节校验那一格仍在）。

## Verify

```powershell
node --check scripts/verify-buyer.mjs
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
```

## Handoff notes

（收口时补）