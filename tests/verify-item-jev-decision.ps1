#requires -Version 7
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$endpoint = Join-Path $root 'scripts\Invoke-ItemJevDecision.ps1'
$fixturesDir = Join-Path $root 'tests\fixtures'
$shadowCfg = Join-Path $fixturesDir 'shadow-off.json'
$decCfg = Join-Path $fixturesDir 'jev-decision-on.json'
$shadowCfgOn = Join-Path $fixturesDir 'shadow-on.json'
$itemJson = Join-Path $fixturesDir 'item.json'
if (-not (Test-Path -LiteralPath $fixturesDir)) { New-Item -ItemType Directory -Force -Path $fixturesDir | Out-Null }
[System.IO.File]::WriteAllText($shadowCfg, '{"enabled":false}', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($shadowCfgOn, '{"enabled":true,"api_script_path":"D:\\gitAll\\agent-cloud-harness\\scripts\\lib\\JevApi.ps1","timeout_ms":5000}', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($decCfg, '{"decision":{"enabled":true,"thresholds":{"low":0.3,"high":0.7}}}', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($itemJson, '{"id":"item-test","title":"demo","repoPath":"","description":"","nextStep":"","priority":"P1","intervalMin":0,"threadId":null,"fake":false}')

$pass = 0; $fail = 0
function Assert-True {
    param([bool]$Condition,[string]$Label)
    if ($Condition) { Write-Host "PASS $Label"; $script:pass++ }
    else { Write-Host "FAIL $Label"; $script:fail++ }
}

Assert-True (Test-Path -LiteralPath $endpoint) 'endpoint present'
$tokens=$null; $errors=$null
[System.Management.Automation.Language.Parser]::ParseFile($endpoint, [ref]$tokens, [ref]$errors) | Out-Null
Assert-True ($errors.Count -eq 0) 'endpoint parses'
$bytes = [System.IO.File]::ReadAllBytes($endpoint)
Assert-True (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) 'endpoint no BOM'

$tmpDir = Join-Path $env:TEMP ('jev-item-dec-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$logPath = Join-Path $tmpDir 'decision-item.json'

# Case 1: shadow off -> FALLBACK
$result = & pwsh -NoProfile -File $endpoint -ItemJsonPath $itemJson -JevShadowConfigPath $shadowCfg -DecisionLogPath $logPath 2>&1 | Out-String
Assert-True ($result -match 'FALLBACK') 'shadow off -> FALLBACK'
Assert-True ($result -match 'shadow_disabled') 'shadow off -> reason shadow_disabled'

# Case 2: shadow on + decision on -> real decision
$env:TYPESAFE_API_KEY = 'apikey_257c5199ec613084b91af3bfaaa4cf16a29_a09cbb8f92b93e6b33267492e0bf0e1c059a09b0159b18c09ea31ec7f1794dad'
$result2 = & pwsh -NoProfile -File $endpoint -ItemJsonPath $itemJson -JevShadowConfigPath $shadowCfgOn -JevDecisionConfigPath $decCfg -DecisionLogPath $logPath 2>&1 | Out-String
Remove-Item Env:TYPESAFE_API_KEY -ErrorAction SilentlyContinue

$logFiles = @(Get-ChildItem -LiteralPath $tmpDir -Filter 'decision-item.json' -File -ErrorAction SilentlyContinue)
Assert-True ($logFiles.Count -gt 0) 'decision log written'
if ($logFiles.Count -gt 0) {
    $logObj = Get-Content -LiteralPath $logFiles[0].FullName -Raw | ConvertFrom-Json
    Assert-True ($logObj.decision.action -in @('KEEP_VERBATIM','TRUNCATE_HEAD','HUMAN_REVIEW')) 'decision.action is allowed'
    Assert-True ($null -ne $logObj.shadow.probability) 'shadow probability present'
}

Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "summary pass=$pass fail=$fail"
if ($fail -gt 0) { exit 1 }