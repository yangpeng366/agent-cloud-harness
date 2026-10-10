#requires -Version 7
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$endpoint = Join-Path $root 'scripts\Invoke-ItemJevDecision.ps1'
$fixturesDir = Join-Path $root 'tests\fixtures'
$shadowCfg = Join-Path $fixturesDir 'shadow-off-item.json'
$decCfg = Join-Path $fixturesDir 'jev-decision-on-item.json'
$itemJson = Join-Path $fixturesDir 'item-row.json'
if (-not (Test-Path -LiteralPath $fixturesDir)) { New-Item -ItemType Directory -Force -Path $fixturesDir | Out-Null }
[System.IO.File]::WriteAllText($shadowCfg, '{"enabled":false}', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($decCfg, '{"decision":{"enabled":true,"thresholds":{"low":0.3,"high":0.7}}}', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($itemJson, '{"id":"item-test","title":"demo","repoPath":"D:\\tmp","description":"d","nextStep":"n","priority":"P1","intervalMin":60,"threadId":"","fake":true}', [System.Text.UTF8Encoding]::new($false))

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
$logPath = Join-Path $tmpDir 'decision-item-test.json'

$result = & pwsh -NoProfile -File $endpoint -ItemJsonPath $itemJson -JevShadowConfigPath $shadowCfg -DecisionLogPath $logPath 2>&1 | Out-String
Assert-True ($result -match 'FALLBACK') 'shadow off -> FALLBACK'
Assert-True ($result -match 'shadow_disabled') 'shadow off -> reason shadow_disabled'
Assert-True (Test-Path -LiteralPath $logPath) 'decision log written'

Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "summary pass=$pass fail=$fail"
if ($fail -gt 0) { exit 1 }