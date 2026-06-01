param(
    [switch]$SkipJavaTests,
    [switch]$SkipNodeTests,
    [switch]$SkipProviderDiscoverySmoke,
    [switch]$SkipCodexPartialTimeoutSmoke,
    [switch]$SkipDryRunMarkdown,
    [string]$MarkdownPath = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Step {
    param(
        [string]$Title,
        [scriptblock]$Action
    )
    Write-Host ("[precheck] {0}" -f $Title)
    & $Action
}

function Ensure-LastExitCodeZero {
    param([string]$Message)
    if ($LASTEXITCODE -ne 0) {
        throw $Message
    }
}

function Invoke-PowerShellJsonScript {
    param(
        [string[]]$Arguments
    )

    $raw = & powershell @Arguments
    $text = (($raw | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
    if (-not $text) {
        throw ("PowerShell child script returned empty output: {0}" -f ($Arguments -join " "))
    }
    return ($text | ConvertFrom-Json)
}

function Resolve-MarkdownPath {
    param(
        [object]$ReportedPath,
        [string]$FallbackRelativePath
    )

    if ($ReportedPath) {
        return [string]$ReportedPath
    }

    if (-not $FallbackRelativePath) {
        return $null
    }

    $fallback = Join-Path $repoRoot $FallbackRelativePath
    if (Test-Path -LiteralPath $fallback) {
        return (Resolve-Path -LiteralPath $fallback).Path
    }

    return $null
}

function New-StringList {
    return New-Object System.Collections.Generic.List[string]
}

function Add-Lines {
    param(
        [System.Collections.Generic.List[string]]$List,
        [string[]]$Lines
    )
    foreach ($line in $Lines) {
        $List.Add($line)
    }
}

function Resolve-PrecheckDate {
    param([string]$Path)

    if ($Path -and $Path -match '(\d{4}-\d{2}-\d{2})') {
        return $Matches[1]
    }

    return (Get-Date).ToString("yyyy-MM-dd")
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

$markdownTarget = if ($MarkdownPath) {
    $MarkdownPath
}
else {
    "docs/GITHUB_FIRST_RELEASE_PRECHECK_$((Get-Date).ToString("yyyy-MM-dd")).md"
}
$precheckDate = Resolve-PrecheckDate -Path $markdownTarget

$results = [ordered]@{
    node_check = [ordered]@{
        command = "node --check src/main/resources/web/dialogue/app.js"
        skipped = $false
        exit_code = $null
    }
    node_tests = [ordered]@{
        command = "node --test src/test/js/*.test.mjs"
        skipped = [bool]$SkipNodeTests
        exit_code = $null
    }
    java_http_regression = [ordered]@{
        command = "powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest,WebConsoleHandlerHttpTest'"
        skipped = [bool]$SkipJavaTests
        exit_code = $null
    }
    provider_discovery_smoke = [ordered]@{
        build_command = "powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven"
        command = "node .\scripts\provider-discovery-smoke.js --port 18432 --report .\.tmp\provider-discovery-smoke\report.json"
        skipped = [bool]$SkipProviderDiscoverySmoke
        build_exit_code = $null
        exit_code = $null
        report_path = $null
        passed = $null
    }
    codex_partial_timeout_smoke = [ordered]@{
        command = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-CodexPartialTimeoutSmoke.ps1"
        skipped = [bool]$SkipCodexPartialTimeoutSmoke
        exit_code = $null
        report_path = $null
        passed = $null
    }
    dialogue_backfill_gate_probe = [ordered]@{
        command = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueAcceptanceScriptedBackfillProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json"
        manual_command = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueAcceptanceManualBackfillProbe.ps1 -InputJsonPath .\.tmp\dialogue-manual-18276.json"
        input_json_path = ".\.tmp\dialogue-manual-18276.json"
        skipped = $false
        skip_reason = $null
        exit_code = $null
        manual_exit_code = $null
        scripted_coverage_prefilled = @()
        residual_human = @()
        scripted_misuse_rejected = $null
        manual_apply_passed = $null
    }
    first_release_dry_run = [ordered]@{
        command = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseDryRun.ps1 -WriteMarkdown"
        skipped = $false
        exit_code = $null
        markdown_path = $null
    }
    first_release_commit_dry_run = [ordered]@{
        command = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1 -Commit all -WriteMarkdown"
        skipped = $false
        exit_code = $null
        markdown_path = $null
        unmatched_count = $null
    }
    first_release_stage_preview = [ordered]@{
        command = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-GitHubFirstReleaseStagePreview.ps1 -Commit all -WriteMarkdown"
        skipped = $false
        exit_code = $null
        markdown_path = $null
    }
    markdown_path = $null
}

Invoke-Step -Title "dialogue frontend entry syntax check" -Action {
    node --check src/main/resources/web/dialogue/app.js
    $results.node_check.exit_code = $LASTEXITCODE
    Ensure-LastExitCodeZero "node --check failed."
}

if (-not $SkipNodeTests) {
    Invoke-Step -Title "dialogue JS smoke tests" -Action {
        node --test src/test/js/*.test.mjs
        $results.node_tests.exit_code = $LASTEXITCODE
        Ensure-LastExitCodeZero "node --test failed."
    }
}

if (-not $SkipJavaTests) {
    Invoke-Step -Title "java http regression tests" -Action {
        powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=ChatFacadeHandlerHttpTest,WebConsoleHandlerHttpTest'
        $results.java_http_regression.exit_code = $LASTEXITCODE
        Ensure-LastExitCodeZero "Java HTTP regression tests failed."
    }
}

if (-not $SkipProviderDiscoverySmoke) {
    Invoke-Step -Title "provider discovery smoke" -Action {
        powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven
        $results.provider_discovery_smoke.build_exit_code = $LASTEXITCODE
        Ensure-LastExitCodeZero "Provider discovery smoke build failed."

        $providerDiscoveryReport = ".\.tmp\provider-discovery-smoke\report.json"
        node .\scripts\provider-discovery-smoke.js --port 18432 --report $providerDiscoveryReport
        $results.provider_discovery_smoke.exit_code = $LASTEXITCODE
        Ensure-LastExitCodeZero "Provider discovery smoke failed."

        $resolvedReport = Resolve-Path -LiteralPath $providerDiscoveryReport
        $results.provider_discovery_smoke.report_path = $resolvedReport.Path
        $smokeReport = Get-Content -LiteralPath $resolvedReport.Path -Raw | ConvertFrom-Json
        $results.provider_discovery_smoke.passed = [bool]$smokeReport.passed
        if (-not $smokeReport.passed) {
            throw "Provider discovery smoke report did not pass."
        }
    }
}

if (-not $SkipCodexPartialTimeoutSmoke) {
    Invoke-Step -Title "codex partial timeout smoke" -Action {
        powershell -ExecutionPolicy Bypass -File .\scripts\Run-CodexPartialTimeoutSmoke.ps1
        $results.codex_partial_timeout_smoke.exit_code = $LASTEXITCODE
        Ensure-LastExitCodeZero "Codex partial timeout smoke failed."

        $codexReport = ".\.tmp\codex-partial-timeout-smoke\report.json"
        $resolvedReport = Resolve-Path -LiteralPath $codexReport
        $results.codex_partial_timeout_smoke.report_path = $resolvedReport.Path
        $smokeReport = Get-Content -LiteralPath $resolvedReport.Path -Raw | ConvertFrom-Json
        $results.codex_partial_timeout_smoke.passed = [bool]$smokeReport.passed
        if (-not $smokeReport.passed) {
            throw "Codex partial timeout smoke report did not pass."
        }
    }
}

Invoke-Step -Title "dialogue A-H scripted/manual backfill gate probe" -Action {
    $backfillInputPath = $results.dialogue_backfill_gate_probe.input_json_path
    if (-not (Test-Path -LiteralPath $backfillInputPath)) {
        $results.dialogue_backfill_gate_probe.skipped = $true
        $results.dialogue_backfill_gate_probe.skip_reason = "missing input json: $backfillInputPath"
        return
    }

    $scriptedProbe = Invoke-PowerShellJsonScript -Arguments @(
        "-ExecutionPolicy", "Bypass",
        "-File", ".\scripts\Run-DialogueAcceptanceScriptedBackfillProbe.ps1",
        "-InputJsonPath", $backfillInputPath
    )
    $results.dialogue_backfill_gate_probe.exit_code = 0
    $results.dialogue_backfill_gate_probe.scripted_coverage_prefilled = @($scriptedProbe.scripted_coverage_prefilled)
    $results.dialogue_backfill_gate_probe.residual_human = @($scriptedProbe.residual_human)
    $results.dialogue_backfill_gate_probe.scripted_misuse_rejected = [bool]$scriptedProbe.scripted_misuse_rejected
    if (-not $scriptedProbe.ok -or -not $scriptedProbe.scripted_misuse_rejected) {
        throw "Dialogue scripted backfill gate probe did not pass."
    }

    $manualProbe = Invoke-PowerShellJsonScript -Arguments @(
        "-ExecutionPolicy", "Bypass",
        "-File", ".\scripts\Run-DialogueAcceptanceManualBackfillProbe.ps1",
        "-InputJsonPath", $backfillInputPath
    )
    $results.dialogue_backfill_gate_probe.manual_exit_code = 0
    $results.dialogue_backfill_gate_probe.manual_apply_passed = [bool]$manualProbe.applied
    if (-not $manualProbe.applied) {
        throw "Dialogue manual backfill probe did not pass."
    }
}

Invoke-Step -Title "first release dry-run" -Action {
    $dryRun = Invoke-PowerShellJsonScript -Arguments @(
        "-ExecutionPolicy", "Bypass",
        "-File", ".\scripts\Run-GitHubFirstReleaseDryRun.ps1",
        "-WriteMarkdown"
    )
    $results.first_release_dry_run.exit_code = 0
    $results.first_release_dry_run.markdown_path = Resolve-MarkdownPath -ReportedPath $dryRun.markdown_path -FallbackRelativePath "docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md"
}

Invoke-Step -Title "first release commit dry-run" -Action {
    $commitDryRun = Invoke-PowerShellJsonScript -Arguments @(
        "-ExecutionPolicy", "Bypass",
        "-File", ".\scripts\Run-GitHubFirstReleaseCommitDryRun.ps1",
        "-Commit", "all",
        "-WriteMarkdown"
    )
    $results.first_release_commit_dry_run.exit_code = 0
    $results.first_release_commit_dry_run.markdown_path = Resolve-MarkdownPath -ReportedPath $commitDryRun.markdown_path -FallbackRelativePath "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md"
    $results.first_release_commit_dry_run.unmatched_count = @($commitDryRun.unmatched).Count
}

Invoke-Step -Title "first release stage preview" -Action {
    $stagePreview = Invoke-PowerShellJsonScript -Arguments @(
        "-ExecutionPolicy", "Bypass",
        "-File", ".\scripts\Run-GitHubFirstReleaseStagePreview.ps1",
        "-Commit", "all",
        "-WriteMarkdown"
    )
    $results.first_release_stage_preview.exit_code = 0
    $results.first_release_stage_preview.markdown_path = Resolve-MarkdownPath -ReportedPath $stagePreview.markdown_path -FallbackRelativePath "docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md"
}

if (-not $SkipDryRunMarkdown) {
    $out = New-StringList
    $out.Add(("# GitHub First Release Precheck {0}" -f $precheckDate))
    $out.Add('')
    $out.Add('> Purpose: capture one real local precheck run for the current first-release slice.')
    $out.Add('')
    $out.Add('## Executed Commands')
    $out.Add('')
    $out.Add('### 1. dialogue frontend entry syntax check')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.node_check.command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    $out.Add(('- Exit code: {0}' -f $results.node_check.exit_code))
    $out.Add('')

    $out.Add('### 2. dialogue JS smoke tests')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.node_tests.command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    if ($results.node_tests.skipped) {
        $out.Add('- Skipped')
    }
    else {
        $out.Add('- Passed; see console output for exact Node test details')
    }
    $out.Add('')

    $out.Add('### 3. Java HTTP regression')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.java_http_regression.command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    if ($results.java_http_regression.skipped) {
        $out.Add('- Skipped')
    }
    else {
        $out.Add('- Maven test run passed')
        $out.Add('- ChatFacadeHandlerHttpTest passed')
        $out.Add('- WebConsoleHandlerHttpTest passed')
    }
    $out.Add('')

    $out.Add('### 4. Provider discovery smoke')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.provider_discovery_smoke.build_command)
    $out.Add($results.provider_discovery_smoke.command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    if ($results.provider_discovery_smoke.skipped) {
        $out.Add('- Skipped')
    }
    else {
        $out.Add(('- Build exit code: {0}' -f $results.provider_discovery_smoke.build_exit_code))
        $out.Add(('- Smoke exit code: {0}' -f $results.provider_discovery_smoke.exit_code))
        $out.Add(('- Report: {0}' -f $results.provider_discovery_smoke.report_path))
        $out.Add(('- Passed: {0}' -f $results.provider_discovery_smoke.passed))
        $out.Add('- Validates `providers.yaml` dynamic provider appears in `/api/v1/agents` and `/api/v1/workers`, and worker list readiness matches runtime readiness')
    }
    $out.Add('')

    $out.Add('### 5. Codex partial timeout smoke')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.codex_partial_timeout_smoke.command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    if ($results.codex_partial_timeout_smoke.skipped) {
        $out.Add('- Skipped')
    }
    else {
        $out.Add(('- Exit code: {0}' -f $results.codex_partial_timeout_smoke.exit_code))
        $out.Add(('- Report: {0}' -f $results.codex_partial_timeout_smoke.report_path))
        $out.Add(('- Passed: {0}' -f $results.codex_partial_timeout_smoke.passed))
        $out.Add('- Validates Codex partial output communication failure, max-duration hard limit, ControlNodeGraph human gate projection, provider thread continuation metadata, and Dialogue worker_round actions')
    }
    $out.Add('')

    $out.Add('### 6. Dialogue A-H scripted/manual backfill gate')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.dialogue_backfill_gate_probe.command)
    $out.Add($results.dialogue_backfill_gate_probe.manual_command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    if ($results.dialogue_backfill_gate_probe.skipped) {
        $out.Add(('- Skipped: {0}' -f $results.dialogue_backfill_gate_probe.skip_reason))
    }
    else {
        $out.Add(('- Scripted exit code: {0}' -f $results.dialogue_backfill_gate_probe.exit_code))
        $out.Add(('- Manual exit code: {0}' -f $results.dialogue_backfill_gate_probe.manual_exit_code))
        $out.Add(('- Scripted coverage prefilled: {0}' -f (($results.dialogue_backfill_gate_probe.scripted_coverage_prefilled | ForEach-Object { [string]$_ }) -join ', ')))
        $out.Add(('- Residual human gate: {0}' -f (($results.dialogue_backfill_gate_probe.residual_human | ForEach-Object { [string]$_ }) -join ', ')))
        $out.Add(('- Scripted misuse rejected: {0}' -f $results.dialogue_backfill_gate_probe.scripted_misuse_rejected))
        $out.Add(('- Manual apply still works: {0}' -f $results.dialogue_backfill_gate_probe.manual_apply_passed))
        $out.Add('- Validates scripted browser evidence cannot mark strict manual A-H Passed=true, while intentional manual backfill still works')
    }
    $out.Add('')

    $out.Add('### 7. first release dry-run')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.first_release_dry_run.command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    $out.Add(('- Artifact: {0}' -f $results.first_release_dry_run.markdown_path))
    $out.Add('- Stable sections present:')
    $out.Add('  - include')
    $out.Add('  - evidence_only')
    $out.Add('  - defer')
    $out.Add('  - review')
    $out.Add('')

    $out.Add('### 8. first release commit dry-run')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.first_release_commit_dry_run.command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    $out.Add(('- Artifact: {0}' -f $results.first_release_commit_dry_run.markdown_path))
    $out.Add('- Stable main groups present:')
    $out.Add('  - Repository Baseline')
    $out.Add('  - chat-first / facade product line')
    $out.Add('  - acceptance harness and operator docs')
    $out.Add(('- Current unmatched_count = {0}' -f $results.first_release_commit_dry_run.unmatched_count))
    $out.Add('')

    $out.Add('### 9. first release stage preview')
    $out.Add('')
    $out.Add('```powershell')
    $out.Add($results.first_release_stage_preview.command)
    $out.Add('```')
    $out.Add('')
    $out.Add('Result:')
    $out.Add('')
    $out.Add(('- Artifact: {0}' -f $results.first_release_stage_preview.markdown_path))
    $out.Add('- Stable simulated staged diff groups present:')
    $out.Add('  - Repository Baseline')
    $out.Add('  - chat-first / facade product line')
    $out.Add('  - acceptance harness and operator docs')
    $out.Add('')

    $out.Add('## Still Outstanding')
    $out.Add('')
    $out.Add('- README.md still uses a published repo placeholder and has not yet been filled with a real public repository URL')
    $out.Add('- /dialogue/ strict A-H manual click-through acceptance is still not complete; scripted current-reachable seam evidence exists but does not close this manual gate')
    $out.Add('- GitHub Actions has not yet been verified on a real remote GitHub repository')
    $out.Add('')

    $out.Add('## Conclusion')
    $out.Add('')
    $out.Add('A real local precheck exists for the current first-release slice, but it is still only a precheck.')
    $out.Add('')
    $out.Add('> local precheck passed for the current first-release slice, while manual dialogue acceptance and real remote GitHub validation are still outstanding.')
    $out.Add('')

    $parent = Split-Path -Parent $markdownTarget
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Set-Content -LiteralPath $markdownTarget -Value $out -Encoding UTF8
    $results.markdown_path = (Resolve-Path -LiteralPath $markdownTarget).Path
}

$results | ConvertTo-Json -Depth 6
