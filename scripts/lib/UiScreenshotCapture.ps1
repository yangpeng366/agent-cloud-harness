# UiScreenshotCapture.ps1
#
# Capture helper for the FEAT-05 screenshot_diff channel.
#
# Locates a top-level window through the OpenEyes CLI (`eyes windows list --json`)
# and writes the window PNG with `eyes capture --window <hwnd> --out <png>`.
# Never throws: callers decide whether a missing capture is a FAIL report.
#
# All CLI plumbing (PATH / PYTHONPATH / encoding / timeout) is delegated to
# scripts/lib/OpenEyesCli.ps1 so the screenshot channel shares the same
# contract-drift adapters as the structured assertion channel.

function New-UiWindowScreenshot {
    param(
        [Parameter(Mandatory = $true)][string]$OutPath,
        [string]$AppTitleContains = '',
        [int]$WindowIndex = 0,
        [string]$EyesBin = 'eyes',
        [int]$TimeoutSec = 60,
        [string[]]$SourceRootCandidates = @(),
        [switch]$SkipCliEnvSetup
    )

    $startedAt = [DateTimeOffset]::UtcNow
    $result = [ordered]@{
        ok = $false
        error = $null
        out_path = $OutPath
        bytes = 0
        hwnd = $null
        window_title = $null
        eyes_cli = $null
        duration_ms = $null
    }

    try {
        $openEyesLib = Join-Path $PSScriptRoot 'OpenEyesCli.ps1'
        if (-not (Get-Command Invoke-OpenEyesJson -ErrorAction SilentlyContinue)) {
            if (-not (Test-Path -LiteralPath $openEyesLib)) { throw "OpenEyesCli.ps1 not found: $openEyesLib" }
            . $openEyesLib
        }

        if (-not $SkipCliEnvSetup) {
            $cli = Initialize-OpenEyesCliEnvironment -EyesBin $EyesBin -SourceRootCandidates $SourceRootCandidates -TimeoutSec $TimeoutSec
            $result.eyes_cli = $cli
            if ($cli.status -ne 'ok') {
                throw "eyes CLI unavailable ($($cli.status)): $($cli.reason). remediation: $($cli.remediation)"
            }
            $EyesBin = $cli.executable
        }

        if ([string]::IsNullOrWhiteSpace($AppTitleContains)) { throw 'capture_target_unavailable: AppTitleContains is empty, cannot locate a window' }

        $outputDirectory = Split-Path -Parent $OutPath
        if ($outputDirectory -and -not (Test-Path -LiteralPath $outputDirectory)) {
            New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
        }
        # A stale current PNG would silently satisfy the comparison below.
        if (Test-Path -LiteralPath $OutPath) { Remove-Item -LiteralPath $OutPath -Force }

        $windows = Invoke-OpenEyesJson -EyesBin $EyesBin -Arguments @('windows', 'list', '--json', '--title-contains', $AppTitleContains) -EnvelopeKeys @('windows') -TimeoutSec $TimeoutSec
        $windowList = @($windows.items)
        if ($windowList.Count -eq 0) { throw "no window matched title-contains '$AppTitleContains'" }
        if ($WindowIndex -lt 0 -or $WindowIndex -ge $windowList.Count) {
            throw "window index $WindowIndex out of range (matched=$($windowList.Count))"
        }

        $window = $windowList[$WindowIndex]
        if ($null -eq $window.hwnd) { throw 'selected window has no hwnd' }
        $result.hwnd = $window.hwnd
        $result.window_title = [string]$window.title

        $run = Invoke-OpenEyesCliProcess -EyesBin $EyesBin -Arguments @('capture', '--window', [string]$window.hwnd, '--out', $OutPath) -TimeoutSec $TimeoutSec
        if ($run.exit_code -ne 0) {
            throw "eyes capture failed exit=$($run.exit_code) output=$($run.output)$($run.stderr)"
        }
        if (-not (Test-Path -LiteralPath $OutPath)) { throw "capture file was not created: $OutPath" }

        $info = Get-Item -LiteralPath $OutPath
        if ($info.Length -le 0) { throw "capture file is empty: $OutPath" }

        $result.ok = $true
        $result.bytes = $info.Length
    }
    catch {
        $result.ok = $false
        $result.error = $_.Exception.Message
    }
    finally {
        $result.duration_ms = [int]([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds
    }

    return [pscustomobject]$result
}
