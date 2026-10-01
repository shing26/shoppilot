# 启动工单与坐席服务（:8092，round23 票 72 / ADR 0053）。
#
# 第四个域服务：工单数据随它搬走了（所有者裁定 B），所以 biz-mock 不再持有 tickets 表。
# 形状与 start-bizmock.ps1 同款——fat jar 优先、jar 缺失退回 spring-boot:run、日志走脱离式调用。
param(
    # 与另两个服务同一档：16G 机器上约 327 MB。工单是审计资产不是热数据，
    # 而坐席动作是低频的——这档够用，不必给更多。
    [int]$MaxRamPercentage = 2
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib-launch.ps1")
$root = Split-Path -Parent $PSScriptRoot
$jdk = Resolve-ShoppilotJdk
$java = Join-Path (Join-Path $jdk "bin") "java.exe"
$logDir = Join-Path $root "logs"
$out = Join-Path $logDir "ticket.out"
$errFile = Join-Path $logDir "ticket.err"
$pidFile = Join-Path $logDir "ticket.pid"
# .env 注入内部凭证；与另两个服务同一套传参口径
$envVars = @{}
foreach ($pair in (Get-ShoppilotDotEnv $root).GetEnumerator()) { $envVars[$pair.Key] = $pair.Value }
$jar = Get-ChildItem (Join-Path $root "shoppilot-ticket\target") -Filter "shoppilot-ticket-*.jar" `
    -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "sources|original" } | Select-Object -First 1

if ($jar) {
    $argsList = @("-XX:MaxRAMPercentage=$MaxRamPercentage", "-Dfile.encoding=UTF-8",
        "-jar", $jar.FullName)
    $cmdPid = Start-ShoppilotService -Name "ticket" -WorkingDirectory $root `
        -FilePath $java -ArgumentList $argsList -StandardOutput $out -Environment $envVars
} else {
    Write-Host "未找到 fat jar，退回 mvn spring-boot:run"
    $env:JAVA_HOME = $jdk
    $env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
    $mvnArgs = @("-B", "-ntp", "-o", "-pl", "shoppilot-ticket",
        "-Dshoppilot.jvm.max-ram-percentage=$MaxRamPercentage", "spring-boot:run")
    $cmdPid = Start-ShoppilotService -Name "ticket" -WorkingDirectory $root `
        -FilePath "mvn.cmd" -ArgumentList $mvnArgs -StandardOutput $out -Environment $envVars
}
Set-Content -Path $pidFile -Value $cmdPid -Encoding ascii
Write-Host "ticket 启动中（launcher PID $cmdPid），日志 $out 与 $errFile"