# 一条命令起栈：中间件 -> 模型 -> 构建 -> 业务库 seed -> 政策入库 -> 两个服务。
#
# 每一步都可重入：容器已在跑就跳过、seed 非空即跳过、入库是幂等 upsert。
# 这样 README 里"照着一行一行跑"才真的成立，而不是"在你那台已经配好三小时的机器上成立"。
param(
    [ValidateSet('local', 'dev', 'perf')] [string]$Profile = 'local',
    [switch]$SkipBuild,
    [switch]$SkipIngest,
    [string]$MvnArgs = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:JAVA_HOME = if ($env:SHOPPILOT_JDK) { $env:SHOPPILOT_JDK } else { 'E:\java\jdk21' }
$env:MAVEN_OPTS = '-Duser.language=en -Duser.country=US'
# JVM 侧 -Dfile.encoding=UTF-8 输出的是 UTF-8 字节，PowerShell 默认按控制台代码页（本机 cp936）
# 解码，日志里的中文就变成乱码。改控制台编码而不是改 JVM：入库与服务的输出都走这里。
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Test-Port([int]$p) {
    return (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0
}

function Wait-For([scriptblock]$check, [string]$what, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        # 探测失败是常态（端口还没监听时 Invoke-RestMethod 直接抛连接拒绝），必须继续等。
        # 本脚本 ErrorActionPreference=Stop，不在这里兜住的话第一次探测失败就会掀掉整个起栈流程。
        $ok = try { & $check } catch { $false }
        if ($ok) { Write-Host "  OK $what"; return $true }
        Start-Sleep -Seconds 3
    }
    Write-Host "  !! 等不到 $what（$seconds s）" -ForegroundColor Yellow
    return $false
}

Write-Host '[1/6] 中间件容器' -ForegroundColor Cyan
docker compose up -d | Out-Host
foreach ($pair in @(@(16379, 'Redis'), @(16333, 'Qdrant'), @(19200, 'Elasticsearch'))) {
    if (-not (Wait-For { Test-Port $pair[0] } "$($pair[1]) :$($pair[0])" 120)) { throw "$($pair[1]) 没起来，看 docker logs shoppilot-$($pair[1].ToLower())" }
}

Write-Host '[2/6] 本地模型（bge-m3 供 embedding，qwen2.5:3b 供 local 模式生成）' -ForegroundColor Cyan
if (Get-Command ollama -ErrorAction SilentlyContinue) {
    foreach ($model in @('bge-m3', 'qwen2.5:3b')) {
        $have = (& ollama list) -match $model
        if ($have) { Write-Host "  OK $model 已在本地" } else { & ollama pull $model | Out-Host }
    }
    if (-not (Wait-For { Test-Port 11434 } 'Ollama :11434' 30)) { throw 'Ollama 未监听 11434，先跑 ollama serve' }
} else {
    Write-Host '  !! 未找到 ollama：local 模式与入库都需要它。装好后重跑本脚本。' -ForegroundColor Yellow
    throw 'Ollama 未安装'
}

Write-Host '[3/6] 构建' -ForegroundColor Cyan
$jars = @('shoppilot-gateway', 'shoppilot-biz-mock') | ForEach-Object {
    Get-ChildItem (Join-Path $root "$_\target") -Filter ($_ + '-*.jar') -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|original' } | Select-Object -First 1
}
if ($SkipBuild -and $jars.Count -eq 2) {
    Write-Host '  OK 用现成的 jar（-SkipBuild）'
} else {
    # 先试离线：本仓库日常用 -o 避开远程元数据抖动；离线失败（干净机器的典型情况）再联网取一次。
    & mvn.cmd -B -ntp -o -DskipTests package @($MvnArgs -split ' ' | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0) {
        Write-Host '  离线构建失败，改用联网构建（首次约需下载依赖）' -ForegroundColor Yellow
        & mvn.cmd -B -ntp -DskipTests package @($MvnArgs -split ' ' | Where-Object { $_ })
        if ($LASTEXITCODE -ne 0) { throw '构建失败' }
    }
}

Write-Host '[4/6] 业务 Mock 中台（seed 5 万订单，与入库并行）' -ForegroundColor Cyan
& (Join-Path $root 'scripts\start-bizmock.ps1') | Out-Null

if (-not $SkipIngest) {
    Write-Host '[5/6] 政策知识库入库（幂等，重跑条目数不增长）' -ForegroundColor Cyan
    # 入库的 mvn 输出落文件而不是 Out-Null：它走管道会被吞掉，失败时只剩一行 throw，
    # 照 README 跑的人无从判断是 Ollama 没起还是 ES 拒绝连接。
    $ingestLog = Join-Path $root 'logs\ingest.out'
    & (Join-Path $root 'scripts\ingest.ps1') *>&1 | Tee-Object -FilePath $ingestLog |
        Where-Object { $_ -cmatch '已入库|切分完成|纪元|ERROR|Exception' } | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "入库失败（exit=$LASTEXITCODE），详见 logs\ingest.out" }
} else {
    Write-Host '[5/6] 跳过入库（-SkipIngest）' -ForegroundColor Yellow
}

Write-Host '[6/6] 网关' -ForegroundColor Cyan
if (-not (Wait-For { (Invoke-RestMethod 'http://127.0.0.1:8091/actuator/health' -TimeoutSec 5).status -eq 'UP' } 'biz-mock :8091' 180)) {
    throw 'biz-mock 未就绪，看 logs\bizmock.out'
}
& (Join-Path $root 'scripts\start-gateway.ps1') -Profile $Profile | Out-Null
$gatewayLog = "logs\gateway-$Profile.out"
if (-not (Wait-For { (Invoke-RestMethod 'http://127.0.0.1:8082/actuator/health' -TimeoutSec 5).status -eq 'UP' } '网关 :8082' 180)) {
    throw "网关未就绪，看 $root\$gatewayLog"
}

Write-Host ''
Write-Host "栈已就绪（profile=$Profile）：" -ForegroundColor Green
Write-Host '  调试台   http://127.0.0.1:8082/'
Write-Host '  三条演示 pwsh -NoProfile -File scripts/demo.ps1'
Write-Host '  停    栈 pwsh -NoProfile -File scripts/down.ps1'
