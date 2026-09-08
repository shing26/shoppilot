# 启动网关（:8082）。默认 local 模式，走本机 Ollama，不产生任何 API 费用。
#
# 优先 java -jar 跑 fat jar；jar 不存在时退回 mvn spring-boot:run 并提示先 package。
# 两条路径都经 Start-ShoppilotService 脱离调用方进程树，见 scripts/lib-launch.ps1。
param(
    [string]$Profile = "",
    [string]$Xmx = "512m"
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib-launch.ps1")
$root = Split-Path -Parent $PSScriptRoot
$jdk = if ($env:SHOPPILOT_JDK) { $env:SHOPPILOT_JDK } else { "E:\java\jdk21" }
$java = Join-Path (Join-Path $jdk "bin") "java.exe"
$logDir = Join-Path $root "logs"
$suffix = if ($Profile) { $Profile } else { "default" }
$out = Join-Path $logDir "gateway-$suffix.out"
$errFile = Join-Path $logDir "gateway-$suffix.err"
$pidFile = Join-Path $logDir "gateway-$suffix.pid"
$jar = Get-ChildItem (Join-Path $root "shoppilot-gateway\target") -Filter "shoppilot-gateway-*.jar" `
    -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "sources|original" } | Select-Object -First 1

if ($jar) {
    $argsList = @("-Xmx$Xmx", "-Dfile.encoding=UTF-8", "-jar", $jar.FullName)
    if ($Profile) { $argsList += "--spring.profiles.active=$Profile" }
    $cmdPid = Start-ShoppilotService -Name "gateway-$suffix" -WorkingDirectory $root `
        -FilePath $java -ArgumentList $argsList -StandardOutput $out
} else {
    Write-Host "未找到 fat jar，退回 mvn spring-boot:run；先跑 mvnw -pl shoppilot-gateway package 可获得更稳的启动路径"
    $env:JAVA_HOME = $jdk
    $env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
    $mvnArgs = @("-B", "-ntp", "-o", "-pl", "shoppilot-gateway", "spring-boot:run")
    if ($Profile) { $mvnArgs += "-Dspring-boot.run.profiles=$Profile" }
    $cmdPid = Start-ShoppilotService -Name "gateway-$suffix" -WorkingDirectory $root `
        -FilePath "mvn.cmd" -ArgumentList $mvnArgs -StandardOutput $out
}
Set-Content -Path $pidFile -Value $cmdPid -Encoding ascii
Write-Host "网关启动中（profile=$suffix，launcher PID $cmdPid），日志 $out 与 $errFile"
