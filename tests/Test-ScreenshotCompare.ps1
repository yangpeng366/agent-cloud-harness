# Test-ScreenshotCompare.ps1
#
# Contract test for scripts/lib/ScreenshotCompare.ps1 Compare-ScreenshotSimilarity.
# Fixtures are real PNGs (decoded with System.Drawing) because the comparator works
# on pixels: comparing encoded PNG bytes made every content change look like a
# `size_mismatch` failure even when the window geometry never changed.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\ScreenshotCompare.ps1'
if (-not (Test-Path -LiteralPath $libPath)) { throw "Lib not found: $libPath" }
. $libPath

Add-Type -AssemblyName System.Drawing

$tmpDir = Join-Path $env:TEMP "screenshot-compare-test"
if (-not (Test-Path $tmpDir)) { New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null }

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

function New-SolidPng {
    param([string]$Path, [int]$Width, [int]$Height, [System.Drawing.Color]$Color)
    $bmp = New-Object System.Drawing.Bitmap($Width, $Height)
    $graphics = [System.Drawing.Graphics]::FromImage($bmp)
    $graphics.Clear($Color)
    $graphics.Dispose()
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    return $Path
}

function New-PngWithPatch {
    param(
        [string]$Path,
        [int]$Width,
        [int]$Height,
        [System.Drawing.Color]$BaseColor,
        [System.Drawing.Color]$PatchColor,
        [int]$PatchX,
        [int]$PatchY,
        [int]$PatchSize
    )
    $bmp = New-Object System.Drawing.Bitmap($Width, $Height)
    $graphics = [System.Drawing.Graphics]::FromImage($bmp)
    $graphics.Clear($BaseColor)
    $brush = New-Object System.Drawing.SolidBrush($PatchColor)
    $graphics.FillRectangle($brush, $PatchX, $PatchY, $PatchSize, $PatchSize)
    $brush.Dispose()
    $graphics.Dispose()
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    return $Path
}

$white = [System.Drawing.Color]::FromArgb(255, 255, 255, 255)
$black = [System.Drawing.Color]::FromArgb(255, 0, 0, 0)
$nearWhite = [System.Drawing.Color]::FromArgb(255, 248, 248, 248)

$pathA = Join-Path $tmpDir 'compare-a.png'
$pathB = Join-Path $tmpDir 'compare-b.png'

# --- identical pixels ---
New-SolidPng -Path $pathA -Width 120 -Height 90 -Color $white | Out-Null
New-SolidPng -Path $pathB -Width 120 -Height 90 -Color $white | Out-Null
$identical = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-Eq $null $identical.error 'identical: error null'
Assert-Eq 1.0 $identical.similarity 'identical: similarity 1.0'
Assert-True $identical.matched 'identical: matched true'
Assert-Eq 0 $identical.differing_pixels 'identical: 0 differing pixels'
Assert-Eq 120 $identical.width 'identical: width reported'

# --- same geometry, different content length: the old byte-length gate failed here ---
New-PngWithPatch -Path $pathB -Width 120 -Height 90 -BaseColor $white -PatchColor $black -PatchX 10 -PatchY 10 -PatchSize 60 | Out-Null
Assert-True ((Get-Item $pathA).Length -ne (Get-Item $pathB).Length) 'different content: encoded sizes differ (precondition)'
$contentChanged = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-Eq $null $contentChanged.error 'different content same geometry: no size_mismatch'
Assert-True ($contentChanged.similarity -lt 1.0) "different content: similarity below 1.0 (got $($contentChanged.similarity))"
Assert-True (-not $contentChanged.matched) 'different content: not matched'

# --- sub-tolerance drift still passes ---
New-SolidPng -Path $pathB -Width 120 -Height 90 -Color $nearWhite | Out-Null
$withinTolerance = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-Eq 1.0 $withinTolerance.similarity 'sub-tolerance drift: similarity 1.0'
Assert-True $withinTolerance.matched 'sub-tolerance drift: matched'

# --- small occlusion (background sliver) keeps similarity above threshold ---
New-PngWithPatch -Path $pathB -Width 120 -Height 90 -BaseColor $white -PatchColor $black -PatchX 0 -PatchY 0 -PatchSize 3 | Out-Null
$smallPatch = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-True ($smallPatch.similarity -ge 0.95) "small overlay: similarity >= 0.95 (got $($smallPatch.similarity))"
Assert-True $smallPatch.matched 'small overlay: matched'

# --- geometry mismatch is reported with both dimensions ---
New-SolidPng -Path $pathB -Width 160 -Height 90 -Color $white | Out-Null
$sizeMismatch = Compare-ScreenshotSimilarity -BaselinePath $pathA -CurrentPath $pathB
Assert-Eq 'size_mismatch' $sizeMismatch.error 'geometry mismatch: error reported'
Assert-Eq $false $sizeMismatch.matched 'geometry mismatch: not matched'
Assert-Eq 120 $sizeMismatch.baseline_width 'geometry mismatch: baseline width reported'
Assert-Eq 160 $sizeMismatch.current_width 'geometry mismatch: current width reported'

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