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
    stop      = @{ Kind = 'ps1'; Cmd = 'scripts\stop.ps1'; Arg = @(); Need = $true; Skip = $SkipBuild }
    build     = @{ Kind = 'mvnw'; Cmd = 'verify'; Arg = @(); Need = $true; Skip = $SkipBuild }
    unit      = @{ Kind = 'mvn'; Cmd = 'test'; Arg = @('-o'); Need = $true; Skip = $SkipBuild }
    stack     = @{ Kind = 'ps1'; Cmd = 'scripts\up.ps1'; Arg = @(@('-Profile', $Profile) + $(if ($SkipIngest) { @('-SkipIngest') } else { @() })); Need = $true; Skip = $SkipStack }
    plan      = @{ Kind = 'ps1'; Cmd = 'scripts\verify-plan-actions.ps1'; Arg = @('-WithRestarts'); Need = $true; Skip = $false }
    hitzero   = @{ Kind = 'ps1'; Cmd = 'scripts\verify-hit-zero-llm.ps1'; Arg = @(); Need = $true; Skip = $false }
    action    = @{ Kind = 'ps1'; Cmd = 'scripts\verify-action-loop.ps1'; Arg = @(); Need = $true; Skip = $false }
    idem      = @{ Kind = 'ps1'; Cmd = 'scripts\verify-idempotency.ps1'; Arg = @(); Need = $true; Skip = $false }
    fallback  = @{ Kind = 'ps1'; Cmd = 'scripts\verify-fallback.ps1'; Arg = @(); Need = $true; Skip = $false }
    ratelimit = @{ Kind = 'ps1'; Cmd = 'scripts\verify-ratelimit.ps1'; Arg = @(); Need = $true; Skip = $false }
    polarity  = @{ Kind = 'ps1'; Cmd = 'scripts\verify-polarity.ps1'; Arg = @(); Need = $true; Skip = $false }
    l2        = @{ Kind = 'py'; Cmd = 'scripts\verify_l2_filters.py'; Arg = @(); Need = $true; Skip = $false }
    console   = @{ Kind = 'node'; Cmd = 'scripts\verify-console.mjs'; Arg = @(); Need = $true; Skip = $false }
    demo      = @{ Kind = 'ps1'; Cmd = 'scripts\demo.ps1'; Arg = @(); Need = $true; Skip = $false }
}

$results = @()
$started = Get-Date
foreach ($name in $steps.Keys) {
    $step = $steps[$name]
    if ($Only.Count -gt 0 -and $name -notin $Only) { continue }
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
    $note = if ($null -eq $code) { 'no exit code' } elseif ($code -eq 0) { 'ok' } else { "exit $code" }
    $color = if ($code -eq 0) { 'Green' } else { 'Red' }
    Write-Host ("  {0}  {1}  {2:N0}s" -f $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }), $note, $sw.Elapsed.TotalSeconds) -ForegroundColor $color
    Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 6 | ForEach-Object { Write-Host "    | $_" }
    $results += [pscustomobject]@{ Step = $name; Exit = $code; Note = "$note / $([int]$sw.Elapsed.TotalSeconds)s" }
}

Write-Host "`n=== 验收矩阵（总耗时 $([int]$((Get-Date) - $started).TotalSeconds)s）===" -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String -Width 120 | ForEach-Object { Write-Host $_ }
$failed = @($results | Where-Object { $_.Exit -ne 0 -and $_.Exit -ne '-' })
if ($failed.Count -gt 0) {
    Write-Host ("失败步骤: " + (($failed | ForEach-Object { $_.Step }) -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host "全部步骤通过，日志在 $outDir" -ForegroundColor Green
exit 0
