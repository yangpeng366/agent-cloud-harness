# Run-JevPatrolL3Tests.ps1
#
# Test runner for Jev-Patrol-L3 + OpenEyes contract tests. Runs all suites,
# aggregates pass/fail counts, exits 0 only if every suite passes.
#
# Suites emit "pass=N fail=N" lines; e2e suite emits exit code only.
#
# Usage:
#   pwsh -File tests/Run-JevPatrolL3Tests.ps1

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')

$suites = @(
    @{ Path = 'tests\Test-JevPatrolL3Extract.ps1'; Name = 'extract'; EmitSummary = $true },
    @{ Path = 'tests\Test-JevPatrolL3Markdown.ps1'; Name = 'render'; EmitSummary = $true },
    @{ Path = 'tests\Test-OpenEyesDelivery.ps1'; Name = 'openeyes-delivery'; EmitSummary = $true },
    @{ Path = 'tests\Test-OpenEyesElement.ps1'; Name = 'openeyes-element'; EmitSummary = $true },
    @{ Path = 'tests\Test-ScreenshotCompare.ps1'; Name = 'screenshot-compare'; EmitSummary = $true },
    @{ Path = 'tests\Test-BaselineScreenshotStub.ps1'; Name = 'baseline-screenshot-stub'; EmitSummary = $true },
    @{ Path = 'tests\Test-BaselinePng.ps1'; Name = 'baseline-png'; EmitSummary = $true },
    @{ Path = 'tests\Run-JevPatrolL3Pipeline.ps1'; Name = 'e2e-pipeline'; EmitSummary = $false }
    @{ Path = 'tests\phase25_smoke.ps1'; Name = 'phase25-smoke'; EmitSummary = $false }
    @{ Path = 'tests\Test-RunFlakyComparison.ps1'; Name = 'flaky-comparison'; EmitSummary = $true }
    @{ Path = 'tests\Test-JevApi.ps1'; Name = 'jev-api'; EmitSummary = $true }
    @{ Path = 'tests\Test-JevPatrolL3Roundtrip.ps1'; Name = 'jev-roundtrip'; EmitSummary = $true }
    @{ Path = 'tests\Test-JevHeuristicComparison.ps1'; Name = 'jev-heuristic-comparison'; EmitSummary = $true }
)

$totalPass = 0
$totalFail = 0
$failedSuites = @()

foreach ($suite in $suites) {
    $suitePath = Join-Path $root $suite.Path
    if (-not (Test-Path -LiteralPath $suitePath)) {
        Write-Host "[SKIP] $($suite.Name): not found at $suitePath"
        continue
    }
    Write-Host ""
    Write-Host "=== Running $($suite.Name) suite: $($suite.Path) ==="
    $output = (& pwsh -NoProfile -File $suitePath 2>&1) | Out-String
    Write-Host $output
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $failedSuites += $suite.Name
        Write-Host "[FAIL] $($suite.Name) suite exited with $exitCode"
    }
    if ($suite.EmitSummary -and $output -match 'pass=(\d+) fail=(\d+)') {
        $totalPass += [int]$Matches[1]
        $totalFail += [int]$Matches[2]
    } elseif (-not $suite.EmitSummary) {
        if ($exitCode -eq 0) { $totalPass++ } else { $totalFail++ }
    }
}

Write-Host ""
Write-Host "=== Aggregate summary ==="
Write-Host "Suites: $($suites.Count) attempted, $($failedSuites.Count) failed"
Write-Host "Total: pass=$totalPass fail=$totalFail"
if ($failedSuites.Count -gt 0 -or $totalFail -gt 0) {
    Write-Host "Failed suites: $($failedSuites -join ', ')"
    exit 1
}
exit 0


 += @{ Path = 'tests\\phase25_smoke.ps1'; Name = 'phase25-smoke'; EmitSummary = False }
