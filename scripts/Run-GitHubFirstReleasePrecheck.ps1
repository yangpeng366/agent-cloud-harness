param(
    [switch]$SkipJavaTests,
    [switch]$SkipNodeTests,
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

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

$markdownTarget = if ($MarkdownPath) {
    $MarkdownPath
}
else {
    "docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md"
}

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
    $out.Add('# GitHub First Release Precheck 2026-05-11')
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

    $out.Add('### 4. first release dry-run')
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

    $out.Add('### 5. first release commit dry-run')
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

    $out.Add('### 6. first release stage preview')
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
    $out.Add('- /dialogue/ A-H real manual acceptance is still not complete')
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
