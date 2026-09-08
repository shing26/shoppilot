# 供 start-gateway.ps1 / start-bizmock.ps1 dot-source。
#
# 为什么不用 Start-Process：Start-Process 出来的子进程挂在调用方的作业对象里，
# 会话被回收时整棵子进程树一起被终止——表现为网关起来一分钟后凭空消失、日志里没有异常、
# 也没有 OutOfMemoryError。用 WMI 的 Win32_Process.Create 启动，父进程是 WmiPrvSE，
# 服务因此真正脱离启动它的那个终端，"一条命令起栈然后关掉窗口"才成立。
function Start-ShoppilotService {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$StandardOutput,
        # 追加到 launcher 进程环境里的变量。多 profile 走 SPRING_PROFILES_ACTIVE 而不是命令行：
        # cmd.exe 会把 --spring.profiles.active=perf,nocache 里的逗号当参数分隔符，第二个 profile
        # 静默丢失，"关缓存基线"那组实验跑的其实还是开着缓存的网关。
        [hashtable]$Environment = @{}
    )
    $logDir = Split-Path -Parent $StandardOutput
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $runner = Join-Path $logDir ("run-$Name.cmd")
    $quoted = ($ArgumentList | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('@echo off')
    $lines.Add("cd /d `"$WorkingDirectory`"")
    foreach ($key in ($Environment.Keys | Sort-Object)) {
        $lines += "set `"$key=$($Environment[$key])`""
    }
    $lines += "`"$FilePath`" $quoted > `"$StandardOutput`" 2>&1"
    Set-Content -Path $runner -Value $lines -Encoding ascii
    $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = "cmd.exe /c `"$runner`""
        CurrentDirectory = $WorkingDirectory
    }
    if ($result.ReturnValue -ne 0) {
        throw "以 WMI 启动 $Name 失败，ReturnValue=$($result.ReturnValue)"
    }
    return [int]$result.ProcessId
}

# 读仓库根的 .env（KEY=VALUE，# 开头为注释），返回要注入子进程的环境变量。
#
# 为什么要自己读：Spring Boot 不认 .env，而 dev 模式的 API key、内部服务凭证这类东西
# 又不能写进 application.yml 提交上去。README 让用户 Copy-Item .env.example .env，
# 那就必须真有一条路径把它读进来，否则那行说明是假的。
# 已经在当前进程环境里的值优先——CI 与实验脚本用环境变量覆盖，不该被本地文件挡住。
function Get-ShoppilotDotEnv {
    param([Parameter(Mandatory = $true)][string]$Root)
    $file = Join-Path $Root '.env'
    $values = @{}
    if (-not (Test-Path $file)) { return $values }
    foreach ($line in [IO.File]::ReadAllLines($file)) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $pos = $text.IndexOf('=')
        if ($pos -lt 1) { continue }
        $key = $text.Substring(0, $pos).Trim()
        $value = $text.Substring($pos + 1).Trim().Trim('"', "'")
        if ($key -and -not (Test-Path "Env:\$key")) { $values[$key] = $value }
    }
    return $values
}
