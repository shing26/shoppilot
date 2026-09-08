# 启动业务 Mock 中台（:8091），与网关是两个进程，走真实 HTTP 边界（ADR 0002）。
param(
    [string]$Xmx = "256m",
    [int]$SeedOrders = 0,
    # HikariCP 饱和点对比实验（ticket 18）：留空用 application.yml 默认值。
    # 走环境变量而不是命令行，是为了和网关那边同一套传参口径（cmd 吃逗号）。
    [string]$PoolSize = ""
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
# .env 注入内部服务凭证等；显式参数（池大小）优先级更高
$envVars = @{}
foreach ($pair in (Get-ShoppilotDotEnv $root).GetEnumerator()) { $envVars[$pair.Key] = $pair.Value }
if ($PoolSize) { $envVars['SHOPPILOT_BIZMOCK_POOL_SIZE'] = $PoolSize }
$jar = Get-ChildItem (Join-Path $root "shoppilot-biz-mock\target") -Filter "shoppilot-biz-mock-*.jar" `
    -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "sources|original" } | Select-Object -First 1

if ($jar) {
    $argsList = @("-Xmx$Xmx", "-Dfile.encoding=UTF-8", "-jar", $jar.FullName)
    if ($SeedOrders -gt 0) { $argsList += "--shoppilot.bizmock.seed.orders=$SeedOrders" }
    $cmdPid = Start-ShoppilotService -Name "bizmock" -WorkingDirectory $root `
        -FilePath $java -ArgumentList $argsList -StandardOutput $out -Environment $envVars
} else {
    Write-Host "未找到 fat jar，退回 mvn spring-boot:run"
    $env:JAVA_HOME = $jdk
    $env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
    $mvnArgs = @("-B", "-ntp", "-o", "-pl", "shoppilot-biz-mock", "spring-boot:run")
    if ($PoolSize) { $env:SHOPPILOT_BIZMOCK_POOL_SIZE = $PoolSize }
    $cmdPid = Start-ShoppilotService -Name "bizmock" -WorkingDirectory $root `
        -FilePath "mvn.cmd" -ArgumentList $mvnArgs -StandardOutput $out -Environment $envVars
}
Set-Content -Path $pidFile -Value $cmdPid -Encoding ascii
# 把池大小落盘：看门狗重启时读它，否则池实验中途 biz-mock 被拉回来会静默回到默认 30，
# 那一档数据就成了挂着 pool10 名字、实际跑 pool30 的假数字。
if ($PoolSize) { Set-Content -Path (Join-Path $logDir "bizmock.pool") -Value $PoolSize -Encoding ascii }
Write-Host "biz-mock 启动中（launcher PID $cmdPid，seed=$(if ($SeedOrders -gt 0) { $SeedOrders } else { "全量" })，池=$PoolSize），日志 $out 与 $errFile"
