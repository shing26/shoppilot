# 106 备份与恢复演练（PG，含大对象）

**Status:** implemented（2026-10-06）

## What to build

「部署形态没有备份/回滚」的 B2 缺口。PG 侧回滚的实现形态 = 从备份恢复（社区版 Flyway 无 undo；H2 侧有
`db/rollback/U1` 口径不变）——**演练过才算存在**。

- `scripts/backup-postgres.ps1`：`pg_dump -Fc` 两库（**大对象默认包含**——ADR 0061 §2b 的承诺在这里兑现），
  容器内写出 + `docker cp` 取回（二进制不过文本管道）；dump 自检：TOC 必须含 `BLOBS` 段，
  没有大对象进备份宁可失败也不留假备份。
- `scripts/verify-backup-restore.ps1`：完整灾难演练——建数据（账号 + 含大对象 transcript 的工单）→
  备份 → `DROP DATABASE … FORCE`（**服务不重启**）→ 重建空库（owner 回应用角色）→ `pg_restore` →
  **重放 CONNECT 授权** → 三层验证：API（同凭据认证同一 accountId / 工单原样）+ LOB 直读
  （`lo_get(transcript)` 逐字相等）+ 授权（跨库仍 FATAL）。
- **脚本即 runbook**：恢复手册就是演练脚本的第 4-6 步注释，不存在第二份文档。

## Blocked by

[104](104-credential-posture-guard-data-side.md)、[105](105-per-database-user-isolation.md)。

## 口径

- **不进验收矩阵**（28 步不动）：备份步依赖「持久档 + 容器栈」姿势，而 full 档还可能是本机 H2——
  硬塞进去让本机 full 必红。**触发**：矩阵按姿势分档或容器档成为唯一 full 形态。
- **服务跨恢复不重启是刻意的验收项**：Hikari 断线重连是投用形态的必答。
- **Redis 不备份**：审计事件已落 PG（票 71）、出站失败落回执工单（票 88），流尾丢失可重建——
  「丢得起」从 B1 的淘汰策略延伸到备份语义。**触发**：出现只存在于 Redis 的持久状态。
- 演练在库与 `backups/` 留 b2drill 痕迹与 dump 文件——是演练证据，不清理；`backups/` 已进 `.gitignore`。

## 验收

- 演练脚本全绿（PASS/FAIL 计数见 EVIDENCE B2 节）。
- `check-ps-syntax.ps1`（pwsh 7）对两个新脚本通过。

## Verify

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-backup-restore.ps1
```

## Handoff notes

- 演练读数与逐条 PASS 明细见 EVIDENCE B2 节；dump 文件留在 `backups/`（本机证据，不入库）。
- 两个实现坑：PowerShell 管道会重编码二进制——dump 全程 `docker exec` 写容器内 + `docker cp` 取回；
  DROP/CREATE/GRANT 每条单独 `-c`（合并进一个 `-c` 会进同一事务块，`WITH (FORCE)` 直接报错）。
