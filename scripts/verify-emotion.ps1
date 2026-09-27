# 票 36 活体验收：情绪门用例（对应 eval/cases-part4-emotion.jsonl）。
# 8 条**情绪驱动**的升级 → 必须在 TRIAGE 之前落 EMOTION_ESCALATION 工单（priority=high，队列反查）；
# 1 条**显式转人工** → 按 ADR 0042 落 USER_REQUESTED（不是 EMOTION_ESCALATION，也不带 high）；
# 12 条非升级 → 不得出现任何 fallback。词典层用例 0 token（不触发任何模型调用）。
# 前置：网关起在 dev/local（需 Ollama 或 DashScope 配置）+ biz-mock + 容器栈。
# 用法：pwsh -NoProfile -File scripts/verify-emotion.ps1
# 词典层判据另跑：mvnw.cmd test -Dtest=SentimentGateTest（0 token，见票 36）
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8082"
$Pass = 0; $Fail = 0

function Get-Token([string]$Tenant, [string]$Customer) {
    $body = '{"tenantId":"' + $Tenant + '","customerId":"' + $Customer + '"}'
    $res = Invoke-RestMethod -Uri "$Base/auth/mock-token" -Method Post -ContentType "application/json" -Body $body
    return $res.token
}

function Get-ChatResult([string]$Token, [string]$Query) {
    $headers = @{ Authorization = "Bearer $Token"; "X-Conversation-Id" = "verify-emotion-$PID-$(Get-Random)" }
    $body = '{"query":' + ($Query | ConvertTo-Json) + '}'
    # 429 不许把整条验收崩掉：本机多个 verify 脚本共用同一批客户，前序步骤的 burst（verify-plan 的
    # ticket-13 连打）可能刚好把这桶打干——2026-09-20 首跑就是在这里以 429 中断，只跑了 5 条用例。
    # 按 Retry-After 退避重试；退完仍 429 才让这一条走 FAIL，而不是中断整份报告。
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            return Invoke-RestMethod -Uri "$Base/api/v1/support/chat" -Method Post -Headers $headers `
                -ContentType "application/json; charset=utf-8" -Body $body
        } catch {
            $status = $_.Exception.Response.StatusCode.value__
            if ($status -ne 429 -or $attempt -eq 4) { throw }
            Start-Sleep -Seconds (5 * $attempt)
        }
    }
}

# 8 条**情绪驱动**的升级样本（id, 期望 emotion）；情绪门在 TRIAGE 前定案，落到 priority=high 工单。
# 2026-09-27（票 54 / ADR 0045）：EMO-ESC-02 移出到下面的 $explicitEscalations，由新增的
# EMO-ESC-09 补位——所以「8 条」这个计数不变，变的是成员。原 02 的问句含 T0 升级词表里的
# 「转人工」，ADR 0042 之后它按显式转人工优先，不再走情绪短路。
$escalations = @(
    @{ id = "EMO-ESC-01"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-03"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-04"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-05"; emotion = "URGENT" },
    @{ id = "EMO-ESC-06"; emotion = "URGENT" },
    @{ id = "EMO-ESC-07"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-08"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-09"; emotion = "ANGRY" }
)
# 1 条**显式转人工**样本：问句含「转人工」，按 ADR 0042 在 TRIAGE 之前不走情绪短路，放行到
# triage 由既有 USER_REQUESTED 出口收口。判据两条：reason 必须是 USER_REQUESTED，且工单
# **不带** high——只有 EMOTION_ESCALATION 才传 high（AgentStateMachine 的 escalate 分支），
# 所以「带不带 high」正是两类出口的区分点，钉住它才不至于把两者混成"都是升级"。
$explicitEscalations = @("EMO-ESC-02")
# 查询原文与 eval/cases-part4-emotion.jsonl 一一对应（id -> query），拷自该文件
$queries = @{
    "EMO-ESC-01" = "你们就是骗子！订单SO20260901001拖了半个月不退款，我今天必须拿到说法，不然就去投诉到底"
    "EMO-ESC-02" = "说的三天到现在一周了物流一动不动，什么破店，给我转人工马上"
    "EMO-ESC-03" = "买了这么个破玩意儿还敢收我运费？黑心商家，我要曝光你们"
    "EMO-ESC-04" = "第三次问了！每次都说稍等每次都没结果，再不解决我直接打12315投诉你们店"
    "EMO-ESC-05" = "急急急！快递马上就要发货了，我下单地址填错了，现在立刻马上帮我改掉"
    "EMO-ESC-06" = "我妈住院等着这笔退款交费，求你们今天一定帮我处理一下订单SO20260901002的退款"
    "EMO-ESC-07" = "客服全是机器人踢皮球，一个能办事的都没有，我要找真人领导谈"
    "EMO-ESC-08" = "退款拖了这么久还有脸让我等？废物系统，给我人工处理现在"
    "EMO-ESC-09" = "什么破玩意儿！收到就是坏的，你们这质量也太差了"
    "EMO-CAL-01" = "请问七天无理由退货需要我自己出运费吗"
    "EMO-CAL-02" = "帮我查一下订单SO20260901003现在什么状态了"
    "EMO-CAL-03" = "物流一般多久更新一次轨迹呀"
    "EMO-CAL-04" = "之前申请的退款进度帮忙看一下，不着急，麻烦了"
    "EMO-DIS-01" = "物流有点慢了啊，这都第五天了还没到，能帮我看看吗"
    "EMO-DIS-02" = "运费还要我承担，感觉不太合理吧，你们这政策是不是该改改"
    "EMO-DIS-03" = "商品和描述有点出入，虽然不算大问题但还是有点失望，帮我查下能不能退"
    "EMO-SAR-01" = "你们服务可真是太棒了呢，问什么都是让看FAQ，真有你们的"
    "EMO-SAR-02" = "哦，又是在路上呢，行吧，反正也不差这两天了"
    "EMO-NEG-01" = "这个商品可以退吗"
    "EMO-NEG-02" = "帮我改一下收货地址，订单号SO20260901004，新的地址是上海市浦东新区世纪大道100号"
    "EMO-NEG-03" = "快递说派送了但我没收到货，这是怎么回事"
}

# 独立客户 C198：不与 verify-plan（C155 的 burst）、verify-channel、verify-style 共用限流桶——
# 共用会把这个脚本的成败绑到前序步骤的连打上，2026-09-20 首跑就是这么吃到 429 的。
$token = Get-Token -Tenant "T001" -Customer "C198"
$ticketIds = @()

foreach ($e in $escalations) {
    $result = Get-ChatResult -Token $token -Query $queries[$e.id]
    $ok = ($result.fallbackReason -eq "EMOTION_ESCALATION") -and $result.ticketId
    if ($ok) {
        $ticketIds += $result.ticketId
        $Pass++
        Write-Host ("PASS  {0} 落 EMOTION_ESCALATION 工单 {1}" -f $e.id, $result.ticketId)
    } else {
        $Fail++
        Write-Host ("FAIL  {0} 期望 EMOTION_ESCALATION 工单，实际 fallbackReason={1}" -f $e.id, $result.fallbackReason)
    }
}

# ---- 1 条显式转人工：ADR 0042 之后落 USER_REQUESTED，不走情绪短路 ----
$explicitTicketIds = @()
foreach ($id in $explicitEscalations) {
    $result = Get-ChatResult -Token $token -Query $queries[$id]
    if ($result.fallbackReason -eq "USER_REQUESTED" -and $result.ticketId) {
        $explicitTicketIds += $result.ticketId
        $Pass++
        Write-Host ("PASS  {0} 显式转人工落 USER_REQUESTED 工单 {1}" -f $id, $result.ticketId)
    } else {
        $Fail++
        Write-Host ("FAIL  {0} 期望 USER_REQUESTED 工单（ADR 0042 显式优先），实际 fallbackReason={1}" -f $id, $result.fallbackReason)
    }
}

# 队列反查：升级工单必须可按号查回，且 priority=high（ADR 0034）。
# 走网关的运维代理（与 verify-fallback 同一条路）：`/api/tickets/{id}` 这条路由不存在，
# 2026-09-20 首跑就崩在这里（"接口不存在"），整份报告只跑到第 8 条。
# **2026-09-27 更正（票 54）**：这里原先只带 X-Ops-Token、没带 bearer，而该端点走 JWT 鉴权，
# 于是返回 401「missing bearer token」→ 本脚本 ErrorActionPreference=Stop，**在中止前
# 下面 8 条 priority 反查与 12 条非升级断言全都没跑**（矩阵里这一步长期只跑到第 9 条）。
$queue = Invoke-RestMethod -Uri "$Base/api/v1/support/ops/tickets" `
    -Headers @{ Authorization = "Bearer $token"; "X-Ops-Token" = "dev-ops-token" }
foreach ($id in $ticketIds) {
    $ticket = $queue | Where-Object { $_.id -eq $id } | Select-Object -First 1
    if (-not $ticket) {
        $Fail++
        Write-Host ("FAIL  工单 {0} 在队列里查不到" -f $id)
    } elseif ($ticket.priority -eq "high") {
        $Pass++
        Write-Host ("PASS  工单 {0} priority=high 可反查" -f $id)
    } else {
        $Fail++
        Write-Host ("FAIL  工单 {0} priority={1}（期望 high）" -f $id, $ticket.priority)
    }
}
# 显式转人工那张单必须**不带** high——这是它与情绪升级单的区分点（票 54）：
# 只有 EMOTION_ESCALATION 才传 high，钉住它才不至于把两类出口混成"都是升级"。
foreach ($id in $explicitTicketIds) {
    $ticket = $queue | Where-Object { $_.id -eq $id } | Select-Object -First 1
    if (-not $ticket) {
        $Fail++
        Write-Host ("FAIL  显式转人工工单 {0} 在队列里查不到" -f $id)
    } elseif ($ticket.priority -ne "high") {
        $Pass++
        Write-Host ("PASS  显式转人工工单 {0} 不带 high（priority={1}）" -f $id, $ticket.priority)
    } else {
        $Fail++
        Write-Host ("FAIL  显式转人工工单 {0} 带了 high——只有情绪升级才该带（ADR 0042/0034）" -f $id)
    }
}

# 12 条非升级样本：不出现任何 fallback（dev 口径下由第二层 LLM 分类兜底）
foreach ($id in @("EMO-CAL-01","EMO-CAL-02","EMO-CAL-03","EMO-CAL-04","EMO-DIS-01","EMO-DIS-02",
                  "EMO-DIS-03","EMO-SAR-01","EMO-SAR-02","EMO-NEG-01","EMO-NEG-02","EMO-NEG-03")) {
    $result = Get-ChatResult -Token $token -Query $queries[$id]
    if (-not $result.fallbackReason) {
        $Pass++
        Write-Host ("PASS  {0} 未误升级" -f $id)
    } else {
        $Fail++
        Write-Host ("FAIL  {0} 被误升级 reason={1}" -f $id, $result.fallbackReason)
    }
}

Write-Host ("`n情绪门验收：PASS {0} / FAIL {1}" -f $Pass, $Fail)
if ($Fail -gt 0) { exit 1 }
