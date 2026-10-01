# 71 事件骨干：Redis Streams 三 topic 族 + 消费组 ACK + 幂等消费

**Status:** implemented（2026-10-01）

## What to build

按 ADR 0054 落地事件面。**范围由所有者裁定收窄（2026-10-01，见 spec §0 的 C 与 D）**：

- **只建 `audit` 一条 topic**，生产端接本轮已存在的真实动作：退款放行/驳回（`reviewRefund`）、反馈复核完成（`markReviewed`）、规则表变更。消费端落审计查询与对账。
- `channel.outbound` 与 `ticket.created` **只落契约**（record + topic 名常量 + 事件 schema 版本号），不写生产端与消费端——它们目前**没有消费者**，为它们造代码是本仓明禁的 speculative generality。触发条件 = 真有下游（渠道出站落地、统计看板）。
- 投递语义是**至少一次** → 消费端幂等（幂等键 = 事件 id）。
- Streams 积压治理：给 `audit` 定 `MAXLEN`，超限丢弃发告警（复用 0051 的告警规则面）。
- `ticket.created` 虽不建，但**落单走 API、事件走 Streams 的分工要写进代码注释**：落单要返回值、要一致，所以走 API；事件只做异步通知。

## 0 token 门禁纪律（裁定 D：接口 + 测试用内存实现）

生产实现走 Spring Data Redis Streams；**另有一个内存实现只存在于测试包**，用来在 CI 上钉死「发布 → 消费 → ACK → 幂等 → pending 归零」这条链路的语义。两点纪律：

1. **内存实现不进生产包**（放测试源集），生产路径不可能误用它；
2. 「真实 Redis 上的行为」（ACK 时序、pending 积压、MAXLEN 丢弃）**本轮无法验证，照登不摘红**——CI 没有 Redis，本机也没有余量起全栈（裁定 A）。

**不得**为了让门禁绿而把生产实现换成内存实现。

## Blocked by

[69](69-unified-ticket-entity.md)、[70](70-routing-rules-and-priority.md)。

## 验收

- 审计事件能被查询端点按「动作 + 对象」列出（退款放行/驳回、复核完成、规则变更各至少一条可查）。
- 同一事件被投递两次，消费端只生效一次（唯一约束生效，有用例）。
- 事件 schema 带版本号；`MAXLEN` 生效且有丢弃计数。
- 内存实现与 Redis 实现跑**同一组**语义用例（同一份契约测试跑两遍），生产实现不是「只跑通就行」。
- 契约库里有另外两条 topic 的 record 与常量，但**没有**它们的实现（这一点有测试钉住：实现类不得出现）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

### 关键决策

1. **biz-mock 第一次依赖 Redis**（新增 `spring-boot-starter-data-redis`）。做成**软依赖**：`AuditChannel.available()` 为假时 `AuditService` 走直写落表。理由：退款放行是资金动作，**不能因为审计发不出去而失败**——审计重要，但没有它重要到让买家退不了款。
2. **消费者是惰性的**（读审计时收流，不跑后台线程），与票 70 的 SLA 打戳同一套理由：少一个线程池就少一处停机负担。
3. **落单走 API、事件走 Streams** 的分工写进了注释：落单要返回值、要一致，所以走 API；事件只做异步通知。这条决定了 `ticket.created` 与 `channel.outbound` 即使有契约也**不改成事件驱动**——它们要的是「拿到 id / 确认投递」，不是「等一会儿看结果」。
4. **幂等落在唯一索引，且必须在独立事务里做**（见下，这是本票踩得最深的一个坑）。
5. **规则表写入口在票 71 才开**（票 70 关掉的那笔）：能改但查不到谁改的，比不能改更糟。只允许**新增**与**启停**，不改匹配键与队列——改匹配键会让历史分派再也复算不出来。

### 踩到并修掉的两个真问题

1. **在 `@Transactional` 方法里 catch 约束冲突是没用的**（第一次实现就炸在这里）。约束冲突会把当前事务标成 rollback-only，**catch 不会清掉那个标志位**，方法正常返回后提交照样抛 `UnexpectedRollbackException`，整个审计查询端点 500。正确形状：写库放独立事务（`REQUIRES_NEW` + `saveAndFlush`），**让异常逃出事务边界**，由调用方 `catch`。三条用例同时红就是它暴露的。
2. **测试替生产代码去重，等于没测**。第一版的内存实现用 `handled` map 拦掉重投，于是「幂等靠 `event_id` 唯一索引」这句话**根本没被考验**——而真实 Redis Streams 本来就会重投未 ack 的消息。改成内存实现也照投、不去重，去重只留在唯一索引上；契约用例相应从「重投不再交付」改成「重投会再次交付」。改完立刻又暴露出第 1 条。

### 口径

- 判据面：gold 180、阈值、`judge()`、`verify_eval_judge.py`、CI 八步——**零改动**。
- **`X-Reviewer` 是调用方自报的，不是认证过的身份**（身份域是下一轮，裁定 F）。它进的是审计不是权限：带上内部 token 就能写这个头。**这条缺口照登**：补上真身份之前，审计只能证明「有人做了什么」，不能证明「是谁」。
- **`RoutingDispatchTest` 的一条用例随本票改写**：票 70 时它断言 `POST /api/routing-rules` 返回 405（写入口刻意不开），现在写入口开了、405 变 201。**这不是判据放宽，是被后续票有意改掉的行为**；换来的约束（每次写都带 audit 事件）由 `AuditEventFlowTest` 钉住，写入口仍受内部 token 守护也另有一条用例。

### 验证落点

- `.\mvnw.cmd -B -ntp verify` → **`5 + 57 + 308 = 370`** 绿（biz-mock 43 → 57，新增 `AuditChannelContractTest` 7 + `AuditEventFlowTest` 6 = 13）。
- 覆盖率：biz-mock **80.74% → 80.87%**、tool-api **47.95% → 45.16%**（新增契约 record，门槛 40.0 未动）、gateway 63.23%。
- **变异对照**（幂等路径是承重的）：把 `catch (DataIntegrityViolationException)` 换成抓不住的类型 → `duplicateDeliveryIsStoredOnce` 转红（端点 500），还原即绿。
- 用例覆盖：发布→消费保序、handler 拒绝留在 pending、**重投会再次交付**、通道不可用拒绝 publish、事件缺 id/action 构造即拒、**主源码只有 Redis 一个实现**（扫源码树，内存实现被挪进去就红）、退款放行/驳回留痕、反馈复核留痕、规则新增/启停留痕、重投不产生新行、通道挂了直写兜底仍可查、跨租户查不到审计。

### 未达成（照登不摘红）

- **真实 Redis 上的行为完全未验证**：ACK 时序、pending 积压、`MAXLEN` 裁剪、消费者组并发——一条都没跑到。CI 无 Redis，本机也不起全栈（裁定 A/D）。`RedisAuditChannel` 目前**零覆盖**，它在覆盖率读数里是一条没被走过的分支。
- 四服务起栈、浏览器断言、两档读数同属裁定 A 的未达成。

### 现场三问

1. **为什么 biz-mock 要依赖 Redis？** 因为审计的生产端在这里（退款审核、复核完成）。所以做成软依赖并配了直写兜底——新增依赖的当天就要想清楚「它挂了会怎样」，而不是等它挂。
2. **为什么消费者不跑后台线程？** 少一个线程池就少一处停机负担，而读审计时收流已经足够；跨进程那条路由 API 保证顺序，事件只负责通知。
3. **为什么幂等要开独立事务？** 因为在事务里 catch 约束冲突是个陷阱：标志位不会因为 catch 而清掉，提交照样炸。这个坑第一次实现就撞上了，是三条用例同时红暴露的。
