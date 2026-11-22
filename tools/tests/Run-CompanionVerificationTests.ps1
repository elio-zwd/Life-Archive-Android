#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$taskSource = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'verify-companion.ps1'
$taskTemp = Join-Path ([IO.Path]::GetTempPath()) ('life-archive-companion-script-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskTemp | Out-Null
$taskPwsh = (Get-Command pwsh.exe -CommandType Application | Select-Object -First 1).Source
$taskAssertions = 0
function Assert-Task([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "断言失败：$Message" }
    $script:taskAssertions++
}
function New-TaskFixture([string]$Mode) {
    $fixture = Join-Path $taskTemp ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $fixture | Out-Null
    Copy-Item -LiteralPath $taskSource -Destination (Join-Path $fixture 'verify-companion.ps1')
    New-Item -ItemType Directory -Path (Join-Path $fixture 'jdk/bin') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixture 'jdk/release'), 'JAVA_VERSION="17.0.19"')
    [IO.File]::WriteAllBytes((Join-Path $fixture 'jdk/bin/java.exe'), [byte[]]@())
    [IO.File]::WriteAllText((Join-Path $fixture 'mode.txt'), $Mode)
    [IO.File]::WriteAllText((Join-Path $fixture 'gradlew.bat'), "@echo off`r`npwsh.exe -NoProfile -File `"%~dp0fake-gradle.ps1`" %*`r`nexit /b %ERRORLEVEL%`r`n")
    [IO.File]::WriteAllText((Join-Path $fixture 'fake-gradle.ps1'), @'
$ErrorActionPreference = 'Stop'
Add-Content -LiteralPath (Join-Path $PSScriptRoot 'called.txt') -Value ($args -join ' ')
$mode = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'mode.txt') -Raw
if ($mode -eq 'fail') { exit 23 }
foreach ($module in @('core/live2d', 'data/ai', 'data/pet', 'feature/pet', 'data/jianyu', 'domain/jianyu', 'app')) {
    $kind = if ($module -eq 'domain/jianyu') { 'test' } else { 'testDebugUnitTest' }
    $dir = Join-Path $PSScriptRoot ($module + '/build/test-results/' + $kind)
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $count = if ($mode -eq 'no-source') { 0 } else { 1 }
    [IO.File]::WriteAllText((Join-Path $dir 'TEST-synthetic.xml'), ('<testsuite tests="' + $count + '" failures="0" errors="0" skipped="0" />'))
}
$apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
New-Item -ItemType Directory -Path (Split-Path -Parent $apk) -Force | Out-Null
[IO.File]::Delete($apk)
$archive = [IO.Compression.ZipFile]::Open($apk, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($name in @(
        'lib/arm64-v8a/libLive2DCubismCoreJNI.so', 'lib/x86/libLive2DCubismCoreJNI.so', 'lib/x86_64/libLive2DCubismCoreJNI.so',
        'assets/live2d/Hiyori/Hiyori.model3.json', 'assets/live2d/Hiyori/Hiyori.moc3',
        'assets/live2d/Hiyori/Hiyori.2048/texture_00.png', 'assets/live2d/Hiyori/Hiyori.2048/texture_01.png',
        'assets/live2d/Hiyori/motions/Hiyori_m04.motion3.json',
        'assets/com/live2d/sdk/cubism/framework/shaders/standardES/VertShaderSrc.vert',
        'assets/live2d/licenses/SDK-LICENSE.md', 'assets/live2d/licenses/Core-LICENSE.md', 'assets/live2d/licenses/Framework-LICENSE.md'
    )) {
        if ($mode -eq 'missing-native' -and $name.StartsWith('lib/')) { continue }
        $stream = $archive.CreateEntry($name).Open()
        try { $stream.WriteByte(42) } finally { $stream.Dispose() }
    }
} finally { $archive.Dispose() }
'@)
    return $fixture
}
$taskFixture = New-TaskFixture 'pass'
$taskEvidence = Join-Path $taskTemp 'preview-evidence'
& $taskPwsh -NoProfile -File (Join-Path $taskFixture 'verify-companion.ps1') -Help *> (Join-Path $taskTemp 'help.log')
Assert-Task ($LASTEXITCODE -eq 0) '帮助正常退出'
& $taskPwsh -NoProfile -File (Join-Path $taskFixture 'verify-companion.ps1') -WhatIf -EvidenceDir $taskEvidence *> (Join-Path $taskTemp 'whatif.log')
Assert-Task ($LASTEXITCODE -eq 0) '预演正常退出'
Assert-Task (-not (Test-Path -LiteralPath $taskEvidence)) '预演不创建目录'
Assert-Task (-not (Test-Path -LiteralPath (Join-Path $taskFixture 'called.txt'))) '帮助和预演不调用 Gradle'
foreach ($taskMode in @('pass', 'fail', 'no-source', 'missing-native')) {
    $taskFixture = New-TaskFixture $taskMode
    $taskEvidence = Join-Path $taskTemp ($taskMode + '-evidence')
    & $taskPwsh -NoProfile -File (Join-Path $taskFixture 'verify-companion.ps1') -JavaHome (Join-Path $taskFixture 'jdk') -EvidenceDir $taskEvidence *> (Join-Path $taskTemp ($taskMode + '.log'))
    $taskCode = $LASTEXITCODE
    if ($taskMode -eq 'pass') {
        Assert-Task ($taskCode -eq 0) '合成通过结果正常退出'
        $summary = Get-Content -LiteralPath (Join-Path $taskEvidence 'summary.json') -Raw | ConvertFrom-Json
        Assert-Task ($summary.device -eq 'NOT_VERIFIED') '电脑通过不冒充真机'
        Assert-Task ($summary.nativeLive2D -eq 'PACKAGED_NOT_DEVICE_VERIFIED') '打包通过不冒充原生设备渲染通过'
        Assert-Task ($summary.nativeDeviceSmoke -eq 'COMPILED_NOT_RUN') '设备冒烟仅编译'
        Assert-Task ($summary.testResults.Count -eq 7) '七组测试都必须有有效结果'
        Assert-Task ($summary.sha256 -eq (Get-FileHash -LiteralPath $summary.apk -Algorithm SHA256).Hash) 'APK 哈希对应产物'
        $reportHash = (Get-FileHash -LiteralPath (Join-Path $taskEvidence 'summary.json')).Hash
        & $taskPwsh -NoProfile -File (Join-Path $taskFixture 'verify-companion.ps1') -JavaHome (Join-Path $taskFixture 'jdk') -EvidenceDir $taskEvidence *> (Join-Path $taskTemp 'reused-evidence.log')
        Assert-Task ($LASTEXITCODE -ne 0) '拒绝覆盖已有证据，避免历史通过掩盖本次失败'
        Assert-Task ($reportHash -eq (Get-FileHash -LiteralPath (Join-Path $taskEvidence 'summary.json')).Hash) '保留原有报告'
    } else {
        Assert-Task ($taskCode -ne 0) "$taskMode 必须失败"
        Assert-Task (-not (Test-Path -LiteralPath (Join-Path $taskEvidence 'summary.json'))) '失败不生成通过报告'
    }
}
Write-Output "PASS：验收脚本 $taskAssertions 项断言；仅使用合成 Gradle、XML 与文件，不安装环境或操作设备。证据：$taskTemp"
