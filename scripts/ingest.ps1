# 政策知识库离线入库：切分 30 篇 Markdown，向量化后写 Qdrant + ES，成功后推进知识库纪元。
param([string]$Dir = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "lib-launch.ps1")
$env:JAVA_HOME = Resolve-ShoppilotJdk
$env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
if ($Dir) { $env:SHOPPILOT_KNOWLEDGE_DIR = $Dir }
Push-Location $root
try {
    & mvn.cmd -B -ntp -o -pl shoppilot-gateway spring-boot:run "-Dspring-boot.run.profiles=ingest"
} finally {
    Pop-Location
}
