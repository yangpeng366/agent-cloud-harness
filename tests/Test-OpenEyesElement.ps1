# Test-OpenEyesElement.ps1
#
# Contract test for scripts/lib/OpenEyesElement.ps1.
# Covers Get-Matches (substring match on element fields) and
# Test-ElementState (visibility/enabled gate).

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\OpenEyesElement.ps1'
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

# Get-Matches: empty input
Assert-Eq 0 (Get-Matches -Elements @() -Field 'name' -Expected 'foo').Count 'empty input -> 0 matches'

# Get-Matches: simple name match (case insensitive)
$elements = @(
    [pscustomobject]@{ name = 'Submit Button'; automation_id = 'btnSubmit'; state = [pscustomobject]@{ visible = $true; enabled = $true } }
    [pscustomobject]@{ name = 'Cancel Button'; automation_id = 'btnCancel'; state = [pscustomobject]@{ visible = $true; enabled = $true } }
)
$byName = Get-Matches -Elements $elements -Field 'name' -Expected 'submit'
Assert-Eq 1 $byName.Count 'one match by name (case insensitive)'
Assert-Eq 'btnSubmit' $byName[0].automation_id 'matched element has correct automation_id'

# Get-Matches: multiple matches
$byName2 = Get-Matches -Elements $elements -Field 'name' -Expected 'Button'
Assert-Eq 2 $byName2.Count 'two matches by partial name'

# Get-Matches: no match
$noMatch = Get-Matches -Elements $elements -Field 'name' -Expected 'xyz'
Assert-Eq 0 $noMatch.Count 'no matches returns empty'

# Get-Matches: matches by automation_id
$byAid = Get-Matches -Elements $elements -Field 'automation_id' -Expected 'btnCancel'
Assert-Eq 1 $byAid.Count 'match by automation_id'
Assert-Eq 'Cancel Button' $byAid[0].name 'matched element has correct name'

# Get-Matches: null field treated as no match (not exception)
$withNull = Get-Matches -Elements (@([pscustomobject]@{ name = $null }) + $elements) -Field 'name' -Expected 'submit'
Assert-Eq 1 $withNull.Count 'null field entries are skipped, not matched'

# Get-Matches: substring matching (not whole word)
$substr = Get-Matches -Elements $elements -Field 'name' -Expected 'ubmi'
Assert-Eq 1 $substr.Count 'substring match (not whole word)'

# Test-ElementState: default (must be visible + enabled)
$good = [pscustomobject]@{ state = [pscustomobject]@{ visible = $true; enabled = $true } }
Assert-True (Test-ElementState -Element $good) 'visible + enabled -> true'

$hidden = [pscustomobject]@{ state = [pscustomobject]@{ visible = $false; enabled = $true } }
Assert-True (-not (Test-ElementState -Element $hidden)) 'invisible hidden by default'

$disabled = [pscustomobject]@{ state = [pscustomobject]@{ visible = $true; enabled = $false } }
Assert-True (-not (Test-ElementState -Element $disabled)) 'disabled blocked by default'

# Test-ElementState: allow toggles
Assert-True (Test-ElementState -Element $hidden -AllowInvisible) 'invisible + AllowInvisible -> true'
Assert-True (-not (Test-ElementState -Element $disabled -AllowInvisible)) 'AllowInvisible does not allow disabled'
Assert-True (Test-ElementState -Element $disabled -AllowDisabled) 'disabled + AllowDisabled -> true'
Assert-True (Test-ElementState -Element $hidden -AllowInvisible -AllowDisabled) 'both Allow toggles accept any'

# Test-ElementState: null element
Assert-True (-not (Test-ElementState -Element $null)) 'null element -> false'

# Test-ElementState: element with no state field
$noState = [pscustomobject]@{ name = 'X' }
Assert-True (-not (Test-ElementState -Element $noState)) 'element without state -> false'

# Test-ElementState: visible+enabled default
Assert-True (Test-ElementState -Element $good -AllowInvisible -AllowDisabled) 'visible+enabled always passes with any allow toggles'

Write-Host ""
Write-Host "=== Test-OpenEyesElement summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
