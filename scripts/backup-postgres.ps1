#requires -Version 7
<#
.SYNOPSIS
    持久档（PostgreSQL）备份——B2 / ADR 0062。脚本即 runbook：本文件的步骤就是恢复手册的一半，
    另一半（恢复）在 verify-backup-restore.ps1，两处不存在第二份文档。

.DESCRIPTION
    对 bizmock 与 ticket 两库各出一份 pg_dump -Fc（自定义格式，**大对象默认包含**——
    ADR 0061 §2b 的承诺在这里兑现：transcript / payload / rule_ids 是 oid，pg_dump -Fc 会把它们
    连同表数据一起收进 dump）。二进制安全：dump 在容器内写出，docker cp 取回宿主，不过任何文本管道。

.EXAMPLE
    .\scripts\backup-postgres.ps1                      # 备份到 backups\<db>-<时间戳>.dump
    .\scripts\backup-postgres.ps1 -OutDir D:\bk        # 自定输出目录

.NOTES
    前置：shoppilot-postgres 容器在跑（中间件档即可，不需要服务）。
#>
param(
    [string]$Container = "shoppilot-postgres",
    [string]$Superuser = "shoppilot",
    [string]$OutDir = "backups"
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

foreach ($db in @("bizmock", "ticket")) {
    $tmp = "/tmp/$db-$stamp.dump"
    docker exec $Container pg_dump -U $Superuser -Fc -d $db -f $tmp
    if ($LASTEXITCODE -ne 0) { throw "pg_dump $db 失败（exit $LASTEXITCODE）" }

    # dump 自检：TOC 清单里必须有 BLOBS 段——大对象真进了备份的证据（本仓 tickets.transcript 非空，
    # 每张工单一行大对象；没有 BLOBS 说明 dump 方式错了，宁可失败也不留假备份）
    $toc = docker exec $Container pg_restore --list $tmp
    if (-not ($toc | Select-String "BLOBS")) {
        docker exec $Container rm -f $tmp
        throw "$db 的 dump 不含 BLOBS 段——大对象没进备份，拒绝产出假备份（ADR 0061 §2b）"
    }

    $out = Join-Path $OutDir "$db-$stamp.dump"
    docker cp "${Container}:$tmp" $out
    if ($LASTEXITCODE -ne 0) { throw "docker cp $db 失败" }
    docker exec $Container rm -f $tmp

    $size = [math]::Round((Get-Item $out).Length / 1KB, 1)
    Write-Host "OK  $db -> $out（$size KB，含 BLOBS）"
}
Write-Host "恢复步骤见 scripts/verify-backup-restore.ps1 的头部注释（同一份 runbook 的另一半）。"
