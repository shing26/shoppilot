# 一条命令起栈：中间件 -> 模型 -> 构建 -> 业务库 seed -> 政策入库 -> 两个服务。
#
# 每一步都可重入：容器已在跑就跳过、seed 非空即跳过、入库是幂等 upsert。
# 这样 README 里"照着一行一行跑"才真的成立，而不是"在你那台已经配好三小时的机器上成立"。
param(
    [ValidateSet('local', 'dev', 'perf')] [string]$Profile = 'local',
    [switch]$SkipBuild,
    [switch]$SkipIngest,
    # 可选容器档（round23 票 76）：四个域服务跑在容器里，干净克隆一条命令起全栈。
    # **默认路径不变**——不带这个开关时仍然起本机 JVM，改代码即重启是日常开发的形态。
    [switch]$Containerized,
    [string]$MvnArgs = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot 'lib-launch.ps1')
# 找不到 JDK 就在这里停：起栈脚本以前拿一个绝对路径兜底，换台机器会把 JAVA_HOME 指到不存在的目录，
# 失败点于是漂到几十秒之后的一句 mvnw 报错上。
$env:JAVA_HOME = Resolve-ShoppilotJdk
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

if ($Containerized) {
    if ($SkipBuild) { throw '-Containerized 与 -SkipBuild 互斥：容器档自己构建镜像，没有「用现成 jar」这档' }
    # 容器档不需要本机 JDK：镜像里那一份由 Dockerfile 的 build stage 提供。
    Write-Host '档位：容器（--profile full）。默认路径仍是本机 JVM。' -ForegroundColor Cyan
    docker compose up -d | Out-Host
    if ($LASTEXITCODE -ne 0) { throw '中间件启动失败' }
    # 端口与 compose 用同一批环境变量（票 79）：第二份克隆整体右移时，等就绪也得等在它自己的端口上。
    # 容器名用 $env:COMPOSE_PROJECT_NAME 派生——compose 会把它设进环境。
    $pRedis = if ($env:SHOPPIOT_REDIS_PORT) { [int]$env:SHOPPIOT_REDIS_PORT } else { 16379 }
    $pQdrant = if ($env:SHOPPIOT_QDRANT_PORT) { [int]$env:SHOPPIOT_QDRANT_PORT } else { 16333 }
    $pEs = if ($env:SHOPPIOT_ES_PORT) { [int]$env:SHOPPIOT_ES_PORT } else { 19200 }
    $pGateway = if ($env:SHOPPIOT_GATEWAY_PORT) { [int]$env:SHOPPIOT_GATEWAY_PORT } else { 8082 }
    $project = if ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME } else { 'shoppilot' }
    foreach ($triple in @(@($pRedis, 'Redis', "$project-redis"), @($pQdrant, 'Qdrant', "$project-qdrant"), @($pEs, 'Elasticsearch', "$project-es"))) {
        if (-not (Wait-For { Test-Port $triple[0] } "$($triple[1]) :$($triple[0])" 180)) {
            throw "$($triple[1]) 没起来，看 docker logs $($triple[2])"
        }
    }
    Write-Host '  构建四个域服务的镜像（首次较慢：要下 Maven 依赖）' -ForegroundColor Cyan
    docker compose --profile full build | Out-Host
    if ($LASTEXITCODE -ne 0) { throw '镜像构建失败' }
    Write-Host '  起服务（含一次性 ingest 作业）' -ForegroundColor Cyan
    docker compose --profile full up -d | Out-Host
    if ($LASTEXITCODE -ne 0) { throw '服务启动失败' }
    # 预热跨服务链路：容器里工单服务的第一个请求要初始化 DispatcherServlet（实测 >3s），
    # 不在这里付掉这笔账，第一个**用户**请求就会打成 downstream_unreachable（票 76 实测）。
    # 顺带把 biz-mock 也碰一下——它同样是容器里的新进程。
    # 预热用的端口在**容器网络内**是固定的（服务自己的监听端口），不走宿主映射，所以不受偏移影响。
    foreach ($warm in @('http://127.0.0.1:8092/actuator/health/readiness',
                        'http://127.0.0.1:8092/api/tickets/count',
                        'http://127.0.0.1:8091/api/actuator/health/readiness')) {
        try { Invoke-RestMethod $warm -TimeoutSec 30 -Headers @{
                'X-Internal-Token' = $env:SHOPPILOT_INTERNAL_TOKEN
                'X-Tenant-Id' = 'T001' } | Out-Null } catch {
            Write-Host "  !! 预热 $warm 失败：$($_.Exception.Message)" -ForegroundColor Yellow
        }
    }

    # 就绪失败必须**失败**，不能打一句「栈已就绪」就当成了——本机档那条路径是 throw，
    # 容器档曾经只 warn 然后照样打印就绪横幅，那是一句假绿（本轮实测踩到）。
    if (-not (Wait-For { (Invoke-RestMethod "http://127.0.0.1:$pGateway/actuator/health/readiness" -TimeoutSec 5).status -eq 'UP' } "网关 :$pGateway" 300)) {
        throw '网关未就绪，看 docker compose logs gateway'
    }
    Write-Host ''
    Write-Host '栈已就绪（容器档）：' -ForegroundColor Green
    Write-Host "  调试台   http://127.0.0.1:$pGateway/"
    Write-Host "  坐席工作台 http://127.0.0.1:$pGateway/workspace/"
    Write-Host '  停    栈 pwsh -NoProfile -File scripts/down.ps1 -Containerized'
    exit 0
}

Write-Host '[1/7] 中间件容器' -ForegroundColor Cyan
docker compose up -d | Out-Host
if ($LASTEXITCODE -ne 0) {
    # 干净检出最常撞的一行是 The container name "/shoppilot-es" is already in use：
    # compose 文件钉死了容器名，而名字在 Docker 里全局唯一，所以"另一个检出只是停着没删"也会挡住起栈。
    # 数据都在命名卷里，删容器不丢数据；down.ps1 -Containers 现在就是 stop + rm。
    throw 'docker compose up -d 失败。若报 already in use：被另一个检出（或原仓库）留下的停止中的容器占了名字，跑 pwsh -NoProfile -File scripts/down.ps1 -Containers 移除后重试。'
}
foreach ($triple in @(@(16379, 'Redis', 'shoppilot-redis'), @(16333, 'Qdrant', 'shoppilot-qdrant'), @(19200, 'Elasticsearch', 'shoppilot-es'))) {
    if (-not (Wait-For { Test-Port $triple[0] } "$($triple[1]) :$($triple[0])" 120)) { throw "$($triple[1]) 没起来，看 docker logs $($triple[2])" }
}

Write-Host '[2/7] 本地模型（bge-m3 供 embedding，qwen2.5:3b 供 local 模式生成）' -ForegroundColor Cyan
if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
    Write-Host '  !! 未找到 ollama：local 模式与入库都需要它。装好后重跑本脚本。' -ForegroundColor Yellow
    throw 'Ollama 未安装'
}
# 先确认端口在听，再问 CLI。顺序反了会冻：11434 没人监听时，第一句 `ollama list` 就让 CLI 去拉起
# Ollama 的应用与服务进程，那几个进程继承了本步骤的重定向输出句柄，PowerShell 要等句柄 EOF 才认
# 为外部命令结束，于是永远等不到——连原来那句"先跑 ollama serve"的 fail-fast 都到不了。
# 2026-09-11 18:13 那一轮门禁就是这么卡在 stack 步 20 分钟的（当时本机 Ollama 服务没在跑），
# 证据是被拉起的 Ollama 自己那几行 INFO 出现在了本步骤的日志里：句柄确实被继承了。
# 这里用仓库自己的脱离式启动法（WMI 创建、父进程是 WmiPrvSE、日志走自己的文件），
# 和 gateway/biz-mock 同一条路；它们从没把卡住过，卡的只有走 CLI 继承句柄的这一条。
# 这个服务起来后不随 down.ps1 停：11434 是共用的模型服务，脚本不去抢别人的端口，只在它没人听时补上。
if (-not (Test-Port 11434)) {
    Start-ShoppilotService -Name 'ollama' -WorkingDirectory $root -FilePath (Get-Command ollama).Source `
        -ArgumentList @('serve') -StandardOutput (Join-Path $root 'logs\ollama.out') | Out-Null
    if (-not (Wait-For { Test-Port 11434 } 'Ollama :11434' 60)) { throw 'Ollama 未监听 11434，先跑 ollama serve' }
}
foreach ($model in @('bge-m3', 'qwen2.5:3b')) {
    $have = (& ollama list) -match $model
    if ($have) { Write-Host "  OK $model 已在本地" } else { & ollama pull $model | Out-Host }
}

Write-Host '[3/7] 构建' -ForegroundColor Cyan
function Find-FatJars {
    @('shoppilot-gateway', 'shoppilot-biz-mock', 'shoppilot-ticket') | ForEach-Object {
        Get-ChildItem (Join-Path $root "$_\target") -Filter ($_ + '-*.jar') -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|original' } | Select-Object -First 1
    }
}
$jars = Find-FatJars
if ($SkipBuild -and $jars.Count -eq 3) {
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
# 退出码为 0 不等于产物齐。2026-09-10 那次干净检出检查里，克隆目录空闲只有 1.5 GB，
# Maven 的 JVM 在 reactor 中途被打断，biz-mock 的 fat jar 根本没打出来；start-bizmock.ps1
# 找不到 jar 就退回 `mvn spring-boot:run`，在已经缺内存的机器上再多开一个 JVM，
# 于是失败点变成"biz-mock 300 s 没 readiness"——离真因隔了两步、还看不出关系。
# 所以在这里就红，红在"产物不齐"这一行，并把唯一的补救动作写出来。
$built = Find-FatJars
if ($built.Count -lt 3) {
    $have = @($built | ForEach-Object { $_.Directory.Parent.Name })
    $missing = @('shoppilot-gateway', 'shoppilot-biz-mock') | Where-Object { $_ -notin $have }
    throw ("构建没有产出 fat jar（缺：{0}）。这台机器上多半是内存不够把 Maven 的 JVM 打断了；" -f ($missing -join ', ')) + '重跑 scripts/up.ps1 即可（每一步都可重入）。'
}

Write-Host '[4/7] 业务 Mock 中台（seed 5 万订单，与入库并行）' -ForegroundColor Cyan
& (Join-Path $root 'scripts\start-bizmock.ps1') | Out-Null

if (-not $SkipIngest) {
    Write-Host '[5/7] 政策知识库入库（幂等，重跑条目数不增长）' -ForegroundColor Cyan
    # 入库的 mvn 输出落文件而不是 Out-Null：它走管道会被吞掉，失败时只剩一行 throw，
    # 照 README 跑的人无从判断是 Ollama 没起还是 ES 拒绝连接。
    $ingestLog = Join-Path $root 'logs\ingest.out'
    & (Join-Path $root 'scripts\ingest.ps1') *>&1 | Tee-Object -FilePath $ingestLog |
        Where-Object { $_ -cmatch '已入库|切分完成|纪元|ERROR|Exception' } | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "入库失败（exit=$LASTEXITCODE），详见 logs\ingest.out" }
} else {
    Write-Host '[5/7] 跳过入库（-SkipIngest）' -ForegroundColor Yellow
}

Write-Host '[6/7] 工单与坐席服务（round23 票 72）' -ForegroundColor Cyan
# 顺序是刻意的：网关的降级落单指向它，而 biz-mock 的退款审批也要经它落单——
# 它没就绪的话，网关一起来就会把「转人工」打到一个不存在的服务上。
& (Join-Path $root 'scripts\start-ticket.ps1') | Out-Null
if (-not (Wait-For { (Invoke-RestMethod 'http://127.0.0.1:8092/actuator/health/readiness' -TimeoutSec 5).status -eq 'UP' } 'ticket :8092' 120)) {
    throw '工单服务未就绪，看 logs	icket.out'
}

Write-Host '[7/7] 网关' -ForegroundColor Cyan
# readiness 组：seed 5 万订单期间就是 503 OUT_OF_SERVICE，避免"health 已 UP 但库里还没数据"的抢跑
# 180 s 不够：09-09 16:33 那次全量验收里，biz-mock 的 seed 与 [5/6] 的入库（90 块 × 向量化 + ES/Qdrant 写入）
# 并行抢同一台 16 G 机器，3 分钟还没 UP，这一步直接红——而服务本身没坏，后面靠健康门又拉回来了。
# 空机上 seed 实测 8.3 s，所以这里不是把超时调到"永远够"，是给并行阶段留出实测 20 倍余量。
if (-not (Wait-For { (Invoke-RestMethod 'http://127.0.0.1:8091/actuator/health/readiness' -TimeoutSec 5).status -eq 'UP' } 'biz-mock :8091' 300)) {
    throw 'biz-mock 未就绪，看 logs\bizmock.out'
}
& (Join-Path $root 'scripts\start-gateway.ps1') -Profile $Profile | Out-Null
$gatewayLog = "logs\gateway-$Profile.out"
if (-not (Wait-For { (Invoke-RestMethod 'http://127.0.0.1:8082/actuator/health/readiness' -TimeoutSec 5).status -eq 'UP' } '网关 :8082' 180)) {
    throw "网关未就绪，看 $root\$gatewayLog"
}

Write-Host ''
Write-Host "栈已就绪（profile=$Profile）：" -ForegroundColor Green
Write-Host '  调试台   http://127.0.0.1:8082/'
Write-Host '  工单服务 http://127.0.0.1:8092/actuator/health'
Write-Host '  三条演示 pwsh -NoProfile -File scripts/demo.ps1'
Write-Host '  停    栈 pwsh -NoProfile -File scripts/down.ps1'
