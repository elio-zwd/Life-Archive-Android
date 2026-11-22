#requires -Version 7.0
<#
.SYNOPSIS
宠物核心与公共 AI 的无设备验收：测试、架构、Lint、Debug APK 和仓库外证据。
#>
[CmdletBinding()]
param(
    [switch]$Help,
    [switch]$WhatIf,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$EvidenceDir
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ($Help) {
    Write-Output '用法：pwsh.exe -NoProfile -File .\verify-companion.ps1 [-JavaHome JDK17目录] [-EvidenceDir 仓库外目录] [-WhatIf]'
    Write-Output '默认运行无设备测试、架构、Debug Lint 和构建；不安装应用，不调用 ADB，不读取真实笔记或凭据。'
    return
}
$taskSteps = @(
    @{ Name = 'data-tests'; Arguments = @(':core:live2d:testDebugUnitTest', ':data:ai:testDebugUnitTest', ':data:pet:testDebugUnitTest', ':feature:pet:testDebugUnitTest', ':data:jianyu:testDebugUnitTest', ':domain:jianyu:test') },
    @{ Name = 'host-tests'; Arguments = @(':app:testDebugUnitTest', '--tests', '*BusinessHostViewModelTest', '--tests', '*BusinessHostUiTest', '--tests', '*BusinessSwitcherTest', '--tests', '*CompleteBackupIntegrationTest', '--tests', '*VisibilityLifecycleOwnerTest') },
    @{ Name = 'build-lint'; Arguments = @('checkArchitecture', ':app:assembleDebug', ':core:live2d:assembleDebugAndroidTest', ':core:live2d:lintDebug', ':app:lintDebug', ':data:ai:lintDebug', ':data:pet:lintDebug', ':feature:pet:lintDebug', ':data:jianyu:lintDebug', ':feature:jianyu:lintDebug') }
)
if ($WhatIf) {
    foreach ($taskStep in $taskSteps) { Write-Output ('.\gradlew.bat ' + ($taskStep.Arguments -join ' ') + ' --console=plain --max-workers=2') }
    Write-Output '仅预演：不构建、不测试、不创建证据目录。'
    return
}
if (-not $IsWindows) { throw '此入口面向 Windows 10 x64，请在 pwsh.exe 中运行。' }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw '请通过 JAVA_HOME 或 -JavaHome 指定已有 JDK 17。' }
$taskJava = [IO.Path]::GetFullPath($JavaHome)
if (-not (Test-Path -LiteralPath (Join-Path $taskJava 'bin/java.exe'))) { throw '指定的 JDK 缺少 java.exe。' }
if (-not ((Get-Content -LiteralPath (Join-Path $taskJava 'release') -Raw) -match 'JAVA_VERSION="17[.]')) { throw '需要 JDK 17；不会自动安装或切换依赖版本。' }
$taskRoot = [IO.Path]::GetFullPath($PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path ([IO.Path]::GetTempPath()) ('life-archive-companion-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$taskEvidence = [IO.Path]::GetFullPath($EvidenceDir)
if ($taskEvidence.Equals($taskRoot, [StringComparison]::OrdinalIgnoreCase) -or $taskEvidence.StartsWith($taskRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw '验收日志与截图必须放在仓库外。'
}
if ((Test-Path -LiteralPath $taskEvidence) -and @(Get-ChildItem -LiteralPath $taskEvidence -Force).Count -gt 0) {
    throw '证据目录已有内容，请指定新的空目录；不会覆盖旧报告，避免本次失败后误读历史通过结果。'
}
New-Item -ItemType Directory -Path $taskEvidence -Force | Out-Null
$taskPreviousJava = $env:JAVA_HOME
$taskStarted = [DateTimeOffset]::Now
$taskLocationPushed = $false
try {
    $env:JAVA_HOME = $taskJava
    Push-Location -LiteralPath $taskRoot
    $taskLocationPushed = $true
    foreach ($taskStep in $taskSteps) {
        $taskArguments = @($taskStep.Arguments) + @('--console=plain', '--max-workers=2')
        & (Join-Path $taskRoot 'gradlew.bat') @taskArguments 2>&1 | Tee-Object -FilePath (Join-Path $taskEvidence ($taskStep.Name + '.log'))
        if ($LASTEXITCODE -ne 0) { throw "$($taskStep.Name) 失败，Gradle 退出码 $LASTEXITCODE；日志保留在 $taskEvidence" }
    }
    $taskCounts = @()
    foreach ($taskModule in @('core/live2d', 'data/ai', 'data/pet', 'feature/pet', 'data/jianyu', 'domain/jianyu', 'app')) {
        $taskResults = Join-Path $taskRoot ($taskModule + '/build/test-results/' + $(if ($taskModule -eq 'domain/jianyu') { 'test' } else { 'testDebugUnitTest' }))
        $taskTests = 0; $taskFailures = 0; $taskSkipped = 0
        foreach ($taskXml in @(Get-ChildItem -LiteralPath $taskResults -Filter 'TEST-*.xml' -ErrorAction Stop)) {
            [xml]$taskSuite = Get-Content -LiteralPath $taskXml.FullName -Raw
            $taskTests += [int]$taskSuite.testsuite.tests
            $taskFailures += [int]$taskSuite.testsuite.failures + [int]$taskSuite.testsuite.errors
            $taskSkipped += [int]$taskSuite.testsuite.skipped
        }
        if ($taskTests -le $taskSkipped -or $taskFailures -gt 0) { throw "$taskModule 未获得有效通过结果；NO-SOURCE、全部跳过或失败均不算通过。" }
        $taskCounts += @{ module = $taskModule; tests = $taskTests; skipped = $taskSkipped; failures = $taskFailures }
    }
    $taskApk = Join-Path $taskRoot 'app/build/outputs/apk/debug/app-debug.apk'
    if (-not (Test-Path -LiteralPath $taskApk)) { throw '构建未生成 Debug APK。' }
    $taskRequiredAssets = @(
        'lib/arm64-v8a/libLive2DCubismCoreJNI.so', 'lib/x86/libLive2DCubismCoreJNI.so', 'lib/x86_64/libLive2DCubismCoreJNI.so',
        'assets/live2d/Hiyori/Hiyori.model3.json', 'assets/live2d/Hiyori/Hiyori.moc3',
        'assets/live2d/Hiyori/Hiyori.2048/texture_00.png', 'assets/live2d/Hiyori/Hiyori.2048/texture_01.png',
        'assets/live2d/Hiyori/motions/Hiyori_m04.motion3.json',
        'assets/com/live2d/sdk/cubism/framework/shaders/standardES/VertShaderSrc.vert',
        'assets/live2d/licenses/SDK-LICENSE.md', 'assets/live2d/licenses/Core-LICENSE.md', 'assets/live2d/licenses/Framework-LICENSE.md'
    )
    $taskArchive = [IO.Compression.ZipFile]::OpenRead($taskApk)
    try {
        foreach ($taskAsset in $taskRequiredAssets) {
            $taskEntry = $taskArchive.GetEntry($taskAsset)
            if ($null -eq $taskEntry -or $taskEntry.Length -eq 0) { throw "APK 缺少原生伙伴资源：$taskAsset" }
        }
    } finally { $taskArchive.Dispose() }
    $taskSummary = @{
        started = $taskStarted.ToString('o'); completed = [DateTimeOffset]::Now.ToString('o')
        testResults = $taskCounts; apk = $taskApk; sha256 = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash
        device = 'NOT_VERIFIED'; nativeLive2D = 'PACKAGED_NOT_DEVICE_VERIFIED'
        nativePackageEntries = $taskRequiredAssets; nativeDeviceSmoke = 'COMPILED_NOT_RUN'
        scope = '电脑核心功能与回归；新增备份后置；不含真实远端模型或设备验收'
    }
    $taskSummary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskEvidence 'summary.json') -Encoding utf8
    Write-Output "PASS：无设备验收完成，证据位于 $taskEvidence"
} finally {
    if ($taskLocationPushed) { Pop-Location }
    $env:JAVA_HOME = $taskPreviousJava
}
