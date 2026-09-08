<#
    SSE 流式端点验证：打印收到的事件帧序列，用于确认打字机与状态事件。
    例: .\scripts\stream.ps1 -Query "生鲜坏了怎么赔"
#>
param(
    [string]$Query = '生鲜坏了怎么赔',
    [string]$Tenant = 'T001',
    [string]$Customer = 'C155',
    [string]$Conversation = '',
    [string]$BaseUrl = 'http://127.0.0.1:8082'
)
$ErrorActionPreference = 'Stop'
if (-not $Conversation) { $Conversation = [guid]::NewGuid().ToString() }
$token = (Invoke-RestMethod -Uri "$BaseUrl/auth/mock-token" -Method Post -ContentType 'application/json' `
    -Body (@{ tenantId = $Tenant; customerId = $Customer } | ConvertTo-Json -Compress)).token
$body = @{ query = $Query } | ConvertTo-Json -Compress
$headers = @{ Authorization = "Bearer $token"; 'X-Conversation-Id' = $Conversation; 'Content-Type' = 'application/json' }
Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat/stream" -Method Post -Headers $headers -Body $body `
    -ContentType 'application/json; charset=utf-8' -TimeoutSec 180 -UseBasicParsing |
    Select-Object -ExpandProperty Content
