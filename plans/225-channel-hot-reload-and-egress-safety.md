# 票 225 契约：渠道配置热生效与出站安全

**状态**：2026-11-22 冻结（所有者指令开票）
**判定**：本票把「保存后重启」升级为可安全生效、失败可回滚，并补出站边界；
属新功能面，实现前必须先新增 ADR 0087 并 Accepted。

**基线**：`main` @ 开票时 HEAD；实现从独立分支开始，起点 SHA 由 Implementer 记录。

## 目标

1. 渠道配置保存 → 探测 → 原子切换；成功时旧飞书长连接停止、新连接接管，事件不重复。
2. 失败时保留 last-known-good；`restartRequired` 与实际生效状态一致。
3. 出站 SSRF 防护：scheme/host/IP/DNS/重定向逐跳校验，显式 allowlist。
4. 投递幂等跨网关重启仍有效；重试指数退避 + 抖动；耗尽落回执工单。
5. 管理面区分「已保存 / 已生效 / 需重启 / 失败回滚」，密钥掩码。

## Allowed paths

- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/channel/**`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/web/ChannelAdminController.java`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/config/**`（出站/重试配置）
- `shoppilot-gateway/src/main/resources/application*.yml`
- `shoppilot-biz-mock/src/main/java/**`（仅出站幂等所需持久化；若选 Redis 则不改库）
- `shoppilot-ticket/src/main/java/**`（仅接收 `CHANNEL_RECEIPT` 所需，不改变工单规则）
- `frontend-workspace/src/components/ChannelsView.vue` 与相关 API 类型
- `scripts/verify-channel-hot-reload.mjs`（新增）
- `docs/adr/0087-*.md`（新增）
- `docs/STATUS.md`、`README.md`、`docs/EVIDENCE.md`（只同步能力/测试口径）
- `plans/225-channel-hot-reload-and-egress-safety.md`
- `.scratch/shoppilot-mvp/issues/225-channel-hot-reload-and-egress-safety.md`
- `.scratch/shoppilot-mvp/README.md`

## Acceptance criteria

1. 保存合法配置后无需重启网关即可生效；新连接收到事件，旧连接不再收，事件不重复。
2. 保存非法配置返回明确错误并回滚；旧连接继续工作；`restartRequired=false` 只在真热生效时返回。
3. 若 SDK 无法安全关闭旧连接，产品必须诚实返回 `restartRequired=true`，且活体证据证明没有假热生效。
4. SSRF：`http://127.0.0.1`、RFC1918、link-local、`169.254.169.254`、IPv6 loopback、
   解析到私网的域名、重定向到私网的地址均被拒；allowlist 内公网地址可投递。
5. 出站请求不跟随未复检的重定向；DNS 重解析后再次校验 IP；错误不泄露内部网络细节。
6. 同一 `event_id` 在网关重启后仍只投递一次；重试间隔递增且有上限；
   耗尽后落 `CHANNEL_RECEIPT` 工单，结论不丢。
7. 管理面状态与实际一致；密钥只显示掩码；「测试连接」与「保存并生效」均可观测。
8. 221 的 ADMIN 专属渠道权限不回归；email 仍以回执工单交付，不假装发信。
9. 新增活体脚本覆盖合法热生效、非法回滚、SSRF 拒绝、跨重启幂等、重试间隔；
   证据绑定 HEAD SHA 与 `git status --short`。
10. 全仓 `mvnw verify`、坐席前端 typecheck/vitest/build、文档真值检查全绿。

## Verification commands

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am test
.\mvnw.cmd -B -ntp verify
cd frontend-workspace; npm run typecheck; npm test; npm run build
cd ..
node scripts/verify-channel-hot-reload.mjs
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
git diff --check
git status --short
```

## Forbidden

- 不做真实 SMTP 发信、微信/多平台接入。
- 不改退款/工单/知识业务规则，不改 221 的 ADMIN 渠道权限。
- 不把「保存成功」写成「已生效」；不隐藏回滚失败。
- 不放宽 SSRF 校验来让测试变绿；不跳过既有渠道/出站测试。
- 不在响应、日志或管理面回显明文密钥。
