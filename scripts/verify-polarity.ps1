# ticket 17 验收：证明"同桶反义在 0.95 下不互命中"这条性质由哪一层兜住。
#
# 标定实测（docs/threshold-calibration.md）：反义对里有若干对越过 0.95，且两侧同属 POLICY_RETURN，
# 意图分区与阈值双双失效。所以只要上一条问法的答案写进了 L2，反义问法就必然被向量检索捞回来。
#
# 两条探针分别验证两层防线：
#   探针 A「这个不能退吗」—— 与「这个能退吗」同为 T0 命中的 POLICY_RETURN（两侧都含"能退吗"），
#          余弦 0.9799，准入层放行，必须由极性守卫在复用前拒掉 -> 计数器 +1。
#   探针 B「这个是不是不能退」—— T0 认不出（不含任何 RETURN 关键词），落到 UNKNOWN，
#          准入层 fail-closed 直接不进缓存 -> 守卫计数器不动，但同样没拿到上一条答案。
#
# 前置：网关 :8082、biz-mock :8091、Redis/Qdrant/ES 起来，Ollama 可用（默认 local 模式）。
param(
    [string]$BaseUrl = "http://127.0.0.1:8082",
    [string]$Tenant = "T001",
    [string]$Customer = "C155",
    [string]$OpsToken = "dev-ops-token"
)
$ErrorActionPreference = "Stop"

function Get-MockToken {
    $body = @{ tenantId = $Tenant; customerId = $Customer } | ConvertTo-Json
    return ((Invoke-WebRequest -Uri "$BaseUrl/auth/mock-token" -Method POST -Body $body `
        -ContentType "application/json" -UseBasicParsing).Content | ConvertFrom-Json).token
}

function Invoke-Chat([string]$token, [string]$query, [string]$conversation) {
    $body = @{ query = $query } | ConvertTo-Json
    $response = Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat" -Method POST `
        -Headers @{ Authorization = "Bearer $token"; "X-Conversation-Id" = $conversation } `
        -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
        -UseBasicParsing -TimeoutSec 180
    return $response.Content | ConvertFrom-Json
}

function Get-Counter([string]$name) {
    try {
        $raw = (Invoke-WebRequest -Uri "$BaseUrl/actuator/metrics/$name" -UseBasicParsing -TimeoutSec 10).Content
        $json = if ($raw -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($raw) } else { $raw }
        return [double]((($json | ConvertFrom-Json).measurements | Where-Object { $_.statistic -eq "COUNT" }).value)
    } catch {
        return -1.0
    }
}

$failures = @()
$script:writtenBack = $false
function Assert-True([bool]$condition, [string]$label) {
    if ($condition) { Write-Host "PASS  $label" } else { Write-Host "FAIL  $label"; $script:failures += $label }
}

$polarityMetric = "shoppilot_cache_l2_polarity_blocked_total"
$source = "这个能退吗"

$token = Get-MockToken
Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/ops/cache/flush" -Method POST `
    -Headers @{ "X-Ops-Token" = $OpsToken; Authorization = "Bearer $token" } `
    -UseBasicParsing -TimeoutSec 30 | Out-Null

$blockedBefore = Get-Counter $polarityMetric
Assert-True ($blockedBefore -ge 0) "极性守卫计数器 $polarityMetric 可读取"

Write-Host "`n== 前置：让「$source」的答案写进缓存 =="
$first = Invoke-Chat $token $source "conv-polarity-src"
Write-Host "  intent=$($first.intent)  triage=$($first.triageLayer)  cache=$($first.cacheLayer)"
$repeat = Invoke-Chat $token $source "conv-polarity-src-2"
Write-Host "  同句再问一次 -> cache=$($repeat.cacheLayer)"
$script:writtenBack = $repeat.cacheLayer -in @("L1", "L2", "FLIGHT")
Assert-True $writtenBack "源问法已写回（第二次命中 $($repeat.cacheLayer)）—— L2 里现在有一条 POLICY_RETURN 向量"

Write-Host "`n== 探针 A：「这个不能退吗」（同为 POLICY_RETURN，余弦 0.9799，准入层放行） =="
$probeA = Invoke-Chat $token "这个不能退吗" "conv-polarity-a"
Write-Host "  intent=$($probeA.intent)  triage=$($probeA.triageLayer)  cache=$($probeA.cacheLayer)"
$blockedAfterA = Get-Counter $polarityMetric
$guardDelta = $blockedAfterA - $blockedBefore
Write-Host "  $polarityMetric 增量 = $guardDelta"
Assert-True ($probeA.intent -eq $first.intent) "探针 A 与源问法同桶（都是 $($first.intent)）：意图分区兜不住这一对"
Assert-True ($guardDelta -ge 1) "极性守卫拦下了这次 L2 命中（计数器 +$guardDelta）"
Assert-True ($probeA.cacheLayer -ne "L2") "探针 A 没有以 L2 命中形态拿到上一条答案（实际 $($probeA.cacheLayer)）"
Assert-True ($probeA.answer -ne $first.answer) "探针 A 的答案不是上一条的复读"

Write-Host "`n== 探针 B：「这个是不是不能退」（T0 认不出，准入层 fail-closed） =="
$blockedBeforeB = Get-Counter $polarityMetric
$probeB = Invoke-Chat $token "这个是不是不能退" "conv-polarity-b"
Write-Host "  intent=$($probeB.intent)  triage=$($probeB.triageLayer)  cache=$($probeB.cacheLayer)"
$blockedAfterB = Get-Counter $polarityMetric
Assert-True ($probeB.cacheLayer -ne "L2") "探针 B 没有拿到上一条答案（实际 $($probeB.cacheLayer)）"
Assert-True (($probeB.intent -eq "UNKNOWN") -or ($probeB.cacheLayer -eq "NONE")) `
    "探针 B 由准入层兜住：intent=$($probeB.intent)、cache=$($probeB.cacheLayer)，未进缓存读取路径"
Assert-True (($blockedAfterB - $blockedBeforeB) -eq 0) "探针 B 确实没走到守卫（增量 0），兜住它的是准入层——两层各有分工"

Write-Host "`n== 结论 =="
if ($failures.Count -eq 0) {
    Write-Host "验收通过：越过 0.95 的同桶反义对在系统层拿不到上一条答案。"
    Write-Host "  守卫层兜住「这个不能退吗」（准入放行、余弦 0.9799、复用前被拒）；"
    Write-Host "  准入层兜住「这个是不是不能退」（intent UNKNOWN -> fail-closed，不进缓存）。"
    exit 0
}
Write-Host "验收失败 $($failures.Count) 项。"
if (-not $script:writtenBack) {
    Write-Host "注意：前置条件未满足（源问法没写回缓存），此时 L2 里没有向量，守卫无从触发。"
}
exit 1
