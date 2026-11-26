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
        JAVA_HOME = 'C:\probe-jdk'
        MAVEN_OPTS = '-Dprobe=true'
    }
    Check '脚本显式覆盖压过调用方与 .env' `
        ($overridden['SHOPPILOT_INTERNAL_TOKEN'] -eq 'from-override' -and $overridden['SHOPPILOT_LLM_MODE'] -eq 'perf') `
        "token=$($overridden['SHOPPILOT_INTERNAL_TOKEN']) mode=$($overridden['SHOPPILOT_LLM_MODE'])"
    $fallback = Get-ShoppilotMvnFallbackEnvironment -Root $tempRoot -JdkHome 'C:\probe-jdk' `
        -Overrides @{ SHOPPILOT_BIZMOCK_POOL_SIZE = '10' }
    Check 'mvn 回退强制注入 JDK 两格' `
        ($fallback['JAVA_HOME'] -eq 'C:\probe-jdk' -and $fallback['MAVEN_OPTS'] -eq '-Duser.language=en -Duser.country=US') `
        "JAVA_HOME=$($fallback['JAVA_HOME']) MAVEN_OPTS=$($fallback['MAVEN_OPTS'])"
    Check 'mvn 回退保留业务覆盖与 .env 键' `
        ($fallback['SHOPPILOT_BIZMOCK_POOL_SIZE'] -eq '10' -and $fallback['SHOPPILOT_OUTBOUND_ALLOWLIST'] -eq 'dotenv-allowlist') `
        "pool=$($fallback['SHOPPILOT_BIZMOCK_POOL_SIZE']) allowlist=$($fallback['SHOPPILOT_OUTBOUND_ALLOWLIST'])"

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
        Check "$script 的 mvn 回退走统一 JDK 环境函数" `
            ($text -match 'Get-ShoppilotMvnFallbackEnvironment\s+-Root\s+\$root') ''
        # 票 229 首轮 Verifier P1：回退分支直接写 $overrides[...] 但没初始化。
        # 只看“退回 mvn”之后那一段：统一函数后回退分支不应再手写下标覆盖。
        $fallbackAt = $text.IndexOf('未找到 fat jar，退回 mvn spring-boot:run')
        if ($fallbackAt -ge 0) {
            $fallbackText = $text.Substring($fallbackAt)
            Check "$script 的回退分支不再手写下标覆盖" ($fallbackText -notmatch '\$overrides\[') ''
        }
        Check "$script 按根 POM 版本精确取 jar" `
            ($text -match 'Get-ShoppilotFatJar\s+-Root\s+\$root') ''
        Check "$script 不再按通配符取 jar" `
            ($text -notmatch 'shoppilot-[A-Za-z-]+-\*\.jar') ''
    }

    $upText = Get-Content (Join-Path $PSScriptRoot 'up.ps1') -Raw
    Check 'up.ps1::Find-FatJars 使用精确版本 jar' `
        ($upText -match 'Get-ShoppilotFatJar\s+-Root\s+\$root') ''
    Check 'up.ps1 不再按通配符取 jar' `
        ($upText -notmatch "Get-ChildItem .*-Filter \(\$_ \+ '-\*\.jar'\)") ''

    $ingestText = Get-Content (Join-Path $PSScriptRoot 'ingest.ps1') -Raw
    Check 'ingest.ps1 按根 POM 版本精确取 jar' `
        ($ingestText -match 'Get-ShoppilotFatJar\s+-Root\s+\$root') ''
    Check 'ingest.ps1 不再按通配符取 jar' `
        ($ingestText -notmatch 'shoppilot-gateway-\*\.jar') ''

    # 票 231 / R20 的受控反证：同一目录里同时放旧版本与当前版本，只选当前版本；
    # 当前版本缺失时返回 $null，让启动器走 mvn 回退，而不是抓起旧包。
    $jarRoot = Join-Path $tempRoot 'jar-probe'
    $jarTarget = Join-Path $jarRoot 'probe-mod\target'
    New-Item -ItemType Directory -Force -Path $jarTarget | Out-Null
    @'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.shoppilot</groupId>
  <artifactId>probe</artifactId>
  <version>1.2.3</version>
</project>
'@ | Set-Content -Path (Join-Path $jarRoot 'pom.xml') -Encoding utf8
    Set-Content -Path (Join-Path $jarTarget 'probe-mod-1.2.3.jar') -Value 'current' -Encoding ascii
    Set-Content -Path (Join-Path $jarTarget 'probe-mod-0.9.0.jar') -Value 'old' -Encoding ascii
    Check '根 POM 版本被正确解析' `
        ((Get-ShoppilotProjectVersion -Root $jarRoot) -eq '1.2.3') ''
    $picked = Get-ShoppilotFatJar -Root $jarRoot -Module 'probe-mod'
    Check '旧版本并存时只选当前版本 jar' `
        ($null -ne $picked -and $picked.Name -eq 'probe-mod-1.2.3.jar') `
        "got=$(if ($picked) { $picked.Name } else { '<null>' })"
    Remove-Item -LiteralPath (Join-Path $jarTarget 'probe-mod-1.2.3.jar') -Force
    $missingJar = Get-ShoppilotFatJar -Root $jarRoot -Module 'probe-mod'
    Check '当前版本缺失时返回 $null（不选旧包）' ($null -eq $missingJar) `
        "got=$(if ($missingJar) { $missingJar.Name } else { '<null>' })"

    # 票 231 / R16：凭证唯一来源 = 进程环境（非空）> .env；两边都缺返回 $null。
    $savedOps = [Environment]::GetEnvironmentVariable('SHOPPILOT_OPS_TOKEN')
    try {
        [Environment]::SetEnvironmentVariable('SHOPPILOT_OPS_TOKEN', '', 'Process')
        Check '凭证：进程环境覆盖 .env' `
            ((Get-ShoppilotCredential -Root $tempRoot -Name 'SHOPPILOT_INTERNAL_TOKEN') -eq 'from-process') ''
        Check '凭证：进程环境为空时回落到 .env' `
            ((Get-ShoppilotCredential -Root $tempRoot -Name 'SHOPPILOT_IDENTITY_DEMO_PASSWORD') -eq 'dotenv-password') ''
        Check '凭证：两处都缺时返回 $null' `
            ($null -eq (Get-ShoppilotCredential -Root $tempRoot -Name 'SHOPPILOT_OPS_TOKEN')) ''
    } finally {
        [Environment]::SetEnvironmentVariable('SHOPPILOT_OPS_TOKEN', $savedOps, 'Process')
    }

    # 验收工装不再内置 dev 凭证默认值。
    foreach ($script in @('demo.ps1', 'verify-plan-actions.ps1', 'verify-action-loop.ps1',
            'verify-fallback.ps1', 'verify-polarity.ps1', 'verify-idempotency.ps1',
            'verify-refund-approval.ps1', 'verify-emotion.ps1', 'verify-feedback.ps1',
            'verify-channel.ps1', 'verify-hit-zero-llm.ps1')) {
        $text = Get-Content (Join-Path $PSScriptRoot $script) -Raw
        Check "$script 不再内置 dev ops token" ($text -notmatch "'dev-ops-token'") ''
        Check "$script 不再内置 dev internal token" `
            ($text -notmatch "'dev-internal-token-change-me'") ''
    }
    foreach ($script in @('verify_l2_filters.py', 'run_tool_eval.py')) {
        $text = Get-Content (Join-Path $PSScriptRoot $script) -Raw
        Check "$script 不再内置 dev ops token" ($text -notmatch '"dev-ops-token"') ''
    }
    foreach ($script in @('verify-console.mjs', 'verify-workspace.mjs',
            'verify-outbound.mjs', 'verify-buyer.mjs')) {
        $text = Get-Content (Join-Path $PSScriptRoot $script) -Raw
        Check "$script 不再内置 dev ops token" ($text -notmatch "'dev-ops-token'") ''
        Check "$script 不再内置 dev internal token" `
            ($text -notmatch "'dev-internal-token-change-me'") ''
    }

    # 票 231 / R19：allowlist 只进验收 harness 的启动环境。
    $acceptanceText = Get-Content (Join-Path $PSScriptRoot 'run-acceptance.ps1') -Raw
    Check 'run-acceptance 为验收栈声明回环 allowlist' `
        ($acceptanceText -match 'SHOPPILOT_OUTBOUND_ALLOWLIST[\s\S]{0,200}127\.0\.0\.1') ''

    # 票 231 / R17：调试台 circuit 优先用坐席令牌，/ops/** 契约不变。
    $consolePath = Join-Path $RepoRoot 'shoppilot-gateway\src\main\resources\static\console.html'
    $consoleText = Get-Content -LiteralPath $consolePath -Raw
    Check '调试台 circuit 用坐席令牌读取' `
        ($consoleText -match "/ops/circuit', null, true, state\.staffToken") ''

    # 票 231 / R18：EMO-ESC-07 与 ADR 0042 对齐，条目仍在。
    $emotionCase = Select-String -Path (Join-Path $RepoRoot 'eval\cases-part4-emotion.jsonl') `
        -Pattern '"id": "EMO-ESC-07"' | Select-Object -First 1
    Check 'EMO-ESC-07 期望 USER_REQUESTED' `
        ($null -ne $emotionCase -and $emotionCase.Line -match '"reason": "USER_REQUESTED"') ''
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
