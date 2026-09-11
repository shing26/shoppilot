# 供 start-gateway.ps1 / start-bizmock.ps1 dot-source。
#
# 现在 dot-source 它的还有 up.ps1 / ingest.ps1 / run-acceptance.ps1。Resolve-ShoppilotJdk 是它们共同的理由，
# up.ps1 还多一个：起栈时用 Start-ShoppilotService 脱离式拉 ollama serve（见 up.ps1 [2/6] 的注释——
# 拿 CLI 当探活会继承重定向句柄，把整条起栈流程冻死）。
# 这个文件只放"两个以上脚本共同遵守的规则"，否则又是同一个常量写五份——本仓库已经在
# readiness 超时上栽过一次"同一个常量两处各写一份，改就只改对一半"。
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
function Get-JavaMajorVersion {
    param([Parameter(Mandatory = $true)][string]$JavaExe)
    if (-not (Test-Path -LiteralPath $JavaExe)) { return 0 }
    $raw = try { (& $JavaExe -version 2>&1 | Select-Object -First 1) } catch { return 0 }
    $m = [regex]::Match("$raw", 'version\s+"(\d+)(?:\.(\d+))?')
    if (-not $m.Success) { return 0 }
    $major = [int]$m.Groups[1].Value
    # 老式版本号 "1.8.0" 的主版本在第二段
    if ($major -eq 1 -and $m.Groups[2].Success) { return [int]$m.Groups[2].Value }
    return $major
}

# 找一个可用的 JDK。项目要 Java 21（虚拟线程）。
#
# 为什么不让它写死某个人的绝对路径：五个启动脚本一度都以 "E:\java\jdk21" 兜底，
# 于是 README 那句"一条命令起栈"只在作者那台恰好把 JDK 装在 E 盘的机器上成立；
# 换一台机器它会把 JAVA_HOME 指向不存在的路径，然后在 mvnw 里以一句看不懂的话失败。
# 顺序：SHOPPILOT_JDK（显式）→ JAVA_HOME → PATH 上的 java，三者都必须过版本校验，
# 全都不合格就直接说人话报错，而不是带着坏路径往下跑二十分钟。
# PATH 那一条一定要校验版本：作者这台机器 PATH 上的 java 是 JDK 18，拿它构建会炸在编译目标上。
function Resolve-ShoppilotJdk {
    param([int]$MinMajor = 21)
    $candidates = @()
    if ($env:SHOPPILOT_JDK) { $candidates += [pscustomobject]@{ Src = 'SHOPPILOT_JDK'; Home = $env:SHOPPILOT_JDK } }
    if ($env:JAVA_HOME) { $candidates += [pscustomobject]@{ Src = 'JAVA_HOME'; Home = $env:JAVA_HOME } }
    $onPath = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
    if ($onPath) {
        $candidates += [pscustomobject]@{ Src = 'PATH'; Home = (Split-Path -Parent (Split-Path -Parent $onPath)) }
    }
    foreach ($c in $candidates) {
        $exe = Join-Path (Join-Path $c.Home 'bin') 'java.exe'
        if ((Get-JavaMajorVersion $exe) -ge $MinMajor) { return $c.Home }
    }
    $tried = if ($candidates.Count) { ($candidates | ForEach-Object { "$($_.Src)=$($_.Home)" }) -join ' | ' } else { '无' }
    throw ("找不到 JDK {0}+（本项目要 Java {0}：虚拟线程）。已试过：{1}。" +
        '请装一个 JDK {0}+ 并把 JAVA_HOME 指向它，或设 SHOPPILOT_JDK 指向它的安装目录。') -f $MinMajor, $tried
}

# PowerShell 7 的定位。同样是"别写死个人路径"：门禁一度兜底到
# C:\Users\<某人>\.cache\... 下的 pwsh.exe，那台机器之外的读者只会拿到一个不存在的路径。
function Resolve-ShoppilotPwsh {
    foreach ($name in @('pwsh.exe', 'pwsh')) {
        $src = (Get-Command $name -ErrorAction SilentlyContinue).Source
        if ($src) { return $src }
    }
    throw '需要 PowerShell 7（pwsh）在 PATH 里，安装见 https://learn.microsoft.com/powershell/scripting/install/installing-powershell-on-windows'
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
