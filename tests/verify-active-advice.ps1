#requires -Version 7
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$digestLib = Join-Path $root 'scripts\lib\JevShadowDigest.ps1'
$run = Join-Path $root 'scripts\Run-BuildJevShadowDigest.ps1'
. $digestLib

$pass = 0; $fail = 0
function Assert-True {
    param([bool]$Condition,[string]$Label)
    if($Condition){ Write-Host "PASS $Label"; $script:pass++ }
    else { Write-Host "FAIL $Label"; $script:fail++ }
}

# 1) active advice function exists with sane defaults
$advice = Get-PatrolJevShadowActiveAdvice -ThresholdLow 0.30 -ThresholdHigh 0.70 -KeptFloor 0.50
Assert-True ($advice.threshold_low -eq 0.30) 'advice.threshold_low = 0.30'
Assert-True ($advice.threshold_high -eq 0.70) 'advice.threshold_high = 0.70'
Assert-True ($advice.kept_floor -eq 0.50) 'advice.kept_floor = 0.50'
Assert-True ($advice.explanation -match 'uncertainty band') 'advice explains uncertainty band'

# 2) Run-BuildJevShadowDigest writes advice JSON
$tmpDir = Join-Path $env:TEMP ('digest-advice-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmpDir | Out-Null
$shadowDir = Join-Path $tmpDir 'shadow'
New-Item -ItemType Directory -Path $shadowDir | Out-Null
# Empty shadow dir still triggers advice file
& pwsh -NoProfile -File $run -ShadowDir $shadowDir -OutputDirectory $tmpDir -BaseDir $root | Out-Null
$adviceJson = @(Get-ChildItem -LiteralPath $tmpDir -Filter 'jev-shadow-active-advice-*.json' -File)
Assert-True ($adviceJson.Count -gt 0) 'advice JSON written'
if ($adviceJson.Count -gt 0) {
    $obj = Get-Content -LiteralPath $adviceJson[0].FullName -Raw | ConvertFrom-Json
    Assert-True ($obj.threshold_low -eq 0.30 -and $obj.threshold_high -eq 0.70) 'advice JSON carries thresholds'
    Assert-True ($obj.explanation.Length -gt 0) 'advice JSON has explanation'
}

Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "summary pass=$pass fail=$fail"
if ($fail -gt 0) { exit 1 }