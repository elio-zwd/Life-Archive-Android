#requires -Version 7.0

<#
.SYNOPSIS
检查最新 APK，并将 Life Archive 覆盖安装到手机。
.DESCRIPTION
复用同目录 build.ps1 的增量检查、构建和安装流程。
已有 APK 最新时直接复用，过期则先构建；保留数据覆盖安装并核对设备版本。
默认不修改版本号；多台在线设备须通过 -DeviceSerial 指定目标。
.EXAMPLE
pwsh.exe -NoProfile -File .\install.ps1
.EXAMPLE
pwsh.exe -NoProfile -File .\install.ps1 -DeviceSerial YOUR_SERIAL -NoPause
.EXAMPLE
pwsh.exe -NoProfile -File .\install.ps1 -WhatIf -NoPause
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$DeviceSerial,
    [string]$AdbPath,
    [string]$JavaHome,
    [string]$OutputDir,
    [switch]$Release,
    [switch]$NoPause,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Help) { Get-Help -Name $PSCommandPath -Detailed; exit 0 }

try {
    $buildScript = Join-Path $PSScriptRoot 'build.ps1'
    if (-not (Test-Path -LiteralPath $buildScript -PathType Leaf)) { throw "未找到配套构建脚本：$buildScript" }
    # 只转发显式参数，保留构建脚本的默认目录、暂停与预演行为。
    $buildParameters = @{}
    foreach ($key in $PSBoundParameters.Keys) { $buildParameters[$key] = $PSBoundParameters[$key] }
    $buildParameters['Install'] = $true
    & $buildScript @buildParameters
    $installExit = $LASTEXITCODE
    exit $installExit
} catch {
    [Console]::Error.WriteLine("安装失败：$($_.Exception.Message)")
    exit 1
}
