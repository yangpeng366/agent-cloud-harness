param(
    [ValidateSet("baseline", "product", "harness", "all")]
    [string]$Commit = "all",
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
        "scripts/Run-GitHubFirstReleaseIndexAudit.ps1",
        "scripts/Run-GitHubFirstReleasePrecheck.ps1",
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

$selectedGroups = if ($Commit -eq "all") {
    @("baseline", "product", "harness")
}
else {
    @($Commit)
}

$statusLines = Read-StatusLines -Path $StatusPath
$buckets = @{}
$unmatched = New-StringList

foreach ($rawLine in $statusLines) {
    if ([string]::IsNullOrWhiteSpace($rawLine)) {
        continue
    }
    if ($rawLine.Length -lt 4) {
        continue
    }

    $status = $rawLine.Substring(0, 2)
    $path = Normalize-StatusPath $rawLine.Substring(3)
    $matched = $false

    foreach ($item in $evidenceOnly) {
        if ($path -eq $item) {
            Add-MapItem -Map $buckets -Key "evidence_only" -Value ("{0} {1}" -f $status, $path)
            $matched = $true
            break
        }
    }

    if (-not $matched) {
        foreach ($prefix in $deferPrefixes) {
            if ($path -eq $prefix -or $path.StartsWith($prefix)) {
                Add-MapItem -Map $buckets -Key "defer" -Value ("{0} {1}" -f $status, $path)
                $matched = $true
                break
            }
        }
    }

    if (-not $matched) {
        foreach ($groupName in $selectedGroups) {
            foreach ($prefix in $groups[$groupName]) {
                if ($path -eq $prefix -or $path.StartsWith($prefix)) {
                    Add-MapItem -Map $buckets -Key $groupName -Value ("{0} {1}" -f $status, $path)
                    $matched = $true
                    break
                }
            }
            if ($matched) {
                break
            }
        }
    }

    if (-not $matched) {
        $unmatched.Add(("{0} {1}" -f $status, $path))
    }
}

$result = [ordered]@{
    commit = $Commit
    baseline = @(Get-MapValues -Map $buckets -Key "baseline")
    product = @(Get-MapValues -Map $buckets -Key "product")
    harness = @(Get-MapValues -Map $buckets -Key "harness")
    evidence_only = @(Get-MapValues -Map $buckets -Key "evidence_only")
    defer = @(Get-MapValues -Map $buckets -Key "defer")
    unmatched = @($unmatched)
}

if ($WriteMarkdown) {
    if (-not $MarkdownPath) {
        $suffix = if ($Commit -eq "all") { "all" } else { $Commit }
        $MarkdownPath = ("docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_{0}_2026-05-11.md" -f $suffix)
    }

    $parent = Split-Path -Parent $MarkdownPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $out = New-StringList
    $out.Add("# GitHub First Release Commit Dry Run")
    $out.Add("")
    $out.Add("> Snapshot generated from current git status.")
    $out.Add("")

    foreach ($name in $selectedGroups) {
        $title = switch ($name) {
            "baseline" { "Repository Baseline" }
            "product" { "chat-first / facade product line" }
            default { "acceptance harness and operator docs" }
        }
        $out.Add("## " + $title)
        $out.Add("")
        $items = @($result[$name])
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

    $out.Add("## Keep, but do not use as release completion proof")
    $out.Add("")
    if ($result.evidence_only.Count -eq 0) {
        $out.Add("- none")
    }
    else {
        foreach ($entry in $result.evidence_only) {
            $out.Add("- " + $entry)
        }
    }
    $out.Add("")

    $out.Add("## Defer or exclude")
    $out.Add("")
    if ($result.defer.Count -eq 0) {
        $out.Add("- none")
    }
    else {
        foreach ($entry in $result.defer) {
            $out.Add("- " + $entry)
        }
    }
    $out.Add("")

    $out.Add("## Unmatched")
    $out.Add("")
    if ($result.unmatched.Count -eq 0) {
        $out.Add("- none")
    }
    else {
        foreach ($entry in $result.unmatched) {
            $out.Add("- " + $entry)
        }
    }
    $out.Add("")

    Set-Content -LiteralPath $MarkdownPath -Value $out -Encoding UTF8
    $result["markdown_path"] = (Resolve-Path -LiteralPath $MarkdownPath).Path
}

$result | ConvertTo-Json -Depth 4
