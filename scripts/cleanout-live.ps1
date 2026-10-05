<#
    清场日活体档：停掉其它项目的容器 → 起本机栈 → 跑活体门禁 → **保证恢复**。

    为什么需要它（round26 清场日实测）：
    验收矩阵分两档，而 full 档要起四服务栈 + 真实模型（≈6.6 GB）。本机 16 GB，
    另外三套项目（nexus / opspilot / moa-gateway）常驻，**空闲常在 0.3–0.8 GB**，
    所以 full 档一年跑得起几次——而每跑一次之前都积压着几笔「等清场日」的活体欠账。
    2026-10-04 实测：**perf 档（MockLLM，不吃模型内存）只需要约 2.6 GB**，
    停掉其它项目之后这台机器跑得动。所以把这一档固化下来，它才是能**定期**跑的那一档。

    保证恢复是这个脚本存在的唯一理由：手工停别人的容器再手工恢复，
    中间任何一步失败或被打断，别人的项目就留在停机状态。

    例:
      pwsh -NoProfile -File scripts/cleanout-live.ps1                     # 三个浏览器门禁
      pwsh -NoProfile -File scripts/cleanout-live.ps1 -Only buyer,outbound
      pwsh -NoProfile -File scripts/cleanout-live.ps1 -Gates none        # 只起栈+恢复，不跑门禁

    **它不会碰任何 shoppilot-* 容器**（中间件要用），也不会删任何东西：只 docker stop / docker start。
#>
param(
    # 要跑哪些活体门禁。none = 只做「停 → 起栈 → 恢复」，用于验证脚本本身。
    [string[]]$Gates = @('buyer', 'outbound', 'workspace'),
    # 要传给 run-acceptance 的档；本脚本默认只跑门禁，不跑矩阵。
    [string]$Profile = 'perf',
    [switch]$SkipStackUp,
    [int]$RestoreWaitSec = 120
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
# `.env` 的读取器在 lib-launch.ps1 里；不 dot-source 就用不了（第一版就踩了这个：
# 报错说「找不到 Get-ShoppilotDotEnv」，而它其实就在隔壁文件里）。
. (Join-Path $PSScriptRoot 'lib-launch.ps1')
$pwsh = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $pwsh) { $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source }
if (-not $pwsh) { throw '需要 PowerShell 7（pwsh）在 PATH 里' }
$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { throw '需要 node（三个浏览器门禁都用 Playwright）' }

$env:NODE_PATH = Join-Path $root '.tools/node_modules'
# 演示账号口令从 .env 取（票 80：仓库里没有默认口令）。工作台与买家端门禁都需要它。
$env:SHOPPILOT_IDENTITY_DEMO_PASSWORD = (Get-ShoppilotDotEnv $root)['SHOPPILOT_IDENTITY_DEMO_PASSWORD']

$outDir = Join-Path $root 'logs\cleanout'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runLog = Join-Path $outDir "cleanout-live-$stamp.log"

function Write-Line([string]$text) {
    $line = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $text
    Write-Host $line
    Add-Content -Path $runLog -Value $line -Encoding utf8
}

# --- 第一件必须做的事：记下「谁在跑」，恢复时只恢复这些 ------------------------------------------------
# 记录时机在 stop 之前；恢复时用**这份清单**而不是「把 docker 全起一遍」——
# 后者会把本来就没在跑的东西也拉起来，那比忘恢复更糟。
$foreign = @(docker ps --format '{{.Names}}' | Where-Object { $_ -and -not $_.StartsWith('shoppilot-') })
Write-Line "清场开始：其它项目在跑 $($foreign.Count) 个容器 —— $($foreign -join ' ')"
Write-Line "清单已落盘：$runLog"

$results = @()
$stackUp = $false
try {
    if ($foreign.Count -gt 0) {
        Write-Line '停掉它们（docker stop，可逆；不删任何容器/卷）'
        docker stop $foreign | Out-Null
        Start-Sleep -Seconds 10
    }

    # 中间件的宿主端口转发在某些情况下会坏死（清场日实测：容器自报 healthy、日志 GREEN、
    # 端口有监听，但数据不回来）。所以起栈之前先**发真实命令**验一遍，不是 Test-NetConnection。
    #
    # **必须重试**：刚停掉 12 个容器的那几秒机器在颠簸，第一次超时是常态（第一次跑就撞上了：
    # qdrant 明明是好的，8 秒超时被打满就报了不通）。重试三次仍不通，才判为坏死。
    Write-Line '验中间件端口（发真实命令 + 重试；不是 Test-NetConnection）'
    foreach ($probe in @(
            @{ Name = 'redis'; Url = $null },
            @{ Name = 'qdrant'; Url = 'http://127.0.0.1:16333/collections' },
            @{ Name = 'es'; Url = 'http://127.0.0.1:19200/_cluster/health' })) {
        $ok = $false
        for ($try = 1; $try -le 3 -and -not $ok; $try++) {
            if ($probe.Name -eq 'redis') {
                try {
                    $client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 16379)
                    $stream = $client.GetStream()
                    $bytes = [Text.Encoding]::ASCII.GetBytes("PING`r`n")
                    $stream.Write($bytes, 0, $bytes.Length)
                    $buf = New-Object byte[] 7
                    $read = $stream.Read($buf, 0, 7)
                    $ok = ($read -eq 7) -and ([Text.Encoding]::ASCII.GetString($buf) -like '*PONG*')
                    $client.Close()
                } catch { $ok = $false }
            } else {
                try { $ok = (Invoke-WebRequest -Uri $probe.Url -TimeoutSec 8 -UseBasicParsing).StatusCode -eq 200 } catch { $ok = $false }
            }
            if (-not $ok) { Start-Sleep -Seconds 4 }
        }
        if (-not $ok) {
            throw "中间件 $($probe.Name) 的宿主端口不通（重试 3 次仍不通）。清场日实测过这个坑：wsl --shutdown 之后端口转发会坏死，" +
            "而容器自己仍然报 healthy。修法是 docker restart <容器>，脚本不替你做这件事。"
        }
    }
    Write-Line '中间件三个端口真验通过'

    if (-not $SkipStackUp) {
        Write-Line "起本机栈（profile=$Profile，MockLLM 不吃模型内存）"
        & $pwsh -NoProfile -File (Join-Path $root 'scripts\up.ps1') -Profile $Profile -SkipIngest 2>&1 |
            ForEach-Object { Add-Content -Path $runLog -Value $_ -Encoding utf8 }
        $stackUp = $true
    }

    $gateScripts = [ordered]@{
        buyer = @{ Path = 'scripts\verify-buyer.mjs'; Expect = '买家中心断言'; Env = 'SHOPPIOT_GATEWAY_BASE' }
        outbound = @{ Path = 'scripts\verify-outbound.mjs'; Expect = '结果回流断言'; Env = 'SHOPPIOT_GATEWAY_BASE' }
        workspace = @{ Path = 'scripts\verify-workspace.mjs'; Expect = '坐席工作台断言'; Env = 'SHOPPIOT_WORKSPACE_BASE' }
    }

    if ($Gates -contains 'none') {
        Write-Line 'Gates=none：只做「停 → 起栈 → 恢复」，不跑门禁（用来验脚本本身）'
    } else {
        foreach ($gate in $Gates) {
            if (-not $gateScripts.Contains($gate)) {
                throw "不认识的门禁 '$gate'（可用：$($gateScripts.Keys -join ', ')、none）"
            }
            $spec = $gateScripts[$gate]
            Write-Line "=== 门禁 $gate（perf 档；这是本档的口径，读数必须带着它）"
            $log = Join-Path $outDir "$gate-$stamp.log"
            & $node (Join-Path $root $spec.Path) 2>&1 | Tee-Object -FilePath $log | Out-Null
            $code = $LASTEXITCODE
            $ok = ($code -eq 0) -and (Select-String -Path $log -Pattern $spec.Expect -Quiet)
            $results += [pscustomobject]@{ Gate = $gate; Exit = $code; Ok = $ok; Log = $log }
            Write-Line ("  {0}  exit={1}  {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $code, $log)
            Get-Content $log -Tail 3 -Encoding utf8 | ForEach-Object { Write-Line "    | $_" }
        }
    }
} catch {
    Write-Line "中断：$($_.Exception.Message)"
    $results += [pscustomobject]@{ Gate = '(harness)'; Exit = 1; Ok = $false; Log = $runLog }
} finally {
    # --- 恢复：无论成败都做，且只恢复开头记下的那些 ---
    if ($foreign.Count -gt 0) {
        Write-Line "恢复另外 $($foreign.Count) 个容器"
        docker start $foreign 2>&1 | ForEach-Object { Add-Content -Path $runLog -Value $_ -Encoding utf8 }
        $deadline = (Get-Date).AddSeconds($RestoreWaitSec)
        do {
            $running = @(docker ps --format '{{.Names}}')
            $missing = @($foreign | Where-Object { $_ -notin $running })
            if ($missing.Count -eq 0) { break }
            Start-Sleep -Seconds 3
        } while ((Get-Date) -lt $deadline)
        $stillDown = @($foreign | Where-Object { $_ -notin @(docker ps --format '{{.Names}}') })
        if ($stillDown.Count -gt 0) {
            Write-Line "!! 没恢复回来的：$($stillDown -join ' ') —— 请手工 docker start 它们"
        } else {
            Write-Line '全部恢复'
        }
    } else {
        Write-Line '没有需要恢复的容器'
    }
}

Write-Line '=== 清场活体档小结 ==='
$results | Format-Table -AutoSize | Out-String -Width 140 | ForEach-Object { Write-Line $_ }
Write-Line "档位口径：perf（MockLLM）。**这一档不覆盖真实模型路径** —— local 档仍需清场日 + 更大内存。"
Write-Line "日志：$runLog"
exit ($(if ($results.Count -gt 0 -and @($results | Where-Object { -not $_.Ok }).Count -eq 0) { 0 } else { 1 }))
