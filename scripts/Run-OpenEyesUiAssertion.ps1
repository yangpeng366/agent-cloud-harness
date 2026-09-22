
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AppTitleContains,
    [int]$WindowIndex = 0,
    [int]$MaxElements = 200,
    [string[]]$ExpectedElementNameContains = @(),
    [string[]]$ExpectedElementAutomationId = @(),
    [string[]]$ExpectedElementControlType = @(),
    [int]$MinimumElementCount = 1,
    [switch]$AllowDisabled,
    [switch]$AllowInvisible,
    [switch]$SkipCapture,
    [string]$CaptureDirectory = '.tmp\openeyes-ui-assertion',
    [string]$OutputJsonPath = '',
    [string]$EyesBin = 'eyes'
)

$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'lib\OpenEyesElement.ps1')) { . (Join-Path $PSScriptRoot 'lib\OpenEyesElement.ps1') }
$startedAt = [DateTimeOffset]::UtcNow
$result = [ordered]@{
    schema_version = 1
    assertion_mode = 'openeyes_structured'
    status = 'FAIL'
    app_title_contains = $AppTitleContains
    window = $null
    element_count = 0
    checks = @()
    capture = $null
    output_json_path = $null
    duration_ms = $null
    error = $null
}

function Invoke-Eyes([string[]]$Arguments) {
    $output = & $EyesBin @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "eyes failed: $($Arguments -join ' ') exit=$LASTEXITCODE output=$($output -join [Environment]::NewLine)"
    }
    return @($output | ForEach-Object { [string]$_ })
}

function Add-Check([System.Collections.Generic.List[object]]$Checks, [string]$Kind, [string]$Expected, [string]$Actual, [bool]$Passed, [string]$Message, $Evidence) {
    $Checks.Add([pscustomobject]@{
        kind = $Kind
        expected = $Expected
        actual = $Actual
        status = if ($Passed) { 'PASS' } else { 'FAIL' }
        message = $Message
        evidence = $Evidence
    })
}


function Test-State([object]$Element) {
    return Test-ElementState -Element $Element -AllowInvisible:$AllowInvisible -AllowDisabled:$AllowDisabled
}

try {
    $eyesCommand = Get-Command -Name $EyesBin -ErrorAction Stop
    $result.eyes_bin = $eyesCommand.Source
    $windowOutput = Invoke-Eyes @('windows', 'list', '--title-contains', $AppTitleContains)
    $windows = @(($windowOutput -join [Environment]::NewLine) | ConvertFrom-Json)
    if ($WindowIndex -ge $windows.Count) {
        throw "window not found: '$AppTitleContains' candidates=$($windows.Count) requested=$WindowIndex"
    }

    $window = $windows[$WindowIndex]
    if ($null -eq $window.hwnd) { throw 'selected window has no hwnd' }
    $result.window = $window

    $checks = New-Object System.Collections.Generic.List[object]
    $titlePassed = ([string]$window.title).IndexOf($AppTitleContains, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    Add-Check $checks 'window_title_contains' $AppTitleContains ([string]$window.title) $titlePassed 'Window title must contain the expected text.' $null

    $detectOutput = Invoke-Eyes @('detect', '--window', [string]$window.hwnd, '--max', [string]$MaxElements)
    $elements = @(($detectOutput -join [Environment]::NewLine) | ConvertFrom-Json)
    $result.element_count = $elements.Count
    Add-Check $checks 'minimum_element_count' ([string]$MinimumElementCount) ([string]$elements.Count) ($elements.Count -ge $MinimumElementCount) 'Window must expose enough UIA elements.' $null

    $groups = @(
        @{ kind = 'element_name_contains'; field = 'name'; values = $ExpectedElementNameContains },
        @{ kind = 'element_automation_id'; field = 'automation_id'; values = $ExpectedElementAutomationId },
        @{ kind = 'element_control_type'; field = 'control_type'; values = $ExpectedElementControlType }
    )
    foreach ($group in $groups) {
        foreach ($expected in @($group.values)) {
            if ([string]::IsNullOrWhiteSpace($expected)) { continue }
            $matched = Get-Matches $elements $group.field $expected
            $qualified = @($matched | Where-Object { Test-State $_ })
            $evidence = [ordered]@{
                field = $group.field
                matched_count = $matched.Count
                qualified_count = $qualified.Count
                elements = $qualified
            }
            Add-Check $checks $group.kind $expected "$($group.field) match" ($qualified.Count -gt 0) 'A visible, enabled element must satisfy the selector.' $evidence
        }
    }

    $result.checks = $checks
    if (-not $SkipCapture) {
        $captureDirectory = if ([System.IO.Path]::IsPathRooted($CaptureDirectory)) { $CaptureDirectory } else { Join-Path (Get-Location).Path $CaptureDirectory }
        New-Item -ItemType Directory -Force -Path $captureDirectory | Out-Null
        $capturePath = Join-Path $captureDirectory ("structured-{0}.png" -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
        Invoke-Eyes @('capture', '--window', [string]$window.hwnd, '--out', $capturePath) | Out-Null
        if (-not (Test-Path -LiteralPath $capturePath)) { throw "capture file was not created: $capturePath" }
        $captureFile = Get-Item -LiteralPath $capturePath
        Add-Check $checks 'capture_file' '>0 bytes' "$($captureFile.Length) bytes" ($captureFile.Length -gt 0) 'Capture is evidence only; decisions use structured fields.' $null
        $result.capture = [pscustomobject]@{ path = $captureFile.FullName; bytes = $captureFile.Length }
        $result.checks = $checks
    }

    if (@($checks | Where-Object { $_.status -ne 'PASS' }).Count -eq 0) { $result.status = 'PASS' }
}
catch {
    $result.error = $_.Exception.Message
}
finally {
    $result.duration_ms = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
}

if (-not [string]::IsNullOrWhiteSpace($OutputJsonPath)) {
    $resolvedOutput = if ([System.IO.Path]::IsPathRooted($OutputJsonPath)) { $OutputJsonPath } else { Join-Path (Get-Location).Path $OutputJsonPath }
    $outputDirectory = Split-Path -Parent $resolvedOutput
    if ($outputDirectory) { New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null }
    $result.output_json_path = $resolvedOutput
}
$json = $result | ConvertTo-Json -Depth 12
if (-not [string]::IsNullOrWhiteSpace($OutputJsonPath)) {
    [System.IO.File]::WriteAllText($resolvedOutput, $json, [System.Text.UTF8Encoding]::new($false))
}

$json
if ($result.status -ne 'PASS') { exit 1 }
