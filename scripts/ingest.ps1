# 政策知识库离线入库：切分 30 篇 Markdown，向量化后写 Qdrant + ES，成功后推进知识库纪元。
param([string]$Dir = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "lib-launch.ps1")
$jdk = Resolve-ShoppilotJdk
$env:JAVA_HOME = $jdk
$env:MAVEN_OPTS = "-Duser.language=en -Duser.country=US"
if ($Dir) { $env:SHOPPILOT_KNOWLEDGE_DIR = $Dir }
# 这份 jar 与常驻网关共用同一套 logback 配置：不分名的话两个进程会去轮转同一个 logs\gateway.log
# 用环境变量而不是 -Dspring-boot.run.jvmArguments：实测后者到不了 spring-boot:run 另起的那个 JVM
$previousLogApp = $env:LOG_APP
$jar = Get-ChildItem (Join-Path $root "shoppilot-gateway\target") -Filter "shoppilot-gateway-*.jar" `
    -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "sources|original" } | Select-Object -First 1
Push-Location $root
try {
    $env:LOG_APP = "ingest"
    if ($jar) {
        # 直接跑 fat jar，避免 `mvn -pl shoppilot-gateway spring-boot:run` 从本地仓库
        # 解析到旧的 tool-api；PostureGuard 那次 ClassNotFoundException 就是这个形状。
        $java = Join-Path (Join-Path $jdk "bin") "java.exe"
        & $java "-XX:MaxRAMPercentage=3" "-Dfile.encoding=UTF-8" `
            -jar $jar.FullName "--spring.profiles.active=ingest"
        exit $LASTEXITCODE
    }
    Write-Host "未找到网关 fat jar，退回 mvn spring-boot:run；先跑 scripts/up.ps1 或 mvn package 可获得更稳的入库路径"
    & mvn.cmd -B -ntp -o -pl shoppilot-gateway spring-boot:run "-Dspring-boot.run.profiles=ingest"
} finally {
    Pop-Location
    if ($null -eq $previousLogApp) {
        Remove-Item Env:LOG_APP -ErrorAction SilentlyContinue
    } else {
        $env:LOG_APP = $previousLogApp
    }
}
