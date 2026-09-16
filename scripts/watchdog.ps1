# 长实验与演示期间的服务看门狗：只负责把死掉的 biz-mock 拉回来。
#
# 这台 16G 机器上还跑着别的项目的容器与 Ollama，实测空闲内存会掉到 0，
# 两个 JVM 都出现过"日志里没有任何异常就凭空消失"（09-08 21:18 与 23:57 各一次）。
# 网关刻意交给压测脚本自己判健康：profile 一旦被看门狗用错误的值拉起来，
# 那一档数据会静默变成假的，比直接停掉更糟（见 scripts/run_loadtest.py 的 gateway_healthy）。
param(
    [int]$IntervalSeconds = 10,
    [int]$MaxMinutes = 240,
    # 重启时比普通起栈的 2% 放宽一档，仍由同一个 MaxRAMPercentage 参数控制。
    [int]$BizMockMaxRamPercentage = 4,
    # 后台实例自己带的标记。不能用 $MyInvocation.InvocationName 区分父/子：
    # 经 -File 起来的子进程里它也拿不到 '&'，结果子进程又拉起孙子，变成 forks 链。
    [switch]$Child
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$log = Join-Path $root 'logs\watchdog.log'

function Say([string]$m) {
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $m
    Add-Content -Path $log -Value $line -Encoding UTF8
    Write-Host $line
}

function Test-Port([int]$p) {
    try {
        return (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
            Measure-Object).Count -gt 0
    } catch { return $true }
}

if (-not $Child) {
    . (Join-Path $PSScriptRoot 'lib-launch.ps1')
    $launcher = Start-ShoppilotService -Name 'watchdog' -WorkingDirectory $root `
        -FilePath 'powershell.exe' -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$PSCommandPath",
            '-IntervalSeconds', "$IntervalSeconds", '-MaxMinutes', "$MaxMinutes",
            '-BizMockMaxRamPercentage', $BizMockMaxRamPercentage,
            '-Child') -StandardOutput (Join-Path $root 'logs\watchdog.out')
    Set-Content -Path (Join-Path $root 'logs\watchdog.pid') -Value $launcher -Encoding ascii
    Say "看门狗启动（后台实例 launcher PID $launcher）"
    exit 0
}

$deadline = (Get-Date).AddMinutes($MaxMinutes)
Say "看护开始：biz-mock:8091 间隔 ${IntervalSeconds}s"
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $IntervalSeconds
    if (-not (Test-Port 8091)) {
        Say "biz-mock 失联，重启（seed 全量，约 40 秒后恢复）"
        # 沿用最后一次显式指定的连接池大小，否则池实验中途被拉回来会静默回到默认值。
        $poolFile = Join-Path $root 'logs\bizmock.pool'
        $pool = if (Test-Path $poolFile) { (Get-Content $poolFile -Raw).Trim() } else { '' }
        & (Join-Path $root 'scripts\start-bizmock.ps1') `
            -MaxRamPercentage $BizMockMaxRamPercentage -PoolSize $pool | Out-Null
        $until = (Get-Date).AddSeconds(180)
        while ((Get-Date) -lt $until) {
            Start-Sleep -Seconds 5
            try {
                if ((Invoke-RestMethod 'http://127.0.0.1:8091/actuator/health' -TimeoutSec 5).status -eq 'UP') { break }
            } catch { }
        }
        Say "biz-mock 重启完成"
    }
}
Say "看门狗退出（超过 $MaxMinutes 分钟）"
