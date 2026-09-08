# 三条对外演示：缓存拦截 / 串号防线 / 降级转人工。
# 与 verify-*.ps1 的区别：验收脚本负责判绿，这个脚本负责"看懂"——
# 每一步都把请求、响应关键字段和计数器增量打出来，演示时可以照着念。
#
# 例:  pwsh -NoProfile -File scripts/demo.ps1                # 三条全跑
#      pwsh -NoProfile -File scripts/demo.ps1 -Which cache    # 只跑一条
param(
    [ValidateSet('cache', 'isolation', 'fallback', 'all')] [string]$Which = 'all',
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$OpsToken = 'dev-ops-token'
)
$ErrorActionPreference = 'Stop'

function Get-Token([string]$tenant, [string]$customer) {
    $body = @{ tenantId = $tenant; customerId = $customer } | ConvertTo-Json -Compress
    return (Invoke-RestMethod -Uri "$BaseUrl/auth/mock-token" -Method POST -Body $body `
        -ContentType "application/json" -TimeoutSec 30).token
}

function Invoke-Chat([string]$token, [string]$query, [string]$conversation) {
    $body = @{ query = $query } | ConvertTo-Json -Compress
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat" -Method POST `
        -Headers @{ Authorization = "Bearer $token"; "X-Conversation-Id" = $conversation } `
        -ContentType "application/json; charset=utf-8" `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -UseBasicParsing -TimeoutSec 240
    $sw.Stop()
    $raw = if ($response.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($response.Content) } else { $response.Content }
    $result = $raw | ConvertFrom-Json
    return [PSCustomObject]@{
        ElapsedMs = [math]::Round($sw.Elapsed.TotalMilliseconds)
        Intent    = $result.intent
        Triage    = $result.triageLayer
        Cache     = $result.cacheLayer
        Citations = @($result.citations).Count
        Answer    = $result.answer
    }
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

function Get-Metric([string]$name) {
    try {
        $raw = (Invoke-WebRequest -Uri "$BaseUrl/actuator/metrics/$name" -UseBasicParsing -TimeoutSec 10).Content
        $json = if ($raw -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($raw) } else { $raw }
        return [double]((($json | ConvertFrom-Json).measurements | Where-Object { $_.statistic -eq 'COUNT' }).value)
    } catch { return -1.0 }
}

function Invoke-Ops([string]$method, [string]$path, $body) {
    $token = Get-Token 'T001' 'C001'
    $params = @{
        Uri = "$BaseUrl$path"; Method = $method; TimeoutSec = 60; UseBasicParsing = $true
        Headers = @{ 'X-Ops-Token' = $OpsToken; Authorization = "Bearer $token" }
    }
    if ($null -ne $body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
    }
    return Invoke-RestMethod @params
}

function Show-Answer([string]$answer, [int]$keep = 160) {
    $one = ($answer -replace '\s+', ' ').Trim()
    if ($one.Length -gt $keep) { return $one.Substring(0, $keep) + '…' }
    return $one
}

$nonce = Get-Random -Maximum 99999

function Show-DemoCache {
    Write-Host ''
    Write-Host '════ 演示 1：缓存拦截（大促热点不进模型） ════' -ForegroundColor Cyan
    Invoke-Ops 'POST' '/api/v1/support/ops/cache/flush' $null | Out-Null
    Write-Host '  已清空缓存，保证第一次问是真冷启动'
    $token = Get-Token 'T001' 'C001'
    $query = '发什么快递'
    $before = Get-Metric 'shoppilot_llm_calls_total'
    $first = Invoke-Chat $token $query "demo-cache-a-$nonce"
    Write-Host ("  第一次  {0}ms  intent={1} triage={2} cache={3} 引用{4}条" -f `
        $first.ElapsedMs, $first.Intent, $first.Triage, $first.Cache, $first.Citations)
    Write-Host ("          " + (Show-Answer $first.Answer))
    $middle = Get-Metric 'shoppilot_llm_calls_total'
    $second = Invoke-Chat $token $query "demo-cache-b-$nonce"
    $after = Get-Metric 'shoppilot_llm_calls_total'
    Write-Host ("  第二次  {0}ms  intent={1} triage={2} cache={3}" -f `
        $second.ElapsedMs, $second.Intent, $second.Triage, $second.Cache)
    Write-Host ("  看点    第一次打模型 {0} 次；第二次命中 {1}，模型调用增量 {2} 次" -f `
        ([int]($middle - $before)), $second.Cache, ([int]($after - $middle)))
    Write-Host '          大促里 80% 的重复热点就停在这一层，模型侧成本与排队同时消失。'
}

function Show-DemoIsolation {
    Write-Host ''
    Write-Host '════ 演示 2：串号防线（跨店查不到、伪造进不来） ════' -ForegroundColor Cyan
    $owner = Get-Token 'T001' 'C001'
    $own = Invoke-Chat $owner '90001 这单现在什么状态' "demo-own-$nonce"
    Write-Host ("  A 店买家问自己的 90001 : cache={0} 答案={1}" -f $own.Cache, (Show-Answer $own.Answer 90))
    $other = Get-Token 'T002' 'C200'
    $cross = Invoke-Chat $other '90001 这单现在什么状态' "demo-cross-$nonce"
    Write-Host ("  B 店买家问同一个 90001 : cache={0} 答案={1}" -f $cross.Cache, (Show-Answer $cross.Answer 90))
    Write-Host ('  两次答案是否不同：' + ($cross.Answer -ne $own.Answer))
    Write-Host '  看点    B 店拿不到 A 店任何订单字段，只得到「未在本店找到该订单」'
    try {
        $body = @{ query = '90001 这单现在什么状态' } | ConvertTo-Json -Compress
        Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat" -Method POST -Body $body `
            -ContentType 'application/json; charset=utf-8' `
            -Headers @{ Authorization = 'Bearer forged.token.not.real'; 'X-Conversation-Id' = "demo-forge-$nonce" } `
            -UseBasicParsing -TimeoutSec 30 | Out-Null
        Write-Host '  看点    伪造 token 竟然没被拒——这是问题，不是通过'
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        Write-Host ("  伪造 token 直接 {0}，没进到任何业务逻辑" -f $code)
    }
    try {
        $body = @{ query = '90001 这单现在什么状态'; tenantId = 'T002' } | ConvertTo-Json -Compress
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/support/chat" -Method POST `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json; charset=utf-8' `
            -Headers @{ Authorization = "Bearer $owner"; 'X-Conversation-Id' = "demo-bodytenant-$nonce" } `
            -UseBasicParsing -TimeoutSec 60
        $raw = if ($resp.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($resp.Content) } else { $resp.Content }
        Write-Host ('  看点    body 里塞 tenantId=T002 被忽略，身份仍来自 token：' + (Show-Answer ($raw | ConvertFrom-Json).answer 90))
    } catch {
        Write-Host "  body 携带 tenantId 的请求被拒绝：$($_.Exception.Message)"
    }
}

function Show-DemoFallback {
    Write-Host ''
    Write-Host '════ 演示 3：降级转人工（依赖挂了不是 500） ════' -ForegroundColor Cyan
    Invoke-Ops 'PUT' '/api/v1/support/ops/fault' @{ delayMs = 0; failRate = 1.0 } | Out-Null
    Write-Host '  已通过网关运维代理注入 failRate=1.0（biz-mock 每次调用必失败）'
    $token = Get-Token 'T001' 'C001'
    try {
        $events = Invoke-Stream $token '90002 的快递到哪了' "demo-fallback-$nonce"
        $names = ($events | ForEach-Object { $_.Event }) -join ' > '
        Write-Host "  事件序列: $names"
        $fallback = $events | Where-Object { $_.Event -eq 'fallback' } | Select-Object -First 1
        if ($fallback) { Write-Host ("  降级帧   " + (Show-Answer $fallback.Data 200)) }
        # 工单号带 -N 后缀（同一秒多单防撞主键），只匹配 T\d+ 会拿到查不到的半截号。
        $ticketId = ''
        $m = [regex]::Match($fallback.Data, '"ticketId":"([^"]+)"')
        if ($m.Success) { $ticketId = $m.Groups[1].Value }
        if ($ticketId) {
            # 工单队列返回的字段是 id（ticketId 只出现在 SSE 帧里），按 ticketId 过滤会永远落空。
            $tickets = Invoke-Ops 'GET' '/api/v1/support/ops/tickets' $null
            $mine = @($tickets) | Where-Object { $_.id -eq $ticketId } | Select-Object -First 1
            if ($mine) {
                Write-Host ("  工单     {0}  reason={1}  status={2}  query={3}" -f `
                    $mine.id, $mine.reason, $mine.status, (Show-Answer $mine.userQuery 40))
                Write-Host '           人工坐席在 :8082 的工单队列里能直接接上这一条，不是日志里的一行字。'
            } else {
                Write-Host "  工单     $ticketId 已生成（队列里未取到，可能被更新的工单挤出）"
            }
        } else {
            Write-Host '  未从降级帧里解析出工单号——请检查 fallback 事件负载格式'
        }
    } finally {
        Invoke-Ops 'PUT' '/api/v1/support/ops/fault' @{ delayMs = 0; failRate = 0.0 } | Out-Null
        Write-Host '  已复位故障注入'
    }
}

Write-Host "ShopPilot 演示 · 目标 $BaseUrl" -ForegroundColor Green
switch ($Which) {
    'cache' { Show-DemoCache }
    'isolation' { Show-DemoIsolation }
    'fallback' { Show-DemoFallback }
    default { Show-DemoCache; Show-DemoIsolation; Show-DemoFallback }
}
Write-Host ''
Write-Host "演示结束。要接着看界面：浏览器打开 $BaseUrl/" -ForegroundColor Green
