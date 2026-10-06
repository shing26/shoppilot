# 101 seed 拆两段：初始化 / 演示

**Status:** implemented（2026-10-06）

## What to build

B1 清单里的「seed 拆成初始化与演示数据两段（现在是一次全量）」。持久化之后这件事从整洁问题变成
**正确性问题**：5 万条压测订单落进真库是污染，而真库也确实需要租户结构才能运作。

- `SeedRunner` 拆两段，各自独立幂等、独立开关：
  - **初始化段**：租户（T001-T003），沿用 `shoppilot.bizmock.seed.enabled`（默认 true 不变）；
  - **演示段**：买家 200 + 压测订单 + 优惠券 + 演示固定单，新开关 `shoppilot.bizmock.seed.demo-data`
    （env `SHOPPILOT_BIZMOCK_DEMO_SEED`），默认 true（默认档行为不变），持久档 yml 里默认 false。
- 幂等口径：初始化段查 `tenants` 非空即跳过；演示段沿用 `orders` 非空即跳过——**重启不翻倍**在两段上各自成立。
- `IdentitySeedRunner` 不动代码，仅更正 javadoc：`subjectRef=C001` 在演示段没播时也只是个字符串标注，建号照常成立。

## Blocked by

[100](100-persistent-postgres-profile.md)（持久档的 yml 里要写这个开关的默认值）。

## 口径

- 默认档行为**逐字不变**：既有测试组（每个都靠演示订单与买家断言）就是「两段全开没被拆坏」的全量回归。
- `AdminController.resetDemoFixtures`（演示复位）走 `seedDemoFixtures`，不经过新开关——运维复位是显式动作，不受播种开关管辖。
- 依据：所有者政策覆盖（program-a-to-b-upgrade.md B1 + ADR 0061 §5）。

## 验收

- 新用例 `SeedSegmentationTest`：独立 H2 + `demo-data=false` → 租户 3 行、买家 0、订单 0。
- 既有套件全绿 = 演示段默认路径未被破坏。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 落点

- `SeedRunner.seedIfEmpty()` → `seedTenantsIfEmpty()` + `seedDemoDataIfEmpty()`（原 `seedMasterData` 拆成
  租户/买家两半，各自进对应段）；`demoData` 字段走 `@Value`，与既有 `customerCount/orderCount` 同风格。

### 现场追问

1. 为什么初始化段是「租户」而不是「租户+买家」？——买家在真实部署里来自真实业务流量，200 个假买家
   就是演示数据；租户才是结构。
2. 持久档上要演示怎么办？——`SHOPPILOT_BIZMOCK_DEMO_SEED=true` 一次，之后重启靠幂等跳过，不翻倍。
3. 演示段关闭时 `IdentitySeedRunner` 会不会因为找不到 C001 而挂？——不会，`subjectRef` 是字符串引用
   不是外键（round25 票 80 的既有设计），javadoc 已更正。
