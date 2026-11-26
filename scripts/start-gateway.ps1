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
# 最终环境 = .env + 调用方已导出（非空）+ 本脚本的显式覆盖。
# WMI 子进程不继承调用方环境，所以必须显式写进 run-*.cmd（票 229）。
$overrides = @{}
if ($Profile) { $overrides['SPRING_PROFILES_ACTIVE'] = $Profile }
# profile 里的 LLM 模式必须压过 .env 的 SHOPPILOT_LLM_MODE。
# Spring 的属性优先级是「环境变量 > profile 专属 application.yml」，而 .env 里一行
# SHOPPILOT_LLM_MODE=dev 就会让 `-Profile perf` 静默跑真模型：2026-11-05 的压测因此
# 实际走了 qwen-plus，花掉 381,709 token 后跌进预算熔断，整轮吞吐读数作废。
# 这里把 profile 名映射成对应模式，和 application.yml 三段 profile 的注释同义。
if ($Profile) {
    $profileMode = ($Profile -split ',') |
        Where-Object { $_ -in @('dev', 'local', 'perf', 'demo', 'replay') } |
        Select-Object -First 1
    if ($profileMode) { $overrides['SHOPPILOT_LLM_MODE'] = $profileMode }
}
$envVars = Get-ShoppilotLaunchEnvironment -Root $root -Overrides $overrides
$version = Get-ShoppilotProjectVersion -Root $root
$jar = Get-ShoppilotFatJar -Root $root -Module 'shoppilot-gateway' -Version $version

if ($jar) {
    $argsList = @("-XX:MaxRAMPercentage=$MaxRamPercentage", "-Dfile.encoding=UTF-8",
        "-jar", $jar.FullName)
    $cmdPid = Start-ShoppilotService -Name "gateway-$fileSuffix" -WorkingDirectory $root `
        -FilePath $java -ArgumentList $argsList -StandardOutput $out -Environment $envVars
} else {
    Write-Host "未找到 shoppilot-gateway-$version.jar（不选其它版本），退回 mvn spring-boot:run；先跑 mvnw -pl shoppilot-gateway package 可获得更稳的启动路径"
    $env:JAVA_HOME = $jdk
    $env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
    # mvn 回退路径同样走 WMI：JDK 两格必须进合并环境（票 229）。
    $envVars = Get-ShoppilotMvnFallbackEnvironment -Root $root -JdkHome $jdk -Overrides $overrides
    # 用户属性覆盖 POM 默认值；直接写 spring-boot.run.jvmArguments 会被 POM 配置压掉。
    $mvnArgs = @("-B", "-ntp", "-o", "-pl", "shoppilot-gateway",
        "-Dshoppilot.jvm.max-ram-percentage=$MaxRamPercentage", "spring-boot:run")
    $cmdPid = Start-ShoppilotService -Name "gateway-$fileSuffix" -WorkingDirectory $root `
        -FilePath "mvn.cmd" -ArgumentList $mvnArgs -StandardOutput $out -Environment $envVars
}
Set-Content -Path $pidFile -Value $cmdPid -Encoding ascii
Write-Host "网关启动中（profile=$Profile，launcher PID $cmdPid），日志 $out 与 $errFile"
