#requires -Version 7
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$endpoint = Join-Path $root 'scripts\Invoke-ProjectJevDecision.ps1'
$fixturesDir = Join-Path $root 'tests\fixtures'
$shadowCfg = Join-Path $fixturesDir 'shadow-off.json'
$decCfg = Join-Path $fixturesDir 'jev-decision-on.json'
$shadowCfgOn = Join-Path $fixturesDir 'shadow-on.json'
$rowJson = Join-Path $fixturesDir 'project-row.json'
if (-not (Test-Path -LiteralPath $fixturesDir)) { New-Item -ItemType Directory -Force -Path $fixturesDir | Out-Null }
[System.IO.File]::WriteAllText($shadowCfg, '{"enabled":false}', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($shadowCfgOn, '{"enabled":true,"api_script_path":"D:\\gitAll\\agent-cloud-harness\\scripts\\lib\\JevApi.ps1","timeout_ms":5000}', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($decCfg, '{"decision":{"enabled":true,"thresholds":{"low":0.3,"high":0.7}}}', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($rowJson, '{"RecordId":"rec-test","Fields":{"项目名":"demo"}}', [System.Text.UTF8Encoding]::new($false))

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

$tmpDir = Join-Path $env:TEMP ('jev-dec-endpoint-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$logPath = Join-Path $tmpDir 'decision-rec-test.json'

# Case 1: shadow disabled -> FALLBACK
$result = & pwsh -NoProfile -File $endpoint -ProjectRowJsonPath $rowJson -JevShadowConfigPath $shadowCfg -DecisionLogPath $logPath 2>&1 | Out-String
Assert-True ($result -match 'FALLBACK') 'shadow off -> FALLBACK'
Assert-True ($result -match 'shadow_disabled') 'shadow off -> reason shadow_disabled'
Assert-True (Test-Path -LiteralPath $logPath) 'decision log written (shadow off)'

# Case 2: optional live shadow (only if local secret key present)
$keyPath = Join-Path $env:USERPROFILE '.openclaw\secrets\typesafe.key'
$liveKey = $null
if (Test-Path -LiteralPath $keyPath) {
    $liveKey = ([System.IO.File]::ReadAllText($keyPath)).Trim()
}
if ([string]::IsNullOrWhiteSpace($liveKey)) {
    Write-Host 'SKIP live shadow (TYPESAFE key file missing)'
} else {
    $env:TYPESAFE_API_KEY = $liveKey
    $liveLog = Join-Path $tmpDir 'decision-rec-test-live.json'
    $null = & pwsh -NoProfile -File $endpoint -ProjectRowJsonPath $rowJson -JevShadowConfigPath $shadowCfgOn -JevDecisionConfigPath $decCfg -DecisionLogPath $liveLog 2>&1 | Out-String
    Remove-Item Env:TYPESAFE_API_KEY -ErrorAction SilentlyContinue
    Assert-True (Test-Path -LiteralPath $liveLog) 'decision log written (live)'
    if (Test-Path -LiteralPath $liveLog) {
        $logObj = Get-Content -LiteralPath $liveLog -Raw | ConvertFrom-Json
        Assert-True ($logObj.decision.action -in @('KEEP_VERBATIM','TRUNCATE_HEAD','HUMAN_REVIEW','FALLBACK')) 'decision.action is allowed'
        $hasScore = ($null -ne $logObj.shadow.probability) -or (-not [string]::IsNullOrWhiteSpace([string]$logObj.shadow.error))
        Assert-True $hasScore 'shadow probability or error present'
    }
}

Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "summary pass=$pass fail=$fail"
if ($fail -gt 0) { exit 1 }