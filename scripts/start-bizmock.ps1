# 启动业务 Mock 中台（:8091），与网关是两个进程，走真实 HTTP 边界（ADR 0002）。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = if ($env:SHOPPILOT_JDK) { $env:SHOPPILOT_JDK } else { "E:\java\jdk21" }
$env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
New-Item -ItemType Directory -Force -Path (Join-Path $root "logs") | Out-Null
Start-Process -FilePath "mvn.cmd" `
    -ArgumentList @("-B", "-ntp", "-o", "-pl", "shoppilot-biz-mock", "spring-boot:run") `
    -WorkingDirectory $root -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $root "logs\bizmock.out") `
    -RedirectStandardError (Join-Path $root "logs\bizmock.err")
Write-Host "biz-mock 启动中（种子数据约 35 秒），日志 logs\bizmock.out"
