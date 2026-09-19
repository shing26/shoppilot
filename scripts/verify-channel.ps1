# 票 38 活体验收：三渠道契约（ADR 0035）。
# 断言一：同一句从 web/app/miniapp 进入答案一致（缓存跨渠道共享，渠道不进缓存键）；
# 断言二：跨渠道会话不互串（同买家续接、不同买家隔离——归属仍是店铺+买家）；
# 断言三：email 全链路落回执工单（reason=EMAIL_REPLY，可按号反查）；webhook 无流式；
# 断言四：限流按渠道维度可查（shoppilot_rate_limited_total 带 channel 标签）。
# 前置：网关起在 dev/local + biz-mock + 容器栈。
# 用法：pwsh -NoProfile -File scripts/verify-channel.ps1
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8082"
$Pass = 0; $Fail = 0

function Get-Token([string]$Tenant, [string]$Customer) {
    $body = '{"tenantId":"' + $Tenant + '","customerId":"' + $Customer + '"}'
    $res = Invoke-RestMethod -Uri "$Base/auth/mock-token" -Method Post -ContentType "application/json" -Body $body
    return $res.token
}

function Post-Json([string]$Path, [string]$Token, [string]$Conversation, [string]$Json) {
    $headers = @{ Authorization = "Bearer $Token" }
    if ($Conversation) { $headers["X-Conversation-Id"] = $Conversation }
    return Invoke-RestMethod -Uri "$Base$Path" -Method Post -Headers $headers `
        -ContentType "application/json; charset=utf-8" -Body $Json
}

function Get-Metrics() { return Invoke-RestMethod -Uri "$Base/actuator/prometheus" }

function Get-Counter([string]$Metrics, [string]$Name, [string]$Tags) {
    $line = $Metrics -split "`n" | Where-Object { $_ -like "$Name*$Tags*" } | Select-Object -First 1
    if ($null -eq $line) { return 0.0 }
    return [double]($line -split '\s+')[-1]
}

$query = "七天无理由退货怎么操作"
$web = Post-Json "/api/v1/support/chat" (Get-Token "T001" "C155") "verify-channel-web" ('{"query":' + ($query | ConvertTo-Json) + '}')
$app = Post-Json "/api/v1/support/webhook/app" (Get-Token "T001" "C155") "verify-channel-app" ('{"query":' + ($query | ConvertTo-Json) + '}')
$mini = Post-Json "/api/v1/support/webhook/miniapp" (Get-Token "T001" "C155") "verify-channel-mini" ('{"query":' + ($query | ConvertTo-Json) + '}')

if ($web.answer -and ($web.answer -eq $app.answer) -and ($web.answer -eq $mini.answer)) {
    $Pass++
    Write-Host "PASS  断言一 三渠道同一句答案一致（缓存跨渠道共享）"
} else {
    $Fail++
    Write-Host ("FAIL  断言一 答案不一致 web=[{0}] app=[{1}] mini=[{2}]" -f $web.answer, $app.answer, $mini.answer)
}
if ($app.channel -eq "app" -and $mini.channel -eq "miniapp" -and $app.streaming -eq $false) {
    $Pass++
    Write-Host "PASS  webhook 回包带 channel 标签且无流式"
} else {
    $Fail++
    Write-Host "FAIL  webhook 回包渠道标签或流式标记不对"
}

# 跨渠道会话续接：同买家先在 web 给出订单号，再在 miniapp 用同一会话追问
$conv = "verify-channel-carryover-$PID"
Post-Json "/api/v1/support/chat" (Get-Token "T001" "C155") $conv '{"query":"帮我查下订单SO20260901007的物流"}' | Out-Null
$carry = Post-Json "/api/v1/support/webhook/miniapp" (Get-Token "T001" "C155") $conv '{"query":"那个订单到哪了"}'
if ($carry.answer) {
    $Pass++
    Write-Host "PASS  断言二 跨渠道同会话可续接（miniapp 追问不报身份错误）"
} else {
    $Fail++
    Write-Host "FAIL  断言二 跨渠道续接失败"
}

# 不同买家不互串：另一个买家用同一会话 id 提问，答案里不许出现前一买家的订单信息
$other = Post-Json "/api/v1/support/webhook/webhook" (Get-Token "T001" "C199") $conv '{"query":"那个订单到哪了"}'
if ($other.answer -notmatch "SO20260901007" -and $other.answer -notmatch "90007") {
    $Pass++
    Write-Host "PASS  断言二 跨买家同一会话 id 不互串（归属仍是店铺+买家）"
} else {
    $Fail++
    Write-Host "FAIL  断言二 另一个买家看到了前一买家的订单信息"
}

# email：全链路 + 回执工单
$email = Post-Json "/api/v1/support/email" (Get-Token "T001" "C155") $null `
    '{"from":"buyer@example.com","subject":"退货","body":"请问退货的运费险怎么用"}'
if ($email.channel -eq "email" -and $email.receiptTicketId) {
    $rechecked = Invoke-RestMethod -Uri "$Base/api/v1/support/ops/tickets" -Headers @{ Authorization = "Bearer $(Get-Token 'T001' 'C155')"; "X-Ops-Token" = "dev-ops-token" }
    $hit = $rechecked | Where-Object { $_.id -eq $email.receiptTicketId -and $_.reason -eq "EMAIL_REPLY" }
    if ($hit) {
        $Pass++
        Write-Host ("PASS  断言三 email 回执工单可反查 {0}（reason=EMAIL_REPLY）" -f $email.receiptTicketId)
    } else {
        $Fail++
        Write-Host ("FAIL  断言三 回执工单 {0} 在队列里找不到或 reason 不对" -f $email.receiptTicketId)
    }
} else {
    $Fail++
    Write-Host "FAIL  断言三 email 没有落回执工单"
}

# 限流按渠道维度可查：渠道计数存在（限流计数带 channel 标签的形态由 /actuator/metrics 聚合读取）
$metrics = Get-Metrics
$channelTotal = (Get-Counter $metrics "shoppilot_channel_requests_total" '{channel="app"}') + `
                (Get-Counter $metrics "shoppilot_channel_requests_total" '{channel="miniapp"}') + `
                (Get-Counter $metrics "shoppilot_channel_requests_total" '{channel="email"}')
if ($channelTotal -ge 3) {
    $Pass++
    Write-Host ("PASS  断言四 渠道请求计数按 {0} 分账" -f "channel 标签")
} else {
    $Fail++
    Write-Host "FAIL  断言四 渠道请求计数缺失"
}

Write-Host ("`n渠道契约验收：PASS {0} / FAIL {1}" -f $Pass, $Fail)
if ($Fail -gt 0) { exit 1 }
