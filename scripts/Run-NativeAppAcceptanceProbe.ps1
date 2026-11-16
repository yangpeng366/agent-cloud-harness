[CmdletBinding()]
param(
    [string]$AppTitleContains = 'Any VPN',
    [string]$ButtonNameContains = '连接',
    [string]$ControlType = 'Button',
    [string]$EyesBin = 'eyes',
    [ValidateRange(0, [int]::MaxValue)]
    [int]$WindowIndex = 0,
    [ValidateRange(0, [int]::MaxValue)]
    [int]$Depth = 0,
    [ValidateRange(1, 500)]
    [int]$MaxElements = 50,
    [string]$CaptureDirectory = '.tmp\openeyes-native-probe',
    [string[]]$OpenEyesSourceRootCandidates = @(),
    [int]$EyesTimeoutSec = 60,
    [switch]$SkipCliEnvSetup,
    [switch]$Go
)

$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'lib\OpenEyesCli.ps1')) { . (Join-Path $PSScriptRoot 'lib\OpenEyesCli.ps1') }
$startedAt = [DateTimeOffset]::UtcNow
$result = [ordered]@{
    schema_version = 1
    status = 'FAIL'
    mode = if ($Go) { 'real_click' } else { 'dry_run' }
    eyes_bin = $EyesBin
    eyes_cli = $null
    app_title_contains = $AppTitleContains
    button_name_contains = $ButtonNameContains
    control_type = $ControlType
    window = $null
    elements = @()
    selected_element = $null
    click_output = $null
    click_coordinates = $null
    capture = $null
    duration_ms = $null
    error = $null
}

function Invoke-Eyes {
    param([string[]]$Arguments)
    $run = Invoke-OpenEyesCliProcess -EyesBin $EyesBin -Arguments $Arguments -TimeoutSec $EyesTimeoutSec
    $combined = (@($run.output, $run.stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join [Environment]::NewLine
    if ($run.exit_code -ne 0) {
        throw "eyes command failed: $($Arguments -join ' ') (exit=$($run.exit_code)): $combined"
    }
    return $combined
}

try {
    if (-not $SkipCliEnvSetup) {
        $cli = Initialize-OpenEyesCliEnvironment -EyesBin $EyesBin -SourceRootCandidates $OpenEyesSourceRootCandidates
        $result.eyes_cli = $cli
        if ($cli.status -ne 'ok') {
            throw "eyes CLI unavailable ($($cli.status)): $($cli.reason). remediation: $($cli.remediation)"
        }
        $EyesBin = $cli.executable
    }
    $eyesCommand = Get-Command -Name $EyesBin -ErrorAction Stop
    $result.eyes_bin = $eyesCommand.Source

    $windowPayload = Invoke-OpenEyesJson -EyesBin $EyesBin -Arguments @(
        'windows', 'list', '--json', '--title-contains', $AppTitleContains
    ) -EnvelopeKeys @('windows') -TimeoutSec $EyesTimeoutSec
    $windows = @($windowPayload.items)
    if ($WindowIndex -ge $windows.Count) {
        throw "window not found: title contains '$AppTitleContains'; candidates=$($windows.Count); requested index=$WindowIndex"
    }

    $window = $windows[$WindowIndex]
    if ($null -eq $window.hwnd) {
        throw "selected window has no hwnd: $($window | ConvertTo-Json -Compress)"
    }
    $result.window = $window

    $detectArguments = @(
        'detect',
        '--window', [string]$window.hwnd,
        '--name-contains', $ButtonNameContains,
        '--control-type', $ControlType,
        '--max', [string]$MaxElements,
        '--json'
    )
    if ($Depth -gt 0) {
        $detectArguments += @('--depth', [string]$Depth)
    }

    $detectPayload = Invoke-OpenEyesJson -EyesBin $EyesBin -Arguments $detectArguments -EnvelopeKeys @('elements') -TimeoutSec $EyesTimeoutSec
    $detectedElements = @($detectPayload.items)
    $elements = @(
        foreach ($element in $detectedElements) {
            if ($null -ne $element.center -and $null -ne $element.bbox) {
                $element
            }
        }
    )
    $result.elements = $elements
    if ($elements.Count -eq 0) {
        throw "button not found: name contains '$ButtonNameContains', control type '$ControlType'"
    }

    $selectedElement = $elements | Select-Object -First 1
    $result.selected_element = $selectedElement

    $clickArguments = @(
        'click',
        '--window', [string]$window.hwnd,
        '--name-contains', $ButtonNameContains,
        '--control-type', $ControlType
    )
    if ($Go) {
        $clickArguments += '--go'
    }
    $clickOutput = Invoke-Eyes -Arguments $clickArguments
    $result.click_output = $clickOutput
    if ($clickOutput -match '\[dry-run\]\s+would click\s+@\s+\((?<x>-?\d+),\s*(?<y>-?\d+)\)') {
        $result.click_coordinates = [pscustomobject]@{
            x = [int]$Matches.x
            y = [int]$Matches.y
        }
    }

    $resolvedCaptureDirectory = if ([System.IO.Path]::IsPathRooted($CaptureDirectory)) {
        [System.IO.Path]::GetFullPath($CaptureDirectory)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $CaptureDirectory))
    }
    New-Item -ItemType Directory -Force -Path $resolvedCaptureDirectory | Out-Null
    $capturePath = Join-Path $resolvedCaptureDirectory (
        'native-probe-{0}.png' -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
    )
    Invoke-Eyes -Arguments @(
        'capture', '--window', [string]$window.hwnd, '--out', $capturePath
    ) | Out-Null
    if (-not (Test-Path -LiteralPath $capturePath)) {
        throw "capture file was not created: $capturePath"
    }
    $captureFile = Get-Item -LiteralPath $capturePath
    $result.capture = [pscustomobject]@{
        path = $captureFile.FullName
        exists = $true
        bytes = $captureFile.Length
    }

    $result.status = 'PASS'
} catch {
    $result.error = $_.Exception.Message
} finally {
    $result.duration_ms = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
}

$result | ConvertTo-Json -Depth 8
if ($result.status -ne 'PASS') {
    exit 1
}
