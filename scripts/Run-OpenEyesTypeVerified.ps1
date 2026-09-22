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
    [double]$Interval = 0
)

$ErrorActionPreference = 'Stop'
$startedAt = [DateTimeOffset]::UtcNow

if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'lib\OpenEyesDelivery.ps1')) { . (Join-Path $PSScriptRoot 'lib\OpenEyesDelivery.ps1') }

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
    $raw = & eyes windows list 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $raw) { return [IntPtr]::Zero }
    try { $windows = $raw | ConvertFrom-Json } catch { return [IntPtr]::Zero }
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

if ($Interval -gt 0) {
    & eyes type --text $Text --interval $Interval 2>$null
} else {
    & eyes type --text $Text 2>$null
}
$eyesExit = $LASTEXITCODE

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
