# 场景包 v1（票 172）

**状态：** 已实现。场景包把“租户属于哪个行业、这个场景的店铺人格”收成可复制文件，启动时加载。

## 包含什么

一个 `config/scenarios/*.json` 文件：

    {
      "id": "hotel",
      "domain": "HOTEL",
      "stores": {
        "T004": {
          "name": "云栖酒店",
          "business": "客房预订与入住服务",
          "guarantees": "标准入住 14:00 后、退房次日 12:00 前"
        }
      },
      "promptAddendum": "可选：场景行为补充，不得写具体承诺"
    }

- `id`：场景唯一标识；
- `domain`：`ECOMMERCE` / `HOTEL`，不能是 `UNKNOWN`；
- `stores`：tenantId → 店铺人格；`name` / `business` 必填；
- `promptAddendum`：可选，追加到系统提示词；只写场景行为，不写时限/金额/承诺。

## 优先级

场景包 &gt; application.yml 的 `tenant-domains` / `store-profiles`。

没有 `config/scenarios` 目录时，保持原 application.yml 行为。这样旧部署不会因为升级直接空配置。

## 拒启条件

- 同一个 tenant 出现在两个包；
- 包 id 重复；
- domain 非法或 UNKNOWN；
- 店铺缺 name/business；
- JSON 读不了。

宁可启动失败，也不静默取最后一个包——租户归属必须可审计。

## 现有包

- `config/scenarios/ecommerce.json`：T001 / T002 / T003；
- `config/scenarios/hotel.json`：T004。

这两个包目前与 application.yml 的值一致，用来证明加载路径；新增场景直接复制一份改字段即可，不需要改 Java。

## 新增一个场景

1. 复制一个 pack，改 `id`、`domain`、`stores`；
2. 如果新行业需要新政策，把带 domain 标记的文档放进 `knowledge/`；
3. 用身份映射文件把 tenant 映射到新租户（见票 171）；
4. 重启网关，查日志/会话键确认 domain 与店铺人格生效。

## 明确不做

- 知识库按包自动装载；
- 管理后台编辑场景；
- 按包裁切工具集；
- 多语言。

## 容器档

`docker-compose.yml` 已把整个 `./config` 只读挂到 `/workspace/config`，并设置
`SHOPPILOT_SCENARIO_PACKS_DIR`。改包后重启网关即可；同一挂载也覆盖 `config/channels.json`。
