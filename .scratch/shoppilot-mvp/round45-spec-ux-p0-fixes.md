# round45：真实用户走查的三处 P0 缺陷修复

开轮依据：[ADR 0080](../../docs/adr/0080-round45-ux-p0-fixes.md)（所有者报障触发的缺陷修复，
不新增能力面、不新增后端接口）。走查报告：[`ux-probe/REPORT.md`](../ux-probe/REPORT.md)。

## 0. 本轮要补的那一格

```text
门户 / → 点店铺链接 → 买家页
   │
   ├─ 同一标签页换店：会话必须属于 URL 里的店铺  ← 票 191 ①（原来沿用上一家）
   │
   ├─ 回答下面：这条回答有用吗？[有用] [没用]     ← 票 191 ②（原来只有「重试」）
   │        └─ 没用 → 可选原因 → POST /chat/feedback → PENDING → 工作台复核队列
   │
   └─ 新消息 / 流式回答 → 线程贴底时跟随          ← 票 191 ③（原来视口停在顶部）
```

## 1. 票拆分

| 票 | 内容 | 依赖 |
| --- | --- | --- |
| [191](issues/191-buyer-session-feedback-and-scroll.md) | 跨店会话校验 + 买家点赞/点踩入口 + 对话自动滚动 | 无 |

## 2. 逐票判据

票 191：

1. 同一标签页从 `?store=T001` 切到 `?store=T004`：店卡、快捷问题、`sessionStorage` 里的
   `tenantId` 三者都是 T004；新标签页直开 T004 与切换后读数一致。
2. 回答下方出现「有用 / 没用」；点「没用」展开原因框，提交后出现「已记下：会进入人工复核」，
   且该 `feedbackId` 出现在工作台 `GET /api/v1/support/ops/feedback/review-queue`（PENDING）。
3. 390×844 连问 3 个问题后，`.thread` 的 `scrollTop + clientHeight >= scrollHeight - 4`，
   最后一条消息在视口内；用户手动上翻时新回答不把视口拽回底部。

## 3. 本轮不改

- 后端反馈 / 复核 / 工单的任何语义与接口面（`markReviewed` 不碰复核工单、队列只回 PENDING 均原样）。
- P1 清单（游客态隐藏订单、刷新丢对话、IME 回车误发、弹层无 Esc/焦点陷阱、订单卡双层滚动、
  退出不中止流）——保留在 `REPORT.md`，不执行。
- 验收判据、阈值、gold、指标分母。

## 4. 登记不执行

1. **复核后销账（REVIEWED 反馈可读 + 复核工单联动结单）**——触发 = 知识飞轮进入产品演示清单，
   或所有者点名。需要新读口或 actor 字段，属接口面动作。
2. **会话与消息跨刷新持久化**——触发 = 产品要承诺「关掉再打开继续上次对话」，且先定隐私边界
   （本地存储的范围与清除时机）。
3. **工作台渠道会话列表的自动滚动**——触发 = 渠道会话进入活体演示；本轮只修买家对话线程。

## 5. 验证分档

| 票 | 必跑 |
| --- | --- |
| 191 | `frontend-buyer` `npm run typecheck` + `npm run build`；`.scratch/ux-probe/verify-p0.mjs` 活体三项；工作台复核队列读数；`bash ./mvnw -B -ntp verify`（运行中的 fat jar 锁住目标文件时用 `-Dspring-boot.repackage.skip=true`，测试照跑、重新打包交 CI）；`git diff --check`、`git status --short` |

**产物入库**：`static/buyer` 的 index.html 与 assets 整目录提交（`emptyOutDir` 会删除旧 chunk），
CI 的 `git status --porcelain` 门禁会抓未跟踪文件。
