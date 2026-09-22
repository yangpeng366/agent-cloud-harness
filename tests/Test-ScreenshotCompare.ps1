# Test-ScreenshotCompare.ps1
#
# Contract test for scripts/lib/ScreenshotCompare.ps1 Compare-ScreenshotSimilarity.
# Uses temp PNGs created via System.Drawing; no real screenshots needed.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\ScreenshotCompare.ps1'
if (-not (Test-Path -LiteralPath $libPath)) { throw "Lib not found: $libPath" }
. $libPath

Add-Type -AssemblyName System.Drawing

$tmpDir = Join-Path $env:TEMP "screenshot-compare-test"
if (-not (Test-Path $tmpDir)) { New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null }

function New-PngBytes([int]$Width, [int]$Height, [System.Drawing.Color]$Color) {
    $bmp = New-Object System.Drawing.Bitmap($Width, $Height)
    for ($y = 0; $y -lt $Height; $y++) {
        for ($x = 0; $x -lt $Width; $x++) {
            $bmp.SetPixel($x, $y, $Color)
        }
    }
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    return $ms.ToArray()
}

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

# Use raw byte streams instead of PNG (PNG compression is non-deterministic,
# so two encodings of the same bitmap can produce different bytes).
$bytesA = New-Object byte[] 1000
for ($i = 0; $i -lt $bytesA.Length; $i++) { $bytesA[$i] = [byte]128 }
$pathA = Join-Path $tmpDir 'raw-a.bin'
$pathB = Join-Path $tmpDir 'raw-b.bin'
[System.IO.File]::WriteAllBytes($pathA, $bytesA)
[System.IO.File]::WriteAllBytes($pathB, $bytesA)
$identical = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-Eq $null $identical.error 'identical: error null'
Assert-Eq 1.0 $identical.similarity 'identical: similarity 1.0'
Assert-True $identical.matched 'identical: matched true'
Assert-Eq 0 $identical.bytes_over_tolerance 'identical: 0 bytes over tolerance'
Assert-Eq 0 $identical.max_byte_diff 'identical: max_byte_diff 0'

# --- one byte changed (within tolerance) ---
$bytesModified = New-Object byte[] 1000
for ($i = 0; $i -lt $bytesModified.Length; $i++) { $bytesModified[$i] = [byte]128 }
$bytesModified[500] = [byte]135  # diff of 7, well within 32 tolerance
[System.IO.File]::WriteAllBytes($pathB, $bytesModified)
$onePixel = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-Eq $null $onePixel.error 'one pixel: error null'
Assert-True ($onePixel.similarity -ge 0.95) "one byte within tolerance: similarity >= 0.95 (got $($onePixel.similarity))"
Assert-True $onePixel.matched 'one byte within tolerance: still matched'

# --- completely different bytes ---
$bytesAll = New-Object byte[] 1000
for ($i = 0; $i -lt $bytesAll.Length; $i++) { $bytesAll[$i] = [byte]255 }
[System.IO.File]::WriteAllBytes($pathB, $bytesAll)
$different = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-Eq $null $different.error 'completely different: error null'
Assert-True ($different.similarity -lt 0.5) "completely different: similarity low (got $($different.similarity))"
Assert-True (-not $different.matched) 'completely different: not matched'

# --- size mismatch ---
$bytesLarge = New-Object byte[] 2000
for ($i = 0; $i -lt $bytesLarge.Length; $i++) { $bytesLarge[$i] = [byte]128 }
[System.IO.File]::WriteAllBytes($pathA, $bytesLarge)
$sizeMismatch = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-Eq 'size_mismatch' $sizeMismatch.error 'size mismatch: error reported'
Assert-Eq $false $sizeMismatch.matched 'size mismatch: not matched'

# --- missing baseline ---
$missing = Compare-ScreenshotSimilarity -BaselinePath "$tmpDir\nonexistent.png" -CurrentPath $pathB
Assert-Eq 'baseline_not_found' $missing.error 'missing baseline: error reported'
Assert-Eq $false $missing.matched 'missing baseline: not matched'

# --- missing current ---
$missingCur = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath "$tmpDir\nonexistent.png"
Assert-Eq 'current_not_found' $missingCur.error 'missing current: error reported'
Assert-Eq $false $missingCur.matched 'missing current: not matched'

Write-Host ""
Write-Host "=== Test-ScreenshotCompare summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
