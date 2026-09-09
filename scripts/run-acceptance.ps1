<#
    一条命令跑完 PLAN.md 的全部验收：构建 + 起栈 + 所有 verify-* + 三条演示。

    为什么要这个脚本：验收脚本散在 12 个文件里，靠人记顺序必然漏跑，
    而"漏跑"和"没通过"对外行来说是一回事。这里把顺序、退出码、日志位置固定下来，
    任何一步失败都留痕并继续跑完剩下的，最后一次看矩阵就知道哪几行没绿。

    步骤顺序有讲究：
      build 必须先 stop 服务（fat jar 被运行中的进程锁住，见 README 已知限制）；
      降级类脚本（verify-fallback / verify-polarity）会读实时计数器，放在 plan-actions 的
      两次重启之后跑，避免被它的 biz-mock 重启打断；
      plan-actions -WithRestarts 会自己重启 biz-mock 与网关并在结束时恢复默认配置。

    例:
      pwsh -NoProfile -File scripts/run-acceptance.ps1                    # 全跑（含构建）
      pwsh -NoProfile -File scripts/run-acceptance.ps1 -SkipBuild         # 用现成 jar
      pwsh -NoProfile -File scripts/run-acceptance.ps1 -Only l2,ratelimit # 只跑指定步骤
#>
param(
    [switch]$SkipBuild,
    [switch]$SkipStack,
    [string]$Profile = 'local',
    [string[]]$Only = @(),
    # 默认让 up.ps1 连知识库入库一起跑：ticket 04 的「重跑不增长」要有 logs\ingest.out 才判得成，
    # 干净机器少了这一步，ES/Qdrant 里根本没有语料。
    [switch]$SkipIngest
)
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$outDir = Join-Path $root 'logs\acceptance'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$env:JAVA_HOME = if ($env:SHOPPILOT_JDK) { $env:SHOPPILOT_JDK } else { 'E:\java\jdk21' }
$env:MAVEN_OPTS = '-Duser.language=en -Duser.country=US'
# python 步骤（verify_l2_filters.py）经 cmd 重定向落盘时默认按控制台代码页 cp936 写，
# 下面的尾部预览用 UTF-8 读就会花屏；显式要 UTF-8 后日志与预览都对。
$env:PYTHONIOENCODING = 'utf-8'
$pwsh = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $pwsh) {
    $pwsh = 'C:\Users\Shing\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe'
}

# 每步只声明一次：Kind 决定用 -File 还是直接可执行文件。
$steps = [ordered]@{
    # 门禁自己也是代码，也会写错。2026-09-10 我把一个非法管道（`try{}catch{} | Where-Object`）提交了进去，
    # 那是整条链的第一环，语法不过它什么都跑不了——而那要等到下一次跑门禁才看得见。
    # 花零点几秒先解析一遍 scripts\ 下所有 .ps1，别用十几分钟的失败来当语法检查。
    syntax    = @{ Kind = 'ps1'; Cmd = 'scripts\check-ps-syntax.ps1'; Arg = @(); Need = $false; Skip = $false; Expect = @('SYNTAX CHECK DONE') }
    stop      = @{ Kind = 'ps1'; Cmd = 'scripts\stop.ps1'; Arg = @(); Need = $true; Skip = $SkipBuild }
    build     = @{ Kind = 'mvnw'; Cmd = 'verify'; Arg = @(); Need = $true; Skip = $SkipBuild; Expect = @('BUILD SUCCESS') }
    unit      = @{ Kind = 'mvn'; Cmd = 'test'; Arg = @('-o'); Need = $true; Skip = $SkipBuild; Expect = @('BUILD SUCCESS') }
    # 报告是生成的，那就把"生成物与产物一致"也纳入门禁：手写文档一定会和 CSV 漂移，
    # 而这份报告的全部价值就在于不漂。--strict 下缺任何一份证据就红。不需要活体服务。
    report    = @{ Kind = 'py'; Cmd = 'scripts\build_loadtest_report.py'; Arg = @('--strict'); Need = $false; Skip = $false; Expect = @('写出 docs/loadtest-report.md') }
    stack     = @{ Kind = 'ps1'; Cmd = 'scripts\up.ps1'; Arg = @(@('-Profile', $Profile) + $(if ($SkipIngest) { @('-SkipIngest') } else { @() })); Need = $true; Skip = $SkipStack; Expect = @('栈已就绪') }
    plan      = @{ Kind = 'ps1'; Cmd = 'scripts\verify-plan-actions.ps1'; Arg = @('-WithRestarts'); Need = $true; Skip = $false }
    hitzero   = @{ Kind = 'ps1'; Cmd = 'scripts\verify-hit-zero-llm.ps1'; Arg = @(); Need = $true; Skip = $false; Expect = @('全部通过：命中路径零模型调用') }
    action    = @{ Kind = 'ps1'; Cmd = 'scripts\verify-action-loop.ps1'; Arg = @(); Need = $true; Skip = $false; Expect = @('业务办理闭环验收通过') }
    idem      = @{ Kind = 'ps1'; Cmd = 'scripts\verify-idempotency.ps1'; Arg = @(); Need = $true; Skip = $false; Expect = @('state check rejected it') }
    fallback  = @{ Kind = 'ps1'; Cmd = 'scripts\verify-fallback.ps1'; Arg = @(); Need = $true; Skip = $false; Expect = @('queue size:') }
    ratelimit = @{ Kind = 'ps1'; Cmd = 'scripts\verify-ratelimit.ps1'; Arg = @(); Need = $true; Skip = $false; Expect = @('rate_limited') }
    polarity  = @{ Kind = 'ps1'; Cmd = 'scripts\verify-polarity.ps1'; Arg = @(); Need = $true; Skip = $false; Expect = @('验收通过') }
    l2        = @{ Kind = 'py'; Cmd = 'scripts\verify_l2_filters.py'; Arg = @(); Need = $true; Skip = $false; Expect = @('全部通过：L2') }
    console   = @{ Kind = 'node'; Cmd = 'scripts\verify-console.mjs'; Arg = @(); Need = $true; Skip = $false; Expect = @('console checks passed') }
    demo      = @{ Kind = 'ps1'; Cmd = 'scripts\demo.ps1'; Arg = @(); Need = $true; Skip = $false; Expect = @('演示结束') }
    # PLAN 第 16 行的验收动作写的是"冒烟后跑完整集"，完整集要 dev 模式与真实 key，
    # 冒烟这一半却是机器现在就能覆盖的：24 条按意图轮流取（--limit 已改成分层抽样，
    # 直接取前 24 条只会落在 POLICY_RETURN/POLICY_SHIPPING 上），十个意图都有份。
    # 这一格量的是评测链路通不通（schema 下发、工具选择、参数抽取、CSV 落盘），
    # 阈值判定只在 dev 模式生效，所以 local 下它绿不代表准确率达标——那是另一行承诺项。
    # 放在最后一步不是排版偏好：它自己打 24 轮模型 + 检索，122 s 里把本机 Ollama 压到向量化超时，
    # 紧跟着的 console 那条"未命中路径要渲染成多帧打字机"就量到了被拖短的答案（09-09 18:16 那轮
    # 只出 3 帧，判红；同一断言在空一点的机器上是 6 帧）。让重负载排在浏览器形状断言之前，
    # 是给门禁自己制造假红。
    eval      = @{ Kind = 'py'; Cmd = 'scripts\run_tool_eval.py'; Arg = @('--limit', '24', '--tag', 'smoke'); Need = $true; Skip = $false; Expect = @('EVAL DONE') }
}

$results = @()
$started = Get-Date
# 工作树干不干净必须在**开跑之前**问：门禁自己会写 logs/、eval/results/、docs/console.png，
# 跑完之后永远是"脏"的，那个字就没有意义了。run11 记的是 commit f3ff880，可它真正测的是当时
# 还没提交的工作树（compose 健康检查与这两个脚本的改动都在里面）——"全绿"要说清绿在哪一份代码上。
# 注意：try{}catch{} 是语句，不能直接接管道（"An empty pipe element is not allowed" 会在解析期就炸）。
$dirtyAtStart = @()
try { $dirtyAtStart = @(& git -C $root status --porcelain 2>$null | Where-Object { $_ }) } catch { $dirtyAtStart = @() }
$treeAtStart = if ($dirtyAtStart.Count -eq 0) { 'clean' } else { "dirty（$($dirtyAtStart.Count) 个未提交改动）" }

function Test-Ready {
    foreach ($port in 8082, 8091) {
        $up = try {
            (Invoke-RestMethod "http://127.0.0.1:$port/actuator/health/readiness" -TimeoutSec 3).status -eq 'UP'
        } catch { $false }
        if (-not $up) { return $false }
    }
    return $true
}

function Wait-Ready([int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Ready) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

# 这台 16 G 机器会把空闲中的网关 JVM 无日志带走（README 已知限制）。验收跑到一半服务没了，
# 后面每一步都报红，看起来像代码坏了，其实是环境问题：先探健康，不健康就拉回来，
# 拉不回来把这一步单独记成"栈没救回来"，不让它伪装成断言失败。
function Invoke-HealthGate {
    if (Wait-Ready 10) { return $true }
    Write-Host '  栈不健康，先用 up.ps1 -SkipIngest 拉起来' -ForegroundColor Yellow
    & $pwsh -NoProfile -File (Join-Path $root 'scripts\up.ps1') -SkipIngest -Profile $Profile `
        *> (Join-Path $outDir 'recover.log')
    return (Wait-Ready 300)
}

# pwsh -File 传进来的数组参数其实是一整串 "plan,hitzero,l2"，不自己拆就一个都匹配不上，
# 于是矩阵里零步骤、最后一行还打印"全部步骤通过"。两条一起堵：自己拆，且零步骤算失败。
$want = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($name in $want) {
    if (-not $steps.Contains($name)) {
        Write-Host "-Only 里有不认识的名字：$name（可用：$($steps.Keys -join ', ')）" -ForegroundColor Red
        exit 2
    }
}
foreach ($name in $steps.Keys) {
    $step = $steps[$name]
    if ($want.Count -gt 0 -and $name -notin $want) { continue }
    if ($step.Skip) {
        Write-Host "`n--- $name 跳过（开关关着）" -ForegroundColor DarkGray
        $results += [pscustomobject]@{ Step = $name; Exit = '-'; Note = 'skipped' }
        continue
    }
    # build/unit 两步的 Cmd 是 maven 的 goal（verify / test），不是文件路径，不能拿去 Test-Path，
    # 否则会被当成缺文件跳过——跳过和没通过对外是一回事，所以缺文件检查只针对脚本类步骤。
    if ($step.Kind -in @('ps1', 'py', 'node') -and -not (Test-Path (Join-Path $root $step.Cmd))) {
        Write-Host "`n--- $name 找不到 $($step.Cmd)" -ForegroundColor Red
        $results += [pscustomobject]@{ Step = $name; Exit = 99; Note = 'missing file' }
        continue
    }
    if ($step.Need -and $name -notin @('stop', 'build', 'unit', 'stack') -and -not (Invoke-HealthGate)) {
        Write-Host '  栈没救回来，这一步不算断言失败，单独标记' -ForegroundColor Red
        $results += [pscustomobject]@{ Step = $name; Exit = 98; Note = 'stack not recoverable' }
        continue
    }
    $log = Join-Path $outDir "$name.log"
    Write-Host "`n=== $name : $($step.Cmd) $($step.Arg -join ' ') -> $log" -ForegroundColor Cyan
    $sw = [Diagnostics.Stopwatch]::StartNew()
    switch ($step.Kind) {
        'ps1' {
            & $pwsh -NoProfile -File (Join-Path $root $step.Cmd) @($step.Arg) *> $log
        }
        'mvnw' {
            & (Join-Path $root 'mvnw.cmd') -B -ntp $step.Cmd @($step.Arg) *> $log
        }
        'mvn' {
            & mvn.cmd @($step.Arg) -B -ntp $step.Cmd *> $log
        }
        'py' {
            $py = Join-Path $root '.venv-loadtest\Scripts\python.exe'
            if (-not (Test-Path $py)) { $py = (Get-Command python).Source }
            & $py (Join-Path $root $step.Cmd) @($step.Arg) *> $log
        }
        'node' {
            $np = Join-Path $root '.tools\node_modules'
            if (Test-Path $np) { $env:NODE_PATH = $np }
            & node (Join-Path $root $step.Cmd) @($step.Arg) *> $log
        }
    }
    $sw.Stop()
    $code = $LASTEXITCODE
    # 光看退出码不够：这台机器会把 mvnw.cmd 中途带走，而它被带走时返回 0，
    # 日志停在 "T E S T S" 却没有 BUILD SUCCESS——记成 PASS 就是假绿。
    # 所以每一步再要求各有一条"只有跑到结尾才会出现"的日志标记，缺失即失败。
    $missing = @()
    if ($code -eq 0 -and $step.Expect) {
        $logText = [string](Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue)
        $missing = @($step.Expect | Where-Object { $logText.IndexOf($_, [StringComparison]::Ordinal) -lt 0 })
        if ($missing.Count -gt 0) { $code = 97 }
    }
    $note = if ($null -eq $code) { 'no exit code' } elseif ($code -eq 0) { 'ok' } else { "exit $code" }
    if ($missing.Count -gt 0) { $note += ' / 日志缺结尾标记: ' + ($missing -join ' | ') }
    $color = if ($code -eq 0) { 'Green' } else { 'Red' }
    Write-Host ("  {0}  {1}  {2:N0}s" -f $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }), $note, $sw.Elapsed.TotalSeconds) -ForegroundColor $color
    Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 6 | ForEach-Object { Write-Host "    | $_" }
    $results += [pscustomobject]@{ Step = $name; Exit = $code; Note = "$note / $([int]$sw.Elapsed.TotalSeconds)s" }
}

Write-Host "`n=== 验收矩阵（总耗时 $([int]$((Get-Date) - $started).TotalSeconds)s）===" -ForegroundColor Cyan
if ($results.Count -eq 0) {
    Write-Host "没有任何步骤被执行——检查 -Only / -SkipBuild / -SkipStack 的组合（可用步骤：$($steps.Keys -join ', ')）" -ForegroundColor Red
    exit 2
}
$results | Format-Table -AutoSize | Out-String -Width 120 | ForEach-Object { Write-Host $_ }
$failed = @($results | Where-Object { $_.Exit -ne 0 -and $_.Exit -ne '-' })
# 矩阵不能只活在某个终端里。run2..run8 那几份日志是人手工 Tee 出来的，run9 就漏了，
# 于是"16 步全绿"这句话在机器上找不到落点。现在由脚本按 $results 自己生成记录——
# 注意是拿数据生成，不是回抓 Write-Host 的输出流，那正是干净检出检查上一轮栽的地方。
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runLog = Join-Path $root "logs\acceptance-run-$stamp.log"
$commit = try { @(& git -C $root rev-parse --short HEAD 2>$null | Select-Object -First 1)[0].Trim() } catch { 'unknown' }
if (-not $commit) { $commit = 'unknown' }
# 光记 commit 不够：run11 那次记的是 f3ff880，可它真正测的是当时**还没提交**的工作树（compose 健康检查、
# 这两个脚本的改动都在里面）。"全绿"必须说清绿在哪一份代码上，所以顺手记工作树干不干净。
$total = [int]((Get-Date) - $started).TotalSeconds
@(
    "run-acceptance  profile=$Profile  commit=$commit  开跑时工作树=$treeAtStart"
    ("开始 {0:yyyy-MM-dd HH:mm:ss}  结束 {1:yyyy-MM-dd HH:mm:ss}  总耗时 ${total}s" -f $started, (Get-Date))
    "逐步日志目录 $outDir"
    $(if ($dirtyAtStart.Count -gt 0) { '开跑时未提交的改动：' + ($dirtyAtStart -join ' | ') } else { $null })
    ''
    ('{0,-10} {1,5}  {2}' -f 'step', 'exit', 'note')
    ($results | ForEach-Object { '{0,-10} {1,5}  {2}' -f $_.Step, $_.Exit, $_.Note })
    ''
    $(if ($failed.Count -eq 0) { "全部步骤通过（$($results.Count) 步）" } else { "失败步骤：$(($failed | ForEach-Object { $_.Step }) -join ', ')" })
) | Out-File -FilePath $runLog -Encoding utf8
if ($failed.Count -gt 0) {
    Write-Host ("失败步骤: " + (($failed | ForEach-Object { $_.Step }) -join ', ')) -ForegroundColor Red
    Write-Host "矩阵记录：$runLog"
    exit 1
}
Write-Host "全部步骤通过，日志在 $outDir ；矩阵记录 $runLog" -ForegroundColor Green
exit 0
