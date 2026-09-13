# 25 — REST 统一错误出口只管网关自产的错误

**What to build:** 网关自己说错话时只有一个形状：`code` / `message` / `traceId`；鉴权拒绝那一处与它共用同一个 writer；而下游透传回来的响应体逐字节不变，谁说的永远分得清。超长输入第一次能拿到可显示的校验文案。落实 ADR 0028。

**Blocked by:** 21 — 密钥与运维端点按绑定地址 fail-fast（要先有可辨的 `ops.disabled` 与 `ops.token_mismatch`）；24 — 四个坐标进 MDC（`traceId` 字段要有来源）

**Status:** done（落点见文末「收尾双轴审查」；双轴审账亦在文末）

**Verify:** 单测面逐类断错误形状 -> 一条用例钉「代理透传体字节级不变」-> 零额度、门禁全绿。

- [x] 一个 `@RestControllerAdvice` 收敛网关自产错误，形状固定为 `code` / `message` / `traceId`
- [x] status code 一律保持现状，一个都不改（改码是另一笔契约变更，本票不做）
- [x] 鉴权过滤器那处直写 response 的拒绝与 advice 出同一形状，并换掉「字符串拼 JSON 且不转义 message」的写法
- [x] 一条用例钉死代理透传的下游响应体逐字节不变，且能分辨是网关拒的还是下游拒的
- [x] 流式那套（八值 reason 加两层文案）一字未动
- [x] 校验失败的文案进 `message` 回带（前端回显是票 26，本票只保证有东西可显）
- [x] 运维失败的两个 code 与既有 403 文案对得上，不新增第三种含义
- [x] 全仓手搓错误体剩余处数由 grep 现算引用，文档里不写死数字（第十一轮 S10 的规矩）

## 承接（票 21 收尾双轴审查转过来的两条）

- **S5**：票 21 把运维 403 的响应体换成了 `{"code":...,"message":...}`，但**没有一条 HTTP 层的用例钉住这个 body**
  （`OpsAccessTest` 只测枚举本身，实跑那一次 403 的报文是一时活体读数，不许长期引用）。
  本票第一条勾落地时补一条 MockMvc/WebTestClient 用例：错令牌与开关关闭各自出 `code=ops.token_mismatch` /
  `code=ops.disabled`，且 body 就是 advice 那一套 `code`/`message`/`traceId` 形状。
- **S2**：「非回环绑定上仍用着仓库默认凭证」这句报错在 `DevDefaultsEnvironmentPostProcessor` 与
  `DevDefaultsConfiguration` 各写一份、措辞不同。第二道阻断不能删（它防的是绕过环境后置处理器的启动方式），
  但该把句子收成一个工厂方法，别让它长成第三种错误形状。

## 承接（票 23 收尾审账转过来的那条）

- **依赖弄残那一档下的一具裸 500**：`nodeps` 档（两个检索引擎指向空端口）下 `POST /api/v1/support/ops/cache/flush`
  返 `{"timestamp":...,"status":500,"error":"Internal Server Error","path":"/api/v1/support/ops/cache/flush"}` ——
  现场在 `logs/acceptance/fallback.log`（`verify-fallback.ps1` 的第一枪就是它）。这一枪是**网关自产**的：
  仓储层抛上来的 `RuntimeException` 没人接，于是走了 Spring Boot 的默认错误体。它恰好落在本票第一条勾的射程里
  （"网关自己说错话时只有一个形状"），而且是最能说明这一票值多少的一条：同一个失效在 `deps` 那一格已经说得清
  （`qdrant` DOWN），在 HTTP 面上却只剩一个 500。本票落地时把它收进 advice，形状 `code`/`message`/`traceId`，
  status code 仍按第二条勾保持现状不改。
  注：这条**不**是票 23 的 fail-open 被打破——`readiness` 在该档下实测仍 UP，放行谓词没变；
  变的只是一句错误该长什么样。
- [x] 零额度；审计项数保持 95

## 收尾双轴审查（fixed point = d8af4ae）

落点与票 24、票 26 共用一份 17/17，日志名与读数写在 README 的「票 24 / 票 25 / 票 26 共用落点」那一段。

**这一票买到了什么**：REST 面上「网关自己说错话」第一次只有一种形状，且这形状不改任何状态码。
改之前同一个失败在三个地方长三种样子：鉴权那处是手拼的 `{"error":"..."}`（message 里出现引号就产出一具
非法 JSON），运维那处是 `Map.of("error", ...)`，没人接的异常干脆是 Spring 默认错误体（`timestamp` / `path`
两样白占着，`code` 一位没有）。更要紧的是「谁拒的」在报文上分不开，而全仓的降级判断都吃这个信息；
所以这一票既要统一形状，又必须逐字节不动透传体。改完之后：400 带着可显示的中文文案出来，前端第一次
有东西可显（票 26 那半收了这笔）；运维 403 的 body 由 HTTP 层用例钉住（票 21 的 S5 自此有格子）；
两道启动阻断的那句话收成一个工厂方法（票 21 的 S2）。

**Spec 轴（逐勾取证，判据在哪、读数是什么）**：

- 勾 1：三键由 `ApiError` 一定义，`ApiErrorWriter` 一支笔落笔，`GatewayErrorHandler` 是唯一出口。
  用例：`RestErrorEnvelopeTest#envelopeShapeDoesNotDependOnTrace`（形状不随链路号有无而变）、
  `#messageIsSerialisedNotConcatenated`（message 里带引号与换行时仍是合法 JSON，且能被读回来）。
- 勾 2：**这一勾第一轮审查判早了**，见下面 S1；现在的形状是「入参异常的状态码由 `ResponseEntityExceptionHandler`
  判，本类只换形状」，`codeFor` 只做 4xx / 5xx 两分，不写死数字。三格并排钉住：`#validationFailureReturnsReadableMessage`
  （400 仍是 400）、`#springStandardErrorsKeepTheirCode`（405 仍是 405）、`#unreadableBodyKeepsItsStatusNotJustAnyStatus`
  （请求体读不出来仍是 400，且 `code=invalid_request` 而不是 `internal_error`）。
- 勾 3：`AuthFilter.reject` 改成 `errors.write(...)`，构造签名带进 `ApiErrorWriter`；拼字符串那一句不再存在，
  证据是同一条 `messageIsSerialisedNotConcatenated` 在 400 与 401 两支上都能把 body 解成 JSON。
  用例：`AuthFilterTest` 从 8 涨到 9 的那一条断 401 的 body 就是那一套三键。
- 勾 4：`#proxyBodyIsByteForByte` 拿下游原体逐字节比（含下游自己的 4xx/5xx），
  `#gatewayVersusDownstreamRejection` 同时打网关拒的一发与下游拒的一发，断两者的 `code` 可分辨。
  变异：把透传那一支改成「重新包一层 + 处理器指错对象」，这三条当场判红；复原复验为绿。
- 勾 5：流式那八值 reason 与两层文案一字未动——本票在 `ChatController` 的改动只有 `ChatRequest` 那两行
  约束注解加了中文 `message`，`git show --stat` 里那一档是 5 行；`Accept: text/event-stream` 那一支另有一格
  （`#streamEndpointSendsTheSameEnvelopeForInvalidInput`）钉「400 的 JSON 信封不被换成 406」，
  否则票 26 那条回显断言会空转。
- 勾 6：`#blankQueryHasItsOwnMessage` 与 `#validationFailureReturnsReadableMessage` 各钉一条文案；
  活体读数在落点那次实跑里（同一枚 token 打 REST：空问句 400 `问题不能为空`、600 字 400 `问题太长，上限 500 字…`）。
- 勾 7：`#opsTokenMismatchPinsBody`、`#opsDisabledKeepsItsOwnCode` 两条把 403 的 body 钉在 HTTP 层，
  code 沿用票 21 那两个（`ops.token_mismatch` / `ops.disabled`），本票一个新 code 都没加。
- 勾 8：剩余处数按 `rg -n '"\{\\"(error|code)' shoppilot-gateway/src/main/java` 现算，文档不写死数字。
  现算结果里唯一还在 REST 面上的是检索探针那条「200 带 error 键」的降级返回；不换它形状的理由写在 README
  落点段（换成 `code=internal_error` 而状态码仍是 200，等于新造一种没人要的含义）。
- 勾 9：全程 `local` 档，`ollama` 打底，零付费额度；审计项数仍是 95，本票没新增审计项。

**Standards 轴：报 6 → 成立已修 1、成立接受 3、报出后不成立 2**。

- S1 [P1] **catch-all 把 Spring 判 400 的入参异常吸成 500**（成立，已修，落点 commit 里那一笔 `fix(round13)`）。
  第一版的边界写在注释里：「Spring MVC 那批 4xx 全是 `ServletException` 的后代，只接 `RuntimeException` 就不碰它们」。
  这句话对 405 成立，对 `HttpMessageNotReadableException` 不成立——它是 `NestedRuntimeException` 的后代，
  于是「请求体读不出来」从 400 变成 500，还带着 `internal_error` 那句去冤枉网关自己。
  **这条是本票自己新造的缺陷**：改之前那一枪是 400，改之后是 500，正好撞在第二条勾上。
  取证方式也说明第一轮审查为什么漏它：变异只量了「把 advice 放宽到 `Exception.class`」这一支（405 当场判红），
  而第二条勾是个合取谓词（405 与 400 两族都要保住），只量一族等于只量半条勾。处置是改成继承
  `ResponseEntityExceptionHandler`，补的那格 `#unreadableBodyKeepsItsStatusNotJustAnyStatus` 在旧版上实测
  `expected: 400` 判红、新版转绿；405 与 500 那两格同批复跑仍绿。
- S2 [P2] filter 那一支的 `Content-Type` 带 `;charset=UTF-8`、advice 那一支不带。成立但接受：两处的字节与
  形状都一致，差异只在 header 文本；要抹平就得让 writer 依赖 servlet 的 response 构造，比留着不值。
- S3 [P3] `codeFor` 的两分法会把 Spring 判出的 406 / 415 一律归进 `invalid_request`。成立并接受：
  本票明写不新增第六种 code；真出现需要分辨的那一档，改的是这张 code 表，不是给客户端回英文话术。
- S4 [P3] `onUnexpected` 打整栈日志，高频缺陷会把日志刷爆。成立并接受：那正是该响的地方，
  落盘量的失控由票 24 的轮转两格管着（按大小与按时间）。
- 报出后不成立 1：「代理透传体被 advice 二次包装」——不成立，透传那一支根本不经 advice，
  勾 4 那两条是字节级断言而不是形状断言。
- 报出后不成立 2：「`getField()` 拼中文文案会让前端无法解析」——不成立，票 26 那条回显断言实测读回的
  整句就是 `query：问题太长，上限 500 字，请精简后再问`。

**转票**：无新增待办。票 26 拿走的是本票已经备好的两样东西：400 报文里的 `message`，和 403 报文里的 `code`。

