# 四条否决项的 dev 复核：把网关真的切进 dev，跑完那几步，再放回 local。
#
# 为什么要单独一个脚本：run-acceptance.ps1 的 -Profile 只喂给 stack 那一步（up.ps1），
# 加了 -SkipStack 之后它什么都不影响。照旧提示跑
#   run-acceptance.ps1 -Profile dev -SkipBuild -SkipStack -Only action,idem,...
# 会得到一排绿勾，而复核的其实是 local 网关——那正是 README 那条口径说明想消除的东西，
# 却被一个看起来像 dev 的命令掩盖掉。检查"是不是真在 dev"必须靠回读 /ops/circuit，
# 而不是靠命令行里写了 dev 这个字。
#
# 默认不发任何计费请求（与 run-dev-eval.ps1 同一条口径）：加 -Run 才动网关、才花钱。
param(
    # 临时日预算。ADR 0012 的熔断在这里就是这次的消费上限，别把它设成"跑不完"的量。
    [int]$Budget = 400000,
    # 真实预算 = max(-Budget, 当天已用 + Headroom)。日终重跑时"当天已用"会比 -Budget 还大，
    # 不推导就会全步被熔断拒掉（这个坑我 2026-09-10 踩过一次，见下面的 VOID 判定）。
    [int]$Headroom = 200000,
    [switch]$Run,
    [string[]]$Only = @()
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot 'lib-launch.ps1')
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$env:PYTHONIOENCODING = 'utf-8'

$envFile = Join-Path $root '.env'
$fromFile = Get-ShoppilotDotEnv $root
function Get-Config([string]$key) {
    if (Test-Path "Env:\$key") { return (Get-Item "Env:\$key").Value }
    return $fromFile[$key]
}

# 七步对应四条否决项在 local 下的取证脚本，一个都不换：复核要的是"同一批断言在 dev 下也成立"。
$steps = [ordered]@{
    hitzero   = @{ Kind = 'ps1'; Cmd = 'scripts\verify-hit-zero-llm.ps1'; Arg = @(); Expect = @('全部通过：命中路径零模型调用') }
    action    = @{ Kind = 'ps1'; Cmd = 'scripts\verify-action-loop.ps1';    Arg = @(); Expect = @('业务办理闭环验收通过') }
    idem      = @{ Kind = 'ps1'; Cmd = 'scripts\verify-idempotency.ps1';    Arg = @(); Expect = @('state check rejected it') }
    fallback  = @{ Kind = 'ps1'; Cmd = 'scripts\verify-fallback.ps1';       Arg = @(); Expect = @('queue size:') }
    polarity  = @{ Kind = 'ps1'; Cmd = 'scripts\verify-polarity.ps1';       Arg = @(); Expect = @('验收通过') }
    l2        = @{ Kind = 'py';  Cmd = 'scripts\verify_l2_filters.py';      Arg = @(); Expect = @('全部通过：L2') }
    demo      = @{ Kind = 'ps1'; Cmd = 'scripts\demo.ps1';                  Arg = @(@('-Which', 'isolation')); Expect = @('演示结束') }
}
# `-File` 传数组参数时 `-Only fallback,demo` 会变成**一个**元素 "fallback,demo"，不是两个名字
# （run-acceptance.ps1 第 121 行早就为这件事做过切分，这里口径保持一致）。
$want = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($want.Count -gt 0) {
    $kept = [ordered]@{}
    foreach ($name in $steps.Keys) { if ($want -contains $name) { $kept[$name] = $steps[$name] } }
    foreach ($name in $want) {
        if ($steps.Keys -notcontains $name) { Write-Host "-Only 里有不认识的名字：$name（可用：$($steps.Keys -join ', ')）" -ForegroundColor Red; exit 2 }
    }
    if ($kept.Count -eq 0) { Write-Host "-Only 里没有一个认识的步骤：$($want -join ',')" -ForegroundColor Red; exit 2 }
    $steps = $kept
}

$key = Get-Config 'SHOPPILOT_LLM_API_KEY'
$baseUrl = Get-Config 'SHOPPILOT_LLM_BASE_URL'
$model = Get-Config 'SHOPPILOT_LLM_MODEL'
$missing = @()
if (-not $key -or $key -eq 'sk-replace-me') { $missing += 'SHOPPILOT_LLM_API_KEY' }
if (-not $baseUrl) { $missing += 'SHOPPILOT_LLM_BASE_URL' }
if (-not $model) { $missing += 'SHOPPILOT_LLM_MODEL' }
if ($missing.Count -gt 0) {
    Write-Host "dev 复核还不能跑，缺这几样：$($missing -join '、')" -ForegroundColor Yellow
    Write-Host "补齐位置：$envFile（.gitignore 已覆盖；值不要贴进聊天或提交信息）"
    exit 1
}
# 真 key 一律不打印，连长度之外的任何片段都不打。
Write-Host "配置就位：端点 $baseUrl 模型 $model（key 不打印）" -ForegroundColor Cyan

$originalBudget = Get-Config 'SHOPPILOT_LLM_DAILY_TOKEN_BUDGET'
Write-Host "待复核步骤：$($steps.Keys -join ', ')"
Write-Host "日预算：现在是 $(if ($originalBudget) { $originalBudget } else { '默认 200000' })，这次临时抬到 max($Budget, 当天已用 + $Headroom)，跑完原样写回" -ForegroundColor Yellow
if (-not $Run) {
    Write-Host '没加 -Run，到此为止：不改 .env、不动网关、不发计费请求。' -ForegroundColor Green
    Write-Host '确认后：pwsh -NoProfile -File scripts/run-dev-guardcheck.ps1 -Run'
    exit 0
}

function Set-BudgetLine([int]$value) {
    # 只动这一行，其余字节原样保留；换行风格跟着文件走（本机 .env 是 CRLF）。
    $raw = [IO.File]::ReadAllText($envFile)
    $eol = if ($raw.Contains("`r`n")) { "`r`n" } else { "`n" }
    $kept = @($raw -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*#?\s*SHOPPILOT_LLM_DAILY_TOKEN_BUDGET\s*=' })
    $kept += "SHOPPILOT_LLM_DAILY_TOKEN_BUDGET=$value"
    [IO.File]::WriteAllText($envFile, ($kept -join $eol) + $eol)
}

# 参数名刻意避开 $profile：那是 PowerShell 的自动变量（用户配置文件路径），
# 传错的时候不会报错，只会拿着一个 .ps1 路径去当 Spring profile 用。
function Restart-Gateway([string]$which) {
    & (Join-Path $root 'scripts\stop.ps1') -Ports '8082' | Out-Null
    Start-Sleep -Seconds 2
    & (Join-Path $root 'scripts\start-gateway.ps1') -Profile $which | Out-Null
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        $status = try { (Invoke-RestMethod 'http://127.0.0.1:8082/actuator/health/readiness' -TimeoutSec 5).status } catch { $null }
        if ($status -eq 'UP') { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Get-Circuit {
    $resp = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8082/auth/mock-token' -ContentType 'application/json' `
        -Body '{"tenantId":"T001","customerId":"C001"}'
    $tok = if ($resp.token) { $resp.token } else { $resp.accessToken }
    return Invoke-RestMethod -Uri 'http://127.0.0.1:8082/api/v1/support/ops/circuit' `
        -Headers @{ 'X-Ops-Token' = 'dev-ops-token'; 'Authorization' = "Bearer $tok" }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $root "logs\dev-guardcheck-$stamp.log"
New-Item -ItemType Directory -Path (Split-Path $logFile) -Force | Out-Null
$results = @()

# 临时预算从**当天真实已用**推导，不能拍一个固定数：2026-09-10 第一次跑时我写 400000，
# 而当天账上已经 1,030,471，于是七步全部拿到"今日智能助手用量已达上限"的降级答案——
# 跑的是熔断演练，不是防线复核，而且它还会假绿：demo isolation 只看"两家答案不同 + 401"，
# 两家都拿到同一句预算降级文案时它照样 PASS。所以这里既推导预算，也事后判定本次是否作废。
$usedNow = 0
try { $usedNow = [long] (Get-Circuit).tokensUsedToday }
catch { Write-Host '  读不到活体网关的 token 记账（栈没起？），只能按 -Budget 直用' -ForegroundColor Yellow }
$tempBudget = [Math]::Max($Budget, $usedNow + $Headroom)
Write-Host "  当天已用 $usedNow，临时预算取 $tempBudget（本次可用余量 $([Math]::Max(0, $tempBudget - $usedNow))）"

Set-BudgetLine $tempBudget
Write-Host "  已写入临时预算 $tempBudget"
if (-not (Restart-Gateway 'dev')) {
    Set-BudgetLine $originalBudget
    Write-Host '网关切到 dev 后 180 s 没就绪；预算已写回，看 logs\gateway-dev.out' -ForegroundColor Red
    exit 1
}
$before = Get-Circuit
Write-Host ("dev 就绪：mode={0} 模型={1} 生效预算={2} 今日已用={3}" -f $before.llmMode, $before.llmModel, $before.dailyTokenBudget, $before.tokensUsedToday) -ForegroundColor Cyan
if ($before.llmMode -ne 'dev') {
    Set-BudgetLine $originalBudget
    Write-Host "运维端点报的 llmMode 不是 dev（$($before.llmMode)），拒绝在假 dev 下复核" -ForegroundColor Red
    exit 1
}

try {
    foreach ($name in $steps.Keys) {
        $step = $steps[$name]
        Write-Host "`n=== $name : $($step.Cmd) $($step.Arg -join ' ') -> $logFile" -ForegroundColor Cyan
        $sw = [Diagnostics.Stopwatch]::StartNew()
        if ($step.Kind -eq 'ps1') {
            & pwsh -NoProfile -File (Join-Path $root $step.Cmd) @($step.Arg) *>&1 | Tee-Object -FilePath $logFile -Append | Out-Null
        } else {
            & python (Join-Path $root $step.Cmd) @($step.Arg) *>&1 | Tee-Object -FilePath $logFile -Append | Out-Null
        }
        $code = $LASTEXITCODE
        $sw.Stop()
        $tail = @(Get-Content $logFile -Tail 400 -ErrorAction SilentlyContinue) -join "`n"
        $verdict = if ($code -ne 0) { 'FAIL' } elseif (@($step.Expect | Where-Object { -not $tail.Contains($_) }).Count -gt 0) { 'NO-MARKER' } else { 'PASS' }
        $results += [pscustomobject]@{ Step = $name; Exit = $code; Verdict = $verdict; Sec = [int]$sw.Elapsed.TotalSeconds }
        Write-Host "  $verdict  exit=$code  $($([int]$sw.Elapsed.TotalSeconds))s" -ForegroundColor $(if ($verdict -eq 'PASS') { 'Green' } else { 'Red' })
    }
} finally {
    $after = try { Get-Circuit } catch { $null }
    if (Restart-Gateway 'local') { Write-Host '网关已放回 local 模式' -ForegroundColor Green }
    else { Write-Host '!! 网关没能放回 local，手动跑 scripts\start-gateway.ps1 -Profile local' -ForegroundColor Red }
    Set-BudgetLine $originalBudget
    Write-Host "  日预算写回 $(if ($originalBudget) { $originalBudget } else { '默认' })"
    if ($after) {
        $delta = [long]$after.tokensUsedToday - [long]$before.tokensUsedToday
        Write-Host ("token 记账：{0} -> {1}（本次 +{2}，上限 {3}）" -f $before.tokensUsedToday, $after.tokensUsedToday, $delta, $tempBudget) -ForegroundColor Cyan
    }
}

Write-Host "`n=== dev 复核矩阵 ==="
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host "日志：$logFile"

# 前提不成立就判作废，不给红也不给绿：口径与 verify-polarity 的 exit 3 一致。
# 判据看**账**，不看日志关键词：verify-fallback 会故意注入 LLM_BUDGET_EXCEEDED 来验第七种降级形态，
# 那是在测防线；上一版按关键词判，把一次七步全绿的复核判成了作废（2026-09-10 10:43 那次，
# 账上实花 45,585 tokens）。真饿掉只有一个特征：整轮一个模型 token 都没花——模型根本没被调用，
# 那测的就不是"云端模型在场时防线是否还成立"，而 ADR 0012 的熔断本身早就被评测那一轮证过一遍了。
$starved = ($null -eq $delta) -or ($delta -le 0)
if ($starved) {
    Write-Host '本次复核作废（VOID）：整轮 token 记账增量为 0，说明模型一次都没被真正调用，测到的是熔断而不是防线。' -ForegroundColor Red
    Write-Host "当天已用 $usedNow / 临时预算 $tempBudget；处置：加大 -Headroom（当前 $Headroom）后重跑，或等日切后计数归零。"
    exit 3
}
if ((Test-Path $logFile) -and (([IO.File]::ReadAllText($logFile)) -match 'LLM_BUDGET_EXCEEDED|用量已达上限')) {
    Write-Host "  说明：日志里出现 LLM_BUDGET_EXCEEDED / 用量已达上限，那是 verify-fallback 注入的降级用例；本轮实花 $delta tokens，不是预算饿死。"
}
if (@($results | Where-Object { $_.Verdict -ne 'PASS' }).Count -gt 0) { exit 1 }
Write-Host '四条否决项在 dev 模式下复核通过' -ForegroundColor Green
exit 0
