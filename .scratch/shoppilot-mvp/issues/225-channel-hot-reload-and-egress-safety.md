# 票 225：渠道配置热生效与出站安全

Round: 56（所有者 2026-11-22 指令；按 224 → 225 → 226 顺序）
ADR: 0087（实现前新增并 Accepted；开票依据 = 所有者政策覆盖）
Status: ready-for-agent　Claimed by: 无　Blocked by: 无（按所有者指定顺序排在 224 后）
契约：[plans/225-channel-hot-reload-and-egress-safety.md](../../../plans/225-channel-hot-reload-and-egress-safety.md)（2026-11-22 冻结）

## What to build

把「保存渠道配置后重启网关」升级为可安全生效、失败可回滚，并补齐出站边界：

1. 配置保存 → 探测 → 原子切换；成功时旧飞书长连接停止、新连接接管，不重复消费事件；
   失败时保留 last-known-good 并返回明确错误。
2. `restartRequired` 只在确实无法热切换时为 true；管理面区分
   「已保存 / 已生效 / 需重启 / 失败回滚」。
3. 出站 SSRF 防护：只允许 http/https；拒绝 loopback、私网、link-local、云元数据地址；
   DNS 重解析与重定向逐跳复检；支持显式 allowlist。
4. 投递幂等与重试：去重键落 Redis/DB，跨网关重启仍生效；指数退避 + 抖动；
   超过上限落 `CHANNEL_RECEIPT` 工单。
5. 管理面：密钥只显示掩码；「测试连接」与「保存并生效」是两个可验证动作。

## 边界

- 不做真实 SMTP 发信；email 继续以回执工单交付。
- 不做微信/多平台接入；只处理飞书与 webhook 出站。
- 不改退款、工单、知识业务规则；不改 221 的 ADMIN 专属渠道权限。
- 不假装热生效：SDK 无法安全关闭旧连接时，必须降级为「需重启」并留证据。

## Verify

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am test
.\mvnw.cmd -B -ntp verify
cd frontend-workspace; npm run typecheck; npm test; npm run build
cd ..
node scripts/verify-channel-hot-reload.mjs
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
git diff --check
git status --short
```

活体脚本至少覆盖：保存合法配置 → 不重启生效 → 新连接收到事件/旧连接不再收；
保存非法配置 → 回滚且旧连接仍可用；私网/元数据/重定向 SSRF 被拒；
同一事件在网关重启后只投递一次；重试间隔递增。

## 验收项

- 合法配置无需重启即可生效；旧连接停止，新连接接管，事件不重复。
- 非法配置返回明确错误并回滚；last-known-good 继续工作；`restartRequired` 与实际一致。
- SSRF：loopback、RFC1918、link-local、`169.254.169.254`、IPv6 loopback 与重定向到私网均被拒；
  allowlist 内公网地址可投递。
- 去重跨进程重启仍有效；重试间隔递增且有上限；耗尽后落回执工单。
- 管理面不泄露密钥；状态文案与实际生效状态一致。
- JVM、坐席前端 typecheck/vitest/build、全仓 verify 与活体脚本全绿。

## Handoff notes

**关键决策**

- 待实现后补；实现前先新增 ADR 0087，冻结连接生命周期、SSRF 判定与重试上限。

**验证落点**

- 待实现后补；证据绑定 HEAD SHA 与 `git status --short`。

**三个现场追问**

1. 飞书 SDK 若没有安全关闭语义，是否接受「需重启」作为正式产品状态，还是换连接实现？
2. 出站 allowlist 由环境变量、配置文件还是管理面维护？默认是否为空（全拒）？
3. email 回执工单是否足够，还是本期必须接 SMTP？
