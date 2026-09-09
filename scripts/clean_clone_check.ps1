# 用一次真正的"干净检出"验 PLAN 第 19 行：克隆 HEAD -> 照 README 跑 up.ps1 -> 跑通三条演示。
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

function Run-Step([string]$name, [scriptblock]$body, [string]$expect, [int]$retries = 1) {
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
        $hit = (-not $expect) -or (($output | Select-String -SimpleMatch $expect | Measure-Object).Count -gt 0)
        if (-not $script:lastError -and $hit) {
            Write-Host ("  PASS  {0}s  expect<{1}> 命中" -f [int]$sw.Elapsed.TotalSeconds, $expect)
            return
        }
        $why = if ($script:lastError) { $script:lastError.Exception.Message } else { "没等到标记「$expect」" }
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
Run-Step 'up' { & (Join-Path $At 'scripts\up.ps1') -Profile local } '三条演示' $UpRetries
Run-Step 'demo' { & (Join-Path $At 'scripts\demo.ps1') } '演示结束' $DemoRetries

Write-Host "`n=== 干净检出检查结论 ===" -ForegroundColor Cyan
if ($script:failed.Count -eq 0) {
    Write-Host 'PASS  从 HEAD 克隆出来，照 README 一条命令起栈并跑通三条演示'
} else {
    Write-Host ("FAIL  失败步骤：{0}" -f ($script:failed -join ', ')) -ForegroundColor Red
}
Write-Host "完整记录：$transcript"
if ($Teardown) {
    Write-Host "`n=== 清理（-Teardown）：停服务并移除克隆起来的容器" -ForegroundColor Cyan
    & (Join-Path $At 'scripts\down.ps1') -Containers 2>&1 | Tee-Object -FilePath $transcript -Append
} else {
    Write-Host ("清理：pwsh -NoProfile -File `"{0}\scripts\down.ps1`" -Containers  然后自行删除该目录，并回原仓库重跑 scripts\up.ps1" -f $At)
}
if ($script:failed.Count -gt 0) { exit 1 }
exit 0
