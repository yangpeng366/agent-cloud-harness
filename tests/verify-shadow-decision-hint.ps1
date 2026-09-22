#requires -Version 7
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$libPath = Join-Path $root "scripts\lib\JevShadow.ps1"
. $libPath

$pass = 0; $fail = 0
function Assert-True {
    param([bool]$Condition,[string]$Label)
    if($Condition){ Write-Host "PASS $Label"; $script:pass++ }
    else { Write-Host "FAIL $Label"; $script:fail++ }
}

$h1 = Get-PatrolJevShadowDecisionHint -Probability 0.2 -Error ""
Assert-True ($h1.action -eq "KEEP_VERBATIM" -and $h1.reason -eq "low_band") "low band -> KEEP_VERBATIM"

$h2 = Get-PatrolJevShadowDecisionHint -Probability 0.85 -Error ""
Assert-True ($h2.action -eq "TRUNCATE_HEAD" -and $h2.reason -eq "high_band") "high band -> TRUNCATE_HEAD"

$h3 = Get-PatrolJevShadowDecisionHint -Probability 0.5 -Error ""
Assert-True ($h3.action -eq "HUMAN_REVIEW" -and $h3.reason -eq "uncertainty_band") "uncertainty band -> HUMAN_REVIEW"

$h4 = Get-PatrolJevShadowDecisionHint -Probability 0.5 -Error "api_key_missing"
Assert-True ($h4.action -eq "FALLBACK" -and $h4.reason -eq "jev_error") "error -> FALLBACK"

$h5 = Get-PatrolJevShadowDecisionHint -Probability 0.0 -Error ""
Assert-True ($h5.action -eq "FALLBACK" -and $h5.reason -eq "no_probability") "zero prob -> FALLBACK"

Write-Host ("summary pass={0} fail={1}" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
