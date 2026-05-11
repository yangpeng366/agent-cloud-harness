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

function Resolve-Group {
    param(
        [string]$Path,
        [hashtable]$Groups,
        [string[]]$EvidenceOnly,
        [string[]]$DeferPrefixes
    )

    foreach ($item in $EvidenceOnly) {
        if ($Path -eq $item) {
            return "evidence_only"
        }
    }

    foreach ($prefix in $DeferPrefixes) {
        if ($Path -eq $prefix -or $Path.StartsWith($prefix)) {
            return "defer"
        }
    }

    foreach ($groupName in @("baseline", "product", "harness")) {
        foreach ($prefix in $Groups[$groupName]) {
            if ($Path -eq $prefix -or $Path.StartsWith($prefix)) {
                return $groupName
            }
        }
    }

    return "unmatched"
}

function Resolve-StateBucket {
    param(
        [string]$X,
        [string]$Y
    )

    if ($X -eq "?" -and $Y -eq "?") {
        return "untracked_only"
    }

    $hasStaged = ($X -ne " " -and $X -ne "?")
    $hasUnstaged = ($Y -ne " " -and $Y -ne "?")

    if ($hasStaged -and $hasUnstaged) {
        return "staged_and_unstaged"
    }
    if ($hasStaged) {
        return "staged_only"
    }
    if ($hasUnstaged) {
        return "unstaged_only"
    }

    return "other"
}

$groups = [ordered]@{
    baseline = @(
        ".gitignore",
        "README.md",
        "LICENSE",
        "CONTRIBUTING.md",
        "SECURITY.md",
        "CODE_OF_CONDUCT.md",
        ".github/",
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
        "docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md"
    )
    product = @(
        "src/main/java/com/agentcloud/engine/ChatFacadeService.java",
        "src/main/java/com/agentcloud/server/WebConsoleHandler.java",
        "src/main/resources/web/console/app.js",
        "src/main/resources/web/dialogue/",
        "src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java",
        "src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java",
        "src/test/js/"
    )
    harness = @(
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
        "scripts/Run-GitHubFirstReleasePrecheck.ps1",
        "scripts/Run-GitHubFirstReleaseIndexAudit.ps1",
        "docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md",
        "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md",
        "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md"
    )
}

$evidenceOnly = @(
    "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md",
    "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md",
    "docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md",
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
$groupBuckets = @{}
$summary = @{}

foreach ($name in @("baseline", "product", "harness", "evidence_only", "defer", "unmatched")) {
    $summary[$name] = [ordered]@{
        staged_only = 0
        staged_and_unstaged = 0
        unstaged_only = 0
        untracked_only = 0
        other = 0
    }
}

foreach ($rawLine in $statusLines) {
    if ([string]::IsNullOrWhiteSpace($rawLine)) {
        continue
    }
    if ($rawLine.Length -lt 4) {
        continue
    }

    $status = $rawLine.Substring(0, 2)
    $x = $status.Substring(0, 1)
    $y = $status.Substring(1, 1)
    $path = Normalize-StatusPath $rawLine.Substring(3)

    $group = Resolve-Group -Path $path -Groups $groups -EvidenceOnly $evidenceOnly -DeferPrefixes $deferPrefixes
    $stateBucket = Resolve-StateBucket -X $x -Y $y
    $summary[$group][$stateBucket]++

    Add-MapItem -Map $groupBuckets -Key ("{0}:{1}" -f $group, $stateBucket) -Value ("{0} {1}" -f $status, $path)
}

$result = [ordered]@{
    summary = $summary
    baseline = [ordered]@{
        staged_only = @(Get-MapValues -Map $groupBuckets -Key "baseline:staged_only")
        staged_and_unstaged = @(Get-MapValues -Map $groupBuckets -Key "baseline:staged_and_unstaged")
        unstaged_only = @(Get-MapValues -Map $groupBuckets -Key "baseline:unstaged_only")
        untracked_only = @(Get-MapValues -Map $groupBuckets -Key "baseline:untracked_only")
    }
    product = [ordered]@{
        staged_only = @(Get-MapValues -Map $groupBuckets -Key "product:staged_only")
        staged_and_unstaged = @(Get-MapValues -Map $groupBuckets -Key "product:staged_and_unstaged")
        unstaged_only = @(Get-MapValues -Map $groupBuckets -Key "product:unstaged_only")
        untracked_only = @(Get-MapValues -Map $groupBuckets -Key "product:untracked_only")
    }
    harness = [ordered]@{
        staged_only = @(Get-MapValues -Map $groupBuckets -Key "harness:staged_only")
        staged_and_unstaged = @(Get-MapValues -Map $groupBuckets -Key "harness:staged_and_unstaged")
        unstaged_only = @(Get-MapValues -Map $groupBuckets -Key "harness:unstaged_only")
        untracked_only = @(Get-MapValues -Map $groupBuckets -Key "harness:untracked_only")
    }
    evidence_only = [ordered]@{
        staged_only = @(Get-MapValues -Map $groupBuckets -Key "evidence_only:staged_only")
        staged_and_unstaged = @(Get-MapValues -Map $groupBuckets -Key "evidence_only:staged_and_unstaged")
        unstaged_only = @(Get-MapValues -Map $groupBuckets -Key "evidence_only:unstaged_only")
        untracked_only = @(Get-MapValues -Map $groupBuckets -Key "evidence_only:untracked_only")
    }
    defer = [ordered]@{
        staged_only = @(Get-MapValues -Map $groupBuckets -Key "defer:staged_only")
        staged_and_unstaged = @(Get-MapValues -Map $groupBuckets -Key "defer:staged_and_unstaged")
        unstaged_only = @(Get-MapValues -Map $groupBuckets -Key "defer:unstaged_only")
        untracked_only = @(Get-MapValues -Map $groupBuckets -Key "defer:untracked_only")
    }
    unmatched = [ordered]@{
        staged_only = @(Get-MapValues -Map $groupBuckets -Key "unmatched:staged_only")
        staged_and_unstaged = @(Get-MapValues -Map $groupBuckets -Key "unmatched:staged_and_unstaged")
        unstaged_only = @(Get-MapValues -Map $groupBuckets -Key "unmatched:unstaged_only")
        untracked_only = @(Get-MapValues -Map $groupBuckets -Key "unmatched:untracked_only")
    }
}

if ($WriteMarkdown) {
    if (-not $MarkdownPath) {
        $MarkdownPath = "docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md"
    }

    $parent = Split-Path -Parent $MarkdownPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $out = New-StringList
    $out.Add("# GitHub First Release Index Audit")
    $out.Add("")
    $out.Add("> Snapshot generated from current `git status --short`, focused on staged vs unstaged drift inside the current first-release slices.")
    $out.Add("")

    $groupTitles = [ordered]@{
        baseline = "Repository Baseline"
        product = "chat-first / facade product line"
        harness = "acceptance harness and operator docs"
        evidence_only = "Evidence-only working logs"
        defer = "Deferred or excluded files"
        unmatched = "Unmatched"
    }

    foreach ($groupName in $groupTitles.Keys) {
        $out.Add("## " + $groupTitles[$groupName])
        $out.Add("")
        $stats = $summary[$groupName]
        $out.Add(("- staged_only: {0}" -f $stats.staged_only))
        $out.Add(("- staged_and_unstaged: {0}" -f $stats.staged_and_unstaged))
        $out.Add(("- unstaged_only: {0}" -f $stats.unstaged_only))
        $out.Add(("- untracked_only: {0}" -f $stats.untracked_only))
        $out.Add("")

        foreach ($bucketName in @("staged_only", "staged_and_unstaged", "unstaged_only", "untracked_only")) {
            $items = @($result[$groupName][$bucketName])
            $out.Add("### " + $bucketName)
            $out.Add("")
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
    }

    $out.Add("## Current Reading")
    $out.Add("")
    $out.Add("- `staged_only` means the file is already in the index and currently has no extra working-tree drift.")
    $out.Add("- `staged_and_unstaged` means the file is in the index, but the working tree has diverged since it was staged; do not assume the current file content matches the staged slice.")
    $out.Add("- `unstaged_only` means the file still belongs to a slice, but has not been staged yet.")
    $out.Add("- `untracked_only` means the file is new and not yet staged.")
    $out.Add("")
    $out.Add("## Still Not Done")
    $out.Add("")
    $out.Add("- This audit does not replace the real `/dialogue/` A-H manual acceptance pass.")
    $out.Add("- This audit does not replace filling a real public GitHub repository URL into `README.md`.")
    $out.Add("- This audit does not prove that GitHub Actions has run green on a real remote repository.")
    $out.Add("")

    Set-Content -LiteralPath $MarkdownPath -Value $out -Encoding UTF8
    $result["markdown_path"] = (Resolve-Path -LiteralPath $MarkdownPath).Path
}

$result | ConvertTo-Json -Depth 6
