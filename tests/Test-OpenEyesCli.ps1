# Test-OpenEyesCli.ps1
#
# Offline contract tests for scripts/lib/OpenEyesCli.ps1.
# Covers: eyes CLI status classification (not_found / ok / module_missing /
# console_encoding / failed), source-root discovery, and --json payload parsing
# (envelope, bare array, single record, NDJSON, ok=false, unparseable table).
# No real eyes process is started except the -SkipProbe environment check.

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\OpenEyesCli.ps1'
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

function Assert-Throws {
    param([scriptblock]$Action, [string]$Label, [string]$ExpectedPattern = '')
    try {
        & $Action | Out-Null
        Write-Host "[FAIL] $Label (expected throw, got success)"
        $script:fail++
    }
    catch {
        if ([string]::IsNullOrWhiteSpace($ExpectedPattern) -or $_.Exception.Message -match $ExpectedPattern) {
            Write-Host "[OK]  $Label"
            $script:pass++
        }
        else {
            Write-Host "[FAIL] $Label (message '$($_.Exception.Message)' does not match '$ExpectedPattern')"
            $script:fail++
        }
    }
}

# --- Resolve-OpenEyesCliStatus -------------------------------------------------

Assert-Eq 'not_found' (Resolve-OpenEyesCliStatus -ExecutableFound $false).status 'missing executable: not_found'

$ok = Resolve-OpenEyesCliStatus -ExecutableFound $true -ProbeExitCode 0
Assert-Eq 'ok' $ok.status 'probe exit 0: ok'
Assert-Eq $null $ok.remediation 'ok carries no remediation'

$missing = Resolve-OpenEyesCliStatus -ExecutableFound $true -ProbeExitCode 1 -ProbeOutput "ModuleNotFoundError: No module named 'openeyes'"
Assert-Eq 'module_missing' $missing.status 'stale editable install: module_missing'
Assert-Eq $true ($missing.remediation -match 'pip install -e') 'module_missing remediation names pip install -e'
Assert-Eq $false ($missing.remediation -match 'PYTHONPATH') 'module_missing without source root: no PYTHONPATH hint'

$missingWithRoot = Resolve-OpenEyesCliStatus -ExecutableFound $true -ProbeExitCode 1 `
    -ProbeOutput "ModuleNotFoundError: No module named 'openeyes'" -SourceRoot 'D:\gitAll\openeyes'
Assert-Eq $true ($missingWithRoot.remediation -match 'PYTHONPATH=D:\\gitAll\\openeyes') 'module_missing with source root: remediation names PYTHONPATH'

$encoding = Resolve-OpenEyesCliStatus -ExecutableFound $true -ProbeExitCode 1 -ProbeOutput "UnicodeEncodeError: 'gbk' codec can't encode character"
Assert-Eq 'console_encoding' $encoding.status 'gbk console crash: console_encoding'
Assert-Eq $true ($encoding.remediation -match 'PYTHONIOENCODING=utf-8') 'console_encoding remediation names PYTHONIOENCODING'

$failed = Resolve-OpenEyesCliStatus -ExecutableFound $true -ProbeExitCode 2 -ProbeOutput 'boom'
Assert-Eq 'failed' $failed.status 'unknown non-zero exit: failed'

# --- Find-OpenEyesSourceRoot ---------------------------------------------------

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ach-openeyes-cli-{0}" -f ([guid]::NewGuid().ToString('N')))
$fakeRepo = Join-Path $tempRoot 'fake-repo'
New-Item -ItemType Directory -Force -Path (Join-Path $fakeRepo 'openeyes') | Out-Null
Set-Content -LiteralPath (Join-Path $fakeRepo 'openeyes\__init__.py') -Value '# fake' -Encoding ASCII
$emptyDir = Join-Path $tempRoot 'empty-repo'
New-Item -ItemType Directory -Force -Path $emptyDir | Out-Null

try {
    $found = Find-OpenEyesSourceRoot -CandidatePaths @($emptyDir, $fakeRepo)
    Assert-Eq $fakeRepo $found 'source root discovery skips dirs without openeyes/__init__.py'

    $notFound = Find-OpenEyesSourceRoot -CandidatePaths @($emptyDir)
    Assert-Eq $true ($null -eq $notFound -or -not ($notFound -like "*$emptyDir*")) 'source root discovery without candidate match: no false positive'

    # --- ConvertFrom-OpenEyesPayload -------------------------------------------

    $windowEnvelope = '{"ok":true,"count":1,"windows":[{"hwnd":42,"title":"Notepad"}]}'
    $windows = @(ConvertFrom-OpenEyesPayload -Text $windowEnvelope -EnvelopeKeys @('windows'))
    Assert-Eq 1 $windows.Count 'envelope: one window unwrapped'
    Assert-Eq 42 $windows[0].hwnd 'envelope: hwnd preserved'

    $elementEnvelope = '{"ok":true,"hwnd":7,"count":1,"elements":[{"name":"OK","control_type":"Button","state":{"enabled":true,"visible":true}}]}'
    $elements = @(ConvertFrom-OpenEyesPayload -Text $elementEnvelope -EnvelopeKeys @('elements'))
    Assert-Eq 'Button' $elements[0].control_type 'envelope: element control_type preserved'
    Assert-Eq $true $elements[0].state.enabled 'envelope: element state preserved'

    $bareArray = '[{"hwnd":1},{"hwnd":2}]'
    Assert-Eq 2 (@(ConvertFrom-OpenEyesPayload -Text $bareArray -EnvelopeKeys @('windows'))).Count 'bare array still accepted (older CLI shape)'

    $singleRecord = '{"hwnd":9,"title":"Solo"}'
    $single = @(ConvertFrom-OpenEyesPayload -Text $singleRecord -EnvelopeKeys @('windows'))
    Assert-Eq 1 $single.Count 'single window record accepted without envelope'

    $ndjson = "{`"hwnd`":1}`n{`"hwnd`":2}`n"
    Assert-Eq 2 (@(ConvertFrom-OpenEyesPayload -Text $ndjson -EnvelopeKeys @('windows'))).Count 'NDJSON lines parsed'

    Assert-Eq 0 (@(ConvertFrom-OpenEyesPayload -Text '' -EnvelopeKeys @('windows'))).Count 'empty output: zero items'
    Assert-Eq 0 (@(ConvertFrom-OpenEyesPayload -Text '{"ok":true,"count":0,"windows":[]}' -EnvelopeKeys @('windows'))).Count 'empty envelope window list: zero items'

    Assert-Throws { ConvertFrom-OpenEyesPayload -Text '{"ok":false,"error":"window gone"}' -EnvelopeKeys @('windows') } 'ok=false raises' 'ok=false'

    $tableText = @"
  hwnd      title
  --------  -----
  42        Notepad
"@
    Assert-Throws { ConvertFrom-OpenEyesPayload -Text $tableText -EnvelopeKeys @('windows') } 'human table output is rejected (--json missing)' 'cannot parse'

    # --- Initialize-OpenEyesCliEnvironment -SkipProbe --------------------------

    $env:PYTHONIOENCODING = $null
    $envInit = Initialize-OpenEyesCliEnvironment -SkipProbe
    Assert-Eq 'utf-8' $envInit.pythonioencoding 'Initialize sets PYTHONIOENCODING=utf-8'
    Assert-Eq $false $envInit.recovered 'SkipProbe: no recovery attempted'
    Assert-Eq $true (@('ok', 'not_found') -contains $envInit.status) 'SkipProbe: status is ok or not_found'
}
finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}

Write-Host ""
Write-Host "=== Test-OpenEyesCli summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
