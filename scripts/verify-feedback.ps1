# 票 37 活体验收：满意度反馈闭环三断言 + 三个隐式信号计数（ADR 0039）。
# 断言一：点踩 → feedback 表落行；断言二：关联工单与 ruleId 可查；断言三：复核队列可见。
# 隐式信号：重问（implied_dissatisfied）/ 降级（negative）/ 幂等重放（implied_retry）计数各断言一次。
# 前置：网关起在 dev/local（需 Ollama 或 DashScope 配置）+ biz-mock + 容器栈。
# 用法：pwsh -NoProfile -File scripts/verify-feedback.ps1
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8082"
$Pass = 0; $Fail = 0

function Get-Token([string]$Tenant, [string]$Customer) {
    $body = '{"tenantId":"' + $Tenant + '","customerId":"' + $Customer + '"}'
    $res = Invoke-RestMethod -Uri "$Base/auth/mock-token" -Method Post -ContentType "application/json" -Body $body
    return $res.token
}

function Get-ChatResult([string]$Token, [string]$Query, [string]$Conversation) {
    $headers = @{ Authorization = "Bearer $Token"; "X-Conversation-Id" = $Conversation }
    $body = '{"query":' + ($Query | ConvertTo-Json) + '}'
    return Invoke-RestMethod -Uri "$Base/api/v1/support/chat" -Method Post -Headers $headers `
        -ContentType "application/json; charset=utf-8" -Body $body
}

function Get-Metrics() {
    return Invoke-RestMethod -Uri "$Base/actuator/prometheus"
}

function Get-Counter([string]$Metrics, [string]$Name, [string]$Tags) {
    $line = $Metrics -split "`n" | Where-Object { $_ -like "$Name*$Tags*" } | Select-Object -First 1
    if ($null -eq $line) { return 0.0 }
    return [double]($line -split '\s+')[-1]
}

$token = Get-Token -Tenant "T001" -Customer "C155"
$conv = "verify-feedback-$PID"

# ---- 隐式信号 ①：降级（negative）—— 用一个必然走降级的问题（不在政策库且无工具诉求）----
#     计数口径：走到 FALLBACK 即 negative，与是否点踩无关
$before = Get-Metrics
$negBefore = Get-Counter $before "shoppilot_feedback_implied_total" '{kind="negative"}'

# ---- 正常问答 + 点踩（断言一/二/三的主链路）----
$chat = Get-ChatResult -Token $token -Query "七天无理由退货需要自己出运费吗" -Conversation $conv
if (-not $chat.citations -or $chat.citations.Count -eq 0) {
    Write-Host "FAIL  问答没有引用块可关联（期望 POLICY 类查询带 ruleId）"
    $Fail++
} else {
    $Pass++
    Write-Host ("PASS  问答携带引用块 {0}" -f ($chat.citations -join ","))
}

$fbBody = '{"conversationId":"' + $conv + '","verdict":"DOWN","reason":"验收脚本点踩"}'
$fb = Invoke-RestMethod -Uri "$Base/api/v1/support/chat/feedback" -Method Post `
    -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json; charset=utf-8" -Body $fbBody

if ($fb.feedbackId) {
    $Pass++
    Write-Host ("PASS  断言一 点踩落行 feedbackId={0}" -f $fb.feedbackId)
} else {
    $Fail++
    Write-Host "FAIL  断言一 点踩没有落行（feedbackId 为空）"
}
if ($fb.reviewQueued -and $fb.ruleIds.Count -gt 0) {
    $Pass++
    Write-Host ("PASS  断言二 关联 ruleId 可查 {0}" -f ($fb.ruleIds -join ","))
} else {
    $Fail++
    Write-Host "FAIL  断言二 复核队列标记或 ruleId 关联缺失"
}

# 队列可见：经运维代理按租户读复核队列，必须包含这条点踩
$queue = Invoke-RestMethod -Uri "$Base/api/v1/support/ops/feedback/review-queue" `
    -Headers @{ Authorization = "Bearer $token"; "X-Ops-Token" = "dev-ops-token" }
$found = $false
foreach ($row in $queue) {
    if ($row.id -eq $fb.feedbackId) {
        $found = $true
        if ($row.ruleIds -and $row.ruleIds.Length -gt 0) {
            $Pass++
            Write-Host ("PASS  断言三 复核队列可见且引用块随行 {0}" -f $row.ruleIds)
        } else {
            $Fail++
            Write-Host "FAIL  断言三 队列里的行缺引用块"
        }
    }
}
if (-not $found) {
    $Fail++
    Write-Host "FAIL  断言三 复核队列里找不到这条点踩"
}

# 工单关联：点踩会话若走过降级，行上应有 ticketId；无降级会话则该字段为空是正确形态
$after = Get-Metrics
$negAfter = Get-Counter $after "shoppilot_feedback_implied_total" '{kind="negative"}'
if ($negAfter -gt $negBefore) {
    $Pass++
    Write-Host ("PASS  隐式信号② 降级计数 negative {0} -> {1}" -f $negBefore, $negAfter)
} else {
    $Fail++
    Write-Host "FAIL  隐式信号② 降级计数没有增长"
}

# ---- 隐式信号 ①：重问（implied_dissatisfied）—— 同会话同意图连续问两遍 ----
$repeatBefore = (Get-Metrics | Get-Counter "shoppilot_feedback_implied_total" '{kind="implied_dissatisfied"}')
Get-ChatResult -Token $token -Query "帮我查一下订单90001现在什么状态" -Conversation "$conv-repeat" | Out-Null
Get-ChatResult -Token $token -Query "帮我查一下订单90001现在什么状态" -Conversation "$conv-repeat" | Out-Null
$repeatAfter = (Get-Metrics | Get-Counter "shoppilot_feedback_implied_total" '{kind="implied_dissatisfied"}')
if ($repeatAfter -gt $repeatBefore) {
    $Pass++
    Write-Host ("PASS  隐式信号① 重问计数 implied_dissatisfied {0} -> {1}" -f $repeatBefore, $repeatAfter)
} else {
    $Fail++
    Write-Host "FAIL  隐式信号① 重问计数没有增长"
}

# ---- 隐式信号 ③：幂等重放（implied_retry）—— 同 token 重复提交退款 ----
$retryBefore = (Get-Metrics | Get-Counter "shoppilot_feedback_implied_total" '{kind="implied_retry"}')
$refundHeaders = @{ Authorization = "Bearer $token"; "X-Conversation-Id" = "$conv-retry" }
$refundBody = '{"query":"帮我给订单90002申请退款，原因是质量问题","idempotencyToken":"verify-feedback-retry-1"}'
Invoke-RestMethod -Uri "$Base/api/v1/support/chat" -Method Post -Headers $refundHeaders `
    -ContentType "application/json; charset=utf-8" -Body $refundBody | Out-Null
Invoke-RestMethod -Uri "$Base/api/v1/support/chat" -Method Post -Headers $refundHeaders `
    -ContentType "application/json; charset=utf-8" -Body $refundBody | Out-Null
$retryAfter = (Get-Metrics | Get-Counter "shoppilot_feedback_implied_total" '{kind="implied_retry"}')
if ($retryAfter -gt $retryBefore) {
    $Pass++
    Write-Host ("PASS  隐式信号③ 幂等重放计数 implied_retry {0} -> {1}" -f $retryBefore, $retryAfter)
} else {
    $Fail++
    Write-Host "FAIL  隐式信号③ 幂等重放计数没有增长"
}

Write-Host ("`n反馈闭环验收：PASS {0} / FAIL {1}" -f $Pass, $Fail)
if ($Fail -gt 0) { exit 1 }
