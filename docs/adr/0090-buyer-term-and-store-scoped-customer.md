# ADR 0090：保留全局「买家」，Shopify Customer 只作租户内的渠道档案

Status: Accepted（2026-11-26 所有者明确「接受 A」）
Date: 2026-11-26
Deciders: 所有者
Round: 62（契约：`plans/232-commerce-kernel-promotion.md`）
Related: [ADR 0004](0004-tenant-is-shop-platform-is-scope.md)、
[ADR 0005](0005-row-level-tenant-isolation-three-defenses.md)、
[ADR 0025](0025-conversation-owned-by-shop-and-customer.md)、
`CONTEXT.md` §买家、`docs/shopify-refund-contract-mapping.md` §5 H6

## Context

`CONTEXT.md` 当前把「买家」定义为：

> 在平台上跨店铺下单的人，身份全局唯一，但在每个租户下的数据互相不可见。

Shopify 的 `Customer` 是**店铺级对象**：同一个自然人在两家店下单，会得到两个不同的
`Customer` id，二者没有平台级关联。把 Shopify Customer 直接当成本系统的「买家」，
会让「全局唯一」这条定义在真实数据上不成立；反过来，如果坚持全局买家，
就必须解释两个店铺级 Customer 如何映射到同一个买家。

## Decision

采用 **A：保留全局买家，Shopify Customer 降为租户内的渠道顾客档案**。

### 1. 平台级「买家」定义不变

平台级买家仍是登录、会话、审计的主体，身份全局唯一；`CONTEXT.md` 原文不改。

### 2. Shopify Customer 只是租户内的「渠道顾客档案」

- 适配器只使用 `(TenantId, commerceCustomerId)` 作为商业系统内的顾客键；
- `commerceCustomerId` 只允许出现在租户作用域内，不得进入跨租户缓存键、
  指标维度或全局唯一索引；
- 一个平台买家可在 N 个租户下有 N 个渠道档案，二者是多对一；
- 「渠道顾客档案」是适配器内部术语，**不新增 `CONTEXT.md` 条目**。

### 3. 禁止按 email/phone 自动合并

不得用 email、手机号或姓名自动把两个租户的 Customer 合并成同一个买家。
共用邮箱、代购、家庭成员都会制造误合并。若未来需要绑定，必须显式授权并留审计，
且另立 ADR。

### 4. 跨租户读取一律 `NOT_FOUND`

这与 ADR 0005 的租户隔离同向：适配器不得因为 `commerceCustomerId` 相同或
email 相同而跨租户返回数据。

## Consequences

- **正面**：隔离边界清晰；不会因为 Shopify 的店铺级 id 破坏现有缓存与会话归属；
  适配器可以先落地而不阻塞术语裁决。
- **负面**：同一个自然人在两家店是两条独立档案；客服跨店查人需要额外产品设计；
  「全局买家」在真实多渠道下仍是一个未被验证的假设。
- **数据**：`commerceCustomerId` 的作用域是租户；全局买家若存在，需要一份
  「该买家在哪些租户有档案」的索引，这属于后续 ADR。
- **删除权**：GDPR/个人信息删除按租户分别执行；跨租户索引的删除语义需另立 ADR。

## Alternatives considered

- **B. 把「买家」改成店铺级**：与 Shopify 语义最接近，但直接推翻 `CONTEXT.md`
  的全局唯一定义，影响 JWT 身份、缓存键、评测 gold、审计与「串号」判据；
  不能作为适配器的顺手改动。拒绝。
- **C. 双主体（平台账号 + 租户顾客）**：概念最干净，但改动面最大，且与 ADR 0025
  的「会话由店铺 + 买家拥有」直接冲突。拒绝。

## Open questions

1. 平台是否真的需要跨店识别同一个人？若不需要，B 可能更诚实，应尽早重新裁决。
2. 若需要，绑定的可信信号是什么（登录账号、显式授权、手机号验证）？
   在答案明确前，禁止自动合并。
3. 是否要把「渠道顾客档案」写进 `CONTEXT.md`？这需要所有者另行授权；
   本 ADR 不代改。
