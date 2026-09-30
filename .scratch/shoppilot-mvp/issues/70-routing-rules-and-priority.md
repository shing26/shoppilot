# 70 分流规则表、优先级与 SLA 计时

**Status:** ready-for-agent

## What to build

工单创建时按**可配置的规则表**分派队列（ADR 0055），并算 SLA 截止时间。

- `routing_rules` 表：`tenantId`（`*` 表示通配）、`intent`、`emotion`、`queue`、`priority`、`slaMinutes`、`enabled`；首次分派按**最长匹配优先**（tenant 精确 > intent 精确 > 通配），**未命中走默认队列**。
- 优先级枚举：`URGENT_EMOTION` > `MONEY` > `NORMAL`（先比来源与情绪，再比规则表给的基线）。
- SLA：创建时按队列的 `slaMinutes` 算 `slaDeadline`；超时**只打 `escalatedAt` 标记**，不承诺解决时限（0055 明确）。
- 规则表是数据不是代码：改动走 Flyway 迁移或管理端点，**每次改动发一条 `audit` 事件**（0056 的审计面）。

## Blocked by

[69](69-unified-ticket-entity.md)——分流需要统一实体当分母。

## 口径

规则表**不得**引入模型判断（LLM 分流在 0055 已否决并登记）。优先级与 SLA 的默认值要写进 ADR 的表格而不是散落在代码常量里；队列命名进 CONTEXT.md。

## 验收

- 命中/未命中/通配/租户覆盖四类分派各有 JVM 用例；未命中走默认队列而不是抛异常。
- 优先级排序可机验：同一队列里 `URGENT_EMOTION` 恒排在 `NORMAL` 之前。
- SLA 超时只打标记，不改工单状态（不得出现"超时自动关闭"这类行为）。
- 规则表改动的每一条都有 `audit` 事件可查。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/retrieval_gate.py        # 夹具与语料未被本票触及，应仍绿
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

（收口时补。）
