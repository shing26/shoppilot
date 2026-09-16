# 启动网关（:8082）。默认 local 模式，走本机 Ollama，不产生任何 API 费用。
#
# 优先 java -jar 跑 fat jar；jar 不存在时退回 mvn spring-boot:run 并提示先 package。
# 两条路径都经 Start-ShoppilotService 脱离调用方进程树，见 scripts/lib-launch.ps1。
param(
    [string]$Profile = "",
    # 16G 机器上约 491 MB，保持显式堆上限并避免把整机 native 内存挤空（票 31）。
    [int]$MaxRamPercentage = 3
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib-launch.ps1")
$root = Split-Path -Parent $PSScriptRoot
$jdk = Resolve-ShoppilotJdk
$java = Join-Path (Join-Path $jdk "bin") "java.exe"
$logDir = Join-Path $root "logs"
$suffix = if ($Profile) { $Profile } else { "default" }
# 文件名里的逗号会让 cmd 的重定向与 WMI 命令行解析出意外（日志曾经直接消失），
# 所以日志/launcher 用净化过的后缀，真实 profile 串只通过环境变量传。
$fileSuffix = if ($Profile) { $Profile -replace '[^A-Za-z0-9._-]', '+' } else { "default" }
$out = Join-Path $logDir "gateway-$fileSuffix.out"
$errFile = Join-Path $logDir "gateway-$fileSuffix.err"
$pidFile = Join-Path $logDir "gateway-$fileSuffix.pid"
# .env 里的键（API key、内部凭证）注入 launcher 环境；已在进程环境里的值不覆盖
$envVars = @{}
foreach ($pair in (Get-ShoppilotDotEnv $root).GetEnumerator()) { $envVars[$pair.Key] = $pair.Value }
if ($Profile) { $envVars['SPRING_PROFILES_ACTIVE'] = $Profile }
$jar = Get-ChildItem (Join-Path $root "shoppilot-gateway\target") -Filter "shoppilot-gateway-*.jar" `
    -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "sources|original" } | Select-Object -First 1

if ($jar) {
    $argsList = @("-XX:MaxRAMPercentage=$MaxRamPercentage", "-Dfile.encoding=UTF-8",
        "-jar", $jar.FullName)
    $cmdPid = Start-ShoppilotService -Name "gateway-$fileSuffix" -WorkingDirectory $root `
        -FilePath $java -ArgumentList $argsList -StandardOutput $out -Environment $envVars
} else {
    Write-Host "未找到 fat jar，退回 mvn spring-boot:run；先跑 mvnw -pl shoppilot-gateway package 可获得更稳的启动路径"
    $env:JAVA_HOME = $jdk
    $env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
    # 用户属性覆盖 POM 默认值；直接写 spring-boot.run.jvmArguments 会被 POM 配置压掉。
    $mvnArgs = @("-B", "-ntp", "-o", "-pl", "shoppilot-gateway",
        "-Dshoppilot.jvm.max-ram-percentage=$MaxRamPercentage", "spring-boot:run")
    $cmdPid = Start-ShoppilotService -Name "gateway-$fileSuffix" -WorkingDirectory $root `
        -FilePath "mvn.cmd" -ArgumentList $mvnArgs -StandardOutput $out -Environment $envVars
}
Set-Content -Path $pidFile -Value $cmdPid -Encoding ascii
Write-Host "网关启动中（profile=$Profile，launcher PID $cmdPid），日志 $out 与 $errFile"
