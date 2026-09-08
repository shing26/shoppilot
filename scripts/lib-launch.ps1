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
        [Parameter(Mandatory = $true)][string]$StandardOutput
    )
    $logDir = Split-Path -Parent $StandardOutput
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $runner = Join-Path $logDir ("run-$Name.cmd")
    $quoted = ($ArgumentList | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $lines = @(
        '@echo off',
        "cd /d `"$WorkingDirectory`"",
        "`"$FilePath`" $quoted > `"$StandardOutput`" 2>&1"
    )
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
