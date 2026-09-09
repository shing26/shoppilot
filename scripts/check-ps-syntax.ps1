# 解析 scripts\ 下每一个 PowerShell 脚本，有任何语法错就非零退出。
#
# 为什么要有它：这套验收门禁本身就是 PowerShell 写的，而语法错要到那一步真被执行时才炸——
# 2026-09-10 就发生过一次：`@(try { ... } catch { ... } | Where-Object ...)` 是非法管道，
# 我把它提交进去了，直到下一次跑门禁才看见"An empty pipe element is not allowed"。
# 门禁自己把整条链挡住，代价是十几分钟；这条检查的代价是零点几秒。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$bad = 0
$files = Get-ChildItem (Join-Path $root 'scripts') -Filter '*.ps1' -Recurse | Sort-Object FullName
foreach ($f in $files) {
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$null, [ref]$errors)
    foreach ($err in $errors) {
        $bad++
        Write-Host "  SYNTAX $($f.Name):$($err.Extent.StartLineNumber) — $($err.Message)" -ForegroundColor Red
    }
}
Write-Host "SYNTAX CHECK DONE files=$($files.Count) errors=$bad"
if ($bad -gt 0) { exit 1 }
exit 0
