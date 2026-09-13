# ticket 18 的完整实验矩阵，一条命令跑完并可重跑（PLAN 承诺项：每组含环境记录）。
#
# 五组实验需要三种网关 profile，所以脚本自己负责"停-起-等健康-跑"。
# 每组跑完立刻把 ladder CSV 的名字打印出来，中途挂了也知道丢了哪一组。
param(
    [string]$Only = "",           # 逗号分隔的实验键：mix80,l1,l2,novirtual,nocache,sse
    [int]$Workers = 4,
    [int]$Duration = 60,
    [int]$Spawn = 20,
    [string]$Steps = "100,200,400,800,1200,1600",
    # HikariCP 实验专用：三档池大小与各自的并发阶梯。写进 param 而不是硬编码在 case 里，
    # 是为了让 -Only pool -PoolSizes 10,30 这种补跑不必改码。
    [string]$PoolSizes = "2,10,30",
    [string]$PoolSteps = "50,100,200"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$all = @("mix80", "l1", "l2", "l2emb", "noollama", "novirtual", "nocache", "sse", "pool")
$selected = if ($Only) { $Only -split "," | ForEach-Object { $_.Trim() } } else { $all }

# 压测依赖装在项目本地 venv 里（locust + requests），系统 python 是 Anaconda base。
# 用它会以 "No module named locust" 失败，或者更糟——把包装进共享环境。
$Python = if ($env:SHOPPILOT_PYTHON) {
    $env:SHOPPILOT_PYTHON
} elseif (Test-Path (Join-Path $root ".venv-loadtest\Scripts\python.exe")) {
    Join-Path $root ".venv-loadtest\Scripts\python.exe"
} else {
    "python"
}
Write-Host "压测解释器：$Python"

function Wait-Healthy([int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            # 读 readiness 而不是总健康：票 23 起网关总健康会把可降级依赖算进去（ADR 0026），
            # 而实验档存在的全部理由就是把某个依赖弄残去量降级——总健康在这几档下本来就该红。
            # 与 up.ps1、run-acceptance.ps1 同一个放行谓词，别在这里另立一套。
            if ((Invoke-RestMethod "http://127.0.0.1:8082/actuator/health/readiness" -TimeoutSec 5).status -eq "UP") { return $true }
        } catch { Start-Sleep -Seconds 4 }
    }
    return $false
}

function Get-OpsSwitches {
    # /ops/switches 在鉴伞后面，先领一个 mock JWT（/auth/mock-token 是免鉴伞的本地端点）。
    $token = (Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8082/auth/mock-token" -ContentType "application/json" `
        -Body '{"tenantId":"T001","customerId":"C001"}' -TimeoutSec 10).token
    if (-not $token) { throw "拿不到 mock token，无法回读实验开关" }
    return Invoke-RestMethod "http://127.0.0.1:8082/api/v1/support/ops/switches" `
        -Headers @{ Authorization = "Bearer $token"; "X-Ops-Token" = "dev-ops-token" } -TimeoutSec 10
}

# 期望值来自命令行而不是硬编码：每组实验只声明它真正关心的那几个开关，其余不校。
function Assert-Switches([hashtable]$Expect) {
    if (-not $Expect) { return }
    # 健康检查 UP 不代表 MVC 分发器就绪：刚起的进程读一次可能被拒，重试几次再判失败
    $actual = $null
    foreach ($attempt in 1..5) {
        try { $actual = Get-OpsSwitches; break } catch { Start-Sleep -Seconds 3 }
    }
    if (-not $actual) { throw "读不到 /ops/switches，回读守卫本身失效，中止" }
    foreach ($key in $Expect.Keys | Sort-Object) {
        if (-not ($actual.PSObject.Properties.Name -contains $key)) {
            throw "网关 /ops/switches 没有字段 $key，回读守卫失效，中止"
        }
        $value = $actual.$key
        if ("$value" -ne "$($Expect[$key])") {
            throw "开关 $key 实际=$value 期望=$($Expect[$key])：profile 生效了但属性没吃进去（YAML 缩进/键名问题），本组数据会作废，中止"
        }
        Write-Host "  回读 $key = $value"
    }
}

function Restart-Gateway([string]$Profile, [hashtable]$Expect = @{}) {
    & (Join-Path $root "scripts\stop.ps1") -Ports "8082" | Out-Null
    Start-Sleep -Seconds 3
    & (Join-Path $root "scripts\start-gateway.ps1") -Profile $Profile | Out-Null
    if (-not (Wait-Healthy)) { throw "网关以 profile=$Profile 启动失败，看 logs\gateway-$Profile.out" }
    # 只看健康是不够的：profile 没吃进去时服务照样健康，数据却整组作废（09-08 的 nocache 就这样白跑）
    $logName = "gateway-" + ($Profile -replace '[^A-Za-z0-9._-]', '+') + ".out"
    $active = Select-String -Path (Join-Path $root "logs\$logName") -Pattern 'profile(s)? (is|are) active' |
        Select-Object -Last 1
    if (-not $active) { throw "网关日志 $logName 里没有 profile 生效记录，实验组 $Profile 无法确认，中止" }
    if ($active.Line -match 'profile(s)? (is|are) active:\s*(.+)$') { Write-Host "  生效 profile：$($matches[3])" }
    # profile 名字对了还差一步：属性是否真的绑上了。日志看不到这个，只有进程自己知道。
    Assert-Switches $Expect
    Write-Host "  网关就绪（profile=$Profile）"
}

function Wait-BizMockHealthy([int]$Seconds = 150) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            if ((Invoke-RestMethod "http://127.0.0.1:8091/actuator/health" -TimeoutSec 5).status -eq "UP") { return $true }
        } catch { Start-Sleep -Seconds 4 }
    }
    return $false
}

# 与网关同一个教训：池子没吃进去时 biz-mock 照样健康，量到的却是默认池。用指标反读确认。
function Restart-BizMock([string]$Pool) {
    # 空值会静默走到默认池。实踩过一次：参数没进 param 块，$PoolSizes 读成空，
    # biz-mock 起在默认 30 上——反读守卫当场中止才没留下挂着 pool2 名字的废数据。
    if (-not $Pool) { throw "Restart-BizMock 需要显式池大小，拒绝按默认值跑" }
    & (Join-Path $root "scripts\stop.ps1") -Ports "8091" | Out-Null
    Start-Sleep -Seconds 3
    & (Join-Path $root "scripts\start-bizmock.ps1") -PoolSize $Pool | Out-Null
    if (-not (Wait-BizMockHealthy)) { throw "biz-mock 池=$Pool 启动失败，看 logs\bizmock.out" }
    $max = (Invoke-RestMethod "http://127.0.0.1:8091/actuator/metrics/hikaricp.connections.max" -TimeoutSec 10).measurements | Where-Object { $_.statistic -eq "VALUE" }
    if (-not $max) { throw "读不到 biz-mock 的 hikaricp.connections.max，无法确认池大小，中止" }
    if ([int]$max.value -ne [int]$Pool) { throw "biz-mock 池实际为 $($max.value)，期望 $Pool，中止" }
    Write-Host "  biz-mock 就绪 HikariCP maximum-pool-size=$([int]$max.value)"
}

function Invoke-Ladder([string]$key, [string]$model, [string]$profile, [string]$steps) {
    Write-Host ""
    Write-Host "===== 实验 $key：模型 $model / profile $profile ====="
    & $Python scripts/run_loadtest.py --model $model --profile $profile --steps $steps `
        --duration $Duration --spawn $Spawn --workers $Workers --tag $key
    if ($LASTEXITCODE -ne 0) { Write-Host "  !! 实验 $key 退出码 $LASTEXITCODE" }
    Get-ChildItem (Join-Path $root "loadtest\results") -Filter "ladder-*-$key.csv" |
        ForEach-Object { Write-Host "  产物 $($_.Name)" }
}

# 每组实验真正依赖的开关，起进程后逐条回读（见 Assert-Switches）。
$perfOn = @{ llmMode = "perf"; cacheEnabled = "True"; singleflightEnabled = "True";
             embeddingInProcessCache = "True" }
$noVirtual = @{ llmMode = "perf"; virtualThreadRequest = "False" }
$noCache = @{ llmMode = "perf"; cacheEnabled = "False" }
$noCacheNoSf = @{ llmMode = "perf"; cacheEnabled = "False"; singleflightEnabled = "False" }
$noEmbedCache = @{ llmMode = "perf"; embeddingInProcessCache = "False" }
$noOllama = @{ llmMode = "perf"; embeddingBaseUrl = "http://127.0.0.1:59999" }

foreach ($key in $selected) {
    switch ($key) {
        "mix80" {
            Restart-Gateway "perf" $perfOn
            Invoke-Ladder "mix80" "mix80" "perf" $Steps
        }
        "l1" {
            Restart-Gateway "perf" $perfOn
            Invoke-Ladder "l1" "l1" "perf" $Steps
        }
        "l2" {
            Restart-Gateway "perf" $perfOn
            Invoke-Ladder "l2" "l2" "perf" $Steps
        }
        "novirtual" {
            # 虚拟线程收益的对照组：同一模型、同一并发，只关 spring.threads.virtual.enabled
            Restart-Gateway "perf,no-virtual" $noVirtual
            Invoke-Ladder "novirtual" "l1" "perf,no-virtual" $Steps
            Restart-Gateway "perf" $perfOn
        }
        "nocache" {
            # Token 节约率的基线：关缓存重放同一流量模型，量的是真实 token 而不是假设
            Restart-Gateway "perf,nocache" $noCache
            Invoke-Ladder "nocache" "l1" "perf,nocache" "200"
            # 零防线基线：缓存与防击穿合并一起关。只关缓存时合并仍在替模型省调用，
            # 节约率会被压到 25% 这种假数字上。
            Restart-Gateway "perf,nocache,nosf" $noCacheNoSf
            Invoke-Ladder "nosf" "l1" "perf,nocache,nosf" "200"
            # 对照组必须同并发、同模型、同 spawn，否则比值里混进的不是缓存这一件事
            Restart-Gateway "perf" $perfOn
            Invoke-Ladder "cacheton" "l1" "perf" "200"
        }
        "l2emb" {
            # L2 曲线天花板归因：关掉进程内向量缓存，让每个请求真打一次 bge-m3
            Restart-Gateway "perf,no-embedding-cache" $noEmbedCache
            Invoke-Ladder "l2emb" "l2" "perf,no-embedding-cache" "100,200,400"
            Restart-Gateway "perf" $perfOn
        }
        "noollama" {
            # ticket 18 的"压测期间模型服务停用"那一格：向量服务指到空端口，等价于 Ollama 整个进程没了，
            # 且不动别人共用的 11434。除了吞吐与拦截率，这组真正要读出的是缓存的两道门各自挡了什么。
            Restart-Gateway "perf,no-ollama" $noOllama
            Invoke-Ladder "noollama" "l1" "perf,no-ollama" "100,200,400"
            Restart-Gateway "perf" $perfOn
        }
        "sse" {
            Restart-Gateway "perf" $perfOn
            Write-Host ""
            Write-Host "===== 实验 sse：500 条并发长连接的 TTFT 与堆占用 ====="
            & $Python scripts/run_sse_ttft.py --connections 500 --duration 60 --label perf
        }
        "pool" {
            # HikariCP 饱和点：同一流量模型、同一并发阶梯，只动 biz-mock 的连接池。
            # 三档而不是两档：2 是故意饿死它，用来拿到"饱和时长什么样"的正样本；
            # 只看 10 与 30 的话，pending 全程为 0，根本说不出饱和点在哪里。
            # 用 biz 模型（100% 业务办理）而不是 l1：l1 里只有 15% 请求真碰数据库。
            Restart-Gateway "perf" $perfOn
            foreach ($pool in ($PoolSizes -split "," | ForEach-Object { $_.Trim() })) {
                Restart-BizMock $pool
                Invoke-Ladder "pool$pool" "biz" "perf" $PoolSteps
            }
            Restart-BizMock "30"
        }
        default { Write-Host "未知实验键 $key" }
    }
}
Write-Host ""
Write-Host "实验矩阵完成"
