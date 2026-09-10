# 把"跑 dev 评测"从五步手工活收成一条命令，但最后那下回车留给用户按。
#
# 为什么要有它：验收矩阵里唯一还空着的一格是"工具调用准确率 >= 95%（dev 模式）"。它要三件事同时成立——
# .env 里有真 key、日预算抬到够跑一轮（180 条约 19 万 token，ADR 0012 的默认 20 万会被一轮吃掉）、
# 网关重启进 dev 模式。少任何一件，评测要么直接拒跑，要么跑到一半被熔断留下半份数据。
# 这个脚本负责检查、摆位置、跑完把网关放回 local（挂着真 key 的 dev 网关是个花钱的陷阱），
# 但默认不发起任何计费请求：加 -Run 才真跑。
param(
    [int]$Limit = 0,
    [string]$OnlyIntent = '',
    [switch]$Run
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot 'lib-launch.ps1')
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$env:PYTHONIOENCODING = 'utf-8'

$envFile = Join-Path $root '.env'
$fromFile = Get-ShoppilotDotEnv $root
function Get-Config([string]$key) {
    # 进程环境优先（与 lib-launch 同一条口径），否则读 .env。
    if (Test-Path "Env:\$key") { return (Get-Item "Env:\$key").Value }
    return $fromFile[$key]
}

$key = Get-Config 'SHOPPILOT_LLM_API_KEY'
$baseUrl = Get-Config 'SHOPPILOT_LLM_BASE_URL'
$model = Get-Config 'SHOPPILOT_LLM_MODEL'
$missing = @()
if (-not $key -or $key -eq 'sk-replace-me') { $missing += 'SHOPPILOT_LLM_API_KEY（占位值 sk-replace-me 等于没填）' }
if (-not $baseUrl) { $missing += 'SHOPPILOT_LLM_BASE_URL（ADR 0012：https://dashscope.aliyuncs.com/compatible-mode/v1）' }
if (-not $model) { $missing += 'SHOPPILOT_LLM_MODEL（qwen-plus）' }
if ($missing.Count -gt 0) {
    Write-Host 'dev 评测还不能跑，缺这几样: ' -ForegroundColor Yellow
    $missing | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    Write-Host "补齐位置：$envFile（.gitignore 已覆盖，不会进库；值也不要贴进聊天或提交信息）"
    Write-Host '键名照 .env.example 抄即可，三行都在。'
    exit 1
}
# 真 key 一律不打印，连片段都不打——终端记录会进日志文件。
Write-Host "配置就位：端点 $baseUrl 模型 $model key 长度 $($key.Length)（值不打印）" -ForegroundColor Cyan

$need = 260000
$budgetLine = "SHOPPILOT_LLM_DAILY_TOKEN_BUDGET=$need"
$current = Get-Config 'SHOPPILOT_LLM_DAILY_TOKEN_BUDGET'
$currentInt = 0
$hasBudget = [int]::TryParse("$current", [ref]$currentInt) -and $currentInt -ge $need
if (-not $hasBudget) {
    Write-Host "日预算现在是 $(if ($current) { $current } else { '默认 200000' })，一轮 180 条约 19 万 token：把它抬到 $need" -ForegroundColor Yellow
    if ($Run) {
        # 只动这一行，其余字节原样保留；换行风格跟着文件走（本机 .env 是 CRLF）。
        $raw = [IO.File]::ReadAllText($envFile)
        $eol = if ($raw.Contains("`r`n")) { "`r`n" } else { "`n" }
        $kept = @($raw -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*#?\s*SHOPPILOT_LLM_DAILY_TOKEN_BUDGET\s*=' })
        $kept += $budgetLine
        [IO.File]::WriteAllText($envFile, ($kept -join $eol) + $eol)
        Write-Host "  已写入 $budgetLine（其余行原样保留）"
    } else {
        Write-Host "  加一行 $budgetLine 到 .env，或让本脚本代加：加上 -Run"
    }
}

function Restart-Gateway([string]$profile) {
    & (Join-Path $root 'scripts\stop.ps1') -Ports '8082' | Out-Null
    Start-Sleep -Seconds 2
    & (Join-Path $root 'scripts\start-gateway.ps1') -Profile $profile | Out-Null
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        $status = try { (Invoke-RestMethod 'http://127.0.0.1:8082/actuator/health/readiness' -TimeoutSec 5).status } catch { $null }
        if ($status -eq 'UP') { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Show-Circuit {
    $resp = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8082/auth/mock-token' -ContentType 'application/json' `
        -Body '{"tenantId":"T001","customerId":"C001"}'
    $tok = if ($resp.token) { $resp.token } else { $resp.accessToken }
    return Invoke-RestMethod -Uri 'http://127.0.0.1:8082/api/v1/support/ops/circuit' `
        -Headers @{ 'X-Ops-Token' = 'dev-ops-token'; 'Authorization' = "Bearer $tok" }
}

$evalArgs = @('scripts/run_tool_eval.py')
if ($Limit -gt 0) { $evalArgs += @('--limit', "$Limit") }
# 按意图补跑是 dev 调度的常态（09-10 那轮预算熔断后就是这么凑齐矩阵的），
# 但原先只透传 -Limit，票面写的"只重跑 ACTION_ORDER"根本到不了 python 侧。
if ($OnlyIntent) { $evalArgs += @('--only-intent', $OnlyIntent) }
Write-Host "评测命令：python $($evalArgs -join ' ')" -ForegroundColor Cyan
if (-not $Run) {
    Write-Host '没加 -Run，所以到此为止：不改网关、不发计费请求。' -ForegroundColor Green
    Write-Host '想先看一轮的 token 预估与口径而不发请求：python scripts/run_tool_eval.py --dry-run'
    Write-Host '确认后加 -Run 重跑本脚本（它会自己抬预算、切 dev、跑完放回 local）。'
    exit 0
}

if (-not (Restart-Gateway 'dev')) { throw '网关切到 dev 后 180 s 没就绪，看 logs\gateway-dev.out' }
$circuit = Show-Circuit
Write-Host ("网关已在 dev：mode={0} 模型={1} 端点={2} 预算={3}/{4}" -f $circuit.llmMode, $circuit.llmModel, $circuit.llmBaseUrl, $circuit.tokensUsedToday, $circuit.dailyTokenBudget)
if ($circuit.llmMode -ne 'dev') { throw "运维端点报的 llmMode 不是 dev（$($circuit.llmMode)），先别跑" }
try {
    & python @evalArgs
    $code = $LASTEXITCODE
} finally {
    $code = if ($null -ne $code) { $code } else { 1 }
    # 无论评测成功、预算熔断还是被 Ctrl+C 打断，都把网关放回 local：
    # 一个挂着真 key 的 dev 网关留在机器上，比一次失败的评测贵得多。
    if (Restart-Gateway 'local') { Write-Host '网关已放回 local 模式' -ForegroundColor Green }
    else { Write-Host '!! 网关没能放回 local，手动跑 scripts\start-gateway.ps1 -Profile local' -ForegroundColor Red }
}
Write-Host '产物在 eval\results\tool-eval-<时间>-dev*{.csv,-summary.csv,-meta.json}；把 README 那一格换成实测值再跑一次门禁。'
# 旧版这里推荐的是 `run-acceptance.ps1 -Profile dev -SkipStack`，那句话是错的：-Profile 只喂给 stack 步，
# 加了 -SkipStack 它谁也不影响，照着跑等于拿 local 网关签一张"dev 已复核"的纸。换成真的会切模式的脚本。
Write-Host "顺手把四条否决项也在 dev 下复核（闭掉 README 里那条口径说明）：pwsh -NoProfile -File scripts/run-dev-guardcheck.ps1 -Run"
exit $code
