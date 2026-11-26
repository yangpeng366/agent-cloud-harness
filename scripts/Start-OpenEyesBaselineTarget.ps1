# Start-OpenEyesBaselineTarget.ps1
#
# Phase 2 data-collection prerequisite for FEAT-05: the flaky comparison legs
# need a deterministic top-level window that both assertion backends can target.
# Previous rounds created it ad hoc; this script makes the target reproducible.
#
# Usage:
#   pwsh -File scripts/Start-OpenEyesBaselineTarget.ps1              # ensure running
#   pwsh -File scripts/Start-OpenEyesBaselineTarget.ps1 -Status      # report only
#   pwsh -File scripts/Start-OpenEyesBaselineTarget.ps1 -Stop        # close it
#   pwsh -File scripts/Start-OpenEyesBaselineTarget.ps1 -LifetimeSeconds 900
#
# The window runs in a Windows PowerShell (STA) child process so it survives the
# caller. Prints a single JSON object: {ok, action, title, pid, main_window_title}.

[CmdletBinding()]
param(
    [string]$Title = 'ACH-OpenEyes-Baseline-Target',
    [int]$LifetimeSeconds = 0,
    [int]$TimeoutSec = 20,
    [string]$PidFile = '.tmp\openeyes-baseline-target.pid',
    [switch]$TopMost,
    [switch]$Status,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'

$windowScript = Join-Path $PSScriptRoot 'lib\OpenEyesBaselineTargetWindow.ps1'
if (-not (Test-Path -LiteralPath $windowScript)) {
    throw "baseline target window script not found: $windowScript"
}

$resolvedPidFile = if ([System.IO.Path]::IsPathRooted($PidFile)) { $PidFile } else { Join-Path (Get-Location).Path $PidFile }
$pidDirectory = [System.IO.Path]::GetDirectoryName($resolvedPidFile)
if ($pidDirectory -and -not (Test-Path -LiteralPath $pidDirectory)) {
    New-Item -ItemType Directory -Force -Path $pidDirectory | Out-Null
}

function Get-TargetProcess {
    if (-not (Test-Path -LiteralPath $resolvedPidFile)) { return $null }
    $raw = (Get-Content -LiteralPath $resolvedPidFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    $targetPid = 0
    if (-not [int]::TryParse($raw, [ref]$targetPid)) { return $null }
    $process = Get-Process -Id $targetPid -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    $process.Refresh()
    return $process
}

function Write-Result {
    param([string]$Action, [object]$Process, [string]$Reason = '')
    $payload = [ordered]@{
        ok                = ($Action -ne 'failed')
        action            = $Action
        title             = $Title
        pid               = if ($null -ne $Process) { $Process.Id } else { $null }
        main_window_title = if ($null -ne $Process) { $Process.MainWindowTitle } else { $null }
        reason            = $Reason
    }
    $payload | ConvertTo-Json -Compress
}

$existing = Get-TargetProcess
if ($existing -and -not [string]::IsNullOrWhiteSpace($existing.MainWindowTitle)) {
    if ($Stop) {
        Stop-Process -Id $existing.Id -Force
        Remove-Item -LiteralPath $resolvedPidFile -Force -ErrorAction SilentlyContinue
        Write-Result -Action 'stopped' -Process $existing
        exit 0
    }
    Write-Result -Action 'already-running' -Process $existing
    exit 0
}

if ($Status) {
    Write-Result -Action 'not-running' -Process $null -Reason 'no live target window tracked'
    exit 0
}

$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (-not (Test-Path -LiteralPath $shell)) { throw "Windows PowerShell not found: $shell" }

$arguments = @(
    '-NoProfile'
    '-Sta'
    '-ExecutionPolicy', 'Bypass'
    '-File', "`"$windowScript`""
    '-Title', "`"$Title`""
    '-LifetimeSeconds', $LifetimeSeconds
)
if ($TopMost) { $arguments += '-TopMost' }
$process = Start-Process -FilePath $shell -ArgumentList $arguments -PassThru
Set-Content -LiteralPath $resolvedPidFile -Value $process.Id -Encoding ascii

$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    $process.Refresh()
    if ($process.HasExited) {
        Remove-Item -LiteralPath $resolvedPidFile -Force -ErrorAction SilentlyContinue
        Write-Result -Action 'failed' -Process $null -Reason "target window process exited with code $($process.ExitCode)"
        exit 1
    }
    if (-not [string]::IsNullOrWhiteSpace($process.MainWindowTitle)) {
        Write-Result -Action 'started' -Process $process
        exit 0
    }
}

Write-Result -Action 'failed' -Process $process -Reason "window did not appear within $TimeoutSec s"
exit 1
