# Run-OpenEyesTypeVerified.ps1
#
# Wraps `eyes type` with focus preflight and delivery evidence.
# Captures the foreground window title before and after typing so the caller
# can detect "text went to wrong window" failures. Exit 0 does not imply
# delivery; the caller must inspect the JSON report.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Text,
    [string]$ExpectedWindowTitle = '',
    [switch]$ActivateIfMismatch,
    [string]$OutputJsonPath = '',
    [double]$Interval = 0,
    [string]$EyesBin = 'eyes',
    [string[]]$OpenEyesSourceRootCandidates = @(),
    [int]$EyesTimeoutSec = 60
)

$ErrorActionPreference = 'Stop'
$startedAt = [DateTimeOffset]::UtcNow

if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'lib\OpenEyesDelivery.ps1')) { . (Join-Path $PSScriptRoot 'lib\OpenEyesDelivery.ps1') }
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'lib\OpenEyesCli.ps1')) { . (Join-Path $PSScriptRoot 'lib\OpenEyesCli.ps1') }

$cli = Initialize-OpenEyesCliEnvironment -EyesBin $EyesBin -SourceRootCandidates $OpenEyesSourceRootCandidates
if ($cli.status -eq 'ok') { $EyesBin = $cli.executable }

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public class OpenEyesFocus {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
}
'@

function Get-ForegroundTitle {
    $hwnd = [OpenEyesFocus]::GetForegroundWindow()
    if ($hwnd -eq [IntPtr]::Zero) { return '' }
    $sb = New-Object System.Text.StringBuilder 512
    [OpenEyesFocus]::GetWindowText($hwnd, $sb, 512) | Out-Null
    return $sb.ToString()
}

function Find-WindowHandle([string]$TitleContains) {
    if ([string]::IsNullOrWhiteSpace($TitleContains)) { return [IntPtr]::Zero }
    if ($cli.status -ne 'ok') { return [IntPtr]::Zero }
    try {
        $payload = Invoke-OpenEyesJson -EyesBin $EyesBin -Arguments @('windows', 'list', '--json', '--title-contains', $TitleContains) -EnvelopeKeys @('windows') -TimeoutSec $EyesTimeoutSec
        $windows = @($payload.items)
    }
    catch { return [IntPtr]::Zero }
    if (-not $windows) { return [IntPtr]::Zero }
    $match = $windows | Where-Object { $_.title -and $_.title.ToLower().Contains($TitleContains.ToLower()) } | Select-Object -First 1
    if ($match -and $match.hwnd) { return [IntPtr]$match.hwnd }
    return [IntPtr]::Zero
}

$focusBefore = Get-ForegroundTitle
$activated = $false

if (-not [string]::IsNullOrWhiteSpace($ExpectedWindowTitle) -and $ActivateIfMismatch) {
    if (-not $focusBefore.ToLower().Contains($ExpectedWindowTitle.ToLower())) {
        $hwnd = Find-WindowHandle -TitleContains $ExpectedWindowTitle
        if ($hwnd -ne [IntPtr]::Zero) {
            [OpenEyesFocus]::SetForegroundWindow($hwnd) | Out-Null
            Start-Sleep -Milliseconds 300
            $focusBefore = Get-ForegroundTitle
            $activated = $true
        }
    }
}

$focusMatches = if ([string]::IsNullOrWhiteSpace($ExpectedWindowTitle)) { $null }
    else { $focusBefore.ToLower().Contains($ExpectedWindowTitle.ToLower()) }

$typeArguments = @('type', '--text', $Text)
if ($Interval -gt 0) { $typeArguments += @('--interval', [string]$Interval) }
$typeRun = Invoke-OpenEyesCliProcess -EyesBin $EyesBin -Arguments $typeArguments -TimeoutSec $EyesTimeoutSec
$eyesExit = $typeRun.exit_code

$focusAfter = Get-ForegroundTitle
$endedAt = [DateTimeOffset]::UtcNow
$durationMs = [int]($endedAt - $startedAt).TotalMilliseconds

$deliveryStatus = Resolve-DeliveryStatus -EyesExitCode $eyesExit -FocusMatchesExpected $focusMatches -FocusBefore $focusBefore -FocusAfter $focusAfter

$result = [ordered]@{
    schema_version = 1
    operation = 'type_verified'
    status = if ($deliveryStatus -eq 'DISPATCHED_FOCUSED') { 'PASS' } else { 'WARN' }
    delivery_status = $deliveryStatus
    text_sent = $Text
    focus_window_before = $focusBefore
    focus_window_after = $focusAfter
    expected_window_title = $ExpectedWindowTitle
    focus_matches_expected = $focusMatches
    window_activated = $activated
    eyes_cli_status = $cli.status
    eyes_exit_code = $eyesExit
    duration_ms = $durationMs
    started_at = $startedAt.ToString('o')
}

$json = $result | ConvertTo-Json -Depth 5
if (-not [string]::IsNullOrWhiteSpace($OutputJsonPath)) {
    $dir = Split-Path $OutputJsonPath -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($OutputJsonPath, $json, [System.Text.UTF8Encoding]::new($false))
}
Write-Output $json
if ($deliveryStatus -eq 'DISPATCH_FAILED') { exit 1 }
