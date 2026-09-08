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
# 文件名里的逗号会让 cmd 的重定向与 WMI 命令行解析出意外（日志曾经直接消失），
# 所以日志/launcher 用净化过的后缀，真实 profile 串只通过环境变量传。
$fileSuffix = if ($Profile) { $Profile -replace '[^A-Za-z0-9._-]', '+' } else { "default" }
$out = Join-Path $logDir "gateway-$fileSuffix.out"
$errFile = Join-Path $logDir "gateway-$fileSuffix.err"
$pidFile = Join-Path $logDir "gateway-$fileSuffix.pid"
$jar = Get-ChildItem (Join-Path $root "shoppilot-gateway\target") -Filter "shoppilot-gateway-*.jar" `
    -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "sources|original" } | Select-Object -First 1

if ($jar) {
    $argsList = @("-Xmx$Xmx", "-Dfile.encoding=UTF-8", "-jar", $jar.FullName)
    $envVars = if ($Profile) { @{ SPRING_PROFILES_ACTIVE = $Profile } } else { @{} }
    $cmdPid = Start-ShoppilotService -Name "gateway-$fileSuffix" -WorkingDirectory $root `
        -FilePath $java -ArgumentList $argsList -StandardOutput $out -Environment $envVars
} else {
    Write-Host "未找到 fat jar，退回 mvn spring-boot:run；先跑 mvnw -pl shoppilot-gateway package 可获得更稳的启动路径"
    $env:JAVA_HOME = $jdk
    $env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
    $mvnArgs = @("-B", "-ntp", "-o", "-pl", "shoppilot-gateway", "spring-boot:run")
    $envVars = if ($Profile) { @{ SPRING_PROFILES_ACTIVE = $Profile } } else { @{} }
    $cmdPid = Start-ShoppilotService -Name "gateway-$fileSuffix" -WorkingDirectory $root `
        -FilePath "mvn.cmd" -ArgumentList $mvnArgs -StandardOutput $out -Environment $envVars
}
Set-Content -Path $pidFile -Value $cmdPid -Encoding ascii
Write-Host "网关启动中（profile=$Profile，launcher PID $cmdPid），日志 $out 与 $errFile"
