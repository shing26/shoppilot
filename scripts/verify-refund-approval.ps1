<#
    round21 票 62 / ADR 0047 验收：退款审批闸门端到端。
    申请 → tool_result 必须是 PENDING_APPROVAL → 审核队列（经网关 ops 代理）里有它 →
    放行得 PROCESSING、买家读回「已放行 + 到账由支付渠道处理」；
    驳回走 REJECTED、买家读回「已驳回」，且订单回到可申请态（能再次受理）。

    B3 起（ADR 0063）：审核走坐席账号登录，不再用 mock-token（买家）。
    资金动作的责任人必须是已验签账号，所以这里用 agent 登录。

    例: pwsh -NoProfile -File scripts/verify-refund-approval.ps1
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$Tenant = 'T001',
    [string]$Customer = 'C001',
    [string]$ApproveOrder = '90001',
    [string]$RejectOrder = '90002',
    [string]$AgentUser = 'agent',
    [string]$AgentPassword = 'agent123'
)
$ErrorActionPreference = 'Stop'

$script:failures = @()
function Assert([string]$name, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("{0}  {1}{2}" -f $mark, $name, $(if ($detail) { "  $detail" } else { '' })) -ForegroundColor $color
    if (-not $ok) { $script:failures += $name }
}

# B3 起（ADR 0063）：资金动作的责任人必须是已验签账号。
# 买家 token（mock-token）不能用于退款审核，所以这里用 agent 登录。
function Get-AgentToken([string]$tenantId, [string]$username, [string]$password) {
    return (Invoke-RestMethod -Uri "$BaseUrl/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ tenantId = $tenantId; username = $username; password = $password } | ConvertTo-Json -Compress)).token
}

# 买家 token 用于申请退款和读回（买家才能申请退款）
function Get-MockToken([string]$tenantId, [string]$customerId) {
    return (Invoke-RestMethod -Uri "$BaseUrl/auth/mock-token" -Method Post -ContentType 'application/json' `
        -Body (@{ tenantId = $tenantId; customerId = $customerId } | ConvertTo-Json -Compress)).token
}

# 每次都用新会话：本地 3B 在带上一轮成功答复的会话里可能不发工具调用，
# 而这条脚本要量的是闸门，不是模型肯不肯在同一会话里重发。
function Invoke-Stream([string]$token, [string]$query, [string]$idemToken) {
    $headers = @{ Authorization = "Bearer $token"; 'X-Conversation-Id' = ('conv-review-' + [guid]::NewGuid()) }
    $body = @{ query = $query; idempotencyToken = $idemToken } | ConvertTo-Json -Compress
    return (Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat/stream" -Method Post -Headers $headers `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
        -ContentType 'application/json; charset=utf-8' -TimeoutSec 180 -UseBasicParsing).Content
}

# 买家读回用**同步**端点取整段答案：流式把答案按 token 分帧下发（data:"支付" 一帧、
# data:"渠道" 另一帧），对原始 SSE 文本做子串匹配永远匹配不到「支付渠道」这类连续词。
function Invoke-Ask([string]$token, [string]$query) {
    $body = @{ query = $query } | ConvertTo-Json -Compress
    return (Invoke-RestMethod -Uri "$BaseUrl/api/v1/support/chat" -Method Post `
        -Headers @{ Authorization = "Bearer $token" } `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
        -ContentType 'application/json; charset=utf-8' -TimeoutSec 180).answer
}

# 审核队列走网关 ops 代理（票 61 的那条缝）：浏览器与脚本都不直连 biz-mock。
function Get-Pending([string]$token) {
    return @(Invoke-RestMethod -Uri "$BaseUrl/api/v1/support/ops/refunds/pending" `
        -Headers @{ Authorization = "Bearer $token" })
}

function Invoke-Review([string]$token, [string]$refundId, [string]$decision) {
    return Invoke-RestMethod -Uri "$BaseUrl/api/v1/support/ops/refunds/$refundId/review" -Method Post `
        -Headers @{ Authorization = "Bearer $token" } `
        -Body (@{ decision = $decision } | ConvertTo-Json -Compress) -ContentType 'application/json'
}

function Reset-DemoFixtures {
    $h = @{ 'X-Internal-Token' = 'dev-internal-token-change-me' }
    Invoke-RestMethod -Uri 'http://127.0.0.1:8091/api/admin/demo/reset' -Method Post -Headers $h | Out-Null
}

function New-Token { return 'rev-' + [guid]::NewGuid().ToString('N').Substring(0, 12) }

Reset-DemoFixtures
$buyerToken = Get-MockToken $Tenant $Customer
$agentToken = Get-AgentToken $Tenant $AgentUser $AgentPassword

Write-Host "`n=== 1. apply refund (order $ApproveOrder) ===" -ForegroundColor Cyan
$raw = Invoke-Stream $buyerToken "订单 $ApproveOrder 我要申请退款，商品有质量问题" (New-Token)
Assert 'apply refund returns PENDING_APPROVAL' ($raw -match 'PENDING_APPROVAL') ''

Write-Host "`n=== 2. review queue via ops proxy (agent) ===" -ForegroundColor Cyan
$pending = Get-Pending $agentToken
$row = $pending | Where-Object { $_.orderNo -eq $ApproveOrder } | Select-Object -First 1
Assert 'pending queue lists the request' ($null -ne $row -and $row.status -eq 'PENDING_REVIEW') `
    $(if ($null -ne $row) { "refundId=$($row.refundId)" } else { "queue=$($pending.Count)" })

Write-Host "`n=== 3. approve -> PROCESSING (agent) ===" -ForegroundColor Cyan
$approved = Invoke-Review $agentToken $row.refundId 'APPROVE'
Assert 'approve moves refund to PROCESSING' ($approved.payload.status -eq 'PROCESSING') $approved.payload.status

Write-Host "`n=== 4. buyer readback states approval + payout boundary ===" -ForegroundColor Cyan
# 读回按**订单状态**措辞问：本机 3B 对「我那退款到哪了」这类措辞会把意图判成 ACTION_REFUND，
# 而 ACTION_REFUND 下网关不派生读工具、模型也不发 queryOrderDetail —— 那一格的缺口登记在
# 票 62 的 Handoff（属路由/措辞，不在本票范围）。这里验的是票 60 的机制本身：
# 一旦走到 queryOrderDetail，OrderView 的审核态与到账边界文案就会进答案。
$readback = Invoke-Ask $buyerToken "帮我查一下订单 $ApproveOrder 现在的状态"
Assert 'buyer readback says released and names the payout boundary' `
    (($readback -match '放行') -and ($readback -match '支付渠道')) $readback

Write-Host "`n=== 5. reject path (order $RejectOrder) ===" -ForegroundColor Cyan
$raw2 = Invoke-Stream $buyerToken "订单 $RejectOrder 我要申请退款" (New-Token)
Assert 'second refund accepted for the reject path' ($raw2 -match 'PENDING_APPROVAL') ''
$pending2 = Get-Pending $agentToken
$row2 = $pending2 | Where-Object { $_.orderNo -eq $RejectOrder } | Select-Object -First 1
$rejected = Invoke-Review $agentToken $row2.refundId 'REJECT'
Assert 'reject moves refund to REJECTED' ($rejected.payload.status -eq 'REJECTED') $rejected.payload.status

Write-Host "`n=== 6. buyer readback states rejection ===" -ForegroundColor Cyan
$readback2 = Invoke-Ask $buyerToken "帮我查一下订单 $RejectOrder 现在什么状态"
Assert 'buyer readback says rejected' ($readback2 -match '驳回|已恢复') $readback2

Write-Host "`n=== 7. rollback: rejected order is refundable again ===" -ForegroundColor Cyan
$raw3 = Invoke-Stream $buyerToken "订单 $RejectOrder 我想再次申请退款" (New-Token)
Assert 'rejected order can be refunded again (rollback)' ($raw3 -match 'PENDING_APPROVAL') ''

Write-Host "`n=== 8. B3: audit actor_authenticated ===" -ForegroundColor Cyan
# B3 起（ADR 0063）：资金动作的责任人必须是已验签账号。
# 查审计表，确认退款审核事件的 actor_authenticated = true。
$auditRows = @(Invoke-RestMethod -Uri "$BaseUrl/api/v1/support/ops/audit?limit=100" `
    -Headers @{ Authorization = "Bearer $agentToken" })
$refundAudit = $auditRows | Where-Object { $_.action -match 'REFUND' -and $_.action -match 'REVIEW' } | Select-Object -First 1
if ($refundAudit) {
    Assert 'audit event actor_authenticated is true' ($refundAudit.actorAuthenticated -eq $true) `
        "actor=$($refundAudit.actor), authenticated=$($refundAudit.actorAuthenticated)"
} else {
    # 审计事件可能还没有刷新，或者 action 名称不匹配。先查所有审计事件看看。
    $allActions = $auditRows | ForEach-Object { $_.action } | Sort-Object -Unique
    Assert 'audit event actor_authenticated is true' $false "no REFUND_REVIEW event found; actions: $($allActions -join ', ')"
}

if ($script:failures.Count -gt 0) {
    Write-Host ("`n退款审批闸门验收失败：" + ($script:failures -join '、')) -ForegroundColor Red
    exit 1
}
Write-Host "`n退款审批闸门验收通过" -ForegroundColor Green
