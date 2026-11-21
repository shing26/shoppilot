#requires -Version 7
<#
.SYNOPSIS
    票 220：Postgres 持久化档验收。

.DESCRIPTION
    只做验收，不改业务代码：
      1. 核对 biz-mock / ticket 的 postgres profile 与 readiness；
      2. 通过网关注册唯一买家，创建一张绑定该买家的工单；
      3. 重启 gateway / biz-mock / ticket，验证同一账号与工单仍可读；
      4. 调用既有 scripts/verify-backup-restore.ps1 做备份、灾难、恢复演练；
      5. 恢复后不重启服务，再次读回同一账号与工单。

.NOTES
    前置：容器 full 档已起，.env 提供 SHOPPILOT_INTERNAL_TOKEN 与
    SHOPPILOT_DB_PASSWORD；本脚本会重启三个服务容器并执行数据库灾难演练。
#>
param(
    [string]$GatewayBase = "http://127.0.0.1:8082",
    [string]$BizMockBase = "http://127.0.0.1:8091",
    [string]$TicketBase = "http://127.0.0.1:8092",
    [string]$TenantId = "T001",
    [string]$GatewayContainer = "shoppilot-gateway-1",
    [string]$BizMockContainer = "shoppilot-biz-mock-1",
    [string]$TicketContainer = "shoppilot-ticket-1",
    [string]$OutDir = "backups",
    [switch]$SkipRestart,
    [switch]$SkipBackupRestore
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "lib-launch.ps1")

function Import-RepoEnv {
    $values = Get-ShoppilotDotEnv -Root $root
    foreach ($key in @("SHOPPILOT_INTERNAL_TOKEN", "SHOPPILOT_DB_PASSWORD")) {
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key)) -and $values.ContainsKey($key)) {
            [Environment]::SetEnvironmentVariable($key, $values[$key], "Process")
        }
    }
    foreach ($key in @("SHOPPILOT_INTERNAL_TOKEN", "SHOPPILOT_DB_PASSWORD")) {
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key))) {
            throw "缺少 $key：请在仓库根 .env 或当前进程环境中设置（脚本不会打印 secret）。"
        }
    }
}

function Assert-True([bool]$Condition, [string]$Name, [string]$Detail = "") {
    if ($Condition) {
        $script:pass++
        Write-Host "PASS  $Name$(if ($Detail) { " —— $Detail" })"
    }
    else {
        $script:fail++
        Write-Host "FAIL  $Name$(if ($Detail) { " —— $Detail" })"
    }
}

function Wait-Ready([string]$Name, [string]$Uri, [string]$Container = "", [int]$Attempts = 90) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            if ($Container) {
                $raw = & docker exec $Container curl -fsS $Uri 2>$null
                if ($LASTEXITCODE -eq 0 -and (($raw | ConvertFrom-Json).status -eq "UP")) { return $true }
            }
            else {
                $body = Invoke-RestMethod -Method Get -Uri $Uri -TimeoutSec 3
                if ($body.status -eq "UP") { return $true }
            }
        }
        catch {
            # 服务重启期间连接拒绝是预期状态；等下一轮。
        }
        Start-Sleep -Seconds 2
    }
    Write-Host "TIMEOUT  $Name readiness 未在 $($Attempts * 2) 秒内恢复"
    return $false
}

function Invoke-Json([string]$Method, [string]$Uri, [hashtable]$Headers = @{}, $Body = $null) {
    $params = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Compress -Depth 8)
    }
    Invoke-RestMethod @params
}

function Invoke-ContainerJson([string]$Container, [string]$Method, [string]$Uri,
                             [hashtable]$Headers = @{}, $Body = $null) {
    $dockerArgs = @("exec", "-i", $Container, "curl", "-fsS", "-X", $Method, $Uri)
    foreach ($key in $Headers.Keys) {
        $dockerArgs += @("-H", "$key`: $($Headers[$key])")
    }
    if ($null -ne $Body) {
        $dockerArgs += @("-H", "Content-Type: application/json", "--data-binary", "@-")
        $json = $Body | ConvertTo-Json -Compress -Depth 8
        $raw = $json | & docker @dockerArgs
    }
    else {
        $raw = & docker @dockerArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "docker exec curl 失败: $Method $Uri" }
    return ($raw | ConvertFrom-Json)
}

function Get-ContainerEnv([string]$Container) {
    $lines = @(docker inspect $Container --format '{{range .Config.Env}}{{println .}}{{end}}')
    if ($LASTEXITCODE -ne 0) { throw "docker inspect $Container 失败" }
    return $lines
}

function Get-MineTicket([string]$Base, [string]$Token, [string]$TicketId) {
    Invoke-Json -Method Get -Uri "$Base/api/v1/support/ops/tickets/mine/$TicketId" `
        -Headers @{ Authorization = "Bearer $Token" }
}

$script:pass = 0
$script:fail = 0
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$evidenceLog = Join-Path $logDir "persistence-tier-220-$stamp.log"

try {
    Import-RepoEnv

    Write-Host "== 0. 容器与持久档 profile =="
    foreach ($pair in @(
        @($BizMockContainer, "biz-mock"),
        @($TicketContainer, "ticket")
    )) {
        $envLines = Get-ContainerEnv $pair[0]
        Assert-True ($envLines -contains "SPRING_PROFILES_ACTIVE=postgres") `
            "$($pair[1]) 使用 postgres profile" $pair[0]
        Assert-True ($envLines -contains "SHOPPILOT_DB_HOST=postgres") `
            "$($pair[1]) 连接 compose postgres" $pair[0]
    }

    Assert-True (Wait-Ready "biz-mock" "http://127.0.0.1:8091/actuator/health/readiness" $BizMockContainer 30) "biz-mock readiness UP"
    Assert-True (Wait-Ready "ticket" "http://127.0.0.1:8092/actuator/health/readiness" $TicketContainer 30) "ticket readiness UP"
    Assert-True (Wait-Ready "gateway" "$GatewayBase/actuator/health/readiness" "" 30) "gateway readiness UP"

    Write-Host "== 1. 创建买家与工单 =="
    $suffix = (Get-Random -Minimum 100000 -Maximum 999999).ToString()
    $username = "t220$suffix"
    $password = "T220-Persist-2026-$suffix"
    $register = Invoke-Json -Method Post -Uri "$GatewayBase/auth/register" -Body @{
        tenantId = $TenantId
        username = $username
        password = $password
        displayName = "票220持久化演练"
    }
    Assert-True ($null -ne $register.token -and $null -ne $register.accountId) `
        "买家注册成功" "username=$username accountId=$($register.accountId)"

    $ticketBody = @{
        source = "DEGRADE"
        customerId = $register.customerId
        reason = "PERSISTENCE_DRILL"
        userQuery = "票220持久化演练：重启后这张工单还在吗？"
        transcript = "票220 persistence drill transcript $suffix"
        priority = "high"
    }
    $ticket = Invoke-ContainerJson $TicketContainer "POST" "http://127.0.0.1:8092/api/tickets" `
        @{
            "X-Internal-Token" = $env:SHOPPILOT_INTERNAL_TOKEN
            "X-Tenant-Id" = $TenantId
            "X-Customer-Id" = $register.customerId
        } $ticketBody
    Assert-True ($null -ne $ticket.id) "工单落库成功" "ticketId=$($ticket.id) status=$($ticket.status)"

    $before = Get-MineTicket $GatewayBase $register.token $ticket.id
    Assert-True ($before.id -eq $ticket.id -and $before.status -eq $ticket.status) `
        "重启前网关可读买家工单" "ticketId=$($before.id) status=$($before.status)"

    Write-Host "== 2. 重启服务容器 =="
    if ($SkipRestart) {
        Write-Host "SKIP  按参数跳过容器重启"
    }
    else {
        docker restart $GatewayContainer $BizMockContainer $TicketContainer | Out-Null
        Assert-True ($LASTEXITCODE -eq 0) "docker restart 执行成功" "$GatewayContainer / $BizMockContainer / $TicketContainer"
        Assert-True (Wait-Ready "biz-mock" "http://127.0.0.1:8091/actuator/health/readiness" $BizMockContainer) "重启后 biz-mock readiness UP"
        Assert-True (Wait-Ready "ticket" "http://127.0.0.1:8092/actuator/health/readiness" $TicketContainer) "重启后 ticket readiness UP"
        Assert-True (Wait-Ready "gateway" "$GatewayBase/actuator/health/readiness" "" ) "重启后 gateway readiness UP"
    }

    Write-Host "== 3. 重启后业务读回 =="
    $loginAfterRestart = Invoke-Json -Method Post -Uri "$GatewayBase/auth/login" -Body @{
        tenantId = $TenantId
        username = $username
        password = $password
        displayName = $null
    }
    Assert-True ($loginAfterRestart.accountId -eq $register.accountId) `
        "重启后同账号登录返回同一 accountId" "$($loginAfterRestart.accountId)"
    Assert-True ($loginAfterRestart.customerId -eq $register.customerId) `
        "重启后同账号 customerId 不变" "$($loginAfterRestart.customerId)"
    $afterRestart = Get-MineTicket $GatewayBase $loginAfterRestart.token $ticket.id
    Assert-True ($afterRestart.id -eq $ticket.id -and $afterRestart.status -eq $ticket.status) `
        "重启后买家仍能读回同一工单" "ticketId=$($afterRestart.id) status=$($afterRestart.status)"

    Write-Host "== 4. 备份恢复演练 =="
    if ($SkipBackupRestore) {
        Write-Host "SKIP  按参数跳过备份恢复演练"
    }
    else {
        $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
        if (-not $pwsh) { throw "找不到 pwsh 7，无法运行 verify-backup-restore.ps1" }
        $backupScript = Join-Path $PSScriptRoot "verify-backup-restore.ps1"
        $previousInternal = $env:SHOPPILOT_INTERNAL_TOKEN
        $previousDb = $env:SHOPPILOT_DB_PASSWORD
        try {
            & $pwsh -NoProfile -File $backupScript -OutDir $OutDir -DockerExec 2>&1 |
                Tee-Object -FilePath (Join-Path $logDir "persistence-tier-220-backup-$stamp.log")
            $backupExit = $LASTEXITCODE
        }
        finally {
            $env:SHOPPILOT_INTERNAL_TOKEN = $previousInternal
            $env:SHOPPILOT_DB_PASSWORD = $previousDb
        }
        Assert-True ($backupExit -eq 0) "备份恢复演练 exit 0" "exit=$backupExit"

        if ($backupExit -eq 0) {
            $loginAfterRestore = $null
            for ($i = 0; $i -lt 15; $i++) {
                try {
                    $loginAfterRestore = Invoke-Json -Method Post -Uri "$GatewayBase/auth/login" -Body @{
                        tenantId = $TenantId
                        username = $username
                        password = $password
                        displayName = $null
                    }
                    break
                }
                catch {
                    Start-Sleep -Seconds 2
                }
            }
            Assert-True ($null -ne $loginAfterRestore -and $loginAfterRestore.accountId -eq $register.accountId) `
                "恢复后同账号仍可登录" $(if ($null -ne $loginAfterRestore) { $loginAfterRestore.accountId } else { "null" })
            if ($null -ne $loginAfterRestore) {
                $afterRestore = Get-MineTicket $GatewayBase $loginAfterRestore.token $ticket.id
                Assert-True ($afterRestore.id -eq $ticket.id -and $afterRestore.status -eq $ticket.status) `
                    "恢复后买家仍能读回同一工单" "ticketId=$($afterRestore.id) status=$($afterRestore.status)"
            }
        }
    }

    [pscustomobject]@{
        stamp = $stamp
        tenantId = $TenantId
        username = $username
        accountId = $register.accountId
        customerId = $register.customerId
        ticketId = $ticket.id
        ticketStatus = $ticket.status
        pass = $script:pass
        fail = $script:fail
        head = (git rev-parse HEAD)
        containerImages = @{
            gateway = (docker inspect $GatewayContainer --format '{{.Image}}')
            bizMock = (docker inspect $BizMockContainer --format '{{.Image}}')
            ticket = (docker inspect $TicketContainer --format '{{.Image}}')
        }
    } | ConvertTo-Json -Depth 6 | Tee-Object -FilePath $evidenceLog

    Write-Host ""
    Write-Host "汇总：PASS $script:pass / FAIL $script:fail"
    Write-Host "本机证据：$evidenceLog"
    if ($script:fail -gt 0) { exit 1 }
    exit 0
}
catch {
    Write-Host "FAIL  未捕获异常：$($_.Exception.Message)"
    Write-Host $_.ScriptStackTrace
    exit 1
}
