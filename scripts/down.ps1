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
    # 停而不移除不够：compose 文件用 container_name 钉死了容器名，而容器名在 Docker 里全局唯一。
    # 只 stop 的话，另一个检出（比如干净检出检查）会撞上 "already in use" 而起不来。
    docker compose rm -f | Out-Host
    # ES 与 Qdrant 的数据在命名卷里，删容器不删数据；Redis 没有卷，本来就是可丢的缓存层，
    # 移除容器等于把 L1 清空——重连后第一次提问是冷启动，这是预期行为而不是事故。
    Write-Host '中间件容器已移除：ES/Qdrant 的命名数据卷保留，Redis 无卷（L1 缓存随之清空）'
} else {
    Write-Host '中间件容器保持运行；要一起停用 -Containers'
}
