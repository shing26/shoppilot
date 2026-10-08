# 114 飞书凭据接线 + PostureGuard

**Status:** implemented

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

**关键决策**：
- `bindAddress` 通过 `@Value("${server.address:}")` 注入构造函数，与 `DevDefaultsPolicy` 同源（ADR 0029）。
- 拒启消息三要素：点名缺失的环境变量、说明非回环绑定下空凭据不能启动长连接（飞书 SDK 需要有效凭据才能建立 WebSocket）、指引到 `.env` 配置。
- 回环 + 空凭据：`log.warn()` + return，不注册长连接，其余链路照常。
- `application.yml` 新增 `shoppilot.feishu` 配置块，三个环境变量占位符走 `.env`，不入库。
- `ConfigValidationTest` 的 `CONFIG_PLACEHOLDERS` 集合同步加入三个飞书变量（家法同既有占位符清单）。

**验证落点**：
- `FeishuLongConnectionClientJvmTest` 新增 7 条测试：非回环空 APP_ID 拒启、非回环空 APP_SECRET 拒启、非回环两个都空拒启、回环空凭据 WARN + 不注册、回环凭据非空正常启动、未启用跳过、变异对照。
- 全量 `.\mvnw.cmd -B -ntp verify` BUILD SUCCESS（4 模块全绿）。

**三个现场追问**：
1. 飞书 SDK 的 `Client.start()` 在凭据无效时是抛异常还是静默失败？当前实现是 catch + log.error，但非回环空凭据在 SDK 调用前就拒启了，所以这个 catch 只覆盖 SDK 内部错误。
2. 回环绑定下如果用户配了真实凭据，长连接会正常启动——这是否需要在 WARN 里提示「已检测到凭据，长连接将启动」？
3. 飞书 SDK 的 `EventDispatcher` 是否需要额外的 `chat_id` 过滤配置？当前实现接收所有事件，由 `FeishuAdapter.normalize()` 做格式校验。
