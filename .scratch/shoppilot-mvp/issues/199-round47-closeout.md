# 票 199：round47 收口

Round: 47（spec：[round47-spec-ux-p2-fixes.md](../round47-spec-ux-p2-fixes.md)）
ADR: 0082　Status: implemented　Blocked by: 195-198

## What to build

1. **登记换代**：
   - `README.md` 首屏的源码口径指标名 **72 / 74 → 73 / 75**（新增
     `shoppilot_handoff_decision_seconds_max`）；正文里出现同一组数字的地方一并核对。
   - `docs/EVIDENCE.md` 补 round47 条目：缺陷来源（`ux-probe/REPORT.md` P2 表）、
     各票验证命令与结果、指标名换代、P2-12「复核后不成立」的判定依据。
2. **边界同步**：`docs/CODE_MAP.md` 标出共享层新增的四张映射、401 钩子、
   质量报表的新读数来源，便于下一位 Agent 首改定位。
3. **tracker**：`.scratch/shoppilot-mvp/README.md` 的 round 表补 round47 一行，
   ticket 索引补 195-199 及最终状态。
4. **核对**：12 项 P2 每项在 README / EVIDENCE 里有明确结论（已修 / 复核不成立 /
   登记不执行），没有含糊的「待跟进」。

## Verify

`git diff --check`、`git status --short`；确认 `docs/STATUS.md` 未手改
（需要更新时只跑 `python scripts/gen_status.py`）。

## 验收项

- README 的指标名总数与实际 `grep` 结果一致（73 / 75）。
- EVIDENCE 的每个数字都能指到命令或产物；没有实跑的项目写「未实跑」。
- tracker round 表与索引能直接看出 round47 已收口、195-199 全部 `implemented`。
- `git status --short` 不混入 `shots/`、`logs/`、崩溃日志或过期快照。

## Handoff notes

**十二项 P2 的最终结论**

| # | 项 | 结论 | 落点 |
| --- | --- | --- | --- |
| 1 | 原始枚举直接进 UI | 已修 | 票 195 四张映射 + `labelOf`，接四个视图 |
| 2 | 筛选与详情打架 | 已修 | 票 197 `selected` 从 `filtered` 取、`setFilter` 联动 |
| 3 | 结单可跳过领取 | 已修（产品面） | 票 197 禁用 + 说明 + `openResolve` 守卫；后端状态机不改 |
| 4 | 质量报表「最大 0.00ms」 | 已修 | 票 198 累计 Gauge + sidecar 优先读 |
| 5 | 对比度不足 | 已修 | 票 195 `--ink-3` `#5c636e`（5.46 / 4.67 / 6.06） |
| 6 | 移动端点击目标过小 | 已修 | 票 196 买家四处 + 票 197 工作台顶栏/导航 |
| 7 | 移动端导航吃掉首屏 | 已修 | 票 197 ≤640px 三列两行 |
| 8 | 移动端「问问这单」不回对话 | 已修 | 票 196 ≤960px 滚回 `.buyer-main` |
| 9 | 401 不自动登出 | 已修 | 票 195 `setUnauthorizedHandler` + SSE 通知 |
| 10 | 整段对话一个 `aria-live` | 已修 | 票 196 `role="log"` + 增量播报 |
| 11 | 五步条「人工复核」恒为 0 | 已修 | 票 197 `reviewedTotal`（服务端 REVIEWED 总数） |
| 12 | 游客转人工后的死路 | **复核后不成立** | round46 票 192 已删短路与文案；证据在票 196 Handoff |

**验证与文档换代**

- `README.md` 指标名 **72 / 74 → 73 / 75**（新增 `shoppilot_handoff_decision_seconds_max`）。
- `docs/CODE_MAP.md`：新增 round47 行、共享层导出、四张映射与 401 钩子。
- `docs/EVIDENCE.md`：round47 条目，含 773 tests / 129 suites、两端产物哈希、活体未实跑边界（**2026-11-03 晚已补活体跑，见下**）。
- `docs/STATUS.md` 由 `python scripts/gen_status.py` 重新生成（771 → **773**，覆盖率 gateway 68.87% → 68.92%）。
- tracker README：round 表 round47 行、索引 195-199。

**三个现场追问**

1. ~~本轮没有跑活体验证~~ → **已补（2026-11-03 晚）**：起栈（重打 fat jar + Docker 中间件恢复）后实跑 `verify-p2.mjs` 全部 11 段并逐段复核；对比度 / 移动端点击目标 / 滚回 / ARIA 四属性 / 游客转人工 / 筛选详情一致 / 结单前置 / 三列导航 / 人工复核计数 / 质量 maxSeconds 全部实测过线，401 登出经真实请求路径验证。probe 的 `unauthorized`/`ariaLog`/`queueFilter` 段自身有选择器/模拟路径缺陷（裸 `fetch` 不走钩子、`.filter-chip` 不存在、无消息时 `.thread` 不渲染），已用等效实测补齐，probe 脚本未改；`labels` 段报的 `DOWN`/`AGENT` 是说明文案不是数据枚举。细目见各票 Handoff 与 EVIDENCE。**仍限定**：买家 SSE 入口的 401、读屏器实际播报、空档筛选（「已超时」档有 2 张）未单独触发。
2. CI 的产物逐字节门禁需要一次干净的 `npm ci && npm run build` 复现——本轮本机构建绿、产物已入库，但没有像 round31 那样在推送前按 CI 原样复现第九步。
3. round47 spec §4 的四条登记不执行（后端 `resolve` 领取前置、完整无障碍审计、refresh token、导航折叠化）仍无触发；下一次产品演示或外部验收前应重新判一次。
