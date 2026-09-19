# 票 36 活体验收：情绪门 20 条用例（对应 eval/cases-part4-emotion.jsonl）。
# 8 条词典层升级 → 必须在 TRIAGE 之前落 EMOTION_ESCALATION 工单（priority=high，队列反查）；
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
    return Invoke-RestMethod -Uri "$Base/api/v1/support/chat" -Method Post -Headers $headers `
        -ContentType "application/json; charset=utf-8" -Body $body
}

# 8 条词典层升级样本（id, 期望 emotion）；情绪门在 TRIAGE 前定案，落到 priority=high 工单
$escalations = @(
    @{ id = "EMO-ESC-01"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-02"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-03"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-04"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-05"; emotion = "URGENT" },
    @{ id = "EMO-ESC-06"; emotion = "URGENT" },
    @{ id = "EMO-ESC-07"; emotion = "ANGRY" },
    @{ id = "EMO-ESC-08"; emotion = "ANGRY" }
)
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

$token = Get-Token -Tenant "T001" -Customer "C155"
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

# 队列反查：升级工单必须可按号查回，且 priority=high（ADR 0034）
foreach ($id in $ticketIds) {
    $ticket = Invoke-RestMethod -Uri "$Base/api/tickets/$id" -Headers @{ Authorization = "Bearer $token" }
    if ($ticket.priority -eq "high") {
        $Pass++
        Write-Host ("PASS  工单 {0} priority=high 可反查" -f $id)
    } else {
        $Fail++
        Write-Host ("FAIL  工单 {0} priority={1}（期望 high）" -f $id, $ticket.priority)
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
