# 174 渠道连接：保存 → 重启 → 生效验证

**Status:** implemented（2026-10-28，保存 → 重启 → 加载已验证）

**Claimed by:** zcode session 2026-10-28

> 票 173 已实现文件保存与重启标记，但保存动作没做过真实重启验证。本票只验证这条闭环，不新增功能。

## 步骤

1. 从工作台渠道页保存配置：租户改为 T003，appId/appSecret 留空沿用；
2. 确认 API/页面 source=file、restartRequired=true；
3. 重启网关；
4. 确认启动日志从 config/channels.json 加载、飞书长连接启动；
5. 确认页面 source=file、tenant=T003；
6. 删除测试文件，恢复 .env 行为，确认工作树干净。

## 验收

- 保存前 source=env；
- 保存后 source=file、restartRequired=true；
- 重启后 source=file、tenant=T003；
- 飞书长连接启动；
- 测试文件删除后不再有未跟踪密钥文件。

## Non-goals

- 热重启；
- 多渠道路由；
- 改密钥管理方案。

## 结果

- 保存前：页面 source=环境变量、tenant=T001；
- 工作台把租户改为 T003、appId/appSecret 留空，点击“保存并标记重启”；
- 保存后：source=文件、tenant=T003、restartRequired=true，提示“已保存。重启网关后生效。”；
- config/channels.json 落盘，enabled=true、tenant=T003、secret 有值（不打印）；
- 重启日志：`渠道连接配置从文件加载：... (enabled=true, tenant=T003)`、`飞书长连接已启动`、WSS 已连接；
- 重启后 API：source=file、tenant=T003、restartRequired=false；
- 测试文件已删除，`.env` 行为恢复，工作树无未跟踪密钥文件。

**环境缺口（照登）**：重启后本地 Docker/Redis 与 embedding 预热被内存压力终止，所以重启后的页面截图没取到；用 API 读取补齐了 source/tenant/restartRequired 三格。

## Handoff notes

**关键决策**

1. 用工作台真实保存，不直接改文件；验证的是 UI → API → 文件；
2. 只改 tenant，appId/secret 留空，验证“留空沿用”语义；
3. 验完删除测试文件，不把本地密钥配置留在工作树。

**验证落点**

- 保存截图：docs/workspace-channels-saved.png；
- 重启日志：logs/gateway-tuning.out；
- 重启后 API：GET /api/v1/support/ops/channels 返回 source=file、tenant=T003。

**三个现场追问**

1. 为什么重启后 restartRequired=false？它是进程内标记，重启后新进程从文件加载，自然不需要再重启；
2. 为什么没保留 T003 配置？测试会改变所有飞书会话的租户，验完必须恢复；
3. 为什么用 API 而不是页面截图？本地栈重启后被内存压力终止；API 的三格与页面同源，足以证明加载结果。
