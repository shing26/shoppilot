<#
    round63 票 236 / R10 活体验收：测试审计事件不得污染活体台账。

    形状（契约 AC5）：
      1. 在活体 biz-mock 上记录 /api/audit 快照（eventId 集合 + 返回条数）；
      2. 跑 RefundReviewTest（测试 classpath 应写 shoppilot:audit-test）；
      3. 再次读取活体 /api/audit，断言没有新增 eventId、没有新增测试行、条数不变；
      4. 解析 surefire 报告确认测试真的跑了 10 条；
      5. 用 Redis XLEN 做正向控制：生产流长度不变、测试流长度增加。

    前置：biz-mock :8091 与 Redis :16379 已运行；测试会自己起 H2，不需要网关。

    例: pwsh -NoProfile -File scripts/verify-audit-isolation.ps1
#>
param(
    [string]$BizMockUrl = 'http://127.0.0.1:8091',
    [string]$RedisHost = '127.0.0.1',
    [int]$RedisPort = 16379,
    [string]$Tenant = 'T001',
    [string]$InternalToken = '',
    [int]$Limit = 200
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-launch.ps1')
$root = Split-Path -Parent $PSScriptRoot
if (-not $InternalToken) {
    $InternalToken = Get-ShoppilotCredential -Root $root -Name 'SHOPPILOT_INTERNAL_TOKEN'
}
if (-not $InternalToken) {
    Write-Host '缺少 SHOPPILOT_INTERNAL_TOKEN：/api/audit 需要内部令牌。请在 .env 里给一个再跑。' -ForegroundColor Red
    exit 2
}

$script:failures = @()
function Assert([string]$name, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("{0}  {1}{2}" -f $mark, $name, $(if ($detail) { "  $detail" } else { '' })) -ForegroundColor $color
    if (-not $ok) { $script:failures += $name }
}

function Get-AuditRows {
    $headers = @{ 'X-Internal-Token' = $InternalToken; 'X-Tenant-Id' = $Tenant }
    $raw = (Invoke-WebRequest -Uri "$BizMockUrl/api/audit?limit=$Limit" -Headers $headers `
        -UseBasicParsing -TimeoutSec 30).Content
    return @($raw | ConvertFrom-Json)
}

function Get-RedisLength([string]$key) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect($RedisHost, $RedisPort)
        $stream = $client.GetStream()
        $payload = "*2`r`n`$4`r`nXLEN`r`n`$$($key.Length)`r`n$key`r`n"
        $bytes = [Text.Encoding]::UTF8.GetBytes($payload)
        $stream.Write($bytes, 0, $bytes.Length)
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
        $line = $reader.ReadLine()
        if ($line -notmatch '^:(\d+)$') { throw "XLEN $key 返回非整数: $line" }
        return [int]$Matches[1]
    } finally {
        $client.Dispose()
    }
}

function Test-ShapedLikeTestRow($row) {
    $actorLooksTest = $row.actor -match '^(agent|agent-\d+|alice|test-.*)$'
    $actionLooksTest = $row.action -match '^(REFUND_(APPROVED|REJECTED)|TICKET_(CLAIM|CLAIMED|RESOLVED|RELEASED))$'
    return $actorLooksTest -or $actionLooksTest
}

$headSha = (& git -C $root rev-parse HEAD).Trim()
$statusShort = (& git -C $root status --short) -join "`n"
Write-Host "HEAD $headSha" -ForegroundColor Cyan
if ($statusShort) { Write-Host "git status --short:`n$statusShort" -ForegroundColor DarkGray }

Write-Host "`n=== 0. preconditions ===" -ForegroundColor Cyan
$bizMockUp = $false
try {
    Invoke-RestMethod -Uri "$BizMockUrl/actuator/health/readiness" -TimeoutSec 10 | Out-Null
    $bizMockUp = $true
} catch {
    # 由下面的 Assert 记录，避免前置失败时只留下一条异常栈。
}
Assert 'biz-mock is up' $bizMockUp $BizMockUrl
Assert 'redis is reachable' ([bool](Test-NetConnection $RedisHost -Port $RedisPort -InformationLevel Quiet -WarningAction SilentlyContinue)) "$RedisHost`:$RedisPort"
if ($script:failures.Count -gt 0) {
    Write-Host "`n审计隔离验收失败：" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== 1. snapshot live audit before test run ===" -ForegroundColor Cyan
$before = @(Get-AuditRows)
$beforeIds = @($before | ForEach-Object { $_.eventId })
$beforeTestRows = @($before | Where-Object { Test-ShapedLikeTestRow $_ })
Write-Host "before: rows=$($before.Count) uniqueEventIds=$(@($beforeIds | Sort-Object -Unique).Count)"
Assert 'live audit baseline has no test-shaped rows' ($beforeTestRows.Count -eq 0) `
    $(if ($beforeTestRows.Count -gt 0) {
        "found=$($beforeTestRows.Count)，请重启 biz-mock 用干净 H2 重跑"
    } else { 'none' })

$prodLenBefore = Get-RedisLength 'shoppilot:audit'
$testLenBefore = Get-RedisLength 'shoppilot:audit-test'
Write-Host "redis before: prod=$prodLenBefore test=$testLenBefore"

Write-Host "`n=== 2. run RefundReviewTest against isolated test stream ===" -ForegroundColor Cyan
$mvn = Join-Path $root 'mvnw.cmd'
$testStarted = Get-Date
& $mvn -B -ntp -pl shoppilot-biz-mock -am test "-Dtest=RefundReviewTest" "-Dsurefire.failIfNoSpecifiedTests=false"
$testExit = $LASTEXITCODE
Assert 'RefundReviewTest passes' ($testExit -eq 0) "exit=$testExit"

$reportPath = Join-Path $root 'shoppilot-biz-mock\target\surefire-reports\TEST-com.shoppilot.bizmock.RefundReviewTest.xml'
$reportOk = $false
$reportDetail = 'missing report'
if (Test-Path $reportPath) {
    $reportFile = Get-Item $reportPath
    [xml]$report = Get-Content $reportPath -Raw
    $suite = $report.testsuite
    $tests = [int]$suite.tests
    $reportFailures = [int]$suite.failures
    $reportErrors = [int]$suite.errors
    $reportSkipped = [int]$suite.skipped
    $fresh = $reportFile.LastWriteTime -ge $testStarted.AddSeconds(-2)
    $reportOk = $fresh -and ($tests -eq 10) -and ($reportFailures -eq 0) -and ($reportErrors -eq 0) -and ($reportSkipped -eq 0)
    $reportDetail = "tests=$tests failures=$reportFailures errors=$reportErrors skipped=$reportSkipped fresh=$fresh"
}
Assert 'RefundReviewTest really executed 10 tests' $reportOk $reportDetail

Write-Host "`n=== 3. snapshot live audit after test run ===" -ForegroundColor Cyan
$after = @(Get-AuditRows)
$afterIds = @($after | ForEach-Object { $_.eventId })
$newIds = @($afterIds | Where-Object { $_ -notin $beforeIds })
$newRows = @($after | Where-Object { $_.eventId -in $newIds })
$testRows = @($newRows | Where-Object { Test-ShapedLikeTestRow $_ })
$prodLenAfter = Get-RedisLength 'shoppilot:audit'
$testLenAfter = Get-RedisLength 'shoppilot:audit-test'
Write-Host "after: rows=$($after.Count) newEventIds=$($newIds.Count) testShapedRows=$($testRows.Count)"
Write-Host "redis after: prod=$prodLenAfter test=$testLenAfter"

Assert 'live audit gained no new event ids' ($newIds.Count -eq 0) `
    $(if ($newIds.Count -gt 0) { "new=$($newIds -join ',')" } else { 'none' })
Assert 'live audit row count is unchanged' ($after.Count -eq $before.Count) "before=$($before.Count) after=$($after.Count)"
Assert 'no test actor/action rows leaked into live audit' ($testRows.Count -eq 0) `
    $(if ($testRows.Count -gt 0) { "leaked=$($testRows.Count)" } else { 'none' })
Assert 'production audit stream length is unchanged' ($prodLenAfter -eq $prodLenBefore) `
    "before=$prodLenBefore after=$prodLenAfter"
Assert 'test audit stream received the test events' ($testLenAfter -gt $testLenBefore) `
    "before=$testLenBefore after=$testLenAfter"

if ($script:failures.Count -gt 0) {
    Write-Host ("`n审计隔离验收失败：" + ($script:failures -join '、')) -ForegroundColor Red
    exit 1
}
Write-Host "`n审计隔离验收通过：测试事件未进入活体 /api/audit" -ForegroundColor Green
