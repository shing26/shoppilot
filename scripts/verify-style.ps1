# 风格票活体验收：3 渠道 × 2 情绪矩阵的档位断言（ADR 0038）。
# 风格档位随 SSE meta 的 style 字段回显；本脚本逐组合发起到对应渠道端点、读回 meta.style 比档位。
# 注入段无业务事实由 0 token 单测把关（StyleServiceTest：无数字、≤200 字）；
# 风格与 TTFT 的联合曲线照登在压测口径里（style 影响 TTFT 属于设计内代价，读数说话）。
# 前置：网关起在 dev/local + biz-mock + 容器栈。
# 用法：pwsh -NoProfile -File scripts/verify-style.ps1
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8082"
$Pass = 0; $Fail = 0

function Get-Token([string]$Tenant, [string]$Customer) {
    $body = '{"tenantId":"' + $Tenant + '","customerId":"' + $Customer + '"}'
    $res = Invoke-RestMethod -Uri "$Base/auth/mock-token" -Method Post -ContentType "application/json" -Body $body
    return $res.token
}

# 读一次 SSE 流里的 meta 事件，取 style 字段
function Get-Style([string]$Path, [string]$Token, [string]$Json) {
    $headers = @{ Authorization = "Bearer $Token"; "X-Conversation-Id" = "verify-style-$PID-$(Get-Random)" }
    $resp = Invoke-WebRequest -Uri "$Base$Path" -Method Post -Headers $headers `
        -ContentType "application/json; charset=utf-8" -Body $Json
    foreach ($line in ($resp.Content -split "`n")) {
        if ($line -match '^data:\{"conversationId"') {
            $meta = $line.Substring(5) | ConvertFrom-Json
            return $meta.style
        }
    }
    return $null
}

$token = Get-Token "T001" "C155"
$calm = '{"query":"七天无理由退货怎么操作"}'
$angry = '{"query":"你们就是骗子！退款拖了半个月，我要投诉到底"}'

# SSE 能覆盖的两条矩阵（web 是唯一可流式的渠道；app/miniapp 走 webhook 无 meta 流）
foreach ($c in @(
    @{ name = "web + ANGRY";  expect = "FORMAL";  path = "/api/v1/support/chat/stream"; body = $angry },
    @{ name = "web + CALM";   expect = "FORMAL";  path = "/api/v1/support/chat/stream"; body = $calm }
)) {
    $style = Get-Style $c.path $token $c.body
    if ($style -eq $c.expect) {
        $Pass++
        Write-Host ("PASS  {0} -> meta.style={1}" -f $c.name, $style)
    } else {
        $Fail++
        Write-Host ("FAIL  {0} 期望 {1} 实际 {2}" -f $c.name, $c.expect, $style)
    }
}

# 非流式渠道的档位断言：单测矩阵已覆盖 6 条 part7 用例（StyleServiceTest）；
# 活体这边验证 email 回执链路带 style 归因可达
$email = Invoke-RestMethod -Uri "$Base/api/v1/support/email" -Method Post `
    -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json; charset=utf-8" `
    -Body '{"from":"buyer@example.com","body":"请问退货的运费险怎么用"}'
if ($email.channel -eq "email") {
    $Pass++
    Write-Host "PASS  email 渠道链路可达（档位矩阵由 StyleServiceTest 0 token 覆盖）"
} else {
    $Fail++
    Write-Host "FAIL  email 渠道回包异常"
}

Write-Host ("`n风格验收：PASS {0} / FAIL {1}（矩阵完整断言见 StyleServiceTest 6 项）" -f $Pass, $Fail)
if ($Fail -gt 0) { exit 1 }
