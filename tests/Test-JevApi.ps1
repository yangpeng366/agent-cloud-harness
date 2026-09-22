# Test-JevApi.ps1
#
# Contract test for scripts/lib/JevApi.ps1 Invoke-JevDecision.
# Live mode requires TYPESAFE_API_KEY + JEV_LIVE_TEST=true env var; otherwise
# verifies the function contract via synthetic error/structure checks.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\JevApi.ps1'
if (-not (Test-Path -LiteralPath $libPath)) { throw "Lib not found: $libPath" }
. $libPath

$pass = 0
$fail = 0

function Assert-Eq {
    param($Expected, $Actual, [string]$Label)
    if ($Expected -eq $Actual) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label (expected='$Expected' actual='$Actual')"
        $script:fail++
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Label)
    if ($Condition) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label"
        $script:fail++
    }
}

function Assert-Null {
    param($Value, [string]$Label)
    if ($null -eq $Value) {
        Write-Host "[OK]  $Label"
        $script:pass++
    } else {
        Write-Host "[FAIL] $Label (expected null)"
        $script:fail++
    }
}

# --- missing key fallback ---
$noKeyResult = Invoke-JevDecision -Text 'sample text' -Id 'no_key_test' -ApiKey ''
Assert-Eq 'api_key_missing' $noKeyResult.error 'missing key: error'
Assert-Null $noKeyResult.probability 'missing key: probability null'
Assert-Null $noKeyResult.action 'missing key: action null'
Assert-Null $noKeyResult.duration_ms 'missing key: duration null'

# --- response shape (regardless of HTTP success) ---
$shapeResult = Invoke-JevDecision -Text 'shape test' -Id 'shape_test'
Assert-True ($null -ne $shapeResult.error -or $null -ne $shapeResult.probability) 'shape: returns error or probability'
Assert-True ($shapeResult.duration_ms -ge 0 -or $null -eq $shapeResult.duration_ms) 'shape: duration_ms is non-negative or null'

# --- live test (optional, gated) ---
if ($env:JEV_LIVE_TEST -eq 'true' -and $env:TYPESAFE_API_KEY) {
    Write-Host '[live] running live Jev API call'
    $live = Invoke-JevDecision -Text 'Grounded patch and test evidence are present; criteria=verified build and test.' -Id 'test_live_jet'
    Assert-Null $live.error "live: no error (got '$($live.error)')"
    Assert-True ($live.probability -ge 0 -and $live.probability -le 1) "live: probability in [0,1] (got $($live.probability))"
    Assert-True ($live.duration_ms -gt 0 -and $live.duration_ms -lt 30000) "live: latency sane (got $($live.duration_ms)ms)"
    Assert-True ($live.action -in @('KEEP_VERBATIM', 'TRUNCATE_HEAD')) "live: action is one of two enum values (got $($live.action))"
    Write-Host "[live] action=$($live.action) probability=$($live.probability) latency=$($live.duration_ms)ms"
} else {
    Write-Host '[skip-live] JEV_LIVE_TEST not set; live assertions skipped'
}

Write-Host ''
Write-Host "=== Test-JevApi summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }