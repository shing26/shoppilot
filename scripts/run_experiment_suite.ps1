# ticket 18 的完整实验矩阵，一条命令跑完并可重跑（PLAN 承诺项：每组含环境记录）。
#
# 五组实验需要三种网关 profile，所以脚本自己负责"停-起-等健康-跑"。
# 每组跑完立刻把 ladder CSV 的名字打印出来，中途挂了也知道丢了哪一组。
param(
    [string]$Only = "",           # 逗号分隔的实验键：mix80,l1,l2,novirtual,nocache,sse
    [int]$Workers = 4,
    [int]$Duration = 60,
    [string]$Steps = "100,200,400,800,1200,1600"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$all = @("mix80", "l1", "l2", "novirtual", "nocache", "sse")
$selected = if ($Only) { $Only -split "," | ForEach-Object { $_.Trim() } } else { $all }

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
    Write-Host "  网关就绪（profile=$Profile）"
}

function Invoke-Ladder([string]$key, [string]$model, [string]$profile, [string]$steps) {
    Write-Host ""
    Write-Host "===== 实验 $key：模型 $model / profile $profile ====="
    & python scripts/run_loadtest.py --model $model --profile $profile --steps $steps `
        --duration $Duration --spawn 400 --workers $Workers --tag $key
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
            Invoke-Ladder "nocache" "l1" "perf,nocache" "100,200,400"
            Restart-Gateway "perf"
        }
        "sse" {
            Restart-Gateway "perf"
            Write-Host ""
            Write-Host "===== 实验 sse：500 条并发长连接的 TTFT 与堆占用 ====="
            & python scripts/run_sse_ttft.py --connections 500 --duration 60 --label perf
        }
        default { Write-Host "未知实验键 $key" }
    }
}
Write-Host ""
Write-Host "实验矩阵完成"
