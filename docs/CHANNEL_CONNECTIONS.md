# 渠道连接管理 v1（票 173）

**状态：** 已实现。当前只管理飞书单连接，保存后需要重启网关。

## 配置来源

优先读 `config/channels.json`；没有文件时回退 `.env` 的飞书配置：

    SHOPPILOT_IM_FEISHU_ENABLED=true
    SHOPPILOT_IM_FEISHU_APP_ID=...
    SHOPPILOT_IM_FEISHU_APP_SECRET=...
    SHOPPILOT_IM_FEISHU_TENANT=T001
    SHOPPILOT_IM_FEISHU_CUSTOMER=C001

文件格式参考 [channels.example.json](config/channels.example.json)：

    {
      "feishu": {
        "enabled": true,
        "appId": "cli_xxx",
        "appSecret": "xxx",
        "tenant": "T001",
        "customer": "C001",
        "identityFile": ""
      }
    }

实际文件 `config/channels.json` 已 gitignore，含 appSecret，不入库。容器档把整个 `./config` 只读挂到 `/workspace/config`，保存后重启容器即生效。

## API

所有接口都在 `/api/v1/support/ops/**` 下，需要 `X-Ops-Token`（或 ops 关闭时直接拒绝）。

### 查看状态

    GET /api/v1/support/ops/channels

返回 enabled、遮罩 appId、tenant/customer、来源（file/env）、restartRequired。**不返回 secret。**

### 测试凭据

    POST /api/v1/support/ops/channels/feishu/test
    Content-Type: application/json

    {}

空 body 用当前配置测试；也可以临时传：

    { "appId": "cli_xxx", "appSecret": "xxx" }

服务端调用飞书 `tenant_access_token/internal`，只返回 ok / 错误消息，不返回 token。

### 保存配置

    PUT /api/v1/support/ops/channels/feishu
    Content-Type: application/json

    {
      "enabled": true,
      "appId": "cli_xxx",
      "appSecret": "xxx",
      "tenant": "T001",
      "customer": "C001",
      "identityFile": ""
    }

- appSecret 留空表示保留旧 secret；
- enabled 省略表示保持当前值；
- 启用但 appId/secret 缺失 → 400；
- 保存后返回 `restartRequired: true`。

## 前端入口

坐席/管理员登录 `/workspace/` 后，左侧「渠道连接」：

- 展示 enabled、遮罩 appId、租户/买家、配置来源、restartRequired；
- appId/appSecret 留空表示沿用，测试凭据直接调 token 接口；
- 保存后提示“重启网关后生效”。

截图：`docs/workspace-channels.png`、`docs/workspace-channels-test.png`、`docs/workspace-channels-mobile.png`。

## 为什么保存后要重启

飞书 SDK 的 `Client.disconnect()` 是 protected，没有公开的优雅关闭；在进程内重建客户端可能留下两个 WebSocket 同时收事件，导致重复回复/幂等 token 碰撞。v1 明确“保存配置 → 重启网关”，不假装热切换。

## 边界

- 单节点、单飞书应用；
- 前端入口在坐席工作台「渠道连接」视图（状态 / 测试 / 保存 / 重启提示）；
- 没有数据库/密钥管理服务，secret 落在本机文件；
- 没有多渠道多实例；
- 测试接口只验证凭据可换 token，不验证机器人事件订阅。
