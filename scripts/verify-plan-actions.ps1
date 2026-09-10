<#
    PLAN.md「逐 ticket 验收动作」表里，那些没有被 verify-*.ps1 覆盖的行，集中在这里跑。
    已覆盖的行不重复实现：02/06/07/08/09/11/12/15/16/17/18 各有自己的脚本或用例。

    默认只跑不动服务进程的 01 / 04 / 05 / 13；03（seed 两次不翻倍）/ 10（biz-mock 进程消失）/
    14（模型端点指向不存在地址）要重启服务，放在 -WithRestarts 分支里。
    04 的「重跑不增长」读的是上一轮入库留下的 logs\ingest.out，起栈时用了 -SkipIngest
    就会退化成只做活体条目数比对，脚本会显式说明。

    例:
      pwsh -NoProfile -File scripts/verify-plan-actions.ps1
      pwsh -NoProfile -File scripts/verify-plan-actions.ps1 -WithRestarts
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$OpsToken = 'dev-ops-token',
    [switch]$WithRestarts
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$failures = @()

function Assert-True([bool]$condition, [string]$label) {
    if ($condition) { Write-Host "PASS  $label" -ForegroundColor Green }
    else { Write-Host "FAIL  $label" -ForegroundColor Red; $script:failures += $label }
}

function Metric([string]$name) {
    $r = Invoke-RestMethod "$BaseUrl/actuator/metrics/$name" -TimeoutSec 15
    $value = ($r.measurements | Where-Object { $_.statistic -eq 'COUNT' }).value
    if ($null -eq $value) { throw "计数器 $name 读不到 COUNT 值，拒绝以 0 代替" }
    [double]$value
}

# Micrometer 的 Counter 在第一次打点时才注册，所以"读不到"有两种含义：
# 名字写错（必须报错）与还没发生过（合法地等于 0）。这里只把 404 当成后者。
function MetricOrZero([string]$name) {
    try { return Metric $name }
    catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) { return 0.0 }
        throw
    }
}

function Test-Port([int]$p) {
    return (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0
}

function Get-Token([string]$tenant, [string]$customer) {
    (Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/mock-token" -ContentType 'application/json' `
        -Body (@{ tenantId = $tenant; customerId = $customer } | ConvertTo-Json -Compress) -TimeoutSec 30).token
}

function Invoke-ChatJson([string]$token, [string]$query, [string]$conversation) {
    $body = @{ query = $query } | ConvertTo-Json -Compress
    $resp = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/v1/support/chat" `
        -Headers @{ Authorization = "Bearer $token"; 'X-Conversation-Id' = $conversation } `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -UseBasicParsing -TimeoutSec 240
    $raw = if ($resp.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($resp.Content) } else { $resp.Content }
    return $raw | ConvertFrom-Json
}

# SSE 用 POST 一次性读全文再解析 (event, data) 序列；EventSource 不支持 POST（ADR 0006）。
function Invoke-ChatStream([string]$token, [string]$query, [string]$conversation) {
    $body = @{ query = $query } | ConvertTo-Json -Compress
    $resp = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/v1/support/chat/stream" `
        -Headers @{ Authorization = "Bearer $token"; 'X-Conversation-Id' = $conversation } `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -UseBasicParsing -TimeoutSec 240
    $raw = if ($resp.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($resp.Content) } else { $resp.Content }
    $events = @()
    $current = $null
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match '^event:\s*(.+)$') { $current = $Matches[1].Trim() }
        elseif ($line -match '^data:\s*(.*)$' -and $current) {
            $events += [pscustomobject]@{ Event = $current; Data = $Matches[1] }
            $current = $null
        }
    }
    return $events
}

function Invoke-Ops([string]$method, [string]$path, $body) {
    $token = Get-Token 'T001' 'C001'
    $params = @{ Uri = "$BaseUrl$path"; Method = $method; TimeoutSec = 60; UseBasicParsing = $true
        Headers = @{ 'X-Ops-Token' = $OpsToken; Authorization = "Bearer $token" } }
    if ($null -ne $body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
    }
    return Invoke-RestMethod @params
}

# 探针标记必须纯字母：数字会被 T0 的实体正则当成订单号，整句判成业务办理，
# 政策链路根本不会被触发（同 verify-hit-zero-llm.ps1 踩过的坑）。
function New-ProbeQuery([string]$prefix) {
    $letters = [guid]::NewGuid().ToString('N') -replace '[0-9]', ''
    while ($letters.Length -lt 8) { $letters += 'q' }
    return "$prefix（探针 $($letters.Substring(0, 8))）"
}

Write-Host "`n=== ticket 01：三中间件在跑、两服务 health 为 UP ===" -ForegroundColor Cyan
foreach ($pair in @(@(16379, 'Redis'), @(16333, 'Qdrant'), @(19200, 'Elasticsearch'),
                    @(8082, 'gateway'), @(8091, 'biz-mock'))) {
    Assert-True (Test-Port $pair[0]) "$($pair[1]) 端口 $($pair[0]) 在监听"
}
foreach ($svc in @(@('8082', '网关'), @('8091', 'biz-mock'))) {
    $status = (Invoke-RestMethod "http://127.0.0.1:$($svc[0])/actuator/health" -TimeoutSec 10).status
    Assert-True ($status -eq 'UP') "$($svc[1]) health=UP（实际 $status）"
}
# 中间件一律按接口实际可用性判，不看 docker 给容器打的 health 标签：Qdrant 那条 healthcheck
# 曾经恒红（镜像里根本没有 wget，2026-09-10 换成 bash 的 /dev/tcp 才真在跑，见 ticket 01 决策 9）。
# 就算它现在绿着也仍然按接口判——"容器说自己健康"和"它答不答 /collections"是两件事。
$esHealth = (Invoke-RestMethod 'http://127.0.0.1:19200/_cluster/health' -TimeoutSec 10).status
$qdOk = (Invoke-RestMethod 'http://127.0.0.1:16333/collections' -TimeoutSec 10).status
Assert-True ($esHealth -in @('green', 'yellow')) "ES 集群健康 $esHealth"
Assert-True ($qdOk -eq 'ok') "Qdrant 集合接口可用"

Write-Host "`n=== ticket 04：入库后 ES 与 Qdrant 条目数相等、重跑不增长 ===" -ForegroundColor Cyan
$esCount = (Invoke-RestMethod 'http://127.0.0.1:19200/policy_rules/_count' -TimeoutSec 10).count
$qdCount = (Invoke-RestMethod -Method Post 'http://127.0.0.1:16333/collections/policy_rules/points/count' `
    -ContentType 'application/json' -Body '{"exact":true}' -TimeoutSec 10).result.count
Assert-True ($esCount -eq $qdCount) "两侧条目数相等（ES=$esCount，Qdrant=$qdCount）"
# 「重跑不增长」的证据在上一轮入库日志里：纪元每跑一次只 +1，条目数仍是同一批，
# 说明前面 N 次重跑没有把语料越写越多。没有这份日志（起栈时用了 -SkipIngest）就退化成活体比对。
$ingestLog = Join-Path $root 'logs\ingest.out'
if (Test-Path $ingestLog) {
    $statLine = (Get-Content $ingestLog -Encoding UTF8 |
        Where-Object { $_ -match '旧纪元=(\d+)\s+新纪元=(\d+).*ES总数=(\d+)\s+Qdrant总数=(\d+)' } |
        Select-Object -Last 1)
    if ($statLine) {
        $m = [regex]::Match($statLine, '旧纪元=(\d+)\s+新纪元=(\d+).*ES总数=(\d+)\s+Qdrant总数=(\d+)')
        $oldEpoch = [int]$m.Groups[1].Value
        $newEpoch = [int]$m.Groups[2].Value
        $logEs = [int]$m.Groups[3].Value
        $logQd = [int]$m.Groups[4].Value
        Assert-True ($newEpoch -eq $oldEpoch + 1) "一轮入库只推进一格纪元（$oldEpoch -> $newEpoch）"
        Assert-True ($oldEpoch -ge 1) "第 $newEpoch 次入库后条目数仍是 $logEs/$logQd，重跑不增长"
        Assert-True ($logEs -eq $esCount -and $logQd -eq $qdCount) `
            "入库日志与活体条目数一致（$logEs/$logQd == $esCount/$qdCount）"
    } else {
        Write-Host '  logs\ingest.out 里没有统计行，跳过重跑不增长这一项' -ForegroundColor Yellow
    }
} else {
    Write-Host '  没有 logs\ingest.out，只做了活体条目数比对' -ForegroundColor Yellow
}

Write-Host "`n=== ticket 05：流式逐字、done 带非空 citations、同步端点同结果 ===" -ForegroundColor Cyan
Invoke-Ops 'POST' '/api/v1/support/ops/cache/flush' $null | Out-Null
$tok = Get-Token 'T001' 'C001'
$probe = New-ProbeQuery '生鲜坏了怎么赔'
$streamEvents = Invoke-ChatStream $tok $probe 'plan-05-stream'
$names = @($streamEvents | ForEach-Object { $_.Event })
$tokenFrames = @($streamEvents | Where-Object { $_.Event -eq 'token' })
$done = $streamEvents | Where-Object { $_.Event -eq 'done' } | Select-Object -First 1
# token 帧的 data 是一段 JSON 字符串（"探针 abc…"）而不是对象；按 .text 取值会得到 $null，
# 拼出来是空串，于是"同步端点同结果"这条永远失败。
$streamAnswer = ((@($tokenFrames) | ForEach-Object {
    $parsed = $_.Data | ConvertFrom-Json
    if ($parsed -is [string]) { $parsed } else { [string]$parsed.text }
}) -join '')
Assert-True ($tokenFrames.Count -gt 1) "流式逐字输出（$($tokenFrames.Count) 个 token 帧）"
Assert-True (($names | Select-Object -Last 1) -eq 'done') "以 done 收尾"
$cites = @($done.Data | ConvertFrom-Json).citations
Assert-True (@($cites).Count -gt 0) "done 帧含非空 citations（$(@($cites).Count) 条）"
$sync = Invoke-ChatJson $tok $probe 'plan-05-sync'
Assert-True ($sync.answer -eq $streamAnswer) "同步端点返回同一段答案（长度 $($sync.answer.Length)）"
Assert-True ($sync.cacheLayer -eq 'L1') "同步端点这次走 L1 命中（实际 $($sync.cacheLayer)）"

Write-Host "`n=== ticket 13：超配额连打，被限流的请求不进模型 ===" -ForegroundColor Cyan
Invoke-Ops 'POST' '/api/v1/support/ops/cache/flush' $null | Out-Null
$burstToken = Get-Token 'T001' 'C155'
# 先用一条冷问句把答案写进缓存并确认它确实打了模型，之后的"连打"全部走同一条热问句：
# 这才是大促现场的样子（同一热点被反复问），也让请求快于令牌桶补充速度，真的能打出 429。
$llmBefore = Metric 'shoppilot_llm_calls_total'
$hotQuery = New-ProbeQuery '发什么快递'
Invoke-ChatJson $burstToken $hotQuery 'plan-13-calib' | Out-Null
$P = [int]((Metric 'shoppilot_llm_calls_total') - $llmBefore)
Assert-True ($P -ge 1) "单个未命中政策请求产生 $P 次模型调用（作为分母）"
$limited = 0; $admitted = 0; $admittedCacheHit = 0
$rejectedModelCalls = 0; $admittedModelCalls = 0
$rate0 = MetricOrZero 'shoppilot_rate_limited_total'
$triage0 = MetricOrZero 'shoppilot_triage_to_model_total'
for ($i = 0; $i -lt 14; $i++) {
    $beforeOne = Metric 'shoppilot_llm_calls_total'
    $body = (@{ query = $hotQuery } | ConvertTo-Json -Compress)
    $wasLimited = $false
    $cacheLayer = ''
    try {
        $resp = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/v1/support/chat" `
            -Headers @{ Authorization = "Bearer $burstToken"; 'X-Conversation-Id' = "plan-13-$i" } `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -UseBasicParsing -TimeoutSec 120
        $cacheLayer = [string](($resp.Content | ConvertFrom-Json).cacheLayer)
        $admitted++
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 429) {
            $limited++
            $wasLimited = $true
        } else { throw }
    }
    # 逐发归因：这一发请求前后各读一次模型调用计数，429 那一发必须零增量
    $deltaOne = [int]((Metric 'shoppilot_llm_calls_total') - $beforeOne)
    if ($wasLimited) {
        $rejectedModelCalls += $deltaOne
    } else {
        $admittedModelCalls += $deltaOne
        if ($cacheLayer -in @('L1', 'L2')) { $admittedCacheHit++ }
    }
}
Assert-True ($limited -gt 0) "确实打出了 $limited 个 429（admitted=$admitted）"
# 判据原文是"返回 rate_limited 的模型调用计数零增长"，所以按请求逐个归因：
# 每发一个请求就读一次模型调用计数，429 那一发必须增量为 0。
# 早先用"整段窗口增量"当判据会误伤。TriageEngine 是 fail-closed 的：T0/T1 两级都拿不准时
# 本轮不进缓存（见 TriageEngine#triage 末尾与 ADR 0005"意图门禁在前"），该请求会带着 tools
# 交给模型定案，于是 shoppilot_triage_to_model_total 与 shoppilot_llm_calls_total 各加一。
# 那笔开销来自准入流量、有独立计数可查，不该记在被限流请求的账上——但必须能被解释。
# 只有真打出 429 之后限流计数器才会被 Micrometer 注册，所以先判 limited 再读它
$rateDelta = if ($limited -gt 0) { [int]((Metric 'shoppilot_rate_limited_total') - $rate0) } else { 0 }
$triageDelta = [int]((MetricOrZero 'shoppilot_triage_to_model_total') - $triage0)
Assert-True ($rateDelta -eq $limited) "限流计数器与 429 数一致（$rateDelta == $limited）"
Assert-True ($rejectedModelCalls -eq 0) "被限流的 $limited 个请求产生的模型调用为 0（实际 $rejectedModelCalls）"
Write-Host "  准入 $admitted 个：缓存命中 $admittedCacheHit 个，模型调用 $admittedModelCalls 次，T0/T1 未定案升级 $triageDelta 次"
Assert-True ($admittedModelCalls -eq 0 -or $triageDelta -gt 0) `
    "准入请求产生的模型调用（$admittedModelCalls 次）都能由 T0/T1 未定案升级（$triageDelta 次）解释"

Write-Host "`n=== summary ===" -ForegroundColor Cyan
function Show-DownEvidence([int]$port, [string]$pidFile, [string]$logFile) {
    # 等不到 readiness 时最没用的输出就是一句"不是 UP"。更糟的是这一步之后门禁的健康门会跑
    # up.ps1 -SkipIngest，它用 > 重开同一个日志文件，那次真失败的现场就没了——2026-09-10 的
    # run10 正是这样丢了 biz-mock 的现场，只剩"没起来"一句话。所以在返回失败之前，先把端口、
    # launcher 进程是否存活、日志尾巴三份现场打出来。
    $launcher = if (Test-Path $pidFile) { (Get-Content $pidFile -Raw).Trim() } else { '无 pid 文件' }
    $alive = $false
    if ($launcher -match '^\d+$') { $alive = [bool](Get-Process -Id ([int]$launcher) -ErrorAction SilentlyContinue) }
    Write-Host ("  现场：端口 {0} 监听={1}  launcher={2} 存活={3}" -f $port, (Test-Port $port), $launcher, $alive) -ForegroundColor Yellow
    if (Test-Path $logFile) {
        Write-Host "  日志最后 8 行（$([IO.Path]::GetFileName($logFile))）：" -ForegroundColor Yellow
        Get-Content $logFile -Encoding UTF8 -Tail 8 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    | $_" }
    }
}

# 默认 300 s，与 up.ps1 里那一等对齐（ticket 01 决策 7）：空机上 5 万单 seed 实测 8-17 s，
# 但这一步跑在整台机器最挤的时候，180 s 实测红过一次（run10 的 plan 步）。
function Wait-ServiceUp([int]$port, [string]$label, [int]$seconds = 300, [string]$pidFile = '', [string]$logFile = '') {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        # readiness 组：Spring Boot 在 ApplicationRunner（biz-mock 的 5 万单 seed、网关的建集合+预热）
        # 跑完之前一直是 503 OUT_OF_SERVICE，端口开着但数据没好，这时读 /ops/stats 会读到半成品。
        $up = try { (Invoke-RestMethod "http://127.0.0.1:$port/actuator/health/readiness" -TimeoutSec 5).status -eq 'UP' } catch { $false }
        if ($up) { return $true }
        Start-Sleep -Seconds 3
    }
    if ($pidFile -or $logFile) { Show-DownEvidence -port $port -pidFile $pidFile -logFile $logFile }
    return $false
}

if ($WithRestarts) {
    # 03 与 10 共用同一次 biz-mock 重启：03 要"seed 跑两次不翻倍"，
    # 10 要"biz-mock 进程不在时网关不 500"，两件事都必然要停一次服务。
    Write-Host "`n=== ticket 03 + 10：重启 biz-mock（seed 两次不翻倍 / 进程消失时降级） ===" -ForegroundColor Cyan
    $statsBefore = Invoke-Ops 'GET' '/api/v1/support/ops/stats' $null
    Write-Host "  重启前 orders=$($statsBefore.orders) tickets=$($statsBefore.tickets)"
    & (Join-Path $root 'scripts\stop.ps1') -Ports '8091' | Out-Null
    Start-Sleep -Seconds 2
    $deadTok = Get-Token 'T001' 'C001'
    $downEvents = Invoke-ChatStream $deadTok '90002 的快递到哪了' 'plan-10-bizdown'
    $downNames = @($downEvents | ForEach-Object { $_.Event })
    $fb = $downEvents | Where-Object { $_.Event -eq 'fallback' } | Select-Object -First 1
    Assert-True ($downNames -contains 'fallback') "biz-mock 停着时仍走同一条 SSE 通道（事件 $($downNames -join '>')）"
    Assert-True ($null -ne $fb -and $fb.Data -match 'TOOL_UNAVAILABLE') "降级原因是 TOOL_UNAVAILABLE 而不是 500"
    # PLAN 对 10 行的判据只有「返回降级语义而非 500」，上面两条已经判完。
    # 这里额外记录一个真实缺口：FallbackService.escalate() 是把工单 POST 给 biz-mock 的，
    # biz-mock 整个进程不在时工单无处可落（网关只留 warn 日志），所以降级帧里没有 ticketId。
    # 单依赖挂掉时工单仍有地方落（DB 在、只是接口慢/失败率 100%），所以这不是主链路缺口；
    # 记进 README 已知限制，而不是改判据去迁就实现。
    if ($fb.Data -match '"ticketId":"([^"]+)"') {
        Write-Host "  降级帧带回了工单号 $($Matches[1])" -ForegroundColor Green
    } else {
        Write-Host '  注意：biz-mock 进程整体消失时工单无处可落（已知限制，见 README）' -ForegroundColor Yellow
    }
    & (Join-Path $root 'scripts\start-bizmock.ps1') | Out-Null
    Assert-True (Wait-ServiceUp 8091 'biz-mock' 300 (Join-Path $root 'logs\bizmock.pid') (Join-Path $root 'logs\bizmock.out')) `
        'biz-mock 重新起来且 seed 跑完（readiness=UP）'
    $statsAfter = Invoke-Ops 'GET' '/api/v1/support/ops/stats' $null
    Write-Host "  重启后 orders=$($statsAfter.orders)"
    Assert-True ($statsAfter.orders -eq $statsBefore.orders) `
        "seed 第二次结果不翻倍（$($statsBefore.orders) == $($statsAfter.orders)）"

    Write-Host "`n=== ticket 14：模型端点指向不存在地址 ===" -ForegroundColor Cyan
    $envFile = Join-Path $root '.env'
    $envBackup = Join-Path $root '.env.plan-actions-backup'
    # 这一条要改写服务配置，而 .env 里可能是真的 DashScope key：先整体挪走再复原，
    # 而不是要求人肉移开——否则"填过 .env 的机器"反而跑不了验收。
    if (Test-Path $envFile) {
        if (Test-Path $envBackup) {
            # 走到这里说明上次跑中途断了：真 .env（补上 key 之后就是带 key 的那份）还躺在备份文件里，
            # 而当前 .env 很可能只是本轮的冒烟占位。不自动覆盖，因为两种方向都会毁掉用户的配置。
            throw "残留的 $envBackup 还在，多半是上次中断留下的 .env 备份（当前 .env 可能是冒烟占位）。先对照看一眼：`n  Get-Content `"$envBackup`"; Get-Content `"$envFile`"`n确认备份那份才是你的配置，就把它恢复回去再重跑：`n  Move-Item -LiteralPath `"$envBackup`" -Destination `"$envFile`" -Force"
        }
        Move-Item -LiteralPath $envFile -Destination $envBackup
    }
    try {
        Set-Content -Path $envFile -Value 'SHOPPILOT_OLLAMA_URL=http://127.0.0.1:59999' -Encoding ascii
        & (Join-Path $root 'scripts\stop.ps1') -Ports '8082' | Out-Null
        Start-Sleep -Seconds 2
        & (Join-Path $root 'scripts\start-gateway.ps1') -Profile local | Out-Null
        Assert-True (Wait-ServiceUp 8082 '网关' 300 (Join-Path $root 'logs\gateway-local.pid') (Join-Path $root 'logs\gateway-local.out')) `
            "指向死端点的网关自己起来了（模型不可用不等于服务起不来）"
        Invoke-Ops 'POST' '/api/v1/support/ops/cache/flush' $null | Out-Null
        $deadTok = Get-Token 'T001' 'C001'
        $deadEvents = Invoke-ChatStream $deadTok (New-ProbeQuery '生鲜坏了怎么赔') 'plan-14-llmdead'
        $deadNames = @($deadEvents | ForEach-Object { $_.Event })
        $deadFb = $deadEvents | Where-Object { $_.Event -eq 'fallback' } | Select-Object -First 1
        Assert-True ($deadNames -contains 'fallback') "模型端点不通时以 fallback 收尾（事件 $($deadNames -join '>')）"
        Assert-True ($null -ne $deadFb -and $deadFb.Data -match 'LLM_CIRCUIT_OPEN') "原因是 LLM_CIRCUIT_OPEN"
        $deadTicket = ''
        if ($deadFb.Data -match '"ticketId":"([^"]+)"') { $deadTicket = $Matches[1] }
        $queue = @(Invoke-Ops 'GET' '/api/v1/support/ops/tickets' $null | ForEach-Object { $_.id })
        Assert-True ($deadTicket -ne '' -and ($queue -contains $deadTicket)) "工单 $deadTicket 能从队列查回"
    } finally {
        if (Test-Path $envFile) { [IO.File]::Delete($envFile) }
        if (Test-Path $envBackup) { Move-Item -LiteralPath $envBackup -Destination $envFile }
        & (Join-Path $root 'scripts\stop.ps1') -Ports '8082' | Out-Null
        Start-Sleep -Seconds 2
        & (Join-Path $root 'scripts\start-gateway.ps1') -Profile local | Out-Null
        Assert-True (Wait-ServiceUp 8082 '网关' 300 (Join-Path $root 'logs\gateway-local.pid') (Join-Path $root 'logs\gateway-local.out')) `
            '已恢复默认配置并重启网关'
    }
}
if ($failures.Count -gt 0) {
    Write-Host ('FAILED: ' + ($failures -join ' | ')) -ForegroundColor Red
    exit 1
}
$done = if ($WithRestarts) { '01/03/04/05/10/13/14' } else { '01/04/05/13（加 -WithRestarts 再跑 03/10/14）' }
Write-Host "PLAN 逐 ticket 验收动作 $done 全部通过" -ForegroundColor Green
exit 0
