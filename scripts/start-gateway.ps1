# 启动网关（:8082）。默认 local 模式，走本机 Ollama，不产生任何 API 费用。
param(
    [string]$Profile = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = if ($env:SHOPPILOT_JDK) { $env:SHOPPILOT_JDK } else { "E:\java\jdk21" }
$env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
New-Item -ItemType Directory -Force -Path (Join-Path $root "logs") | Out-Null
$argsList = @("-B", "-ntp", "-o", "-pl", "shoppilot-gateway", "spring-boot:run")
if ($Profile) { $argsList += "-Dspring-boot.run.profiles=$Profile" }
Start-Process -FilePath "mvn.cmd" -ArgumentList $argsList -WorkingDirectory $root `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $root "logs\gateway.out") `
    -RedirectStandardError (Join-Path $root "logs\gateway.err")
Write-Host "网关启动中，日志 logs\gateway.out"
