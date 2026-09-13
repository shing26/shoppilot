# 25 — REST 统一错误出口只管网关自产的错误

**What to build:** 网关自己说错话时只有一个形状：`code` / `message` / `traceId`；鉴权拒绝那一处与它共用同一个 writer；而下游透传回来的响应体逐字节不变，谁说的永远分得清。超长输入第一次能拿到可显示的校验文案。落实 ADR 0028。

**Blocked by:** 21 — 密钥与运维端点按绑定地址 fail-fast（要先有可辨的 `ops.disabled` 与 `ops.token_mismatch`）；24 — 四个坐标进 MDC（`traceId` 字段要有来源）

**Status:** ready-for-agent

**Verify:** 单测面逐类断错误形状 -> 一条用例钉「代理透传体字节级不变」-> 零额度、门禁全绿。

- [ ] 一个 `@RestControllerAdvice` 收敛网关自产错误，形状固定为 `code` / `message` / `traceId`
- [ ] status code 一律保持现状，一个都不改（改码是另一笔契约变更，本票不做）
- [ ] 鉴权过滤器那处直写 response 的拒绝与 advice 出同一形状，并换掉「字符串拼 JSON 且不转义 message」的写法
- [ ] 一条用例钉死代理透传的下游响应体逐字节不变，且能分辨是网关拒的还是下游拒的
- [ ] 流式那套（八值 reason 加两层文案）一字未动
- [ ] 校验失败的文案进 `message` 回带（前端回显是票 26，本票只保证有东西可显）
- [ ] 运维失败的两个 code 与既有 403 文案对得上，不新增第三种含义
- [ ] 全仓手搓错误体剩余处数由 grep 现算引用，文档里不写死数字（第十一轮 S10 的规矩）

## 承接（票 21 收尾双轴审查转过来的两条）

- **S5**：票 21 把运维 403 的响应体换成了 `{"code":...,"message":...}`，但**没有一条 HTTP 层的用例钉住这个 body**
  （`OpsAccessTest` 只测枚举本身，实跑那一次 403 的报文是一时活体读数，不许长期引用）。
  本票第一条勾落地时补一条 MockMvc/WebTestClient 用例：错令牌与开关关闭各自出 `code=ops.token_mismatch` /
  `code=ops.disabled`，且 body 就是 advice 那一套 `code`/`message`/`traceId` 形状。
- **S2**：「非回环绑定上仍用着仓库默认凭证」这句报错在 `DevDefaultsEnvironmentPostProcessor` 与
  `DevDefaultsConfiguration` 各写一份、措辞不同。第二道阻断不能删（它防的是绕过环境后置处理器的启动方式），
  但该把句子收成一个工厂方法，别让它长成第三种错误形状。
- [ ] 零额度；审计项数保持 95
