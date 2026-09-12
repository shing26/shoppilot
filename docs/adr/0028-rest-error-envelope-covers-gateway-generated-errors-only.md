# REST 统一错误出口只管网关自产错误，代理透传体保持原样

Context: 评估快照（`docs/prod-readiness-2026-09-12.md`）批 REST 侧零 `@RestControllerAdvice`、若干处手搓错误体。这里不抄份数：错误体散在 `ResponseEntity.status(` 那些调用点与 `AuthFilter.reject()` 直写 response 的那一处里，具体几处由全仓 grep 现算为准——票 25 一落地这个数必然变小，抄进 ADR 就是下一轮的过期自述。两件事值得单独记：其一，那几处 403 只有两个文案（`ops endpoint disabled` 与 `invalid or disabled ops token`）却同出一个 `requireOps` 布尔，所以「同一令牌错误两种文案」的成因不止那个布尔，文案本身是按端点各写一遍；其二，filter 跑在 DispatcherServlet 之前，advice 天生看不见它那一处。这些文案在全仓没有任何 assert 面，前端也不读 REST 错误体（签发失败只看 status 码，运维行的文案是前端自己传的），改形状不必先拆谁的依赖。

决定：advice 只统一网关自己产生的错误，形状是 `code` / `message` / `traceId` 三个字段，`traceId` 取 ADR 0027 那个 MDC 键；鉴权拒绝那一处与 advice 共用同一个 writer，出同一形状。status code 一律保持现状。代理透传的下游体原样出。SSE 那套八值 reason 加两层文案不动。

## Considered Options

- 给代理透传也套一层统一外壳：否决。本仓的降级判断全建立在能分辨「是网关拒的还是下游拒的」，包一层恰好把这个信息抹平，而它是 ADR 0003 与 0018 那些取向的前提。
- 顺手统一 status code：否决。门禁与前端按码断言，改码是另一笔契约变更。

## Consequences

- REST 侧从此永久并存两种形状，且这个不一致是有意的：网关自产的走 `code` / `message` / `traceId`，下游透传的原样出。文档必须写明边界在哪，否则下一轮会把它当遗漏修掉。
- `reject()` 那处用字符串拼 JSON 且不对 message 做转义，本轮一并换掉。它是拼出来的形状，不是设计出来的形状。
- 400 的校验文案进 `message` 回带，前端才有东西可显示；前端那一半留票 26。
