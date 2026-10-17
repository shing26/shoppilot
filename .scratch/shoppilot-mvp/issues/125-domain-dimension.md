# 125 Domain 维度落检索 / 缓存 / 入库

**Status:** implemented

## What to build

把「跨行业」从**知识库文件名不同**补成**运行时可隔离的行业维度 `Domain`**，贯穿租户解析、规则块、
入库 payload、检索过滤、缓存分区与负缓存。

- `knowledge/Domain`（`ECOMMERCE`/`HOTEL`/`UNKNOWN`，`ofIntent()`、`platformVisible()`）；
  `config/TenantDomainRegistry`（配置 `shoppilot.tenant-domains`，未登记 = `UNKNOWN` fail-closed）。
- `RuleChunk`/`MarkdownChunker`/`IngestRunner`：切分时带上 `domain`，Qdrant payload 与 ES document
  同步落 `domain`。
- `CacheEntry.matches(intent, tenant, domain, epoch)`；`CacheService` 平台桶键改为 `PLATFORM:<DOMAIN>`，
  `lookup/prepareWrite/writeNegative/markNegative` 全签名带 domain；`writeNegative` 只标本租户桶。
- `L2SemanticCache.search(vec, tenant, scope, intent, domain, epoch)`：payload filter 与索引键带 domain。
- `HybridRetriever.retrieve/diagnose(query, tenant, domain, intent)`：Qdrant + ES 都按 domain 过滤，
  **删掉空召回的 `intent=null` 放宽**；`visibleTenants()` 对 `UNKNOWN` fail-closed。
- `AgentStateMachine`：`run()` 内解析一次 domain，串到 triage/cache/retrieval/writeback/tools；
  `toolsFor(triage, domain)` 非电商域返回空列表；`OpsController` 的 `markNegative/diagnose` 同步带 domain。
- **活体正确性补丁**：`EsRestClient.ensureIndex` 改为「索引已存在也 `PUT /{index}/_mapping` 合并一次」——
  老索引缺 `domain` 映射时 ES 会动态映射成 `text`，`term` 过滤永远不命中（真机复现）；`StartupInitializer`
  的 Qdrant 规则集合 keywordFields 加 `domain`。

## Blocked by

[124](124-round33-adr-and-spec.md)。

## 口径

- 隔离 fail-closed：未登记 domain 的租户只看本租户规则，**不自动获得平台级电商政策**。
- 平台桶不再全局共享：同一问法在不同 domain 是两条缓存空间（`PLATFORM:ECOMMERCE` vs `PLATFORM:HOTEL`）。
- 空召回不再放宽意图：按意图过滤为空就返回空，宁可转人工，不拿另一个行业的条款回答。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/retrieval_gate.py
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：`CacheEntry.matches` 现在要求 `domain != null && domain == expectedDomain`——round33 之前
  写入的缓存条目一律 miss（有意的 fail-closed，不是缺陷）。检索可见范围从「本租户 + PLATFORM」变成
  「本租户 + 同行业 PLATFORM」；因为目前所有 PLATFORM 语料都是 `ECOMMERCE`，指纹口径与 round32 重合，
  一旦第一份 HOTEL 平台语料落地，`RetrievalFusionReplayTest` 与 `retrieval_gate.py` 的 `visible_to` 都要
  一起改成 domain-aware。
- **验证落点**：`CacheServiceTest`（domain 分区 / 跨 domain 不可见）、`HybridRetrieverDomainFilterTest`
  （过滤含 domain、空召回不放宽）、`L2SemanticCacheTest`、`TenantDomainRegistryTest`、
  `EsRestClientWireTest`（新建：索引存在时合并 mapping、body 含 domain）；全仓 `verify` 绿。
- **活体**：ES `policy_rules` 重建映射后 `term domain:HOTEL` 命中 18 条、`term domain:ECOMMERCE` 命中 93 条；
  T001/T004 同一句「押金什么时候退」分别落到 `dense=0`（转人工）与 `dense=18`（酒店押金条款），不再串答案。
- **三个现场追问**：
  1. 老索引靠 `PUT _mapping` 合并能补字段，但**老文档**（缺 domain）不会被删——本轮靠重入库覆盖同 ruleId；
     若删过语料文件，残留的旧 ruleId 没有 domain，是否要给 ingest 加「按新纪元清理」？
  2. Qdrant 规则集合的 keywordFields 加了 domain，但它是**性能索引**不是正确性门槛（未建索引也能过滤）——
     要不要在 flush/重建时显式校验字段索引齐全？
  3. `Domain.UNKNOWN` 租户 fail-closed 只看本租户规则——如果某个真实电商租户忘了登记 domain，会静默退化成
     「只看自己的店铺语料」，是否需要在 `/ops` 暴露未登记租户清单？
