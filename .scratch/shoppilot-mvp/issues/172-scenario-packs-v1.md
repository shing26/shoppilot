# 172 场景包 v1：域 + 店铺人格配置化

**Status:** implemented（2026-10-28）

**Claimed by:** zcode session 2026-10-28

> L2 已能按租户路由。下一刀：把“这个租户是什么场景”从 application.yml 里的散配置收成一个可复制的场景包。

## 目标

- `config/scenarios/*.json` 每个文件定义一个场景包；
- 包内包含：场景 id、行业域、该场景下的店铺人格（按 tenantId）、可选场景补充提示；
- 启动时加载，覆盖 application.yml 的 tenant-domains / store-profiles；
- 同一 tenant 出现在两个包 → 拒启，不静默取最后一个；
- 政策事实仍只来自 knowledge/ 与工具，场景补充提示不得写具体承诺。

## 包格式

    {
      "id": "hotel",
      "domain": "HOTEL",
      "stores": {
        "T004": {
          "name": "云栖酒店",
          "business": "客房预订与入住服务",
          "guarantees": "标准入住 14:00 后..."
        }
      },
      "promptAddendum": "当前场景为酒店咨询..."
    }

## 验收

- 无 packs 目录：保持现有 application.yml 行为；
- 包覆盖域名与店铺人格；
- 重复 tenant 拒启；
- 包内 domain 非法拒启；
- 场景补充提示只在有配置时追加，不改变政策事实来源；
- 全量 JVM 不回退。

## Non-goals

- 知识库按包隔离/自动装载；
- 管理后台编辑场景；
- 工具集按包裁切；
- 多语言包。

## 已实现

- ScenarioPackService：加载 `config/scenarios/*.json`，校验 id/domain/stores/重复 tenant；
- TenantDomainRegistry 优先取场景包 domain，再回退 application.yml；
- StoreProfileService 优先取场景包店铺人格，并追加可选 promptAddendum；
- config/scenarios/ecommerce.json、hotel.json 示例包；
- docker-compose 只读挂载 config/scenarios；
- 文档 docs/SCENARIO_PACKS.md。

## 验证

- ScenarioPackServiceTest 4 条：加载/重复 tenant/非法 domain/缺目录；
- TenantDomainRegistryTest 增加场景包覆盖用例；
- StoreProfileServiceTest 增加店铺人格覆盖 + 补充提示用例；
- 全量 verify：715 tests / 119 suites 全绿；
- 未做：按包自动装载知识库、管理后台。

## Handoff notes

**关键决策**

1. 场景包只覆盖配置层（domain/店铺人格/补充提示），政策事实仍只来自 knowledge/ 与工具；
2. 包优先于 application.yml，缺目录回退旧配置，保证旧部署可升级；
3. 重复 tenant 直接拒启，不静默取最后一个；
4. promptAddendum 明确禁止写具体承诺，避免和条款打架。

**验证落点**

- ScenarioPackServiceTest；
- TenantDomainRegistryTest#scenarioPackOverridesConfiguredDomain；
- StoreProfileServiceTest#scenarioPackOverridesProfileAndAddsAddendum；
- 全量 logs/verify172.log。

**三个现场追问**

1. 为什么不把政策也放进包？政策要可检索、可引用、按 domain 隔离；直接塞 Prompt 会绕过引用与条款限缩。
2. 为什么保留 application.yml？旧部署与测试有默认值；包是覆盖层，不是唯一来源。
3. 怎么证明包生效？单测用 T900 覆盖 domain/店铺人格；运行时日志与租户会话键可继续核对。
