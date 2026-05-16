param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ReportPath = '.tmp\console-provider-window-probe.json',
    [string]$ScreenshotPath = '.tmp\console-provider-window-probe.png',
    [int]$NodeMaxOldSpaceMb = 512
)

$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot 'console-provider-window-probe.js'
$resolvedReportPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $ReportPath))
$resolvedScreenshotPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $ScreenshotPath))

New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($resolvedReportPath)) | Out-Null
New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($resolvedScreenshotPath)) | Out-Null

$nodeArgs = @(
    "--max-old-space-size=$NodeMaxOldSpaceMb",
    $scriptPath,
    '--base-url', $BaseUrl,
    '--report', $resolvedReportPath,
    '--screenshot', $resolvedScreenshotPath
)

$output = & node @nodeArgs
if ($LASTEXITCODE -ne 0) {
    throw "console provider window probe failed"
}

$output
