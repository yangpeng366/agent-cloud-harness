# Test-BaselinePng.ps1
#
# Contract test for scripts/lib/BaselinePng.ps1.
# Covers Save-BaselinePng and Get-BaselinePng pure functions.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\BaselinePng.ps1'
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

# Setup: temp directory with a source file
$tmpDir = Join-Path $env:TEMP "baseline-png-test"
if (Test-Path $tmpDir) { Remove-Item -Recurse -Force $tmpDir }
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

$sourcePath = Join-Path $tmpDir 'source.png'
$baselinePath = Join-Path $tmpDir 'sub/nested/baseline.png'
$sourceBytes = [byte[]](1..100 | ForEach-Object { [byte]$_ })
[System.IO.File]::WriteAllBytes($sourcePath, $sourceBytes)

# --- Save-BaselinePng: happy path ---
$saveResult = Save-BaselinePng -SourcePath $sourcePath -BaselinePath $baselinePath
Assert-Eq $null $saveResult.error 'happy path: no error'
Assert-True $saveResult.saved 'happy path: saved'
Assert-Eq 100 $saveResult.baseline_size 'happy path: baseline_size correct'
Assert-True (Test-Path -LiteralPath $baselinePath) 'happy path: file exists at destination'
Assert-True ($null -eq (Compare-Object $sourceBytes ([System.IO.File]::ReadAllBytes($baselinePath))) -or ((Compare-Object $sourceBytes ([System.IO.File]::ReadAllBytes($baselinePath)) | Measure-Object).Count -eq 0)) 'happy path: bytes match source'

# --- Save-BaselinePng: already exists (no overwrite) ---
$saveResult2 = Save-BaselinePng -SourcePath $sourcePath -BaselinePath $baselinePath
Assert-Eq 'already_exists' $saveResult2.error 'no-overwrite: error reported'
Assert-Eq $false $saveResult2.saved 'no-overwrite: saved = false'

# --- Save-BaselinePng: overwrite ---
$newBytes = [byte[]](1..50 | ForEach-Object { [byte]$_ })
[System.IO.File]::WriteAllBytes($sourcePath, $newBytes)
$saveResult3 = Save-BaselinePng -SourcePath $sourcePath -BaselinePath $baselinePath -Overwrite
Assert-Eq $null $saveResult3.error 'overwrite: no error'
Assert-True $saveResult3.saved 'overwrite: saved'
Assert-Eq 50 $saveResult3.baseline_size 'overwrite: size updated'

# --- Save-BaselinePng: source missing ---
$missingSource = Join-Path $tmpDir 'nonexistent.png'
$saveMissing = Save-BaselinePng -SourcePath $missingSource -BaselinePath (Join-Path $tmpDir 'out.png')
Assert-Eq 'source_not_found' $saveMissing.error 'source missing: error'
Assert-Eq $false $saveMissing.saved 'source missing: not saved'

# --- Get-BaselinePng: existing file ---
$getResult = Get-BaselinePng -BaselinePath $baselinePath
Assert-True $getResult.exists 'get: exists'
Assert-Eq 50 $getResult.size 'get: size correct'
Assert-Eq $baselinePath $getResult.path 'get: path matches'
Assert-True ($getResult.age_seconds -ge 0) 'get: age_seconds is non-negative'
Assert-True ($getResult.last_modified -match '^\d{4}-\d{2}-\d{2}T') 'get: last_modified ISO 8601'

# --- Get-BaselinePng: missing file ---
$missingPath = Join-Path $tmpDir 'nonexistent.png'
$getMissing = Get-BaselinePng -BaselinePath $missingPath
Assert-Eq $false $getMissing.exists 'get missing: not exists'
Assert-Eq 0 $getMissing.size 'get missing: size 0'
Assert-Eq 0 $getMissing.age_seconds 'get missing: age 0'

Write-Host ''
Write-Host "=== Test-BaselinePng summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
