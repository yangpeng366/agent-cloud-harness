#requires -Version 7
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$feishuMain = 'D:\gitAll\patrols\feishu-projects-patrol\Start-FeishuProjectsWorkLoop.ps1'
$endpoint = Join-Path $root 'scripts\Invoke-ProjectJevDecision.ps1'

$pass = 0; $fail = 0
function Assert-True {
    param([bool]$Condition,[string]$Label)
    if ($Condition) { Write-Host "PASS $Label"; $script:pass++ }
    else { Write-Host "FAIL $Label"; $script:fail++ }
}

# Confirm the hook wiring exists in feishu main loop: it must call the endpoint
$main = Get-Content -LiteralPath $feishuMain -Raw
Assert-True ($main -match 'Invoke-ProjectJevDecision.ps1') 'feishu main loop calls Invoke-ProjectJevDecision endpoint'
Assert-True ($main -match 'HUMAN_REVIEW.*skipping codex dispatch') 'feishu main loop logs HUMAN_REVIEW skip'
Assert-True ($main -match 'jev-shadow-config') 'feishu main loop passes jev-shadow-config'

# Confirm endpoint contract
$tokens=$null; $errors=$null
[System.Management.Automation.Language.Parser]::ParseFile($endpoint, [ref]$tokens, [ref]$errors) | Out-Null
Assert-True ($errors.Count -eq 0) 'endpoint parses'
$bytes = [System.IO.File]::ReadAllBytes($endpoint)
Assert-True (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) 'endpoint no BOM'

Write-Host "summary pass=$pass fail=$fail"
if ($fail -gt 0) { exit 1 }