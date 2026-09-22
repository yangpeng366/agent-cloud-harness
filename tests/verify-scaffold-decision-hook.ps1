#requires -Version 7
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$scaffoldMain = "D:\gitAll\patrol-scaffold\scripts\patrol-loop.ps1"
$endpoint = Join-Path $root 'scripts\Invoke-ItemJevDecision.ps1'

$pass = 0; $FAIL = 0
function Assert-True {
    param([bool]$Condition,[string]$Label)
    if ($Condition) { Write-Host "PASS $Label"; $script:pass++ }
    else { Write-Host "FAIL $Label"; $script:fail++ }
}

$main = Get-Content -LiteralPath $scaffoldMain -Raw
Assert-True ($main -match 'Invoke-ItemJevDecision.ps1') 'scaffold main loop calls Invoke-ItemJevDecision endpoint'
Assert-True ($main -match 'HUMAN_REVIEW') 'scaffold main loop references HUMAN_REVIEW'
Assert-True ($main -match 'JEV_DECISION_CONFIG_PATH') 'scaffold main loop reads JEV_DECISION_CONFIG_PATH env'
Assert-True ($main -match 'decision.enabled') 'scaffold main loop checks decision.enabled'

$tokens=$null; $errors=$null
[System.Management.Automation.Language.Parser]::ParseFile($endpoint, [ref]$tokens, [ref]$errors) | Out-Null
Assert-True ($errors.Count -eq 0) 'endpoint parses'
$bytes = [System.IO.File]::ReadAllBytes($endpoint)
Assert-True (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) 'endpoint no BOM'

Write-Host "summary pass=$pass fail=$fail"
if ($fail -gt 0) { exit 1 }