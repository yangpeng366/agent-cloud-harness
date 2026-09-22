# Test-OpenEyesDelivery.ps1
#
# Contract test for scripts/lib/OpenEyesDelivery.ps1 Resolve-DeliveryStatus.
# Covers the four delivery_status enum values: DISPATCHED_FOCUSED, FOCUS_MISMATCH,
# FOCUS_CHANGED_DURING_TYPE, DISPATCH_FAILED.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\OpenEyesDelivery.ps1'
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

# DISPATCHED_FOCUSED: eyes exit 0 + focus matches + focus did not change
Assert-Eq 'DISPATCHED_FOCUSED' (Resolve-DeliveryStatus -EyesExitCode 0 -FocusMatchesExpected $true -FocusBefore 'WinA' -FocusAfter 'WinA') 'happy path: DISPATCHED_FOCUSED'

# DISPATCHED_FOCUSED with $null FocusMatchesExpected (no expected title) and focus unchanged
Assert-Eq 'DISPATCHED_FOCUSED' (Resolve-DeliveryStatus -EyesExitCode 0 -FocusMatchesExpected $null -FocusBefore 'WinA' -FocusAfter 'WinA') 'no expected title: still DISPATCHED_FOCUSED'

# DISPATCH_FAILED: eyes exit non-zero
Assert-Eq 'DISPATCH_FAILED' (Resolve-DeliveryStatus -EyesExitCode 1 -FocusMatchesExpected $true -FocusBefore 'WinA' -FocusAfter 'WinA') 'eyes exit 1: DISPATCH_FAILED'
Assert-Eq 'DISPATCH_FAILED' (Resolve-DeliveryStatus -EyesExitCode 2 -FocusMatchesExpected $null -FocusBefore 'WinA' -FocusAfter 'WinA') 'eyes exit 2 with no expected: DISPATCH_FAILED'

# DISPATCH_FAILED takes priority over FOCUS_MISMATCH
Assert-Eq 'DISPATCH_FAILED' (Resolve-DeliveryStatus -EyesExitCode 1 -FocusMatchesExpected $false -FocusBefore 'WinA' -FocusAfter 'WinA') 'exit 1 + mismatch: DISPATCH_FAILED wins'

# DISPATCH_FAILED takes priority over FOCUS_CHANGED_DURING_TYPE
Assert-Eq 'DISPATCH_FAILED' (Resolve-DeliveryStatus -EyesExitCode 1 -FocusMatchesExpected $true -FocusBefore 'WinA' -FocusAfter 'WinB') 'exit 1 + focus change: DISPATCH_FAILED wins'

# FOCUS_MISMATCH: exit 0 + focus doesn't match expected
Assert-Eq 'FOCUS_MISMATCH' (Resolve-DeliveryStatus -EyesExitCode 0 -FocusMatchesExpected $false -FocusBefore 'WinA' -FocusAfter 'WinA') 'focus not matching: FOCUS_MISMATCH'

# FOCUS_MISMATCH takes priority over FOCUS_CHANGED_DURING_TYPE
Assert-Eq 'FOCUS_MISMATCH' (Resolve-DeliveryStatus -EyesExitCode 0 -FocusMatchesExpected $false -FocusBefore 'WinA' -FocusAfter 'WinB') 'mismatch + change: FOCUS_MISMATCH wins'

# FOCUS_CHANGED_DURING_TYPE: exit 0 + focus matched but changed during type
Assert-Eq 'FOCUS_CHANGED_DURING_TYPE' (Resolve-DeliveryStatus -EyesExitCode 0 -FocusMatchesExpected $null -FocusBefore 'WinA' -FocusAfter 'WinB') 'focus changed during type'
Assert-Eq 'FOCUS_CHANGED_DURING_TYPE' (Resolve-DeliveryStatus -EyesExitCode 0 -FocusMatchesExpected $true -FocusBefore 'WinA' -FocusAfter 'WinB') 'matched at start but changed'

Write-Host ""
Write-Host "=== Test-OpenEyesDelivery summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }