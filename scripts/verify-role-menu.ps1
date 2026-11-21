#requires -Version 7
<#
.SYNOPSIS
    票 221：角色级 403 与菜单能力活体验收。

.DESCRIPTION
    用演示登录拿 AGENT / ADMIN / BUYER 三张真令牌，验证：
      1. capabilities 与角色一致；
      2. AGENT 访问 /channels 与 /tenants 返回 403，ADMIN 放行；
      3. BUYER 仍只能读自己的工单；
      4. 工单、退款、知识、质量对 AGENT 仍可用；
      5. X-Ops-Token 平台路径不变，平台动作仍只认它；
      6. 403 不会让令牌失效（随后 /auth/me 仍 200）。

.NOTES
    前置：网关已用包含票 221 代码的镜像启动，且容器档开启 demo-login。
    脚本不打印任何 token 或口令。
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$Tenant = 'T001'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib-launch.ps1')

$script:pass = 0
$script:fail = 0
$script:skip = 0

function Assert([string]$Name, [bool]$Condition, [string]$Detail = '') {
    if ($Condition) {
        $script:pass++
        Write-Host "PASS  $Name$(if ($Detail) { " — $Detail" })"
    }
    else {
        $script:fail++
        Write-Host "FAIL  $Name$(if ($Detail) { " — $Detail" })"
    }
}

function Skip([string]$Name, [string]$Detail) {
    $script:skip++
    Write-Host "SKIP  $Name — $Detail"
}

function Invoke-Http {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token = '',
        [string]$OpsToken = '',
        $Body = $null
    )

    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    if ($OpsToken) { $headers.'X-Ops-Token' = $OpsToken }

    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        SkipHttpErrorCheck = $true
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Compress -Depth 8)
    }

    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) {
        try { $json = $response.Content | ConvertFrom-Json } catch { }
    }
    return [pscustomobject]@{
        Status = [int]$response.StatusCode
        Json = $json
        Text = [string]$response.Content
    }
}

function Get-DemoSession([string]$Role) {
    $response = Invoke-Http -Method Post -Path '/auth/demo-login' -Body @{ tenantId = $Tenant; role = $Role }
    if ($response.Status -eq 404) {
        throw 'demo-login 不可用：需要 SHOPPILOT_IDENTITY_DEMO_LOGIN_ENABLED=true 与 SHOPPILOT_IDENTITY_DEMO_PASSWORD'
    }
    if ($response.Status -ne 200) {
        throw "demo-login $Role 返回 HTTP $($response.Status)"
    }
    return $response.Json
}

function CapsOf($Session) {
    return @($Session.capabilities)
}

function CapsText($Session) {
    return ((CapsOf $Session) -join ',')
}

try {
    $agent = Get-DemoSession 'agent'
    $agentCaps = CapsOf $agent
    Assert 'AGENT demo-login 200' ($agent.role -eq 'AGENT') "role=$($agent.role)"
    Assert 'AGENT capabilities 五项且顺序固定' (
        $agentCaps.Count -eq 5 -and
        (CapsText $agent) -eq 'tickets,conversations,refunds,knowledge,quality'
    ) (CapsText $agent)
    Assert 'AGENT 无 channels 能力' (-not ($agentCaps -contains 'channels'))

    $channels = Invoke-Http -Method Get -Path '/api/v1/support/ops/channels' -Token $agent.token
    Assert 'AGENT GET /channels → 403' ($channels.Status -eq 403) "HTTP $($channels.Status)"
    Assert 'channels 403 code=role.denied' ($channels.Json.code -eq 'role.denied') "$($channels.Json.code)"
    Assert 'channels 403 文案含管理员' ($channels.Text -match '管理员')

    $tenants = Invoke-Http -Method Get -Path '/api/v1/support/ops/tenants' -Token $agent.token
    Assert 'AGENT GET /tenants → 403' ($tenants.Status -eq 403) "HTTP $($tenants.Status)"
    Assert 'tenants 403 code=role.denied' ($tenants.Json.code -eq 'role.denied') "$($tenants.Json.code)"
    Assert 'tenants 403 文案含管理员' ($tenants.Text -match '管理员')

    $matrixChannels = Invoke-Http -Method Get -Path '/api/v1/support/ops/channels;x' -Token $agent.token
    Assert 'AGENT GET /channels;x → 403（矩阵参数不绕过）' ($matrixChannels.Status -eq 403) "HTTP $($matrixChannels.Status)"

    $agentLlmFault = Invoke-Http -Method Get -Path '/api/v1/support/ops/llm-fault' -Token $agent.token
    Assert 'AGENT 无 ops token GET /llm-fault → 403' ($agentLlmFault.Status -eq 403) "HTTP $($agentLlmFault.Status)"
    Assert 'llm-fault 403 code=role.denied' ($agentLlmFault.Json.code -eq 'role.denied') "$($agentLlmFault.Json.code)"
    Assert 'llm-fault 403 文案含运维' ($agentLlmFault.Text -match '运维')
    $matrixLlmFault = Invoke-Http -Method Get -Path '/api/v1/support/ops/llm-fault;x' -Token $agent.token
    Assert 'AGENT GET /llm-fault;x → 403（矩阵参数不绕过）' ($matrixLlmFault.Status -eq 403) "HTTP $($matrixLlmFault.Status)"

    Assert 'AGENT 工单队列仍 200' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/tickets' -Token $agent.token).Status -eq 200)
    Assert 'AGENT 退款队列仍 200' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/refunds/pending' -Token $agent.token).Status -eq 200)
    Assert 'AGENT 知识复核队列仍 200' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/feedback/review-queue' -Token $agent.token).Status -eq 200)
    Assert 'AGENT 知识修订列表仍 200' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/knowledge/revisions' -Token $agent.token).Status -eq 200)
    Assert 'AGENT 质量报表仍 200' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/quality/report' -Token $agent.token).Status -eq 200)

    $admin = Get-DemoSession 'admin'
    $adminCaps = CapsOf $admin
    Assert 'ADMIN demo-login 200' ($admin.role -eq 'ADMIN') "role=$($admin.role)"
    Assert 'ADMIN capabilities 七项且含 channels/tenants' (
        $adminCaps.Count -eq 7 -and
        (CapsText $admin) -eq 'tickets,conversations,refunds,knowledge,quality,channels,tenants'
    ) (CapsText $admin)
    Assert 'ADMIN GET /channels 200' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/channels' -Token $admin.token).Status -eq 200)
    Assert 'ADMIN GET /tenants 200' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/tenants' -Token $admin.token).Status -eq 200)
    Assert 'ADMIN 无 ops token GET /llm-fault → 403' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/llm-fault' -Token $admin.token).Status -eq 403)
    Assert 'ADMIN 无 ops token GET /fault → 403' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/fault' -Token $admin.token).Status -eq 403)
    Assert 'ADMIN GET /channels;x → 200（矩阵参数不降级管理员）' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/channels;x' -Token $admin.token).Status -eq 200)
    Assert 'ADMIN GET /llm-fault;x → 403（矩阵参数不绕过）' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/llm-fault;x' -Token $admin.token).Status -eq 403)

    $buyer = Get-DemoSession 'buyer'
    Assert 'BUYER demo-login 200' ($buyer.role -eq 'BUYER') "role=$($buyer.role)"
    Assert 'BUYER capabilities 为空' ((CapsOf $buyer).Count -eq 0) (CapsText $buyer)
    Assert 'BUYER GET /channels → 403' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/channels' -Token $buyer.token).Status -eq 403)
    Assert 'BUYER /tickets/mine 仍 200' (
        (Invoke-Http -Method Get -Path '/api/v1/support/ops/tickets/mine' -Token $buyer.token).Status -eq 200)

    $envValues = Get-ShoppilotDotEnv -Root $root
    $opsToken = $envValues['SHOPPILOT_OPS_TOKEN']
    if ([string]::IsNullOrWhiteSpace($opsToken)) {
        $opsToken = [Environment]::GetEnvironmentVariable('SHOPPILOT_OPS_TOKEN')
    }
    if ([string]::IsNullOrWhiteSpace($opsToken)) {
        Skip 'X-Ops-Token 平台路径' 'SHOPPILOT_OPS_TOKEN 未设置'
        Skip '平台动作仍只认 ops token' 'SHOPPILOT_OPS_TOKEN 未设置'
    }
    else {
        Assert 'ops token GET /channels 200' (
            (Invoke-Http -Method Get -Path '/api/v1/support/ops/channels' -Token $buyer.token -OpsToken $opsToken).Status -eq 200)
        Assert 'ops token GET /tenants 200' (
            (Invoke-Http -Method Get -Path '/api/v1/support/ops/tenants' -Token $buyer.token -OpsToken $opsToken).Status -eq 200)
        Assert 'ops token GET /fault 200' (
            (Invoke-Http -Method Get -Path '/api/v1/support/ops/fault' -Token $buyer.token -OpsToken $opsToken).Status -eq 200)
        Assert 'ops token GET /llm-fault 200' (
            (Invoke-Http -Method Get -Path '/api/v1/support/ops/llm-fault' -Token $buyer.token -OpsToken $opsToken).Status -eq 200)
        Assert 'ops token GET /llm-fault;x 200' (
            (Invoke-Http -Method Get -Path '/api/v1/support/ops/llm-fault;x' -Token $buyer.token -OpsToken $opsToken).Status -eq 200)
        Assert 'AGENT 无 ops token GET /fault → 403' (
            (Invoke-Http -Method Get -Path '/api/v1/support/ops/fault' -Token $agent.token).Status -eq 403)
    }

    $me = Invoke-Http -Method Get -Path '/auth/me' -Token $agent.token
    Assert '403 后 AGENT 令牌仍有效（/auth/me 200）' ($me.Status -eq 200) "HTTP $($me.Status)"
    Assert '403 后 /auth/me 仍带 capabilities' ((CapsOf $me.Json).Count -eq 5) (CapsText $me.Json)
}
catch {
    $script:fail++
    Write-Host "FAIL  未完成：$($_.Exception.Message)"
}

Write-Host ""
Write-Host ("PASS {0} / FAIL {1} / SKIP {2}" -f $script:pass, $script:fail, $script:skip)
if ($script:fail -gt 0) { exit 1 }
exit 0
