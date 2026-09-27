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

# 取一个 kind 的计数。**刻意不接受管道输入**：本脚本原先在四处写成
#   Get-Metrics | Get-Counter "名字" '标签'
# 而管道形式会让参数整体前移一格（$Metrics 收到名字、$Name 收到标签、$Tags 为空），
# 于是下面的筛选永不命中、恒返回 0.0，两条断言在结构上不可能通过——
# 这个 bug 静默了几个月（票 53 / ADR 0045 更正）。所以现在只保留位置参数，
# 并用 Get-Implied 把三参数调用从调用点整体消掉，别再写回管道形式。
function Get-Counter([string]$Metrics, [string]$Name, [string]$Tags) {
    $line = $Metrics -split "`n" | Where-Object { $_ -like "$Name*$Tags*" } | Select-Object -First 1
    if ($null -eq $line) { return 0.0 }
    return [double]($line -split '\s+')[-1]
}

function Get-Implied([string]$Kind) {
    return Get-Counter (Get-Metrics) "shoppilot_feedback_implied_total" ('{kind="' + $Kind + '"}')
}

$token = Get-Token -Tenant "T001" -Customer "C155"
$conv = "verify-feedback-$PID"

# ---- 隐式信号 ②：降级（negative）—— 刺激在 :93 之前（一次显式转人工），读数在前后两处 ----
#     计数口径：走到 FALLBACK 即 negative（FeedbackService.noteAnswer 判 fallbackReason != null），与是否点踩无关。
#     留意下面那条主链路问句：它是正常政策咨询、**不降级**，所以它本身不会让这个计数动。
#     （2026-09-27 更正：这里原先写「用一个必然走降级的问题（不在政策库且无工具诉求）」，
#       而实际发的是正常问句——注释描述的是意图、代码做的是另一件事。票 53 / ADR 0045。）
$negBefore = Get-Implied "negative"

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

# ---- 隐式信号 ② 的刺激：一次真的会走 FALLBACK 的请求 ----
# 用「转人工」：ADR 0042 之后它稳定落 USER_REQUESTED，是本仓现成的降级路径（verify-fallback.ps1 也靠它）。
# 它会给工单队列多落一条——不影响断言一/二/三，那三条按 feedbackId 与队列行匹配，不数总数。
(Get-ChatResult -Token $token -Query "转人工" -Conversation "$conv-fallback") | Out-Null

# 工单关联：点踩会话若走过降级，行上应有 ticketId；无降级会话则该字段为空是正确形态
$negAfter = Get-Implied "negative"
if ($negAfter -gt $negBefore) {
    $Pass++
    Write-Host ("PASS  隐式信号② 降级计数 negative {0} -> {1}" -f $negBefore, $negAfter)
} else {
    $Fail++
    Write-Host "FAIL  隐式信号② 降级计数没有增长"
}

# ---- 隐式信号 ①：重问（implied_dissatisfied）—— 同会话同意图连续问两遍 ----
$repeatBefore = Get-Implied "implied_dissatisfied"
Get-ChatResult -Token $token -Query "帮我查一下订单90001现在什么状态" -Conversation "$conv-repeat" | Out-Null
Get-ChatResult -Token $token -Query "帮我查一下订单90001现在什么状态" -Conversation "$conv-repeat" | Out-Null
$repeatAfter = Get-Implied "implied_dissatisfied"
if ($repeatAfter -gt $repeatBefore) {
    $Pass++
    Write-Host ("PASS  隐式信号① 重问计数 implied_dissatisfied {0} -> {1}" -f $repeatBefore, $repeatAfter)
} else {
    $Fail++
    Write-Host "FAIL  隐式信号① 重问计数没有增长"
}

# ---- 隐式信号 ③：幂等重放（implied_retry）—— 同 token 重复提交退款 ----
# 两个前置必须都满足，否则这条断言测的不是幂等（2026-09-27 票 53 更正两处）：
#   ① **必须是订单的拥有者**。90001-90004 是 SeedRunner 的演示固定单，拥有者是 C001（DEMO_CUSTOMER）；
#      用别的买家问会走归属双条件判 NOT_FOUND，写操作不成功 → 幂等结果不落库（IdempotencyService 只在
#      业务 OK 时存结果，非 OK 走 abandon）→ 第二次天然不算重放。本脚本原先用 C155 问 90002，
#      退款的第一次就永远不会成功，这条断言等于在测一个不可能发生的事。
#   ② **幂等键要按本次进程取**。结果按 token 存 Redis，写死 token 会让**下一次跑脚本的第一次请求**
#      一开始就是重放，断言可能假绿。同时写操作会改订单状态，跑前把演示固定单复位（与 run_tool_eval 同例）。
Invoke-RestMethod -Uri "$Base/api/v1/support/ops/demo/reset" -Method Post `
    -Headers @{ Authorization = "Bearer $token"; "X-Ops-Token" = "dev-ops-token" } `
    -ContentType "application/json" -Body '{}' | Out-Null
$refundToken = Get-Token -Tenant "T001" -Customer "C001"
$retryBefore = Get-Implied "implied_retry"
$refundHeaders = @{ Authorization = "Bearer $refundToken"; "X-Conversation-Id" = "$conv-retry" }
$retryIdem = "verify-feedback-retry-$PID"
$refundBody = '{"query":"帮我给订单90002申请退款，原因是质量问题","idempotencyToken":"' + $retryIdem + '"}'
Invoke-RestMethod -Uri "$Base/api/v1/support/chat" -Method Post -Headers $refundHeaders `
    -ContentType "application/json; charset=utf-8" -Body $refundBody | Out-Null
Invoke-RestMethod -Uri "$Base/api/v1/support/chat" -Method Post -Headers $refundHeaders `
    -ContentType "application/json; charset=utf-8" -Body $refundBody | Out-Null
$retryAfter = Get-Implied "implied_retry"
if ($retryAfter -gt $retryBefore) {
    $Pass++
    Write-Host ("PASS  隐式信号③ 幂等重放计数 implied_retry {0} -> {1}" -f $retryBefore, $retryAfter)
} else {
    $Fail++
    Write-Host "FAIL  隐式信号③ 幂等重放计数没有增长"
}

Write-Host ("`n反馈闭环验收：PASS {0} / FAIL {1}" -f $Pass, $Fail)
if ($Fail -gt 0) { exit 1 }
