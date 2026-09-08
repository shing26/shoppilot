<#
    ticket 12 验收：同一笔退款重复提交只产生一条退款单，第二次推 duplicate_submit。
    对已发货订单改地址必须被状态前置校验拒绝，并由模型说明原因。
    例: .\scripts\verify-idempotency.ps1
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$Tenant = 'T001',
    [string]$Customer = 'C001',
    [string]$RefundOrder = '90001',
    [string]$ShippedOrder = '90002'
)
$ErrorActionPreference = 'Stop'

function Get-MockToken([string]$tenantId, [string]$customerId) {
    return (Invoke-RestMethod -Uri "$BaseUrl/auth/mock-token" -Method Post -ContentType 'application/json' `
        -Body (@{ tenantId = $tenantId; customerId = $customerId } | ConvertTo-Json -Compress)).token
}

function Invoke-Stream([string]$token, [string]$conversation, [string]$query, [string]$idemToken) {
    $headers = @{ Authorization = "Bearer $token"; 'X-Conversation-Id' = $conversation }
    $body = @{ query = $query; idempotencyToken = $idemToken } | ConvertTo-Json -Compress
    return (Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat/stream" -Method Post -Headers $headers `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
        -ContentType 'application/json; charset=utf-8' -TimeoutSec 180 -UseBasicParsing).Content
}

function Get-EventNames([string]$raw) {
    return @([regex]::Matches($raw, '(?m)^event:\s*(\S+)') | ForEach-Object { $_.Groups[1].Value })
}

function Get-RefundCount {
    $h = @{ 'X-Internal-Token' = 'dev-internal-token-change-me' }
    return (Invoke-RestMethod -Uri 'http://127.0.0.1:8091/api/admin/stats' -Headers $h).refunds
}

function Reset-DemoFixtures {
    $h = @{ 'X-Internal-Token' = 'dev-internal-token-change-me' }
    Invoke-RestMethod -Uri 'http://127.0.0.1:8091/api/admin/demo/reset' -Method Post -Headers $h | Out-Null
}

Reset-DemoFixtures
$token = Get-MockToken $Tenant $Customer
$idem = 'idem-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$before = Get-RefundCount

Write-Host "`n=== 1. first refund (order $RefundOrder, token $idem) ===" -ForegroundColor Cyan
$first = Invoke-Stream $token ('conv-refund-' + [guid]::NewGuid()) "订单 $RefundOrder 我要申请退款，商品有质量问题" $idem
Write-Host ('events: ' + ((Get-EventNames $first) -join ' -> '))
Write-Host ('refunds: ' + $before + ' -> ' + (Get-RefundCount))

Write-Host "`n=== 2. replay with same idempotency token ===" -ForegroundColor Cyan
$second = Invoke-Stream $token ('conv-refund-' + [guid]::NewGuid()) "订单 $RefundOrder 我要申请退款，商品有质量问题" $idem
$secondEvents = Get-EventNames $second
Write-Host ('events: ' + ($secondEvents -join ' -> '))
$after = Get-RefundCount
Write-Host ('refunds: ' + $before + ' -> ' + $after)

$ok1 = $secondEvents -contains 'duplicate_submit'
$ok2 = ($after - $before) -eq 1
Write-Host ('duplicate_submit emitted : ' + $ok1) -ForegroundColor $(if ($ok1) { 'Green' } else { 'Red' })
Write-Host ('exactly one refund row     : ' + $ok2) -ForegroundColor $(if ($ok2) { 'Green' } else { 'Red' })

# 地址工具要求 7 个槽位全齐，缺任一槽位状态机会追问而不是猜（ADR 0008），所以这里给全。
Write-Host "`n=== 3. address change on SHIPPED order $ShippedOrder (all slots filled) ===" -ForegroundColor Cyan
$addrQuery = "订单 $ShippedOrder 改收货地址：收件人张三，手机 13800001234，省 上海市，市 上海市，区 浦东新区，详址 世纪大道 100 号"
$third = Invoke-Stream $token ('conv-addr-' + [guid]::NewGuid()) $addrQuery $null
Write-Host ('events: ' + ((Get-EventNames $third) -join ' -> '))
$stateRejected = $third -match 'STATE_NOT_ALLOWED'
Write-Host ('state check rejected it    : ' + $stateRejected) -ForegroundColor $(if ($stateRejected) { 'Green' } else { 'Red' })

if (-not ($ok1 -and $ok2 -and $stateRejected)) { exit 1 }
