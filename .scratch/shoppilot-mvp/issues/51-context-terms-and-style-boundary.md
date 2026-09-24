# 51 — CONTEXT.md 术语补齐 + 风格档位已知边界

**What to build:** 补 5 个「代码与 README 里反复出现、却从未进术语表」的 canonical term，并把一处容易被误判成缺陷的设计写成已知边界。

**Blocked by:** None（纯文档，只碰 `CONTEXT.md`、`README.md` 与可能的一处 ADR 注）。

**Status:** implemented（2026-09-24）。

口径（ADR 0044 已定，本票只执行）：

- **按现有格式写**：粗体 `**中文名 (English)**` + 定义行 + 可选 `_Avoid_:` 行；**语义邻接插入**，不追加末尾、不按字典序（`CONTEXT.md` 现有 38 条按主题聚簇）。
- **不引入新能力**：这 5 个词描述的都是**已经存在**的机制，补的是「引用时无权威定义」这个缺口。
- **风格档位那条不是缺陷，别改代码**：`StyleService.tierFor(Channel, Emotion, Intent)` 的 intent 是**活的匹配维度**（`Rule.matches(channel, emotion, intent)`），`StyleServiceTest` 有 7 处调用传真 Intent（`Intent.ACTION_REFUND` 等）作断言，`profiles.yml` 只是**没写 intent 规则**；调用方在 INTAKE 阶段（意图未定）传 `null` 是诚实的。三者自洽，所以处置是「写成已知边界」，不是「修 bug」。

- [ ] `CONTEXT.md` 新增 **槽位 (Slot)**（`## Language`，邻接「待办动作」）
- [ ] 新增 **降级 (Fallback)**（`## Language`）：须写明换算规则「降级 9 = 枚举 10 − 主动转人工」并指向 `README.md:271`，避免「8 类/9 类/10 类」再被当成互相矛盾
- [ ] 新增 **缓存写回资格 (Write-back Eligibility)**（`## Language`，邻接「缓存准入」）：写明二者是**不同的门**（准入管读、写回管写）
- [ ] 新增 **复核队列 (Review Queue)**（`## 知识侧`，邻接「引用」）
- [ ] 新增 **计划 (Plan)**（`## Language`）：写明「有序步骤 ≤ 2，与 2 轮硬上限是同一条边界的两种表述」（引 ADR 0036）
- [ ] 风格档位已知边界写进 `README.md` 已知限制段（或 ADR 0038 注）：档位在 INTAKE 计算、意图未定，故 `profiles.yml` 的 intent 维度当前不可能命中
- [ ] 每个新术语至少核对一处源码落点，避免「文档比代码超前」
- [ ] `git diff --check` 与 `git status --short` 干净

**Verify:**

```powershell
git diff --check
git status --short
```

## Handoff notes

**关键决策**

- **只补「已经存在却无权威定义」的词，不补本轮没实现的机制。** 5 个词（缓存写回资格 / 槽位 / 计划 / 降级 / 复核队列）描述的都是代码里跑着的机制；「工单回流」「审批闸门」「退款终态」这类**尚未实现**的概念进的是 round19 spec 的登记节，不进术语表——`CONTEXT.md` 是术语表，不是待办清单。唯一的例外是既有的 `可查工单` 条目，它用 `_Avoid_` 显式写下「不承诺什么」，那是消歧不是许愿。
- **「降级」这个词条的主要价值是掐掉一个反复出现的误读。** 仓内三套数字（枚举 10 / 降级 9 / 脚本确定性表 7 行）被外部材料多次当成「仓内自相矛盾」。词条里直接写清换算规则并指向 `README.md` 验收对照段，`_Avoid_` 里点名禁用「8 类降级」这种没有换算依据的说法。
- **风格档位那条是「写成边界」而不是「修 bug」——这个判断是核对之后改的。** 我最初的判断是「配置与实现漂移」，核对后否掉了：`Rule.matches(channel, emotion, intent)` 里 intent 是**活的匹配维度**、`StyleServiceTest` 有 7 处调用传真 Intent 作断言、`profiles.yml` 只是没写 intent 规则，而调用方在 INTAKE 阶段（意图未定）传 `null` 是诚实的。三者自洽，所以处置是写进 README 已知限制并说明「要让 intent 真参与判定，得先把档位计算点移到 TRIAGE 之后，那是行为变更」。**先核对再下判断，省掉一次改错方向的"修复"。**
- **每个词条都核对了源码落点**：写回资格七道门（`WriteBackPolicy`）、`GATEWAY_CONFIDENT_SLOTS` 的四个槽位、`PlanExpression` 的 `{steps[i].result.<field>}` 形态、`FallbackReason` 的 10 个枚举、`markReviewed` 的单向流转。术语不能比代码超前。

**验证落点**

- `CONTEXT.md` 术语数 38 → 43（`grep -cE '^\*\*[^*]+\*\*:$'`），5 个新词按语义邻接插入而非追加末尾（缓存写回资格紧跟缓存准入；槽位/计划/降级接在待办动作之后；复核队列接在引用之后）。
- `README.md` 已知限制段新增风格档位 intent 维度一条。
- `git diff --check` 干净。

**你需要能当场回答的三个追问**

1. *Q：风格档位传 `null` 是不是 bug？* A：不是。规则引擎支持 intent 维度，只是没有规则用它；调用点在 INTAKE 阶段，那时意图确实还没判定，传 null 是如实表达「还不知道」。三者自洽，所以本票只把它写成已知边界。要让 intent 真生效得把档位计算点移到 TRIAGE 之后——那会改 system prompt 内容，是行为变更，得单独决策。
2. *Q：「降级 9 种」和「枚举 10 个」到底哪个对？* A：都对，是三个不同集合：枚举 10 是全集、「降级」= 枚举 − 主动转人工 = 9、`verify-fallback.ps1` 的确定性表是 7 行（6 种降级 + 主动转人工，限流是第 8 步、只在真打出 429 时才落行）。换算规则写在 `README.md` 验收对照段，词条里做了指引。
3. *Q：为什么不把「工单回流」「审批」也补进术语表？* A：因为术语表记的是**系统里存在的东西**。这两个概念当前不存在，写进去会让下一个读者以为它们存在——那正是 `可查工单` 条目 `_Avoid_` 里那句「工单回流（现状不存在此路径，别拿这个词指称它）」要防的事。它们的位置是 round19 spec 的登记节，带触发条件。
