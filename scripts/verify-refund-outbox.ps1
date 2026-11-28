<#
    round63 票 234 / U3 活体验收：退款审批工单 outbox 补偿。

    形状（契约 AC9）：
      1. 记下工单服务当前状态，停掉 :8092；
      2. 直连 biz-mock 受理一笔退款（不靠模型派发，判据必须确定）；
      3. 断言受理照常提交（PENDING_APPROVAL），工单服务不在也不回滚；
      4. 恢复工单服务，等补偿器把「待开单」意图补开成 REFUND_APPROVAL 工单；
      5. 经网关坐席账号放行，断言退款进入 PROCESSING；
      6. 无论成败，finally 里把工单服务恢复到运行状态。

    例: pwsh -NoProfile -File scripts/verify-refund-outbox.ps1
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$BizMockUrl = 'http://127.0.0.1:8091',
    [string]$TicketUrl = 'http://127.0.0.1:8092',
    [string]$Tenant = 'T001',
    [string]$Customer = 'C001',
    [string]$Order = '90001',
    [string]$AgentUser = 'agent',
    [string]$AgentPassword = '',
    # 补偿器默认 5s 扫一轮；给足 60s，慢机器上也不把「还没轮到」误判成「补不开」。
    [int]$WaitSeconds = 60,
    [switch]$SkipReset
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-launch.ps1')
$root = Split-Path -Parent $PSScriptRoot
if (-not $AgentPassword) {
    $AgentPassword = Get-ShoppilotCredential -Root $root -Name 'SHOPPILOT_IDENTITY_DEMO_PASSWORD'
}
$internalToken = Get-ShoppilotCredential -Root $root -Name 'SHOPPILOT_INTERNAL_TOKEN'
if (-not $AgentPassword) {
    Write-Host '缺少 SHOPPILOT_IDENTITY_DEMO_PASSWORD：退款审核走坐席账号登录，演示口令不进仓库（ADR 0056）。' -ForegroundColor Red
    exit 2
}
if (-not $internalToken) {
    Write-Host '缺少 SHOPPILOT_INTERNAL_TOKEN：直连 biz-mock 与工单列表都要内部令牌。' -ForegroundColor Red
    exit 2
}

$script:failures = @()
function Assert([string]$name, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("{0}  {1}{2}" -f $mark, $name, $(if ($detail) { "  $detail" } else { '' })) -ForegroundColor $color
    if (-not $ok) { $script:failures += $name }
}

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Port([int]$Port, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return (Test-Port $Port)
}

function Wait-Healthy([string]$Url, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$Url/actuator/health" -TimeoutSec 3
            if ($health.status -eq 'UP') { return $true }
        } catch {
            # 服务还在起，继续等；失败留到最后由 Assert 报。
        }
        Start-Sleep -Milliseconds 1000
    }
    return $false
}

function Start-TicketService {
    & (Join-Path $PSScriptRoot 'start-ticket.ps1') | Out-Host
}

function Ensure-TicketService {
    if (Test-Port 8092) { return $true }
    Start-TicketService
    return (Wait-Healthy $TicketUrl 120)
}

function Get-AgentToken([string]$tenantId, [string]$username, [string]$password) {
    return (Invoke-RestMethod -Uri "$BaseUrl/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ tenantId = $tenantId; username = $username; password = $password } | ConvertTo-Json -Compress)).token
}

function Get-RefundTickets([string]$refundId) {
    $headers = @{ 'X-Internal-Token' = $internalToken; 'X-Tenant-Id' = $Tenant }
    $rows = @((Invoke-WebRequest -Uri "$TicketUrl/api/tickets?queue=REFUND" -Headers $headers `
        -UseBasicParsing).Content | ConvertFrom-Json)
    $marker = '"refundId":"' + $refundId + '"'
    return @($rows | Where-Object { $_.source -eq 'REFUND_APPROVAL' -and $_.payload -and $_.payload.Contains($marker) })
}

$headSha = (& git -C $root rev-parse HEAD).Trim()
$statusShort = (& git -C $root status --short) -join "`n"
Write-Host "HEAD $headSha" -ForegroundColor Cyan
if ($statusShort) { Write-Host "git status --short:`n$statusShort" -ForegroundColor DarkGray }

$refundId = $null
$reviewed = $null
try {
    Write-Host "`n=== 0. preconditions ===" -ForegroundColor Cyan
    Assert 'gateway is up' (Wait-Healthy $BaseUrl 20) $BaseUrl
    Assert 'biz-mock is up' (Wait-Healthy $BizMockUrl 20) $BizMockUrl
    if ($script:failures.Count -gt 0) { throw '前置服务不齐，活体验收不成立' }

    if (-not $SkipReset) {
        Write-Host "`n=== 1. reset demo fixtures ===" -ForegroundColor Cyan
        Invoke-RestMethod -Uri "$BizMockUrl/api/admin/demo/reset" -Method Post `
            -Headers @{ 'X-Internal-Token' = $internalToken } | Out-Null
        Assert 'demo fixtures reset' $true "order $Order 回到可退款态"
    }

    $agentToken = Get-AgentToken $Tenant $AgentUser $AgentPassword

    Write-Host "`n=== 2. stop ticket service (:8092) ===" -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'stop.ps1') -Ports '8092' -ReleaseTimeoutSec 30 | Out-Host
    Assert 'ticket service is down before acceptance' (-not (Test-Port 8092)) '端口 8092'

    Write-Host "`n=== 3. accept refund while ticket service is unreachable ===" -ForegroundColor Cyan
    $idem = 'outbox-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
    $applyHeaders = @{
        'X-Internal-Token' = $internalToken
        'X-Tenant-Id' = $Tenant
        'X-Customer-Id' = $Customer
        'Idempotency-Token' = $idem
    }
    $applied = Invoke-RestMethod -Uri "$BizMockUrl/api/tools/applyRefund" -Method Post `
        -Headers $applyHeaders -ContentType 'application/json' `
        -Body (@{ orderNo = $Order } | ConvertTo-Json -Compress)
    $refundId = [string]$applied.payload.refundId
    Assert 'acceptance is not rolled back by missing work item' ($applied.status -eq 'PENDING_APPROVAL') `
        "status=$($applied.status) refundId=$refundId"
    Assert 'refund stays PENDING_REVIEW' ($applied.payload.status -eq 'PENDING_REVIEW') $applied.payload.status

    Write-Host "`n=== 4. restore ticket service and wait for outbox compensation ===" -ForegroundColor Cyan
    if (-not (Ensure-TicketService)) { throw '工单服务未能恢复，补偿无法验证' }
    Assert 'ticket service is back' $true '端口 8092'

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $tickets = @()
    while ((Get-Date) -lt $deadline) {
        $tickets = Get-RefundTickets $refundId
        if ($tickets.Count -gt 0) { break }
        Start-Sleep -Seconds 2
    }
    Assert 'outbox compensation creates exactly one REFUND_APPROVAL ticket' ($tickets.Count -eq 1) `
        "matched=$($tickets.Count)"
    Assert 'ticket payload points back to the same refund' `
        ($tickets.Count -eq 1 -and $tickets[0].payload.Contains('"refundId":"' + $refundId + '"')) `
        $(if ($tickets.Count -eq 1) { "ticket=$($tickets[0].id)" } else { '' })

    Write-Host "`n=== 4b. compensation metric is scraped under the documented name ===" -ForegroundColor Cyan
    $scrape = (Invoke-WebRequest -Uri "$BizMockUrl/actuator/prometheus" -UseBasicParsing).Content
    Assert 'Prometheus scrape contains shoppilot_refund_ticket_outbox_compensated_total' `
        ($scrape -match '(?m)^shoppilot_refund_ticket_outbox_compensated_total ') ''
    Assert 'Prometheus scrape does not contain the reserved-suffix name' `
        (-not ($scrape -match 'shoppilot_refund_ticket_outbox_created_total')) ''

    Write-Host "`n=== 5. approve the compensated refund ===" -ForegroundColor Cyan
    if (-not $refundId) { throw '没有拿到退款号，审核无法继续' }
    $reviewed = Invoke-RestMethod -Uri "$BaseUrl/api/v1/support/ops/refunds/$refundId/review" -Method Post `
        -Headers @{ Authorization = "Bearer $agentToken" } `
        -Body (@{ decision = 'APPROVE' } | ConvertTo-Json -Compress) -ContentType 'application/json'
    Assert 'approve moves refund to PROCESSING' ($reviewed.payload.status -eq 'PROCESSING') $reviewed.payload.status

    Write-Host "`n=== 6. queue no longer lists the approved refund ===" -ForegroundColor Cyan
    $pending = @(Invoke-RestMethod -Uri "$BaseUrl/api/v1/support/ops/refunds/pending" `
        -Headers @{ Authorization = "Bearer $agentToken" })
    Assert 'approved refund leaves the pending queue' `
        (-not ($pending | Where-Object { [string]$_.refundId -eq $refundId })) "refundId=$refundId"
}
catch {
    Assert 'live verification completed without an unexpected error' $false $_.Exception.Message
}
finally {
    Write-Host "`n=== cleanup ===" -ForegroundColor Cyan
    try {
        if (Ensure-TicketService) {
            Write-Host 'ticket service is running' -ForegroundColor DarkGray
        } else {
            Write-Warning '工单服务未能恢复运行：请手工执行 scripts/start-ticket.ps1 并检查 logs/ticket.err'
            $script:failures += 'ticket service restored'
        }
    } catch {
        Write-Warning "恢复工单服务失败：$($_.Exception.Message)"
        $script:failures += 'ticket service restored'
    }
    Write-Host "HEAD $headSha" -ForegroundColor Cyan
    if ($statusShort) { Write-Host "git status --short:`n$statusShort" -ForegroundColor DarkGray }
}

if ($script:failures.Count -gt 0) {
    Write-Host ("`n退款审批 outbox 活体验收失败：" + ($script:failures -join '、')) -ForegroundColor Red
    exit 1
}
Write-Host "`n退款审批 outbox 活体验收通过" -ForegroundColor Green
