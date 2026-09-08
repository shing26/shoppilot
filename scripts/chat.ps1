# 冒烟一次对话：拿 mock token -> 调同步端点 -> 打印意图、缓存层、引用数与答案。
param(
    [string]$Query = "生鲜坏了怎么赔",
    [string]$Tenant = "T001",
    [string]$Customer = "C155",
    [string]$Conversation = "conv-smoke",
    [string]$BaseUrl = "http://127.0.0.1:8082"
)
$ErrorActionPreference = "Stop"
$tokenBody = @{ tenantId = $Tenant; customerId = $Customer } | ConvertTo-Json
$token = ((Invoke-WebRequest -Uri "$BaseUrl/auth/mock-token" -Method POST -Body $tokenBody `
    -ContentType "application/json" -UseBasicParsing).Content | ConvertFrom-Json).token
$body = @{ query = $Query } | ConvertTo-Json
$sw = [Diagnostics.Stopwatch]::StartNew()
$response = Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat" -Method POST `
    -Headers @{ Authorization = "Bearer $token"; "X-Conversation-Id" = $Conversation } `
    -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 180
$sw.Stop()
$result = $response.Content | ConvertFrom-Json
"HTTP $($response.StatusCode)  用时 $([math]::Round($sw.Elapsed.TotalSeconds, 2))s"
"intent=$($result.intent)  triage=$($result.triageLayer)  cache=$($result.cacheLayer)  citations=$($result.citations.Count)"
$result.answer
