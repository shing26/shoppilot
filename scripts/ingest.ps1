# 政策知识库离线入库：切分 30 篇 Markdown，向量化后写 Qdrant + ES，成功后推进知识库纪元。
param([string]$Dir = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "lib-launch.ps1")
$env:JAVA_HOME = Resolve-ShoppilotJdk
$env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
if ($Dir) { $env:SHOPPILOT_KNOWLEDGE_DIR = $Dir }
# 这份 jar 与常驻网关共用同一套 logback 配置：不分名的话两个进程会去轮转同一个 logs\gateway.log
# 用环境变量而不是 -Dspring-boot.run.jvmArguments：实测后者到不了 spring-boot:run 另起的那个 JVM
$env:LOG_APP = "ingest"
Push-Location $root
try {
    & mvn.cmd -B -ntp -o -pl shoppilot-gateway spring-boot:run "-Dspring-boot.run.profiles=ingest"
} finally {
    Pop-Location
    Remove-Item Env:LOG_APP -ErrorAction SilentlyContinue
}
