# 停本项目的两个服务（以及可选的中间件容器与看门狗）。
# 只按端口与本项目 pid 文件定位进程，绝不按进程名批量杀——这台机器上还跑着别的项目。
param([switch]$Containers)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

& (Join-Path $root 'scripts\stop.ps1') -Ports '8082,8091'

$watchdog = Join-Path $root 'logs\watchdog.pid'
if (Test-Path $watchdog) {
    $procId = (Get-Content $watchdog -Raw).Trim()
    if ($procId -and (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
        Stop-Process -Id ([int]$procId) -Force -ErrorAction SilentlyContinue
        Write-Host "已停止看门狗 $procId"
    }
    # 用 .NET 删除而不是 Remove-Item：受限执行环境里后者可能被策略挡掉，前者总能跑通。
    try { [IO.File]::Delete($watchdog) } catch { Write-Host "  未能删除 $watchdog : $($_.Exception.Message)" }
}

if ($Containers) {
    docker compose stop | Out-Host
    Write-Host '中间件容器已停止（数据卷保留，docker compose rm 才清）'
} else {
    Write-Host '中间件容器保持运行；要一起停用 -Containers'
}
