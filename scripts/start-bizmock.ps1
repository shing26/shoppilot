# 启动业务 Mock 中台（:8091），与网关是两个进程，走真实 HTTP 边界（ADR 0002）。
param(
    [string]$Xmx = "256m",
    [int]$SeedOrders = 0
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib-launch.ps1")
$root = Split-Path -Parent $PSScriptRoot
$jdk = if ($env:SHOPPILOT_JDK) { $env:SHOPPILOT_JDK } else { "E:\java\jdk21" }
$java = Join-Path (Join-Path $jdk "bin") "java.exe"
$logDir = Join-Path $root "logs"
$out = Join-Path $logDir "bizmock.out"
$errFile = Join-Path $logDir "bizmock.err"
$pidFile = Join-Path $logDir "bizmock.pid"
$jar = Get-ChildItem (Join-Path $root "shoppilot-biz-mock\target") -Filter "shoppilot-biz-mock-*.jar" `
    -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "sources|original" } | Select-Object -First 1

if ($jar) {
    $argsList = @("-Xmx$Xmx", "-Dfile.encoding=UTF-8", "-jar", $jar.FullName)
    if ($SeedOrders -gt 0) { $argsList += "--shoppilot.bizmock.seed.orders=$SeedOrders" }
    $cmdPid = Start-ShoppilotService -Name "bizmock" -WorkingDirectory $root `
        -FilePath $java -ArgumentList $argsList -StandardOutput $out
} else {
    Write-Host "未找到 fat jar，退回 mvn spring-boot:run"
    $env:JAVA_HOME = $jdk
    $env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
    $mvnArgs = @("-B", "-ntp", "-o", "-pl", "shoppilot-biz-mock", "spring-boot:run")
    $cmdPid = Start-ShoppilotService -Name "bizmock" -WorkingDirectory $root `
        -FilePath "mvn.cmd" -ArgumentList $mvnArgs -StandardOutput $out
}
Set-Content -Path $pidFile -Value $cmdPid -Encoding ascii
Write-Host "biz-mock 启动中（launcher PID $cmdPid，seed=$(if ($SeedOrders -gt 0) { $SeedOrders } else { "全量" })），日志 $out 与 $errFile"
