# 命中路径必须零模型调用、零远程向量化调用（PLAN 承诺项）。
# 这条断言不能靠读代码成立，只能靠计数器差值成立，所以单独一个脚本。
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:8082"
$failures = @()

function Metric([string]$path) {
    # /actuator/metrics 的 measurement 元素字段是 statistic，写成 metric 会静默返回空，
    # 于是所有差值都变成 "null - null = 0"，断言全绿但什么都没验。这里显式抛错堵住这条路。
    $r = Invoke-RestMethod "$base/actuator/metrics/$path" -TimeoutSec 15
    $value = ($r.measurements | Where-Object { $_.statistic -eq "COUNT" }).value
    if ($null -eq $value) { throw "计数器 $path 读不到 COUNT 值，拒绝以 0 代替" }
    [double]$value
}
function Assert-True([bool]$condition, [string]$label) {
    if ($condition) { Write-Host "PASS  $label" } else { Write-Host "FAIL  $label"; $script:failures += $label }
}

$tok = (Invoke-RestMethod -Method Post "$base/auth/mock-token" -ContentType "application/json" `
        -Body '{"tenantId":"T001","customerId":"C001"}').token
$ops = @{ Authorization = "Bearer $tok"; "X-Ops-Token" = "dev-ops-token" }
Invoke-RestMethod -Method Post "$base/api/v1/support/ops/cache/flush" -Headers $ops `
        -ContentType "application/json" -Body '{}' | Out-Null

function Ask([string]$query, [string]$conv, [string]$suffix) {
    $body = @{ query = $query; idempotencyToken = "hit-$suffix" } | ConvertTo-Json
    Invoke-RestMethod -Method Post "$base/api/v1/support/chat" -Headers @{ Authorization = "Bearer $tok"; "X-Conversation-Id" = $conv } `
        -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 120
}

# 探针标记必须是纯字母：数字会被 T0 的实体正则当成订单号，整句直接判成业务办理，
# 那条"未命中路径走检索 + 生成"的链路就根本不会被触发。
# 32 位十六进制里期望约 12 个字母，仍留一个兜底避免极小概率截断越界
$letters = [guid]::NewGuid().ToString("N") -replace '[0-9]', ''
while ($letters.Length -lt 8) { $letters += "q" }
$stamp = $letters.Substring(0, 8)
$query = "生鲜理赔的时限是多久来着（探针 $stamp）"

$llm0 = Metric "shoppilot_llm_calls_total"
$emb0 = Metric "shoppilot_embedding_calls_total?tag=result:remote"
$first = Ask $query "zero-llm-probe" $stamp
$llm1 = Metric "shoppilot_llm_calls_total"
$emb1 = Metric "shoppilot_embedding_calls_total?tag=result:remote"
$missLlm = [int]($llm1 - $llm0)
$missEmbed = [int]($emb1 - $emb0)
Assert-True ($missLlm -gt 0) "未命中路径确实产生了模型调用（+$missLlm）"
Assert-True ($missEmbed -ge 1) "未命中路径做了向量化（远程 +$missEmbed）"
Assert-True ($first.intent -like "POLICY_*") "未命中时已定案政策意图（$($first.intent)）"
Assert-True ($first.cacheLayer -eq "NONE") "首次提问不进缓存（$($first.cacheLayer)）"
Assert-True (@($first.citations).Count -gt 0) "未命中答案带非空 citations（$(@($first.citations).Count) 条）"

$llm2 = Metric "shoppilot_llm_calls_total"
$emb2 = Metric "shoppilot_embedding_calls_total?tag=result:remote"
$second = Ask $query "zero-llm-probe" "$stamp-b"
$llm3 = Metric "shoppilot_llm_calls_total"
$emb3 = Metric "shoppilot_embedding_calls_total?tag=result:remote"
$hitLlm = [int]($llm3 - $llm2)
$hitEmbed = [int]($emb3 - $emb2)
Assert-True ($second.cacheLayer -eq "L1") "第二次命中 L1（实际 $($second.cacheLayer)）"
Assert-True ($hitLlm -eq 0) "命中路径模型调用增量为 0（实际 +$hitLlm）"
Assert-True ($hitEmbed -eq 0) "命中路径远程向量化调用增量为 0（实际 +$hitEmbed）"
Assert-True ($second.answer -eq $first.answer) "命中答案与首次一致"

if ($failures.Count -gt 0) { Write-Host "`n$($failures.Count) 项未通过"; exit 1 }
Write-Host "`n全部通过：命中路径零模型调用、零远程向量化调用"
exit 0
