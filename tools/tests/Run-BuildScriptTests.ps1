#requires -Version 7.0

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'build.ps1'
$pwsh = (Get-Command pwsh.exe -CommandType Application | Select-Object -First 1).Source
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("life-archive-build-tests-" + [guid]::NewGuid().ToString('N'))
$testRoot = [IO.Path]::GetFullPath($testRoot)
New-Item -ItemType Directory -Path $testRoot | Out-Null
$assertions = 0

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "断言失败：$Message" }
    $script:assertions++
}

function New-Fixture([string]$Mode = 'success', [bool]$Bom = $false) {
    $fixture = Join-Path $testRoot ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path (Join-Path $fixture 'app') -Force | Out-Null
    Copy-Item -LiteralPath $scriptPath -Destination (Join-Path $fixture 'build.ps1')
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $scriptPath) 'install.ps1') -Destination (Join-Path $fixture 'install.ps1')
    $content = "// 用户未提交的中文注释`r`nandroid {`r`n    defaultConfig {`r`n        versionCode = 8 // 内部版本`r`n        versionName = `"0.2.6`" // 显示版本`r`n    }`r`n}`r`n// 保留末尾`r`n"
    [IO.File]::WriteAllText((Join-Path $fixture 'app/build.gradle.kts'), $content, [Text.UTF8Encoding]::new($Bom))
    [IO.File]::WriteAllText((Join-Path $fixture 'mode.txt'), $Mode)
    [IO.File]::WriteAllText((Join-Path $fixture 'gradlew.bat'), "@echo off`r`npwsh.exe -NoProfile -File `"%~dp0fake-gradle.ps1`" %*`r`nexit /b %ERRORLEVEL%`r`n")
    [IO.File]::WriteAllText((Join-Path $fixture 'fake-gradle.ps1'), @'
$ErrorActionPreference = 'Stop'
if ((Get-Location).Path -ne $PSScriptRoot) { exit 30 }
$mode = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'mode.txt'))
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'gradle-called.txt'), ($args -join ' '))
if ($mode -eq 'gradle-failure') { exit 23 }
$variant = if ($args[0] -eq ':app:assembleRelease') { 'release' } else { 'debug' }
$dir = Join-Path $PSScriptRoot "app/build/outputs/apk/$variant"
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$config = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'app/build.gradle.kts'))
$name = [regex]::Match($config, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$code = [int][regex]::Match($config, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$file = if ($variant -eq 'release') { 'app-release-unsigned.apk' } else { 'app-debug.apk' }
$inputFile = Join-Path $PSScriptRoot 'input.txt'
$payload = if (Test-Path -LiteralPath $inputFile) { [IO.File]::ReadAllText($inputFile) } else { 'synthetic apk fixture' }
if ($mode -ne 'missing-apk') { [IO.File]::WriteAllText((Join-Path $dir $file), $payload) }
if ($mode -eq 'missing-metadata') { exit 0 }
if ($mode -eq 'stale-version') { $name = '0.0.1'; $code = 1 }
$id = if ($mode -eq 'wrong-package') { 'com.elio.jianyu' } else { 'com.elio.lifearchive' }
@{ applicationId = $id; variantName = $variant; elements = @(@{ versionName = $name; versionCode = $code; outputFile = $file }) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $dir 'output-metadata.json') -Encoding utf8
exit 0
'@)
    [IO.File]::WriteAllText((Join-Path $fixture 'fake-adb.cmd'), (@('@echo off', 'pwsh.exe -NoProfile -File "%~dp0fake-adb.ps1" %*', 'exit /b %ERRORLEVEL%') -join [Environment]::NewLine))
    [IO.File]::WriteAllText((Join-Path $fixture 'adb-mode.txt'), 'online')
    [IO.File]::WriteAllText((Join-Path $fixture 'fake-adb.ps1'), @'
$ErrorActionPreference = 'Stop'
$mode = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'adb-mode.txt'))
($args | ConvertTo-Json -Compress) | Add-Content -LiteralPath (Join-Path $PSScriptRoot 'adb-calls.log') -Encoding utf8
if ($args[0] -eq 'devices') {
    if ($mode -eq 'adb-failure') { exit 20 }
    Write-Output 'List of devices attached'
    if ($mode -eq 'no-device') { exit 0 }
    if ($mode -eq 'unauthorized') { Write-Output ('FAKE-1' + [char]9 + 'unauthorized'); exit 0 }
    Write-Output ('FAKE-1' + [char]9 + 'device')
    if ($mode -eq 'multiple') { Write-Output ('FAKE-2' + [char]9 + 'device') }
    exit 0
}
if ($args[2] -eq 'install') {
    if ($mode -eq 'install-failure') { exit 21 }
    if ($args[3] -ne '-r') { exit 31 }
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'installed-serial.txt'), $args[1])
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'installed-path.txt'), $args[4])
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'installed-payload.txt'), [IO.File]::ReadAllText($args[4]))
    Write-Output 'Success'
    exit 0
}
if ($args[2] -eq 'shell' -and $args[-1] -eq 'com.elio.lifearchive') {
    if ($mode -eq 'package-failure') { exit 22 }
    $config = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'app/build.gradle.kts'))
    $name = [regex]::Match($config, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $code = [int][regex]::Match($config, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    if ($mode -eq 'wrong-installed') { $code = 1; $name = '0.0.1' }
    Write-Output "    versionCode=$code minSdk=26 targetSdk=36"
    Write-Output "    versionName=$name"
    exit 0
}
exit 32
'@)
    return $fixture
}

function Invoke-Fixture([string]$Fixture, [string[]]$Extra = @(), [string]$InputText = '', [string]$ScriptName = 'build.ps1') {
    $info = [Diagnostics.ProcessStartInfo]::new($pwsh)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WorkingDirectory = [IO.Path]::GetTempPath()
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in @('-NoProfile', '-File', (Join-Path $Fixture $ScriptName), '-NoPause', '-OutputDir', (Join-Path $Fixture 'export with spaces')) + $Extra) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(30000)) { $process.Kill($true); throw '构建脚本测试超时' }
        $result = [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult() }
        [IO.File]::WriteAllText((Join-Path $Fixture 'result.log'), $result.Output)
        return $result
    } finally { $process.Dispose() }
}

try {
    $fixture = New-Fixture
    $original = [IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts'))
    $result = Invoke-Fixture $fixture @('-Help')
    Assert-True ($result.ExitCode -eq 0 -and $result.Output.Contains('VersionName')) '帮助命令'
    $result = Invoke-Fixture $fixture @('-VersionName', '0.2.7', '-Install', '-WhatIf')
    Assert-True ($result.ExitCode -eq 0 -and $result.Output.Contains('0.2.7 (9)')) '预演显示自动递增后的版本'
    Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts')) -ceq $original) '预演不改版本'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $fixture 'gradle-called.txt'))) '帮助／预演不执行 Gradle'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $fixture 'adb-calls.log'))) '预演不查询或操作设备'

    $result = Invoke-Fixture $fixture
    Assert-True ($result.ExitCode -eq 0) "默认构建成功：$($result.Output)"
    Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts')) -ceq $original) '默认不修改版本'
    Assert-True (Test-Path -LiteralPath (Join-Path $fixture 'export with spaces/Life-Archive-0.2.6-debug.apk')) '从其他工作目录导出到带空格路径'

    $fixture = New-Fixture -Bom $true
    $versionFile = Join-Path $fixture 'app/build.gradle.kts'
    $original = [IO.File]::ReadAllText($versionFile)
    $result = Invoke-Fixture $fixture @('-VersionName', '0.2.7')
    Assert-True ($result.ExitCode -eq 0) "更新版本构建成功：$($result.Output)"
    Assert-True ([IO.File]::ReadAllText($versionFile) -ceq $original.Replace('versionCode = 8', 'versionCode = 9').Replace('"0.2.6"', '"0.2.7"')) '只改版本，保留 CRLF、注释和其他改动'
    $bytes = [IO.File]::ReadAllBytes($versionFile)
    Assert-True ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) '保留 UTF-8 BOM'

    $fixture = New-Fixture
    $result = Invoke-Fixture $fixture @('-VersionName', '1.0.0-beta.1', '-VersionCode', '25')
    Assert-True ($result.ExitCode -eq 0 -and [IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts')).Contains('versionCode = 25')) '显式内部版本优先'
    $result = Invoke-Fixture $fixture @('-VersionCode', '26')
    Assert-True ($result.ExitCode -eq 0 -and (Test-Path -LiteralPath (Join-Path $fixture 'export with spaces/Life-Archive-1.0.0-beta.1-debug.apk'))) '仅改内部版本时保留显示版本'

    $fixture = New-Fixture
    $result = Invoke-Fixture $fixture @('-EditVersion') "0.2.8`n`n"
    Assert-True ($result.ExitCode -eq 0 -and [IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts')).Contains('versionName = "0.2.8"')) '交互式修改显示版本'
    Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts')).Contains('versionCode = 9')) '交互式回车采用递增内部版本'

    $fixture = New-Fixture
    $result = Invoke-Fixture $fixture @('-Release')
    Assert-True ($result.ExitCode -eq 0 -and (Test-Path -LiteralPath (Join-Path $fixture 'export with spaces/Life-Archive-0.2.6-release-unsigned.apk'))) 'Release 未签名产物正确导出并命名'

    foreach ($mode in @('gradle-failure', 'missing-apk', 'missing-metadata', 'stale-version', 'wrong-package')) {
        $fixture = New-Fixture $mode
        $result = Invoke-Fixture $fixture @('-VersionName', '0.2.7')
        $expected = if ($mode -eq 'gradle-failure') { 23 } else { 1 }
        Assert-True ($result.ExitCode -eq $expected) "拒绝失败／错误产物：$mode；$($result.Output)"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $fixture 'export with spaces'))) "失败不导出：$mode"
        Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts')).Contains('versionName = "0.2.7"')) '构建失败不回滚请求的版本修改'
    }
    foreach ($extra in @(@('-VersionName', '../bad'), @('-VersionCode', '0'), @('-VersionName', '0.2.7', '-JavaHome', (Join-Path $testRoot 'missing-jdk')), @('-EditVersion', '-VersionName', '0.2.7'))) {
        $fixture = New-Fixture
        $before = [IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts'))
        $result = Invoke-Fixture $fixture $extra
        Assert-True ($result.ExitCode -ne 0) '非法参数或无效 JDK 被拒绝'
        Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts')) -ceq $before) '预检失败不改源码'
    }
    $fixture = New-Fixture
    $installArgs = @('-Install', '-AdbPath', (Join-Path $fixture 'fake-adb.cmd'))
    [IO.File]::WriteAllText((Join-Path $fixture 'input.txt'), 'first source')
    $result = Invoke-Fixture $fixture $installArgs
    Assert-True ($result.ExitCode -eq 0) "唯一在线设备可覆盖安装并核对版本：$($result.Output)"
    Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'installed-serial.txt')) -ceq 'FAKE-1') '使用唯一在线设备'
    Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'installed-payload.txt')) -ceq 'first source') '安装刚校验的构建产物'
    Remove-Item -LiteralPath (Join-Path $fixture 'gradle-called.txt')
    [IO.File]::WriteAllText((Join-Path $fixture 'input.txt'), 'changed source with same version')
    $result = Invoke-Fixture $fixture $installArgs
    Assert-True ($result.ExitCode -eq 0 -and (Test-Path -LiteralPath (Join-Path $fixture 'gradle-called.txt'))) '已有 APK 且版本未变时仍调用 Gradle 校验'
    Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'installed-payload.txt')) -ceq 'changed source with same version') '源码变化后不安装旧 APK'
    $calls = [IO.File]::ReadAllText((Join-Path $fixture 'adb-calls.log'))
    Assert-True ($calls.Contains('com.elio.lifearchive') -and -not $calls.Contains('uninstall') -and -not $calls.Contains('clear')) '核对本项目包名，不卸载或清库'
    [IO.File]::WriteAllText((Join-Path $fixture 'adb-mode.txt'), 'multiple')
    $result = Invoke-Fixture $fixture ($installArgs + @('-DeviceSerial', 'FAKE-2'))
    Assert-True ($result.ExitCode -eq 0 -and [IO.File]::ReadAllText((Join-Path $fixture 'installed-serial.txt')) -ceq 'FAKE-2') '多设备时可显式指定目标'
    foreach ($mode in @('no-device', 'unauthorized', 'multiple', 'adb-failure', 'install-failure', 'package-failure', 'wrong-installed')) {
        $fixture = New-Fixture
        [IO.File]::WriteAllText((Join-Path $fixture 'adb-mode.txt'), $mode)
        $result = Invoke-Fixture $fixture @('-Install', '-AdbPath', (Join-Path $fixture 'fake-adb.cmd'))
        $expected = switch ($mode) { 'adb-failure' { 20 } 'install-failure' { 21 } 'package-failure' { 22 } default { 1 } }
        Assert-True ($result.ExitCode -eq $expected) "拒绝错误安装状态：$mode；$($result.Output)"
        if ($mode -in @('no-device', 'unauthorized', 'multiple', 'adb-failure')) {
            Assert-True (-not (Test-Path -LiteralPath (Join-Path $fixture 'installed-path.txt'))) '设备门禁失败不调用安装'
        }
    }
    $fixture = New-Fixture
    $result = Invoke-Fixture $fixture @('-Release', '-Install', '-AdbPath', (Join-Path $fixture 'fake-adb.cmd'))
    Assert-True ($result.ExitCode -ne 0 -and -not (Test-Path -LiteralPath (Join-Path $fixture 'adb-calls.log'))) '未签名 Release 不查询或安装设备'
    $fixture = New-Fixture
    $result = Invoke-Fixture $fixture @('-Help') -ScriptName 'install.ps1'
    Assert-True ($result.ExitCode -eq 0 -and $result.Output.Contains('DeviceSerial')) '独立安装入口的帮助'
    $result = Invoke-Fixture $fixture @('-WhatIf') -ScriptName 'install.ps1'
    Assert-True ($result.ExitCode -eq 0 -and -not (Test-Path -LiteralPath (Join-Path $fixture 'gradle-called.txt')) -and -not (Test-Path -LiteralPath (Join-Path $fixture 'adb-calls.log'))) '安装入口预演不构建或查询设备'
    $before = [IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts'))
    [IO.File]::WriteAllText((Join-Path $fixture 'adb-mode.txt'), 'multiple')
    $result = Invoke-Fixture $fixture @('-DeviceSerial', 'FAKE-2', '-AdbPath', (Join-Path $fixture 'fake-adb.cmd')) -ScriptName 'install.ps1'
    Assert-True ($result.ExitCode -eq 0 -and [IO.File]::ReadAllText((Join-Path $fixture 'installed-serial.txt')) -ceq 'FAKE-2') '独立入口默认安装并转发设备参数'
    Assert-True ([IO.File]::ReadAllText((Join-Path $fixture 'app/build.gradle.kts')) -ceq $before) '独立安装入口不修改版本'
    [IO.File]::WriteAllText((Join-Path $fixture 'adb-mode.txt'), 'install-failure')
    $result = Invoke-Fixture $fixture @('-AdbPath', (Join-Path $fixture 'fake-adb.cmd')) -ScriptName 'install.ps1'
    Assert-True ($result.ExitCode -eq 21) '独立入口保留安装失败退出码'
    Write-Host "PASS：构建／安装脚本 $assertions 项断言（临时工程与模拟 Gradle，无设备操作）。"
} finally {
    # 只删除本轮创建的临时目录，先核对绝对路径与固定前缀。
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'life-archive-build-tests-*') { throw '拒绝清理意外路径' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
