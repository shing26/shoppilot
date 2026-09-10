# 用一次真正的"干净检出"验 PLAN 第 19 行：克隆 HEAD -> 照 README 跑 up.ps1 -> 跑通三条演示。
#
# 判据（2026-09-10 收口）：PLAN 第 165 行原本写的是"让未参与的人照 README 跑一遍 / 十分钟内起栈并完成三条演示"。
# 人肉那半由脚本自闭环替代，具体是两条硬判定：① 整个冷启动 ≤ $BudgetMinutes（默认 10 分钟，超时即 FAIL，
# 不因"最后成了"而豁免）；② 三条演示各自的**预期输出**逐条断言（缓存命中且模型零调用 / 跨店答案不同 +
# 伪造 token 401 / 降级帧里解析出工单号并在队列里查到 reason+status），而不是只看 demo.ps1 的退出码。
# 这一条改动的直接理由：demo.ps1 是"看懂"脚本，它把结论排成表格给人念，自己并不判红——
# 只看退出码时"缓存没命中""工单没进队列"都会绿着过去（本仓库在门禁上已经为同样的事红过两次）。
#
# 为什么要有这个脚本：验收矩阵一直是在开发工作目录里跑的，那里有 .env、有 target/、有已经拉好的模型、
# 有上一轮留下的数据卷。"在我这儿能跑"和"没参与的人照 README 能跑"是两件事，这台机器上就撞过不止一次
# （fat jar 文件锁、`.env` 里的 embed 模型名）。克隆出来只有 HEAD，所以这一步顺带验"提交的东西够不够"。
#
# 两个前提：
#   1. 中间件端口固定，一台机器同时只能有一个检出在跑：原栈必须先停
#      （pwsh -NoProfile -File scripts/down.ps1 -Containers）。停而不移除不够——
#      见下面 prereq 步的说明，那正是这个脚本第一次跑就撞到的坑。
#   2. 跑完自己清理：见最后一行提示（脚本不删任何目录，也不留容器）。
param(
    [string]$At = '',
    [int]$DemoRetries = 2,
    [int]$UpRetries = 2,
    # 冷启动预算（分钟）。PLAN 第 165 行的"十分钟"，从本脚本开跑算到三条演示结束，含克隆与冷构建。
    [int]$BudgetMinutes = 10,
    [switch]$Teardown
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $At) { $At = "$repo-cleancheck" }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path $repo 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$transcript = Join-Path $logDir "clean-clone-check-$stamp.log"
$script:failed = @()
$script:timings = @()
$clock = [Diagnostics.Stopwatch]::StartNew()

function Run-Step([string]$name, [scriptblock]$body, [object[]]$Expect, [int]$retries = 1) {
    # $Expect 可以是纯字符串（名字即正则），也可以是 @{Name=..; Pattern=..} ——断言逐条判，
    # 红的时候要说得出"是三条里的哪一条没出来"，不能只报"输出里没找到标记"。
    $checks = @($Expect | Where-Object { $_ } | ForEach-Object {
        if ($_ -is [string]) { [pscustomobject]@{ Name = $_; Pattern = $_ } } else { $_ }
    })
    for ($attempt = 1; $attempt -le $retries; $attempt++) {
        $suffix = if ($retries -gt 1) { " (第 $attempt/$retries 次)" } else { '' }
        Write-Host "`n=== $name$suffix" -ForegroundColor Cyan
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $script:lastError = $null
        # 用 *>&1 而不是 2>&1：up.ps1 与 demo.ps1 的成功行是 Write-Host 打的，走 information 流。
        # 只并 stderr 的话这两行永远进不了 $output，于是"标记没命中"——2026-09-10 那次克隆起来的栈
        # 已经打印「栈已就绪」、三条演示也全部通过，脚本却把两步判成 FAIL。量具先修，再谈结论。
        $output = try {
            @(& $body *>&1 | ForEach-Object { "$_" })
        } catch {
            $script:lastError = $_
            @()
        }
        $sw.Stop()
        $output | Out-File -FilePath $transcript -Append -Encoding utf8
        $output | Select-Object -Last 6 | ForEach-Object { "    | $_" }
        $missed = @()
        foreach ($c in $checks) {
            $n = ($output | Select-String $c.Pattern | Measure-Object).Count
            $mark = if ($n) { 'x' } else { ' ' }
            Write-Host ("    [{0}] {1}" -f $mark, $c.Name)
            if (-not $n) { $missed += $c.Name }
        }
        $script:timings += [pscustomobject]@{ Step = $name; Sec = [int]$sw.Elapsed.TotalSeconds; Attempt = $attempt }
        if (-not $script:lastError -and $missed.Count -eq 0) {
            Write-Host ("  PASS  {0}s  预期输出 {1}/{1} 命中" -f [int]$sw.Elapsed.TotalSeconds, $checks.Count)
            return
        }
        $why = if ($script:lastError) { $script:lastError.Exception.Message } else { "没等到预期输出：" + ($missed -join ' / ') }
        Write-Host ("  这次没成（{0}s）：{1}" -f [int]$sw.Elapsed.TotalSeconds, $why) -ForegroundColor Yellow
    }
    Write-Host "  FAIL  $name" -ForegroundColor Red
    $script:failed += $name
}

Write-Host "原仓库：$repo" -ForegroundColor Cyan
Write-Host "干净检出：$At" -ForegroundColor Cyan
if (Test-Path $At) { throw "目标目录已存在：$At（先清理，或用 -At 换一个路径）" }

function Get-ContainerState([string]$name) {
    $out = docker inspect -f '{{.State.Status}}' $name 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    ($out | Select-Object -First 1).Trim()
}

# compose 文件用 container_name 钉死了容器名（README 里的 docker logs shoppilot-es 靠它）。
# 容器名在 Docker 里是全局唯一的，而 compose 的项目名取自所在目录，所以同一个项目的第二个检出
# 会撞上"名字已被占用"——哪怕原栈只是停着没删。第一次跑这个脚本就红在这里，
# 而且红得毫无信息量：报的是 /shoppilot-es is already in use，不会告诉你是谁占的、该怎么腾。
Run-Step '前置检查（固定容器名与原栈端口）' {
    $names = @('shoppilot-redis', 'shoppilot-qdrant', 'shoppilot-es')
    $stale = @()
    foreach ($n in $names) {
        $state = Get-ContainerState $n
        if ($state -eq 'running') { throw "原栈容器 $n 还在运行——先 pwsh -NoProfile -File scripts/down.ps1 -Containers 再跑本脚本" }
        if ($state) { $stale += $n }
    }
    foreach ($p in @(16379, 16333, 19200)) {
        if ((Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0) {
            throw "宿主端口 $p 已被占用（原栈没停干净？两个检出不能同时起）"
        }
    }
    # 空闲内存打出来：这台 16 G 机器上另有两个项目的容器在跑，克隆出来的第二套全栈
    # （ES 512m + Qdrant + 两个 JVM）挤进去时，JVM 会在分配不到内存时无日志消失——
    # README 已知限制里记过这个现象，2026-09-10 那次干净检出检查就是死在 seed 之后。
    $freeGb = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB, 1)
    Write-Host "  当前空闲物理内存 $freeGb GB（冷构建+冷入库那一分钟最挤，低于 ~3 GB 建议先停掉别的项目的容器）" -ForegroundColor Cyan
    if ($stale.Count -gt 0) {
        # 数据在命名卷里（shoppilot_es-data / shoppilot_qdrant-data），删容器不删数据，所以这一步是安全的。
        docker rm -f @stale 2>&1
        Write-Host "  已移除停止中的容器（数据卷保留）：$($stale -join ', ')"
    }
    '检查通过'
} '检查通过'

Run-Step 'clone' {
    git clone --no-hardlinks --quiet $repo $At 2>&1
    git -C $At log --oneline -1
    git -C $At status --short --branch
} ''
# up 允许重试：这个脚本的每一步本来就设计成可重入（容器在跑就跳过、seed 非空即跳过、入库幂等），
# 而"起栈"这一步唯一的真实失败模式是宿主机内存不够把 JVM 弄死——第二次跑时构建产物与已入库的
# 数据卷都在，正好绕开最挤的那一刻。日志里会留下"第 1 次失败"的记录，不会被重试掩盖。
# 起栈：认 up.ps1 自己的两行成功标志（"栈已就绪" 与它推荐的下一步），缺一个就是没起干净。
Run-Step 'up' { & (Join-Path $At 'scripts\up.ps1') -Profile local } @(
    [pscustomobject]@{ Name = '栈已就绪'; Pattern = '栈已就绪' },
    [pscustomobject]@{ Name = 'up.ps1 交出三条演示入口'; Pattern = '三条演示' }
) $UpRetries

# 三条演示：逐条断言各自的预期输出。缓存那一组刻意要求"第一次真的打了模型"，
# 否则 Get-Metric 拿不到计数器时（返回 -1）增量也是 0，会假绿。
Run-Step 'demo' { & (Join-Path $At 'scripts\demo.ps1') } @(
    [pscustomobject]@{ Name = '演示结束（三条全部跑完）'; Pattern = '演示结束' },
    [pscustomobject]@{ Name = '① 缓存拦截：第一次打了模型'; Pattern = '第一次打模型\s*[1-9]\d*\s*次' },
    [pscustomobject]@{ Name = '① 缓存拦截：第二次 cache=L1/L2'; Pattern = '第二次\s+\d+ms\s+intent=\S+\s+triage=\S+\s+cache=(L1|L2)' },
    [pscustomobject]@{ Name = '① 缓存拦截：模型调用增量 0 次'; Pattern = '模型调用增量\s+0\s+次' },
    [pscustomobject]@{ Name = '② 串号防线：A/B 店答案不同'; Pattern = '两次答案是否不同：True' },
    [pscustomobject]@{ Name = '② 串号防线：伪造 token 返回 401'; Pattern = '伪造 token 直接 401' },
    [pscustomobject]@{ Name = '③ 降级转人工：工单落库且队列可查'; Pattern = '工单\s+\S+\s+reason=\S+\s+status=\S+' }
) $DemoRetries
$elapsed = [int]$clock.Elapsed.TotalSeconds
$clock.Stop()

Write-Host "`n=== 干净检出检查结论 ===" -ForegroundColor Cyan
$budgetSec = $BudgetMinutes * 60
$overBudget = $elapsed -gt $budgetSec
Write-Host ("  冷启动用时 {0}s（预算 {1}s）" -f $elapsed, $budgetSec)
$script:timings | ForEach-Object {
    Write-Host ("    {0,-14} {1,5}s  第{2}次尝试" -f $_.Step, $_.Sec, $_.Attempt)
}
if ($overBudget) { $script:failed += "超出 $BudgetMinutes 分钟预算" }
if ($script:failed.Count -eq 0) {
    Write-Host ("PASS  从 HEAD 克隆出来，照 README 一条命令在 {0}s 内起栈，三条演示的预期输出逐条命中" -f $elapsed)
} else {
    Write-Host ("FAIL  失败步骤：{0}" -f ($script:failed -join ', ')) -ForegroundColor Red
}
Write-Host "完整记录：$transcript"
('' , "结果：$(if ($script:failed.Count -eq 0) { 'PASS' } else { 'FAIL: ' + ($script:failed -join ', ') })  冷启动 ${elapsed}s / 预算 ${budgetSec}s  commit=$(git -C $repo rev-parse --short HEAD)") |
    Out-File -FilePath $transcript -Append -Encoding utf8
if ($Teardown) {
    Write-Host "`n=== 清理（-Teardown）：停服务并移除克隆起来的容器" -ForegroundColor Cyan
    & (Join-Path $At 'scripts\down.ps1') -Containers 2>&1 | Tee-Object -FilePath $transcript -Append
} else {
    Write-Host ("清理：pwsh -NoProfile -File `"{0}\scripts\down.ps1`" -Containers  然后自行删除该目录，并回原仓库重跑 scripts\up.ps1" -f $At)
}
if ($script:failed.Count -gt 0) { exit 1 }
exit 0
