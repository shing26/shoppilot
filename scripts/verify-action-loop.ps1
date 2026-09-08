# ticket 11 验收：业务办理闭环走 SSE 事件契约，不看模型写了什么漂亮话。
#
# 三条断言分别钉住三件不能退让的事：
#   1. 给了订单号 -> 真的跨进程打了 biz-mock（tool_executing + tool_result 成对出现）
#   2. 没给订单号 -> 走 slot_ask，且绝不出现带编造订单号的 tool_result
#   3. 跨店订单 -> tool_result 只能是 NOT_FOUND，且答案里不出现别店的字段
#
# 前置：网关 :8082、biz-mock :8091、Redis/Qdrant/ES 起来，Ollama 可用。
param(
    [string]$BaseUrl = "http://127.0.0.1:8082",
    [string]$OpsToken = "dev-ops-token"
)
$ErrorActionPreference = "Stop"

function Get-Token([string]$tenant, [string]$customer) {
    $body = @{ tenantId = $tenant; customerId = $customer } | ConvertTo-Json -Compress
    return (Invoke-RestMethod -Uri "$BaseUrl/auth/mock-token" -Method POST -Body $body `
        -ContentType "application/json" -TimeoutSec 30).token
}

function Invoke-Stream([string]$token, [string]$query, [string]$conversation) {
    $body = @{ query = $query } | ConvertTo-Json -Compress
    $response = Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat/stream" -Method POST `
        -Headers @{ Authorization = "Bearer $token"; "X-Conversation-Id" = $conversation } `
        -ContentType "application/json; charset=utf-8" `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -UseBasicParsing -TimeoutSec 240
    $raw = if ($response.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($response.Content) } else { $response.Content }
    $events = @()
    $current = $null
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match '^event:\s*(.+)$') { $current = $Matches[1].Trim() }
        elseif ($line -match '^data:\s*(.*)$' -and $current) {
            $events += [PSCustomObject]@{ Event = $current; Data = $Matches[1] }
            $current = $null
        }
    }
    return $events
}

$failures = @()
function Assert-True([bool]$condition, [string]$label) {
    if ($condition) { Write-Host "PASS  $label" } else { Write-Host "FAIL  $label"; $script:failures += $label }
}

$nonce = Get-Random -Maximum 99999
Write-Host "== 复位演示固定单 =="
$admin = Get-Token "T001" "C001"
Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/ops/demo/reset" -Method POST `
    -Headers @{ "X-Ops-Token" = $OpsToken; Authorization = "Bearer $admin" } -UseBasicParsing -TimeoutSec 60 | Out-Null

Write-Host "`n== 1. 给了订单号：90002 发货没 =="
$events = Invoke-Stream $admin "90002 这单发货没" "loop-a-$nonce"
$names = $events | ForEach-Object { $_.Event }
Write-Host ("  事件序列: " + (($names | Select-Object -Unique) -join " > "))
Assert-True ($names -contains "tool_executing") "下发 tool_executing（真的开始查业务系统）"
$result = $events | Where-Object { $_.Event -eq "tool_result" } | Select-Object -First 1
Assert-True ($null -ne $result) "下发 tool_result（biz-mock 真的返回了）"
Assert-True ($result.Data -match "queryLogistics|queryOrderDetail") "tool_result 带工具名"
Assert-True ($names -contains "done") "以 done 收尾"

Write-Host "`n== 2. 不给订单号：帮我查下物流轨迹 =="
$events2 = Invoke-Stream $admin "帮我查下物流轨迹" "loop-b-$nonce"
$names2 = $events2 | ForEach-Object { $_.Event }
Write-Host ("  事件序列: " + (($names2 | Select-Object -Unique) -join " > "))
$ask = $events2 | Where-Object { $_.Event -eq "slot_ask" } | Select-Object -First 1
Assert-True ($null -ne $ask) "触发 slot_ask 向用户追问订单号"
Assert-True ($ask.Data -match "orderNo") "追问的槽位是 orderNo"
$leaked = $events2 | Where-Object { $_.Event -eq "tool_result" }
Assert-True ($null -eq $leaked) "追问之前没有偷跑工具（绝不猜订单号）"

Write-Host "`n== 3. 跨店：T002 买家问 T001 的 90001 =="
$other = Get-Token "T002" "C200"
$events3 = Invoke-Stream $other "90001 这单到哪了" "loop-c-$nonce"
$result3 = $events3 | Where-Object { $_.Event -eq "tool_result" } | Select-Object -First 1
Assert-True ($null -ne $result3) "跨店查询同样落到 biz-mock（不是网关自己编的）"
Assert-True ($result3.Data -match "NOT_FOUND") "归属校验返回 NOT_FOUND 语义而非 500"
$answer = ($events3 | Where-Object { $_.Event -eq "token" } | ForEach-Object { $_.Data }) -join ""
Assert-True ($answer -notmatch "数码旗舰店") "答案里不出现别店店铺名"
Assert-True ($answer -notmatch "90001 的") "答案不复述别店订单字段"

Write-Host "`n== 结论 =="
if ($failures.Count -eq 0) {
    Write-Host "业务办理闭环验收通过：查得到、问得出、越不了权。"
    exit 0
}
Write-Host "验收失败 $($failures.Count) 项。"
exit 1
