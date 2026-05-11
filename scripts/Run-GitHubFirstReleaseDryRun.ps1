param(
    [string]$StatusPath = "",
    [switch]$WriteMarkdown,
    [string]$MarkdownPath = ""
)

$ErrorActionPreference = "Stop"

function Normalize-StatusPath {
    param([string]$Value)
    return ($Value -replace "\\", "/").Trim()
}

function Read-StatusLines {
    param([string]$Path)
    if ($Path -and (Test-Path -LiteralPath $Path)) {
        return Get-Content -LiteralPath $Path
    }
    $output = git status --short
    if ($LASTEXITCODE -ne 0) {
        throw "git status --short failed."
    }
    return $output
}

function New-StringList {
    return New-Object System.Collections.Generic.List[string]
}

function Add-MapItem {
    param(
        [hashtable]$Map,
        [string]$Key,
        [string]$Value
    )
    if (-not $Map.ContainsKey($Key)) {
        $Map[$Key] = New-StringList
    }
    $Map[$Key].Add($Value)
}

function Get-MapValues {
    param(
        [hashtable]$Map,
        [string]$Key
    )
    if ($Map.ContainsKey($Key)) {
        return @($Map[$Key])
    }
    return @()
}

$includePrefixes = @(
    ".github/",
    ".gitignore",
    "README.md",
    "LICENSE",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "CODE_OF_CONDUCT.md",
    "src/main/java/com/agentcloud/engine/ChatFacadeService.java",
    "src/main/java/com/agentcloud/server/WebConsoleHandler.java",
    "src/main/resources/web/console/app.js",
    "src/main/resources/web/dialogue/",
    "src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java",
    "src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java",
    "src/test/js/",
    "scripts/Start-DialogueChatFacadeManualAcceptance.ps1",
    "scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1",
    "scripts/Run-ChatFacadePathMatrixProbe.ps1",
    "scripts/Run-DialogueBrowserAcceptanceProbe.ps1",
    "scripts/dialogue-browser-acceptance-probe-runner.cjs",
    "scripts/Render-DialogueAcceptanceRecordSeed.ps1",
    "scripts/Run-DialogueRecordSeedProbe.ps1",
    "scripts/Run-GitHubFirstReleaseDryRun.ps1",
    "scripts/Run-GitHubFirstReleaseCommitDryRun.ps1",
    "scripts/Run-GitHubFirstReleaseStagePreview.ps1",
    "scripts/Run-GitHubFirstReleaseIndexAudit.ps1",
    "docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md",
    "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md",
    "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md",
    "docs/GITHUB_RELEASE_CHECKLIST.md",
    "docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md",
    "docs/GITHUB_FIRST_RELEASE_FILESET.md",
    "docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md",
    "docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md",
    "docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md",
    "scripts/Run-GitHubFirstReleasePrecheck.ps1"
)

$evidenceOnly = @(
    "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md",
    "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md"
)

$deferPrefixes = @(
    ".tmp/",
    "test-results/",
    "hs_err_pid",
    "replay_pid",
    "docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md"
)

$statusLines = Read-StatusLines -Path $StatusPath
$buckets = @{}

foreach ($rawLine in $statusLines) {
    if ([string]::IsNullOrWhiteSpace($rawLine)) {
        continue
    }
    if ($rawLine.Length -lt 4) {
        continue
    }

    $status = $rawLine.Substring(0, 2)
    $path = Normalize-StatusPath $rawLine.Substring(3)
    $bucket = "review"

    foreach ($prefix in $deferPrefixes) {
        if ($path.StartsWith($prefix)) {
            $bucket = "defer"
            break
        }
    }

    if ($bucket -eq "review") {
        foreach ($item in $evidenceOnly) {
            if ($path -eq $item) {
                $bucket = "evidence_only"
                break
            }
        }
    }

    if ($bucket -eq "review") {
        foreach ($prefix in $includePrefixes) {
            if ($path -eq $prefix -or $path.StartsWith($prefix)) {
                $bucket = "include"
                break
            }
        }
    }

    Add-MapItem -Map $buckets -Key $bucket -Value ("{0} {1}" -f $status, $path)
}

$result = [ordered]@{
    include = @(Get-MapValues -Map $buckets -Key "include")
    evidence_only = @(Get-MapValues -Map $buckets -Key "evidence_only")
    defer = @(Get-MapValues -Map $buckets -Key "defer")
    review = @(Get-MapValues -Map $buckets -Key "review")
}

if ($WriteMarkdown) {
    if (-not $MarkdownPath) {
        $MarkdownPath = "docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md"
    }

    $parent = Split-Path -Parent $MarkdownPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $out = New-StringList
    $out.Add("# GitHub First Release Dry Run")
    $out.Add("")
    $out.Add("> Snapshot generated from current git status.")
    $out.Add("")

    $sections = @(
        @{ key = "include"; title = "A. Include in first release" },
        @{ key = "evidence_only"; title = "B. Keep, but do not use as release completion proof" },
        @{ key = "defer"; title = "C. Defer or exclude" },
        @{ key = "review"; title = "D. Needs manual review" }
    )

    foreach ($section in $sections) {
        $out.Add("## " + $section.title)
        $out.Add("")
        $items = @($result[$section.key])
        if ($items.Count -eq 0) {
            $out.Add("- none")
        }
        else {
            foreach ($entry in $items) {
                $out.Add("- " + $entry)
            }
        }
        $out.Add("")
    }

    Set-Content -LiteralPath $MarkdownPath -Value $out -Encoding UTF8
    $result["markdown_path"] = (Resolve-Path -LiteralPath $MarkdownPath).Path
}

$result | ConvertTo-Json -Depth 4
