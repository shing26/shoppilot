# 114 飞书凭据接线 + PostureGuard

**Status:** ready-for-agent

## What to build

真凭据的持面与守卫，家法与 B2 完全同源（ADR 0029 → PostureGuard）。

- **`.env` 家法**：`SHOPPILOT_IM_FEISHU_APP_ID` / `SHOPPILOT_IM_FEISHU_APP_SECRET` 走
  `.env`（不入库）；启动脚本/compose 用 `:?` 必需语法或等价（B2 PG 密码先例）。
- **PostureGuard 接入**：飞书长连接启用且网关绑定**非回环**时，空凭据**拒启**
  （消息三要素：哪个凭据缺、为什么危险、怎么配——同 B2 拒启消息的家法）；
  回环绑定时空凭据 WARN + 不启用长连接（演示口径：没有凭据时长连接客户端不注册，
  其余链路照常）。
- **CI 不进**：所有用例 0 token、免凭据（凭据缺失走的是拒启/WARN 分支的单元测试）。

## Blocked by

[113](113-feishu-longconnection-client.md)。

## 口径

- 凭据不进仓库、不进 CI、不进日志（SDK 日志里出现 secret 要确认被脱敏）。
- 「回环时空凭据 WARN」与 B2 的姿势守卫同语义——dev 默认值合法性由绑定地址决定
  （ADR 0029）。
- 不为本票放宽任何既有守卫。

## 验收

- JVM：非回环 + 启用长连接 + 空凭据 → 拒启；回环 + 空凭据 → WARN + 长连接不注册 +
  其余链路正常。
- `.\mvnw.cmd -B -ntp verify` 绿。
- **变异对照**：把拒启条件改成「凭据非空即可」→ 空串凭据漏进来的用例红。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

（收口时补）
