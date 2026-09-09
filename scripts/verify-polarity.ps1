# ticket 17 验收：证明"同桶反义在 0.95 下不互命中"这条性质由哪一层兜住。
#
# 标定实测（docs/threshold-calibration.md）：反义对里有若干对越过 0.95，且两侧同属 POLICY_RETURN，
# 意图分区与阈值双双失效。所以只要上一条问法的答案写进了 L2，反义问法就必然被向量检索捞回来。
#
# 两条探针分别验证两层防线：
#   探针 A「这个不能退吗」—— 与「这个能退吗」同为 T0 命中的 POLICY_RETURN（两侧都含"能退吗"），
#          余弦 0.9799，准入层放行，必须由极性守卫在复用前拒掉 -> 计数器 +1。
#   探针 B「这个是不是不能退」—— 与探针 A 同为否定极性；09-08 T1 重标定后它落 POLICY_RETURN，
#          允许复用探针 A 写回的同极性答案。这里断言的是性质本身：绝不拿到极性相反那条源答案。
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

# 答案差异断言只在真模型下有意义：perf 模式的 MockLLM 对所有问句返回同一句罐头，
# 「探针没拿到上一条的复读」会变成一条永远失败的检查。这里直接拒绝在错误的模式下跑。
$circuit = (Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/ops/circuit" -UseBasicParsing -Headers @{ "X-Ops-Token" = $OpsToken; Authorization = "Bearer $token" } -TimeoutSec 20).Content | ConvertFrom-Json
if ($circuit.llmMode -notin @('local', 'dev')) {
    Write-Host "需要 local 或 dev 模式（当前 llmMode=$($circuit.llmMode)）：先 .\scripts\stop.ps1 -Ports 8082 再 .\scripts\start-gateway.ps1 -Profile local"
    exit 2
}

$blockedBefore = Get-Counter $polarityMetric
Assert-True ($blockedBefore -ge 0) "极性守卫计数器 $polarityMetric 可读取"

Write-Host "`n== 前置：让「$source」的答案写进缓存 =="
$first = Invoke-Chat $token $source "conv-polarity-src"
Write-Host "  intent=$($first.intent)  triage=$($first.triageLayer)  cache=$($first.cacheLayer)"
$repeat = Invoke-Chat $token $source "conv-polarity-src-2"
Write-Host "  同句再问一次 -> cache=$($repeat.cacheLayer)"
$script:writtenBack = $repeat.cacheLayer -in @("L1", "L2", "FLIGHT")
Assert-True $writtenBack "源问法已写回（第二次命中 $($repeat.cacheLayer)）—— L2 里现在有一条 POLICY_RETURN 向量"

# 上面那句其实没说中要害：L1 命中只证明正文写回了，不证明 L2 里有向量。ADR 0018 之后写回允许只落 L1
# （向量化失败不挡 L1 写回），而探针 A 要量的极性守卫只在"从 L2 复用之前"才生效。
# 09-09 16:57 那次全量验收就是这么红的：那一刻向量化失败，写回走成 L1-only，L2 空着，
# 守卫自然一次都没触发，日志却写成"极性守卫拦下了这次 L2 命中 FAIL"——把环境问题报成防线失效。
# 所以先用同极性近义问法确认 L2 里真有条目；拿不到就重打几次源问法（每次都会重试写回与向量化），
# 仍然不行就以另一个退出码单独收场，不污染下面那组断言的语义。
$l2Probe = "这个能退么"
$preconditionOk = $false
foreach ($attempt in 1..3) {
    $check = Invoke-Chat $token $l2Probe "conv-polarity-pre-$attempt"
    Write-Host "  前置探针「$l2Probe」第 $attempt 次 -> cache=$($check.cacheLayer)"
    if ($check.cacheLayer -eq "L2") { $preconditionOk = $true; break }
    Invoke-Chat $token $source "conv-polarity-src-retry-$attempt" | Out-Null
    Start-Sleep -Seconds 2
}
if (-not $preconditionOk) {
    # 两种成因都实测过：向量不可用时写回要么只落 L1（ADR 0018 拆掉向量门之后），
    # 要么被 ADR 0006 的"检索未降级"资格整个拒掉（稠密召回同时失败时），后者连 L1 都没有。
    Write-Host "`n前置不成立：L2 里没有源条目（写回只落了 L1，或整条被降级资格拒掉；两者都指向那一刻向量不可用）。" -ForegroundColor Red
    Write-Host "极性守卫只在从 L2 复用之前才生效，这个前提没了就没有可判的东西；下面的断言不作为防线失效的证据。"
    Write-Host "处置：确认向量服务在跑（默认 :11434），然后重跑本脚本；这一步红不代表防线失效。"
    exit 3
}
Assert-True $true "L2 里确实有一条 $($first.intent) 向量（同极性近义问法命中 L2）—— 极性守卫的前提成立"

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

Write-Host "`n== 探针 B：「这个是不是不能退」（与探针 A 同极性；09-08 T1 重标定后落 POLICY_RETURN）=="
$blockedBeforeB = Get-Counter $polarityMetric
$probeB = Invoke-Chat $token "这个是不是不能退" "conv-polarity-b"
Write-Host "  intent=$($probeB.intent)  triage=$($probeB.triageLayer)  cache=$($probeB.cacheLayer)"
$blockedAfterB = Get-Counter $polarityMetric
# 安全性质是「绝拿不到极性相反那条答案」，不是「必须 miss 缓存」。
# 这一句与探针 A 同极性（都含否定），命中探针 A 写回的同极性条目正是语义缓存该做的事；
# 旧断言把「落在 UNKNOWN」这个内部细节当成防线，T1 阈值一改就必然假报警。
Assert-True ($probeB.answer -ne $first.answer) "探针 B 没有拿到极性相反的源答案（cache=$($probeB.cacheLayer)）"
Assert-True ($probeB.cacheLayer -ne "L2" -or ($blockedAfterB - $blockedBeforeB) -eq 0) "探针 B 若从 L2 取答，复用的是同极性条目（守卫增量 $([int]($blockedAfterB - $blockedBeforeB))）"

Write-Host "`n== 结论 =="
if ($failures.Count -eq 0) {
    Write-Host "验收通过：越过 0.95 的同桶反义对在系统层拿不到上一条答案。"
    Write-Host "  守卫层兜住「这个不能退吗」（准入放行、余弦 0.9799、复用前被拒）；"
    Write-Host "  同极性的「这个是不是不能退」允许复用探针 A 的答案——这是语义缓存的正解，不是漏洞。"
    exit 0
}
Write-Host "验收失败 $($failures.Count) 项。"
if (-not $script:writtenBack) {
    Write-Host "注意：前置条件未满足（源问法没写回缓存），此时 L2 里没有向量，守卫无从触发。"
}
exit 1
