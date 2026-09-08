# ticket 18 的完整实验矩阵，一条命令跑完并可重跑（PLAN 承诺项：每组含环境记录）。
#
# 五组实验需要三种网关 profile，所以脚本自己负责"停-起-等健康-跑"。
# 每组跑完立刻把 ladder CSV 的名字打印出来，中途挂了也知道丢了哪一组。
param(
    [string]$Only = "",           # 逗号分隔的实验键：mix80,l1,l2,novirtual,nocache,sse
    [int]$Workers = 4,
    [int]$Duration = 60,
    [int]$Spawn = 20,
    [string]$Steps = "100,200,400,800,1200,1600",
    # HikariCP 实验专用：三档池大小与各自的并发阶梯。写进 param 而不是硬编码在 case 里，
    # 是为了让 -Only pool -PoolSizes 10,30 这种补跑不必改码。
    [string]$PoolSizes = "2,10,30",
    [string]$PoolSteps = "50,100,200"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$all = @("mix80", "l1", "l2", "l2emb", "novirtual", "nocache", "sse", "pool")
$selected = if ($Only) { $Only -split "," | ForEach-Object { $_.Trim() } } else { $all }

# 压测依赖装在项目本地 venv 里（locust + requests），系统 python 是 Anaconda base。
# 用它会以 "No module named locust" 失败，或者更糟——把包装进共享环境。
$Python = if ($env:SHOPPILOT_PYTHON) {
    $env:SHOPPILOT_PYTHON
} elseif (Test-Path (Join-Path $root ".venv-loadtest\Scripts\python.exe")) {
    Join-Path $root ".venv-loadtest\Scripts\python.exe"
} else {
    "python"
}
Write-Host "压测解释器：$Python"

function Wait-Healthy([int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            if ((Invoke-RestMethod "http://127.0.0.1:8082/actuator/health" -TimeoutSec 5).status -eq "UP") { return $true }
        } catch { Start-Sleep -Seconds 4 }
    }
    return $false
}

function Restart-Gateway([string]$Profile) {
    & (Join-Path $root "scripts\stop.ps1") -Ports "8082" | Out-Null
    Start-Sleep -Seconds 3
    & (Join-Path $root "scripts\start-gateway.ps1") -Profile $Profile | Out-Null
    if (-not (Wait-Healthy)) { throw "网关以 profile=$Profile 启动失败，看 logs\gateway-$Profile.out" }
    # 只看健康是不够的：profile 没吃进去时服务照样健康，数据却整组作废（09-08 的 nocache 就这样白跑）
    $logName = "gateway-" + ($Profile -replace '[^A-Za-z0-9._-]', '+') + ".out"
    $active = Select-String -Path (Join-Path $root "logs\$logName") -Pattern 'profile(s)? (is|are) active' |
        Select-Object -Last 1
    if (-not $active) { throw "网关日志 $logName 里没有 profile 生效记录，实验组 $Profile 无法确认，中止" }
    if ($active.Line -match 'profile(s)? (is|are) active:\s*(.+)$') { Write-Host "  生效 profile：$($matches[3])" }
    Write-Host "  网关就绪（profile=$Profile）"
}

function Wait-BizMockHealthy([int]$Seconds = 150) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            if ((Invoke-RestMethod "http://127.0.0.1:8091/actuator/health" -TimeoutSec 5).status -eq "UP") { return $true }
        } catch { Start-Sleep -Seconds 4 }
    }
    return $false
}

# 与网关同一个教训：池子没吃进去时 biz-mock 照样健康，量到的却是默认池。用指标反读确认。
function Restart-BizMock([string]$Pool) {
    # 空值会静默走到默认池。实踩过一次：参数没进 param 块，$PoolSizes 读成空，
    # biz-mock 起在默认 30 上——反读守卫当场中止才没留下挂着 pool2 名字的废数据。
    if (-not $Pool) { throw "Restart-BizMock 需要显式池大小，拒绝按默认值跑" }
    & (Join-Path $root "scripts\stop.ps1") -Ports "8091" | Out-Null
    Start-Sleep -Seconds 3
    & (Join-Path $root "scripts\start-bizmock.ps1") -PoolSize $Pool | Out-Null
    if (-not (Wait-BizMockHealthy)) { throw "biz-mock 池=$Pool 启动失败，看 logs\bizmock.out" }
    $max = (Invoke-RestMethod "http://127.0.0.1:8091/actuator/metrics/hikaricp.connections.max" -TimeoutSec 10).measurements | Where-Object { $_.statistic -eq "VALUE" }
    if (-not $max) { throw "读不到 biz-mock 的 hikaricp.connections.max，无法确认池大小，中止" }
    if ([int]$max.value -ne [int]$Pool) { throw "biz-mock 池实际为 $($max.value)，期望 $Pool，中止" }
    Write-Host "  biz-mock 就绪 HikariCP maximum-pool-size=$([int]$max.value)"
}

function Invoke-Ladder([string]$key, [string]$model, [string]$profile, [string]$steps) {
    Write-Host ""
    Write-Host "===== 实验 $key：模型 $model / profile $profile ====="
    & $Python scripts/run_loadtest.py --model $model --profile $profile --steps $steps `
        --duration $Duration --spawn $Spawn --workers $Workers --tag $key
    if ($LASTEXITCODE -ne 0) { Write-Host "  !! 实验 $key 退出码 $LASTEXITCODE" }
    Get-ChildItem (Join-Path $root "loadtest\results") -Filter "ladder-*-$key.csv" |
        ForEach-Object { Write-Host "  产物 $($_.Name)" }
}

foreach ($key in $selected) {
    switch ($key) {
        "mix80" {
            Restart-Gateway "perf"
            Invoke-Ladder "mix80" "mix80" "perf" $Steps
        }
        "l1" {
            Restart-Gateway "perf"
            Invoke-Ladder "l1" "l1" "perf" $Steps
        }
        "l2" {
            Restart-Gateway "perf"
            Invoke-Ladder "l2" "l2" "perf" $Steps
        }
        "novirtual" {
            # 虚拟线程收益的对照组：同一模型、同一并发，只关 spring.threads.virtual.enabled
            Restart-Gateway "perf,no-virtual"
            Invoke-Ladder "novirtual" "l1" "perf,no-virtual" $Steps
            Restart-Gateway "perf"
        }
        "nocache" {
            # Token 节约率的基线：关缓存重放同一流量模型，量的是真实 token 而不是假设
            Restart-Gateway "perf,nocache"
            Invoke-Ladder "nocache" "l1" "perf,nocache" "200"
            # 零防线基线：缓存与防击穿合并一起关。只关缓存时合并仍在替模型省调用，
            # 节约率会被压到 25% 这种假数字上。
            Restart-Gateway "perf,nocache,nosf"
            Invoke-Ladder "nosf" "l1" "perf,nocache,nosf" "200"
            # 对照组必须同并发、同模型、同 spawn，否则比值里混进的不是缓存这一件事
            Restart-Gateway "perf"
            Invoke-Ladder "cacheton" "l1" "perf" "200"
        }
        "l2emb" {
            # L2 曲线天花板归因：关掉进程内向量缓存，让每个请求真打一次 bge-m3
            Restart-Gateway "perf,no-embedding-cache"
            Invoke-Ladder "l2emb" "l2" "perf,no-embedding-cache" "100,200,400"
            Restart-Gateway "perf"
        }
        "sse" {
            Restart-Gateway "perf"
            Write-Host ""
            Write-Host "===== 实验 sse：500 条并发长连接的 TTFT 与堆占用 ====="
            & $Python scripts/run_sse_ttft.py --connections 500 --duration 60 --label perf
        }
        "pool" {
            # HikariCP 饱和点：同一流量模型、同一并发阶梯，只动 biz-mock 的连接池。
            # 三档而不是两档：2 是故意饿死它，用来拿到"饱和时长什么样"的正样本；
            # 只看 10 与 30 的话，pending 全程为 0，根本说不出饱和点在哪里。
            # 用 biz 模型（100% 业务办理）而不是 l1：l1 里只有 15% 请求真碰数据库。
            Restart-Gateway "perf"
            foreach ($pool in ($PoolSizes -split "," | ForEach-Object { $_.Trim() })) {
                Restart-BizMock $pool
                Invoke-Ladder "pool$pool" "biz" "perf" $PoolSteps
            }
            Restart-BizMock "30"
        }
        default { Write-Host "未知实验键 $key" }
    }
}
Write-Host ""
Write-Host "实验矩阵完成"
