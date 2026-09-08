<#
    ticket 14 验收：七种降级原因逐条稳定复现，每条都落到可查工单。
    全部故障经网关运维代理注入，浏览器/脚本不接触 biz-mock 的内部凭证。
    例: .\scripts\verify-fallback.ps1
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$Tenant = 'T001',
    [string]$Customer = 'C001',
    [string]$OpsToken = 'dev-ops-token'
)
$ErrorActionPreference = 'Stop'
$script:Results = @()

function Get-MockToken([string]$tenantId, [string]$customerId) {
    return (Invoke-RestMethod -Uri "$BaseUrl/auth/mock-token" -Method Post -ContentType 'application/json' `
        -Body (@{ tenantId = $tenantId; customerId = $customerId } | ConvertTo-Json -Compress)).token
}

$Token = Get-MockToken $Tenant $Customer

function Invoke-ChatStream([string]$conversation, [string]$query) {
    $headers = @{ Authorization = "Bearer $Token"; 'X-Conversation-Id' = $conversation }
    $body = @{ query = $query } | ConvertTo-Json -Compress
    return (Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat/stream" -Method Post -Headers $headers `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
        -ContentType 'application/json; charset=utf-8' -TimeoutSec 180 -UseBasicParsing).Content
}

function Invoke-Ops([string]$method, [string]$path, $body) {
    $headers = @{ Authorization = "Bearer $Token"; 'X-Ops-Token' = $OpsToken }
    $params = @{ Uri = "$BaseUrl$path"; Method = $method; Headers = $headers; TimeoutSec = 60 }
    if ($null -ne $body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
    }
    return (Invoke-RestMethod @params)
}

function Get-FallbackOf([string]$raw) {
    $m = [regex]::Match($raw, '(?m)^data:\{"reason":"([A-Z_]+)"(?:,"ticketId":"([^"]+)")?\}')
    if (-not $m.Success) { return @{ reason = ''; ticketId = '' } }
    return @{ reason = $m.Groups[1].Value; ticketId = $m.Groups[2].Value }
}

function Assert-Reason([string]$name, [string]$expected, [string]$raw) {
    $fb = Get-FallbackOf $raw
    $ok = ($fb.reason -eq $expected) -and ($fb.ticketId -ne '')
    $script:Results += [pscustomobject]@{ Reason = $expected; Trigger = $name; TicketId = $fb.ticketId; Pass = $ok }
    $color = if ($ok) { 'Green' } else { 'Red' }
    $shown = if ($fb.reason) { $fb.reason } else { '(no fallback)' }
    Write-Host ("  {0,-22} reason={1,-18} ticket={2,-26} {3}" -f $name, $shown, $fb.ticketId, $(if ($ok) { 'PASS' } else { 'FAIL' })) -ForegroundColor $color
}

function Reset-AllFaults {
    Invoke-Ops 'PUT' '/api/v1/support/ops/llm-fault' @{ mode = 'none' } | Out-Null
    Invoke-Ops 'PUT' '/api/v1/support/ops/fault' @{ delayMs = 0; failRate = 0.0 } | Out-Null
}

function Clear-AnswerCache {
    # 清答案缓存：上一轮留下的 L1 命中与负标记会让"未命中路径"根本走不到模型。
    # 刻意不推纪元——纪元同时是检索过滤器，推一次等于把政策条款摘出检索范围。
    Invoke-Ops 'POST' '/api/v1/support/ops/cache/flush' $null | Out-Null
    Start-Sleep -Milliseconds 300
}

Write-Host "`n=== ShopPilot fallback reproducibility (7 reasons) ===" -ForegroundColor Cyan
Reset-AllFaults
Clear-AnswerCache

Write-Host "`n[1-3] LLM family: inject via gateway, no restart needed"
foreach ($pair in @(@('timeout', 'LLM_TIMEOUT'), @('unavailable', 'LLM_CIRCUIT_OPEN'), @('budget', 'LLM_BUDGET_EXCEEDED'))) {
    Invoke-Ops 'PUT' '/api/v1/support/ops/llm-fault' @{ mode = $pair[0] } | Out-Null
    $raw = Invoke-ChatStream ('conv-' + [guid]::NewGuid()) '生鲜坏了怎么赔'
    Assert-Reason "llm-fault=$($pair[0])" $pair[1] $raw
    Clear-AnswerCache
}
Reset-AllFaults

Write-Host "`n[4] TOOL_UNAVAILABLE: biz-mock failRate=1.0"
Invoke-Ops 'PUT' '/api/v1/support/ops/fault' @{ delayMs = 0; failRate = 1.0 } | Out-Null
$raw = Invoke-ChatStream ('conv-' + [guid]::NewGuid()) '订单 90002 到哪了'
Assert-Reason 'fault=failRate1.0' 'TOOL_UNAVAILABLE' $raw
Invoke-Ops 'PUT' '/api/v1/support/ops/fault' @{ delayMs = 0; failRate = 0.0 } | Out-Null

Write-Host "`n[5] INTENT_UNRESOLVED: negative cache marker, asked twice"
Clear-AnswerCache
Invoke-Ops 'POST' '/api/v1/support/ops/negative-cache' @{ query = '生鲜坏了怎么赔'; intent = 'POLICY_FRESH' } | Out-Null
$raw = Invoke-ChatStream ('conv-' + [guid]::NewGuid()) '生鲜坏了怎么赔'
Assert-Reason 'negative-marker' 'INTENT_UNRESOLVED' $raw

Write-Host "`n[6] SLOT_UNRESOLVED: ask for an order, refuse to name it twice"
$conv = 'conv-slot-' + [guid]::NewGuid()
$ask = Invoke-ChatStream $conv '帮我查一下订单'
Write-Host ('  first turn events: ' + (([regex]::Matches($ask, '(?m)^event:\s*(\S+)') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique) -join ','))
$raw = Invoke-ChatStream $conv '就是不想说'
Assert-Reason 'slot-missing-x2' 'SLOT_UNRESOLVED' $raw

Write-Host "`n[7] USER_REQUESTED: explicit escalation"
$raw = Invoke-ChatStream ('conv-' + [guid]::NewGuid()) '转人工'
Assert-Reason 'user-says-escalate' 'USER_REQUESTED' $raw

Write-Host "`n[8] RATE_LIMITED: burst past the customer bucket"
$burst = @()
foreach ($i in 1..14) {
    $headers = @{ Authorization = "Bearer $Token"; 'X-Conversation-Id' = 'conv-burst-' + [guid]::NewGuid() }
    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat" -Method Post -Headers $headers `
        -Body ([System.Text.Encoding]::UTF8.GetBytes((@{ query = '发什么快递' } | ConvertTo-Json -Compress))) `
        -ContentType 'application/json; charset=utf-8' -TimeoutSec 60 -UseBasicParsing -SkipHttpErrorCheck
    if ([int]$resp.StatusCode -eq 429) {
        $ticket = $resp.Headers['X-Fallback-Ticket']
        $burst += $ticket
        $script:Results += [pscustomobject]@{ Reason = 'RATE_LIMITED'; Trigger = "burst-429-#$i"; TicketId = $ticket; Pass = ($null -ne $ticket -and "$ticket" -ne '') }
    }
}
Write-Host ("  429 responses: " + @($burst).Count + "  distinct tickets: " + @($burst | Where-Object { $_ } | Select-Object -Unique).Count)

Write-Host "`n=== tickets landed in biz-mock (via gateway proxy) ==="
(Invoke-Ops 'GET' '/api/v1/support/ops/tickets' $null) | ForEach-Object {
    Write-Host ("  {0}  {1,-20} {2}" -f $_.id, $_.reason, $_.userQuery)
}

Write-Host "`n=== summary ==="
$script:Results | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($script:Results | Where-Object { -not $_.Pass })
Reset-AllFaults
if ($failed.Count -gt 0) { Write-Host ("FAILED: " + (($failed | ForEach-Object { $_.Reason }) -join ', ')) -ForegroundColor Red; exit 1 }
Write-Host 'all 7 fallback reasons reproduced with a queryable ticket' -ForegroundColor Green
