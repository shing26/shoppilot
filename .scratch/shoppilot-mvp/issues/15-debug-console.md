# 15 — 单文件调试台

**What to build:** 打开网关自带页面就能换身份提问、逐字看到答案、看到状态机事件时间线、注入故障、查看工单队列。零构建链。

**Blocked by:** 14 — 降级原因枚举、工单落点与 local 模式验证

**Status:** ready-for-agent

**Verify:** 浏览器打开页面走完三条演示（串号防线、降级转人工、缓存命中）-> 时间线逐帧与状态机一致，故障注入经网关代理生效，DevTools 里看不到任何指向 `:8091` 的请求与内部 token。

- [ ] `gateway/src/main/resources/static/index.html`，不引 Node/Vite；用 `fetch()` + `ReadableStream` 手解 SSE 帧（`EventSource` 不支持 POST）
- [ ] 三栏布局：左身份、中对话、右时间线，底部故障注入条。替换 ticket 05 那个 60 行壳，但沿用同一套 SSE 解析代码，不重写第二份
- [ ] 左侧身份区：选店铺 + 选买家一键换 token，可看到当前 token 的 claims
- [ ] 中间对话流；右侧事件时间线逐帧显示 `meta` `status` `tool_executing` `tool_result` `slot_ask` `token` `done` `fallback` `rate_limited`
- [ ] 命中路径与未命中路径的推送形态不同（一次性 vs 打字机），时间线上要能区分
- [ ] 故障注入与工单队列一律经网关代理端点转发到 biz-mock，浏览器只与同源网关通信。不得让页面直连 `:8091`：那会把 `X-Internal-Token` 暴露进浏览器，且 biz-mock 只监听本机、跨 origin 必被 CORS 拦
- [ ] 底部故障注入面板：滑块调 `delayMs` / `failRate`，经代理生效
- [ ] 工单抽屉：只读列表 + 状态流转按钮，同样经代理拉取
- [ ] 布局稳定：事件文本长度不得把时间线挤变形

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
