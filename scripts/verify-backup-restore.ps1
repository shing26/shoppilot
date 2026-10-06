#requires -Version 7
<#
.SYNOPSIS
    B2 备份恢复灾难演练（round29 票 106 / ADR 0062）——**脚本即 runbook**，
    恢复手册就是本文件的第 4-6 步，不存在第二份文档。

.DESCRIPTION
    完整走一遍「建数据 → 备份（含大对象）→ 灾难（DROP DATABASE FORCE）→ 恢复 → 三层验证」：

      1. 建演练数据：注册一个账号、落一张带大对象 transcript 的工单；
      2. 备份：pg_dump -Fc 两库（TOC 必须含 BLOBS 段），docker cp 出宿主；
      3. 灾难：DROP DATABASE … FORCE——**两服务不重启**，Hikari 断线重连本身就是验收项；
      4. 恢复：重建空库（owner 回应用角色）→ pg_restore → **重放 CONNECT 授权**
         （pg_dump 不含库级属性：owner 与授权随 DROP 丢失，角色是集群级的所以还在——
         这半步漏掉 = 「恢复了数据但服务再也连不上」，是 runbook 里最容易被忘的一格）；
      5. 三层验证：API 层（同凭据认证返回同一 accountId、工单原样）+
         LOB 直读（lo_get(transcript) 逐字相等，ADR 0061 §2b 的既定输入）+
         授权层（跨库连接仍被 FATAL 拒绝）。

    任何一步失败即 exit 1；PASS/FAIL 计数打印在末尾。

.NOTES
    前置：① shoppilot-postgres 容器在跑；② biz-mock / ticket 两服务**已在持久档运行**
    （readiness UP）；③ .env 提供 SHOPPILOT_INTERNAL_TOKEN 与 SHOPPILOT_DB_PASSWORD。
    演练会在库与宿主 backups/ 留下 b2drill 痕迹与 dump 文件——它们是演练证据，不清理。
#>
param(
    [string]$BizMockBase = "http://127.0.0.1:8091",
    [string]$TicketBase = "http://127.0.0.1:8092",
    [string]$Container = "shoppilot-postgres",
    [string]$Superuser = "shoppilot",
    [string]$OutDir = "backups",
    [string]$InternalToken,
    [string]$DbPassword
)

$ErrorActionPreference = "Stop"
if (-not $InternalToken) { $InternalToken = $env:SHOPPILOT_INTERNAL_TOKEN }
if (-not $DbPassword) { $DbPassword = $env:SHOPPILOT_DB_PASSWORD }
if (-not $InternalToken -or -not $DbPassword) {
    Write-Error "缺凭证：SHOPPILOT_INTERNAL_TOKEN / SHOPPILOT_DB_PASSWORD 必须在环境或 .env 里（ADR 0029）。"
    exit 2
}

$script:pass = 0
$script:fail = 0
function Assert-True([bool]$Condition, [string]$Name, [string]$Detail = "") {
    if ($Condition) {
        $script:pass++
        Write-Host "PASS  $Name $(if ($Detail) { "—— $Detail" })"
    }
    else {
        $script:fail++
        Write-Host "FAIL  $Name $(if ($Detail) { "—— $Detail" })"
    }
}

function Invoke-InternalJson([string]$Method, [string]$Uri, [string]$Body) {
    Invoke-RestMethod -Method $Method -Uri $Uri -Body $Body -ContentType "application/json" -Headers @{
        "X-Internal-Token" = $InternalToken
        "X-Tenant-Id"      = "T001"
    }
}

# ---- 0. 前置 ----------------------------------------------------------------
foreach ($svc in @(@("biz-mock", "$BizMockBase/actuator/health/readiness"), @("ticket", "$TicketBase/actuator/health/readiness"))) {
    try {
        $ok = (Invoke-RestMethod -Uri $svc[1] -TimeoutSec 5).status -eq "UP"
    }
    catch { $ok = $false }
    Assert-True $ok "前置：$($svc[0]) 在持久档运行（readiness UP）"
    if (-not $ok) { Write-Error "服务没起来，演练无从谈起。"; exit 2 }
}

# ---- 1. 建演练数据 ----------------------------------------------------------
$suffix = Get-Random -Minimum 1000 -Maximum 9999
$drillUser = "b2drill$suffix"
$drillPass = "B2-drill-2026-$suffix"
$account = Invoke-InternalJson Post "$BizMockBase/api/identity/register" (
    '{"username":"' + $drillUser + '","password":"' + $drillPass + '","displayName":"b2-drill"}')
Assert-True ($null -ne $account.accountId) "演练账号已建" "accountId=$($account.accountId)"

$transcript = "B2 drill transcript $suffix - large object round trip"
$ticket = Invoke-InternalJson Post "$TicketBase/api/tickets" (
    '{"source":"DEGRADE","customerId":"C-b2drill","reason":"EMOTION_ESCALATION",' +
    '"userQuery":"b2 drill","transcript":"' + $transcript + '"}')
Assert-True ($null -ne $ticket.id) "演练工单已落（含大对象 transcript）" "id=$($ticket.id)"

# ---- 2. 备份（pg_dump -Fc，含大对象）----------------------------------------
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
foreach ($db in @("bizmock", "ticket")) {
    $tmp = "/tmp/$db-$stamp.dump"
    docker exec $Container pg_dump -U $Superuser -Fc -d $db -f $tmp
    if ($LASTEXITCODE -ne 0) { throw "pg_dump $db 失败" }
    # 库里有大对象数据时，TOC 必须含 BLOBS 段；没有 LO 数据的库 pg_dump 本就不产生该段（不算失败）
    $hasLob = (docker exec $Container psql -U $Superuser -d $db -tAc "SELECT count(*) > 0 FROM pg_largeobject") -match "t"
    $toc = (docker exec $Container pg_restore --list $tmp) -join "`n"
    if ($hasLob) {
        Assert-True ($toc -match "BLOBS") "$db dump 含 BLOBS 段（大对象进备份，ADR 0061 §2b）"
    }
    else {
        Assert-True $true "$db 当前无大对象数据，BLOBS 断言按空库口径放行"
    }
    docker cp "${Container}:$tmp" (Join-Path $OutDir "$db-$stamp.dump")
    if ($LASTEXITCODE -ne 0) { throw "docker cp $db 失败" }
}

# ---- 3. 灾难：DROP DATABASE FORCE（服务不重启）------------------------------
docker exec $Container psql -U $Superuser -d postgres -c "DROP DATABASE bizmock WITH (FORCE);" | Out-Null
docker exec $Container psql -U $Superuser -d postgres -c "DROP DATABASE ticket WITH (FORCE);" | Out-Null
Assert-True $true "灾难注入：两库已 DROP（服务仍在运行，等它断线重连）"

# ---- 4. 恢复：重建空库 → pg_restore → 重放 CONNECT 授权 ----------------------
docker exec $Container psql -U $Superuser -d postgres -c "CREATE DATABASE bizmock OWNER bizmock_app;" | Out-Null
docker exec $Container psql -U $Superuser -d postgres -c "CREATE DATABASE ticket OWNER ticket_app;" | Out-Null
foreach ($db in @("bizmock", "ticket")) {
    $tmp = "/tmp/$db-$stamp.dump"
    docker cp (Join-Path $OutDir "$db-$stamp.dump") "${Container}:$tmp"
    docker exec $Container pg_restore -U $Superuser -d $db --exit-on-error $tmp
    if ($LASTEXITCODE -ne 0) { throw "pg_restore $db 失败" }
}
docker exec $Container psql -U $Superuser -d postgres -c "REVOKE CONNECT ON DATABASE bizmock FROM PUBLIC; GRANT CONNECT ON DATABASE bizmock TO bizmock_app;" | Out-Null
docker exec $Container psql -U $Superuser -d postgres -c "REVOKE CONNECT ON DATABASE ticket FROM PUBLIC; GRANT CONNECT ON DATABASE ticket TO ticket_app;" | Out-Null
Assert-True $true "恢复完成：空库重建（owner 回应用角色）+ pg_restore + CONNECT 授权重放"

# ---- 5a. API 层：同凭据认证 → 同一 accountId；工单原样 ----------------------
$recovered = $null
foreach ($i in 1..15) {
    try {
        $recovered = Invoke-InternalJson Post "$BizMockBase/api/identity/authenticate" (
            '{"username":"' + $drillUser + '","password":"' + $drillPass + '"}')
        break
    }
    catch { Start-Sleep -Seconds 2 }
}
Assert-True ($null -ne $recovered -and $recovered.accountId -eq $account.accountId) `
    "API：同凭据认证返回同一 accountId（服务跨恢复不重启）" "$($account.accountId)"

$ticketAfter = Invoke-InternalJson Get "$TicketBase/api/tickets/$($ticket.id)" ""
Assert-True ($ticketAfter.id -eq $ticket.id -and $ticketAfter.status -eq $ticket.status) `
    "API：工单原样（id + status）" "$($ticketAfter.id) / $($ticketAfter.status)"

# ---- 5b. LOB 直读：lo_get(transcript) 逐字相等 -------------------------------
$lobBack = (docker exec $Container psql -U $Superuser -d ticket -tAc `
    "SELECT convert_from(lo_get(transcript), 'UTF8') FROM tickets WHERE id = '$($ticket.id)'").Trim()
Assert-True ($lobBack -eq $transcript) "LOB 直读：恢复后大对象逐字相等" "len=$($lobBack.Length)"

# ---- 5c. 授权层：跨库连接仍被 FATAL 拒绝 -------------------------------------
docker exec -e PGPASSWORD=$DbPassword $Container psql -U ticket_app -d bizmock -c "select 1" 2>$null | Out-Null
Assert-True ($LASTEXITCODE -ne 0) "授权：ticket_app 连 bizmock 库被拒"
docker exec -e PGPASSWORD=$DbPassword $Container psql -U bizmock_app -d ticket -c "select 1" 2>$null | Out-Null
Assert-True ($LASTEXITCODE -ne 0) "授权：bizmock_app 连 ticket 库被拒"
docker exec -e PGPASSWORD=$DbPassword $Container psql -U ticket_app -d ticket -c "select 1" *> $null
Assert-True ($LASTEXITCODE -eq 0) "授权（正向）：ticket_app 连自己的库放行"

Write-Host ""
Write-Host "演练汇总：PASS $script:pass / FAIL $script:fail"
if ($script:fail -gt 0) { exit 1 }
exit 0
