# 104 姿势守卫下沉共享库 + 数据侧接入

**Status:** implemented（2026-10-06）

## What to build

ADR 0029 的启动阻断此前只在网关，而持有订单、退款与全部账号的 biz-mock / ticket 在容器档绑 `0.0.0.0`
却没有同等收紧；B1 新增的数据库口令也不在任何姿势账本里。

- 姿势原语下沉共享库：`shoppilot-tool-api` 新增 `tool/config/PostureGuard`（`isLoopback` 空地址 fail-closed、
  `stillDefault`、三个仓库默认值常量）。
- 网关 `DevDefaultsPolicy` 改为委托共享原语，**公开 API 与行为逐字节不变**（三份既有测试类一字未改）。
- biz-mock / ticket 各加一道同形状的启动阻断（`config/PostureGuardConfiguration`），账本两格：
  内部令牌（仍是仓库默认值即阻断）+ 数据库口令（**仅当数据源是 `jdbc:postgresql:`** 且为空即阻断——
  默认档 H2 无口令，不许误伤）。
- 刻意不搬网关的 EnvironmentPostProcessor：数据侧 yml 占位符自带仓库默认值，没有「回环填默认值」的需求。

## Blocked by

无（B1 已收口，round28）。

## 口径

- 回环上守卫是 no-op：`up.ps1` 链路全部绑 127.0.0.1，干净克隆判据与全部活体判据不受影响（判据面零触碰）。
- compose 侧 DB 口令的 `:?` 必需语法是第一道（compose 解析即拒）；这道守卫防的是绕过 compose 直接起 jar。

## 验收

- JVM（每服务 5 断言）：非回环+默认令牌拒启（句子点名环境变量/监听地址/127.0.0.1 出路）；空地址 fail-closed；
  持久档空口令拒启且不误伤 H2；全覆盖放行；回环 no-op。
- 网关既有三份守卫测试类原样绿（行为零变化的证明）。
- 活体：0.0.0.0 + 默认令牌真起 biz-mock → 拒启（读数见 EVIDENCE B2 节）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

- 委托注意点：原 `stillDefault` 用 `Objects.equals(actual.trim(), repoDefault)`，共享版 `actual.trim().equals(repoDefault)`
  等价（常量无空白）；`Objects` import 已随迁移移除。
- 活体拒启读数与拒启消息原文见 EVIDENCE B2 节。
