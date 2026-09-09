# 未命中 TTFT 的并发扫描（ticket 18 / PLAN 承诺项"未命中 TTFT <500ms"）。
#
# 为什么要有这一支：README 里那条 592ms 只有一个点，说明不了"判据差一点"还是"这条路径本来就慢"。
# 这一支把并发当唯一自变量，每个档位跑两臂：
#   miss —— 每次提问加随机后缀，L1 必然不命中，整条链路（向量化→混合检索→模型首字）真实走一遍；
#   mix  —— 先预热再放自然流量，报告命中与未命中各自的分位数。
#
# 沿用 run_experiment_suite.ps1 的教训：profile 没吃进去时服务照样健康，数据整组作废，
# 所以起进程后必须回读 /ops/switches 确认 llmMode=perf（MockLLM 首字下限 300ms）。
# local 模式会把真实模型的推理时延混进来，那种数不能用来对 500ms 这条线。
param(
    [string]$Levels = "50,100,200,500",
    [int]$Duration = 60,
    [string]$Profile = "perf",
    [string]$Arms = "miss,mix"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$Python = if ($env:SHOPPILOT_PYTHON) {
    $env:SHOPPILOT_PYTHON
} elseif (Test-Path (Join-Path $root ".venv-loadtest\Scripts\python.exe")) {
    Join-Path $root ".venv-loadtest\Scripts\python.exe"
} else {
    "python"
}
$logFile = Join-Path $root "logs\sse-sweep.log"

function Say([string]$m) {
    $line = (Get-Date -Format 'HH:mm:ss') + ' ' + $m
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

function Wait-Healthy([int]$Seconds = 150) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            if ((Invoke-RestMethod "http://127.0.0.1:8082/actuator/health/readiness" -TimeoutSec 5).status -eq "UP") { return $true }
        } catch { Start-Sleep -Seconds 4 }
    }
    return $false
}

function Invoke-Flush {
    # 每个档位都从空缓存出发，否则前一档留下的命中会把后一档的首字拉低，档位之间不可比
    $token = (Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8082/auth/mock-token" -ContentType "application/json" `
        -Body '{"tenantId":"T001","customerId":"C001"}' -TimeoutSec 10).token
    return Invoke-RestMethod -Method Post "http://127.0.0.1:8082/api/v1/support/ops/cache/flush" `
        -Headers @{ Authorization = "Bearer $token"; "X-Ops-Token" = "dev-ops-token" } -TimeoutSec 20
}

function Get-OpsSwitches {
    $token = (Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8082/auth/mock-token" -ContentType "application/json" `
        -Body '{"tenantId":"T001","customerId":"C001"}' -TimeoutSec 10).token
    return Invoke-RestMethod "http://127.0.0.1:8082/api/v1/support/ops/switches" `
        -Headers @{ Authorization = "Bearer $token"; "X-Ops-Token" = "dev-ops-token" } -TimeoutSec 10
}

Say "===== TTFT 扫描开始：profile=$Profile 档位=$Levels 每档 ${Duration}s ====="
& (Join-Path $root "scripts\stop.ps1") -Ports "8082" | Out-Null
Start-Sleep -Seconds 3
& (Join-Path $root "scripts\start-gateway.ps1") -Profile $Profile | Out-Null
if (-not (Wait-Healthy)) { throw "网关以 profile=$Profile 启动失败，看 logs\gateway-$Profile.out" }

$switches = $null
foreach ($attempt in 1..5) {
    try { $switches = Get-OpsSwitches; break } catch { Start-Sleep -Seconds 3 }
}
if (-not $switches) { throw "读不到 /ops/switches，回读守卫本身失效，中止" }
Say "  回读 llmMode=$($switches.llmMode) cache=$($switches.cacheEnabled) virtual=$($switches.virtualThreadRequest) 阈值=$($switches.semanticThreshold)"
if ("$($switches.llmMode)" -ne "perf") {
    throw "llmMode 实际=$($switches.llmMode) 期望=perf：首字下限不是 300ms 的 Mock，这组数不能用来对 500ms 线，中止"
}

$armList = $Arms -split "," | ForEach-Object { $_.Trim() }
foreach ($level in ($Levels -split "," | ForEach-Object { [int]$_.Trim() })) {
    foreach ($arm in $armList) {
        $extra = @()
        if ($arm -eq "miss") { $extra = @("--force-miss") }
        if ($arm -eq "mix") { $extra = @("--warm") }
        $cleared = Invoke-Flush
        Say "----- 并发 $level 臂 $arm（已清 L1 键 $($cleared.l1KeysDeleted)）-----"
        & $Python scripts/run_sse_ttft.py --connections $level --duration $Duration --label "sweep$arm" @extra
        if ($LASTEXITCODE -ne 0) { Say "  !! 并发 $level 臂 $arm 退出码 $LASTEXITCODE" }
        Start-Sleep -Seconds 8
    }
}
Say "===== 扫描结束，产物见 loadtest\results\sse-ttft-*-sweep*.csv ====="
