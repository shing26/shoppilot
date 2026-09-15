# 只停本项目占用的端口，绝不碰别的项目的进程。
param([string]$Ports = '8082,8091', [int]$ReleaseTimeoutSec = 40)
$portList = $Ports -split ',' | ForEach-Object { [int]$_ }
foreach ($port in $portList) {
    $owners = (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).OwningProcess
    foreach ($procId in ($owners | Select-Object -Unique)) {
        if ($procId) {
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Write-Host "已停止端口 $port 上的进程 $procId"
        }
    }
}

# 票 27：网关的婉拒停机预算是 35s（HTTP 收尾 30s + 写回池 drain 5s，见 README 运维段）。
# 等端口释放的超时默认 40s ≥ 预算——预算没走完就报「停了」是误判，宁可等满再报警。
foreach ($port in $portList) {
    $deadline = (Get-Date).AddSeconds($ReleaseTimeoutSec)
    while ((Get-Date) -lt $deadline -and (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) {
        Start-Sleep -Milliseconds 500
    }
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Warning "端口 $port 在 ${ReleaseTimeoutSec}s 后仍在监听：进程可能还在 drain，不许当已停"
    } else {
        Write-Host "端口 $port 已释放"
    }
}
