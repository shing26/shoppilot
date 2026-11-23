# 启动器环境继承确定性验收（票 229 / ADR 0031）。
#
# 只验证"最终环境怎么算、怎么写进 run-*.cmd"，不启动任何服务、不连网络。
# 活体（导出探针令牌后重启网关、断言登录 200）在票面 AC7 单独跑。
#
# 背景：Start-ShoppilotService 走 WMI，子进程不继承调用方环境；
# Get-ShoppilotDotEnv 又会跳过调用方已存在的键。两者叠加会让已导出的
# SHOPPILOT_* 静默丢失（票 228 实测登录 401）。本脚本钉住修复后的合并口径。

param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-launch.ps1')

$results = @()
function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    $script:results += [pscustomobject]@{ Name = $Name; Ok = $Ok; Detail = $Detail }
    $suffix = if ($Detail) { "  — $Detail" } else { '' }
    Write-Host ("{0}  {1}{2}" -f $(if ($Ok) { 'PASS' } else { 'FAIL' }), $Name, $suffix)
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("shoppilot-launcher-env-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
try {
    $dotenvPath = Join-Path $tempRoot '.env'
    @(
        'SHOPPILOT_INTERNAL_TOKEN=from-dotenv'
        'SHOPPILOT_IDENTITY_DEMO_PASSWORD=dotenv-password'
        'SHOPPILOT_OUTBOUND_ALLOWLIST=dotenv-allowlist'
        'SPRING_PROFILES_ACTIVE=from-dotenv'
    ) | Set-Content -Path $dotenvPath -Encoding utf8

    # 调用方环境：一个覆盖 .env 的键、一个 .env 没有的键、三个非本仓敏感键。
    [Environment]::SetEnvironmentVariable('SHOPPILOT_INTERNAL_TOKEN', 'from-process', 'Process')
    [Environment]::SetEnvironmentVariable('SHOPPILOT_EMBEDDING_SIDECAR_URL', 'http://127.0.0.1:8110', 'Process')
    [Environment]::SetEnvironmentVariable('SHOPPILOT_IDENTITY_DEMO_PASSWORD', '', 'Process')
    [Environment]::SetEnvironmentVariable('AWS_SECRET_ACCESS_KEY', 'aws-should-not-leak', 'Process')
    [Environment]::SetEnvironmentVariable('GITHUB_TOKEN', 'github-should-not-leak', 'Process')
    [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', 'openai-should-not-leak', 'Process')

    $merged = Get-ShoppilotLaunchEnvironment -Root $tempRoot

    Check '调用方已导出的同名键覆盖 .env' ($merged['SHOPPILOT_INTERNAL_TOKEN'] -eq 'from-process') `
        "got=$($merged['SHOPPILOT_INTERNAL_TOKEN'])"
    Check '调用方空串不算已导出，回落到 .env' ($merged['SHOPPILOT_IDENTITY_DEMO_PASSWORD'] -eq 'dotenv-password') `
        "got=$($merged['SHOPPILOT_IDENTITY_DEMO_PASSWORD'])"
    Check '调用方独有的 SHOPPILOT_* 键被带上（sidecar 接线）' `
        ($merged['SHOPPILOT_EMBEDDING_SIDECAR_URL'] -eq 'http://127.0.0.1:8110') `
        "got=$($merged['SHOPPILOT_EMBEDDING_SIDECAR_URL'])"
    Check '.env 的其余键仍在' ($merged['SHOPPILOT_OUTBOUND_ALLOWLIST'] -eq 'dotenv-allowlist') `
        "got=$($merged['SHOPPILOT_OUTBOUND_ALLOWLIST'])"
    Check '非本仓命名空间变量不进合并结果' `
        (-not $merged.ContainsKey('AWS_SECRET_ACCESS_KEY') -and -not $merged.ContainsKey('GITHUB_TOKEN') `
            -and -not $merged.ContainsKey('OPENAI_API_KEY')) `
        (($merged.Keys | Sort-Object) -join ',')

    $overridden = Get-ShoppilotLaunchEnvironment -Root $tempRoot -Overrides @{
        SHOPPILOT_INTERNAL_TOKEN = 'from-override'
        SHOPPILOT_LLM_MODE = 'perf'
    }
    Check '脚本显式覆盖压过调用方与 .env' `
        ($overridden['SHOPPILOT_INTERNAL_TOKEN'] -eq 'from-override' -and $overridden['SHOPPILOT_LLM_MODE'] -eq 'perf') `
        "token=$($overridden['SHOPPILOT_INTERNAL_TOKEN']) mode=$($overridden['SHOPPILOT_LLM_MODE'])"

    # run-*.cmd 的内容：合并结果逐条写 set，敏感键不外泄。
    $runnerLines = New-ShoppilotRunnerScript -WorkingDirectory $tempRoot -FilePath 'java.exe' `
        -ArgumentList @('-jar', 'app.jar') -StandardOutput (Join-Path $tempRoot 'out.log') `
        -Environment $merged
    $runnerText = $runnerLines -join "`n"
    Check 'run-*.cmd 写入调用方覆盖后的令牌' ($runnerText -match 'set "SHOPPILOT_INTERNAL_TOKEN=from-process"') ''
    Check 'run-*.cmd 写入 sidecar 键' ($runnerText -match 'set "SHOPPILOT_EMBEDDING_SIDECAR_URL=http://127.0.0.1:8110"') ''
    Check 'run-*.cmd 不写非本仓敏感变量' `
        ($runnerText -notmatch 'AWS_SECRET_ACCESS_KEY|GITHUB_TOKEN|OPENAI_API_KEY') ''
    Check 'run-*.cmd 仍保留工作目录与重定向' `
        ($runnerText.Contains("cd /d `"$tempRoot`"") -and $runnerText.Contains('> ')) ''

    # 三个启动脚本必须走同一口径（静态断言，防止有人改回旧的逐键复制）。
    foreach ($script in @('start-gateway.ps1', 'start-bizmock.ps1', 'start-ticket.ps1')) {
        $text = Get-Content (Join-Path $PSScriptRoot $script) -Raw
        Check "$script 使用合并后的启动环境" `
            ($text -match 'Get-ShoppilotLaunchEnvironment\s+-Root\s+\$root') ''
        Check "$script 不再自行逐键复制 .env" `
            ($text -notmatch 'Get-ShoppilotDotEnv\s+\$root') ''
        Check "$script 的 mvn 回退也注入 JDK 环境" `
            ($text -match "\`$overrides\['JAVA_HOME'\]\s*=\s*\`$env:JAVA_HOME") ''
    }
} finally {
    foreach ($key in @('SHOPPILOT_INTERNAL_TOKEN', 'SHOPPILOT_EMBEDDING_SIDECAR_URL',
            'SHOPPILOT_IDENTITY_DEMO_PASSWORD', 'AWS_SECRET_ACCESS_KEY', 'GITHUB_TOKEN', 'OPENAI_API_KEY')) {
        [Environment]::SetEnvironmentVariable($key, $null, 'Process')
    }
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$failed = @($results | Where-Object { -not $_.Ok })
Write-Host ''
Write-Host ("启动器环境断言 {0}/{1} 通过" -f ($results.Count - $failed.Count), $results.Count)
if ($failed.Count) {
    foreach ($item in $failed) { Write-Host ("FAILED  {0}  — {1}" -f $item.Name, $item.Detail) }
    exit 1
}
