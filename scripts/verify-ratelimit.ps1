<#
    限流验收：连打同一买家，统计 429 与 SSE rate_limited。
    例: .\scripts\verify-ratelimit.ps1 -Total 12
#>
param(
    [int]$Total = 12,
    [string]$Query = '生鲜坏了怎么赔',
    [string]$Tenant = 'T001',
    [string]$Customer = 'C155',
    [string]$BaseUrl = 'http://127.0.0.1:8082'
)
$ErrorActionPreference = 'Stop'
$token = (Invoke-RestMethod -Uri "$BaseUrl/auth/mock-token" -Method Post -ContentType 'application/json' `
    -Body (@{ tenantId = $Tenant; customerId = $Customer } | ConvertTo-Json -Compress)).token
$headers = @{ Authorization = "Bearer $token"; 'Content-Type' = 'application/json' }
$body = (@{ query = $Query } | ConvertTo-Json -Compress)
$ok = 0; $limited = 0; $other = 0
for ($i = 0; $i -lt $Total; $i++) {
    try {
        $response = Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat" -Method Post -Headers $headers -Body $body `
            -ContentType 'application/json; charset=utf-8' -TimeoutSec 60 -UseBasicParsing
        if ($response.StatusCode -eq 200) { $ok++ } else { $other++ }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        if ($status -eq 429) { $limited++ } else { $other++ }
    }
}
"同步端点 total=$Total ok=$ok rate_limited(429)=$limited other=$other"

$stream = Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat/stream" -Method Post -Headers $headers -Body $body `
    -ContentType 'application/json; charset=utf-8' -TimeoutSec 60 -UseBasicParsing
if ($stream.Content -match 'rate_limited') { 'SSE 通道收到 rate_limited 事件' } else { 'SSE 通道未触发限流（可能仍在配额内）' }
