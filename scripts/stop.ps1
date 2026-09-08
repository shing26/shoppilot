# 只停本项目占用的端口，绝不碰别的项目的进程。
param([string]$Ports = '8082,8091')
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
