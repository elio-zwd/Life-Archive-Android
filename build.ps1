#requires -Version 7.0

<#
.SYNOPSIS
使用 JDK 17 构建 Life Archive，并导出 APK。
.DESCRIPTION
默认构建 Debug 并保留当前版本，APK 导出到系统“图片”目录。
可通过参数或 -EditVersion 修改 app/build.gradle.kts 中的版本号。
仅修改 VersionName 时，名称变化会自动将 versionCode 加一；显式 VersionCode 优先。
请求的版本修改会保留在源码中，即使后续构建失败也不自动回滚。
Release 未配置签名时导出 unsigned APK，不将其称为可安装的签名包。
指定 -Install 时，先由 Gradle 增量检查并复用最新产物，再保留数据覆盖安装。
不依赖版本号或 APK 修改时间判断源码是否已构建；多台在线设备须指定 -DeviceSerial。
.EXAMPLE
pwsh.exe -NoProfile -File .\build.ps1
.EXAMPLE
pwsh.exe -NoProfile -File .\build.ps1 -VersionName 0.2.7 -VersionCode 9 -NoPause
.EXAMPLE
pwsh.exe -NoProfile -File .\build.ps1 -EditVersion
.EXAMPLE
pwsh.exe -NoProfile -File .\build.ps1 -Release -OutputDir D:\Temp\LifeArchive -NoPause
.EXAMPLE
pwsh.exe -NoProfile -File .\build.ps1 -VersionName 0.2.7 -WhatIf
.EXAMPLE
pwsh.exe -NoProfile -File .\build.ps1 -Install -NoPause
.EXAMPLE
pwsh.exe -NoProfile -File .\build.ps1 -Install -DeviceSerial YOUR_SERIAL -NoPause
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$OutputDir = [Environment]::GetFolderPath('MyPictures'),
    [string]$JavaHome,
    [switch]$Release,
    [string]$VersionName,
    [ValidateRange(1, 2147483647)]
    [int]$VersionCode,
    [switch]$EditVersion,
    [switch]$Install,
    [string]$DeviceSerial,
    [string]$AdbPath,
    [switch]$NoPause,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ($Help) { Get-Help -Name $PSCommandPath -Detailed; exit 0 }

$exitCode = 1
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
try {
    if (-not $Install -and ($PSBoundParameters.ContainsKey('DeviceSerial') -or $PSBoundParameters.ContainsKey('AdbPath'))) {
        throw '-DeviceSerial／-AdbPath 需与 -Install 一起使用。'
    }
    if ($EditVersion -and ($PSBoundParameters.ContainsKey('VersionName') -or $PSBoundParameters.ContainsKey('VersionCode'))) {
        throw '-EditVersion 不与 -VersionName／-VersionCode 同时使用。'
    }
    $projectRoot = $PSScriptRoot
    $gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
    $versionFile = Join-Path $projectRoot 'app/build.gradle.kts'
    foreach ($required in @($gradleWrapper, $versionFile)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "未找到项目文件：$required" }
    }
    if ([string]::IsNullOrWhiteSpace($OutputDir)) { throw '无法确定导出目录，请显式指定 -OutputDir。' }
    $OutputDir = [IO.Path]::GetFullPath($OutputDir)

    # 只替换唯一的版本声明，保留其他未提交内容、UTF-8 BOM 与原有换行。
    $originalBytes = [IO.File]::ReadAllBytes($versionFile)
    $hasBom = $originalBytes.Length -ge 3 -and $originalBytes[0] -eq 0xEF -and $originalBytes[1] -eq 0xBB -and $originalBytes[2] -eq 0xBF
    $offset = if ($hasBom) { 3 } else { 0 }
    $utf8 = [Text.UTF8Encoding]::new($hasBom, $true)
    $original = $utf8.GetString($originalBytes, $offset, $originalBytes.Length - $offset)
    $codePattern = [regex]'(?m)^(\s*versionCode\s*=\s*)(\d+)([^\S\r\n]*(?://[^\r\n]*)?\r?)$'
    $namePattern = [regex]'(?m)^(\s*versionName\s*=\s*)"([^"\r\n]*)"([^\S\r\n]*(?://[^\r\n]*)?\r?)$'
    $codeMatches = $codePattern.Matches($original)
    $nameMatches = $namePattern.Matches($original)
    if ($codeMatches.Count -ne 1 -or $nameMatches.Count -ne 1) { throw '必须有且仅有一处 versionCode 与 versionName 声明，未修改文件。' }
    $currentCode = [int]$codeMatches[0].Groups[2].Value
    $currentName = $nameMatches[0].Groups[2].Value
    $nextName = if ($PSBoundParameters.ContainsKey('VersionName')) { $VersionName } else { $currentName }
    $nextCode = if ($PSBoundParameters.ContainsKey('VersionCode')) { $VersionCode } else { $currentCode }
    if ($EditVersion) {
        $inputName = Read-Host "新 versionName（回车保留 $currentName）"
        if (-not [string]::IsNullOrWhiteSpace($inputName)) { $nextName = $inputName }
    }
    if ($nextName -cne $currentName -and -not $PSBoundParameters.ContainsKey('VersionCode')) {
        if ($currentCode -eq [int]::MaxValue) { throw 'versionCode 已达到整数上限，请显式指定有效版本号。' }
        $nextCode = $currentCode + 1
    }
    if ($EditVersion) {
        $inputCode = Read-Host "新 versionCode（回车使用 $nextCode）"
        if (-not [string]::IsNullOrWhiteSpace($inputCode)) {
            $parsedCode = 0
            if (-not [int]::TryParse($inputCode, [ref]$parsedCode) -or $parsedCode -lt 1) { throw 'versionCode 必须是正整数。' }
            $nextCode = $parsedCode
        }
    }
    if ($nextName -cnotmatch '^[0-9A-Za-z][0-9A-Za-z._+-]{0,79}$') { throw 'versionName 需为 1～80 个字母、数字、点、下划线、加号或连字符，并以字母或数字开头。' }
    if ($nextCode -lt 1) { throw 'versionCode 必须是正整数。' }
    $variant = if ($Release) { 'release' } else { 'debug' }
    $task = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    Write-Host "Life Archive · $variant · $currentName ($currentCode) → $nextName ($nextCode)" -ForegroundColor Cyan
    $installDescription = if ($Install) { '，然后保留数据覆盖安装到指定或唯一在线设备' } else { '' }
    if (-not $PSCmdlet.ShouldProcess($projectRoot, "按版本 $nextName ($nextCode) 增量检查／构建 $task，并导出 APK 到 $OutputDir$installDescription")) { exit 0 }

    $javaExe = $null
    if ($JavaHome) {
        $javaExe = Join-Path $JavaHome 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) { throw "指定 JDK 下未找到 bin/java.exe：$JavaHome" }
    } elseif ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin/java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $javaExe = $candidate }
        else { Write-Warning 'JAVA_HOME 无效，继续检查 PATH 中的 Java。' }
    }
    if (-not $javaExe) {
        $command = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { $javaExe = $command.Source }
    }
    if (-not $javaExe) { throw '未检测到 JDK 17，请设置 JAVA_HOME，或通过 -JavaHome 指定 JDK 17。' }
    $javaOutput = (& $javaExe -XshowSettings:properties -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
    $javaExit = $LASTEXITCODE
    if ($javaExit -ne 0 -or $javaOutput -notmatch '(?m)^\s*java\.specification\.version\s*=\s*17\s*$') {
        throw '检测到的 Java 不是可用的 JDK 17，请通过 -JavaHome 指定正确路径。'
    }
    $homeMatch = [regex]::Match($javaOutput, '(?m)^\s*java\.home\s*=\s*([^\r\n]+)')
    if (-not $homeMatch.Success) { throw '无法确定 Java 的实际安装目录。' }
    $resolvedJavaHome = $homeMatch.Groups[1].Value.Trim()
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedJavaHome 'bin/javac.exe') -PathType Leaf)) { throw '所选 Java 缺少 javac.exe，请使用完整 JDK 17。' }
    $env:JAVA_HOME = $resolvedJavaHome
    $env:Path = (Join-Path $resolvedJavaHome 'bin') + [IO.Path]::PathSeparator + $previousPath

    $updated = $codePattern.Replace($original, { param($match) $match.Groups[1].Value + $nextCode + $match.Groups[3].Value })
    $updated = $namePattern.Replace($updated, { param($match) $match.Groups[1].Value + '"' + $nextName + '"' + $match.Groups[3].Value })
    if ($updated -cne $original) {
        if ([IO.File]::ReadAllText($versionFile) -cne $original) { throw '版本文件已被其他操作修改，请重新运行，未覆盖新内容。' }
        [IO.File]::WriteAllText($versionFile, $updated, $utf8)
        Write-Host "版本已更新：$nextName ($nextCode)" -ForegroundColor Yellow
    }
    Write-Host "使用 JDK：$resolvedJavaHome" -ForegroundColor Gray
    Write-Host '检查当前源码对应的 APK；Gradle 会复用最新产物，仅重建过期部分。' -ForegroundColor Gray
    Push-Location -LiteralPath $projectRoot
    try {
        & $gradleWrapper $task '--console=plain'
        $gradleExit = $LASTEXITCODE
        if ($gradleExit -ne 0) { $exitCode = $gradleExit; throw "Gradle 编译失败，退出码：$gradleExit" }
    } finally { Pop-Location }

    # 使用本项目的 AGP 元数据定位签名／未签名产物，拒绝误导出其他包或旧版本。
    $apkDirectory = Join-Path $projectRoot "app/build/outputs/apk/$variant"
    $metadataFile = Join-Path $apkDirectory 'output-metadata.json'
    if (-not (Test-Path -LiteralPath $metadataFile -PathType Leaf)) { throw "构建完成但缺少 APK 元数据：$metadataFile" }
    $metadata = Get-Content -LiteralPath $metadataFile -Raw -Encoding utf8 | ConvertFrom-Json
    if ($metadata.applicationId -cne 'com.elio.lifearchive' -or $metadata.variantName -cne $variant) { throw 'APK 包名或构建类型与 Life Archive 不一致，未导出。' }
    $elements = @($metadata.elements)
    if ($elements.Count -ne 1) { throw '预期一个独立 APK，当前产物数量不符，未导出。' }
    $apk = $elements[0]
    if ($apk.versionName -cne $nextName -or $apk.versionCode -ne $nextCode) { throw "APK 版本 $($apk.versionName) ($($apk.versionCode)) 与请求 $nextName ($nextCode) 不一致，未导出旧产物。" }
    $outputFile = [string]$apk.outputFile
    if ([IO.Path]::GetFileName($outputFile) -cne $outputFile -or $outputFile -notlike '*.apk') { throw 'APK 元数据包含非法文件名。' }
    $sourceApk = Join-Path $apkDirectory $outputFile
    if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) { throw "构建完成但未找到 APK：$sourceApk" }
    $unsigned = $outputFile.EndsWith('-unsigned.apk', [StringComparison]::OrdinalIgnoreCase)
    $unsignedSuffix = if ($unsigned) { '-unsigned' } else { '' }
    $destination = Join-Path $OutputDir "Life-Archive-$nextName-$variant$unsignedSuffix.apk"
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    if ([IO.Path]::GetFullPath($sourceApk) -ine [IO.Path]::GetFullPath($destination)) {
        Copy-Item -LiteralPath $sourceApk -Destination $destination -Force
    }
    $item = Get-Item -LiteralPath $destination
    Write-Host "编译与导出成功：$($item.FullName)" -ForegroundColor Green
    Write-Host "版本：$nextName ($nextCode)；大小：$($item.Length) 字节；SHA-256：$((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash)"
    if ($unsigned) { Write-Warning 'Release APK 未签名，不能直接安装；本脚本不创建或修改签名材料。' }
    if ($Install) {
        if ($unsigned) { throw 'APK 已导出，但未签名，不能安装。请配置正式签名或使用默认 Debug 构建。' }
        $adbExe = $null
        if ($AdbPath) {
            if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "未找到指定 ADB：$AdbPath" }
            $adbExe = $AdbPath
        } else {
            $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
            $localProperties = Join-Path $projectRoot 'local.properties'
            if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
                $sdkMatch = [regex]::Match([IO.File]::ReadAllText($localProperties), '(?m)^\s*sdk\.dir\s*=\s*([^\r\n]+)')
                if ($sdkMatch.Success) { $sdkRoots += $sdkMatch.Groups[1].Value.Trim().Replace('\\', '\').Replace('\:', ':').Replace('\ ', ' ') }
            }
            foreach ($sdkRoot in $sdkRoots) {
                if (-not [string]::IsNullOrWhiteSpace($sdkRoot)) {
                    $candidate = Join-Path $sdkRoot 'platform-tools/adb.exe'
                    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $adbExe = $candidate; break }
                }
            }
            if (-not $adbExe) {
                $command = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($command) { $adbExe = $command.Source }
            }
        }
        if (-not $adbExe) { throw 'APK 已导出，但未找到 ADB；请配置 Android SDK，或传入 -AdbPath。' }
        $deviceOutput = @(& $adbExe devices 2>&1 | ForEach-Object { $_.ToString() })
        $adbExit = $LASTEXITCODE
        if ($adbExit -ne 0) { $exitCode = $adbExit; throw '无法查询 ADB 设备，APK 已导出。' }
        $onlineDevices = @($deviceOutput | ForEach-Object {
            if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
        })
        if ($DeviceSerial) {
            if ($onlineDevices -cnotcontains $DeviceSerial) { throw "指定设备未在线或未授权：$DeviceSerial" }
            $selectedDevice = $DeviceSerial
        } else {
            if ($onlineDevices.Count -ne 1) { throw "检测到 $($onlineDevices.Count) 台在线设备；请连接并授权唯一设备，或传入 -DeviceSerial。" }
            $selectedDevice = $onlineDevices[0]
        }
        Write-Host "覆盖安装到 $selectedDevice：com.elio.lifearchive $nextName ($nextCode)" -ForegroundColor Cyan
        & $adbExe '-s' $selectedDevice 'install' '-r' $sourceApk
        $adbExit = $LASTEXITCODE
        if ($adbExit -ne 0) { $exitCode = $adbExit; throw "覆盖安装失败，退出码 $adbExit；APK 已导出，请检查签名、版本与设备状态。" }
        $packageOutput = (& $adbExe '-s' $selectedDevice 'shell' 'dumpsys' 'package' 'com.elio.lifearchive' 2>&1 | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        $adbExit = $LASTEXITCODE
        if ($adbExit -ne 0) { $exitCode = $adbExit; throw '安装命令已结束，但无法核对设备上的包状态。' }
        $installedCode = [regex]::Match($packageOutput, '(?m)^\s*versionCode=(\d+)\b')
        $installedName = [regex]::Match($packageOutput, '(?m)^\s*versionName=(\S+)')
        if (-not $installedCode.Success -or -not $installedName.Success -or [int]$installedCode.Groups[1].Value -ne $nextCode -or $installedName.Groups[1].Value -cne $nextName) {
            throw '安装命令已结束，但设备上的版本与本次 APK 不一致，未报告安装成功。'
        }
        Write-Host "安装完成，已核对设备版本：$nextName ($nextCode)。" -ForegroundColor Green
    }
    $exitCode = 0
} catch {
    [Console]::Error.WriteLine("构建／导出／安装失败：$($_.Exception.Message)")
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}

if (-not $NoPause -and -not $env:CI -and -not [Console]::IsInputRedirected) {
    $null = Read-Host '按回车键退出'
}
exit $exitCode
