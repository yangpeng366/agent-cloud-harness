param(
    [string]$RepoRoot = "",
    [switch]$WriteMarkdown,
    [string]$MarkdownPath = "",
    [switch]$FailOnViolation
)

$ErrorActionPreference = "Stop"
$Utf8Encoding = New-Object System.Text.UTF8Encoding($false)
$Utf8StrictEncoding = New-Object System.Text.UTF8Encoding($false, $true)

function Resolve-RepoRootPath {
    param([string]$Value)
    if ($Value) {
        return (Resolve-Path -LiteralPath $Value).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
}

function Normalize-RelativePath {
    param(
        [string]$BasePath,
        [string]$TargetPath
    )
    $baseFullPath = (Resolve-Path -LiteralPath $BasePath).Path
    $targetFullPath = (Resolve-Path -LiteralPath $TargetPath).Path
    $baseUri = New-Object System.Uri(($baseFullPath.TrimEnd('\') + '\'))
    $targetUri = New-Object System.Uri($targetFullPath)
    $relativeUri = $baseUri.MakeRelativeUri($targetUri)
    return ([System.Uri]::UnescapeDataString($relativeUri.ToString()) -replace "\\", "/")
}

function Read-Utf8Text {
    param([string]$Path)
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    return [System.IO.File]::ReadAllText($resolvedPath, $Utf8StrictEncoding)
}

function Write-Utf8Lines {
    param(
        [string]$Path,
        [string[]]$Lines
    )
    [System.IO.File]::WriteAllLines($Path, $Lines, $Utf8Encoding)
}

function New-UnicodeString {
    param([int[]]$CodePoints)
    $builder = New-Object System.Text.StringBuilder
    foreach ($codePoint in $CodePoints) {
        [void]$builder.Append([char]$codePoint)
    }
    return $builder.ToString()
}

function Test-LiteralReference {
    param(
        [string]$Content,
        [string]$Literal
    )
    if ([string]::IsNullOrEmpty($Content)) {
        return $false
    }
    return [regex]::IsMatch($Content, [regex]::Escape($Literal), [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

function Test-RegexReference {
    param(
        [string]$Content,
        [string]$Pattern
    )
    if ([string]::IsNullOrEmpty($Content)) {
        return $false
    }
    return [regex]::IsMatch($Content, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

function Test-AnyRegexMatch {
    param(
        [string]$Value,
        [string[]]$Patterns
    )
    foreach ($pattern in $Patterns) {
        if ([regex]::IsMatch($Value, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
            return $true
        }
    }
    return $false
}

function Test-HeadingOrder {
    param(
        [string]$Content,
        [string[]]$Headings
    )
    $previousIndex = -1
    $lines = $Content -split "\r?\n"
    foreach ($heading in $Headings) {
        $currentIndex = -1
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i].Trim() -eq $heading) {
                $currentIndex = $i
                break
            }
        }
        if ($currentIndex -lt 0) {
            return $false
        }
        if ($currentIndex -le $previousIndex) {
            return $false
        }
        $previousIndex = $currentIndex
    }
    return $true
}

function Test-DocsReadmeWorkspaceRow {
    param(
        [string]$Content,
        [string]$TopicName,
        [string]$TopicState,
        [string]$ReadmeOnlyLabel
    )
    $topicCellPattern = [regex]::Escape(('`{0}/`' -f $TopicName))
    $pattern = '(?m)^\|\s*' + $topicCellPattern + '\s*\|.*$'
    if ($TopicState -eq "readme_only") {
        $readmeCellPattern = [regex]::Escape($ReadmeOnlyLabel) + '\s*' + [regex]::Escape('`README.md`')
        $pattern = '(?m)^\|\s*' + $topicCellPattern + '\s*\|\s*' + $readmeCellPattern + '\s*\|.*$'
    }
    $match = [regex]::Match($Content, $pattern)
    if (-not $match.Success) {
        return $false
    }
    if ($TopicState -ne "workspace_enabled") {
        return $true
    }

    $runsPath = Join-Path (Join-Path $docsRootPath $TopicName) "runs"
    if (Test-Path -LiteralPath $runsPath) {
        return Test-LiteralReference -Content $match.Value -Literal ("{0}/runs/README.md" -f $TopicName)
    }
    return $true
}

function Test-RootEntryWorkspaceRow {
    param(
        [string]$Content,
        [string]$TopicName,
        [string]$TopicState
    )
    $expectedState = switch ($TopicState) {
        "workspace_enabled" { "README.md + PROGRESS.md" }
        "readme_only" { "README-only" }
        default { return $true }
    }
    $topicLiteral = ('`{0}/`' -f $TopicName)
    $stateLiteral = ('`{0}`' -f $expectedState)
    $pattern = '(?m)^-\s*' + [regex]::Escape($topicLiteral) + ':\s*' + [regex]::Escape($stateLiteral) + '\s*$'
    return Test-RegexReference -Content $Content -Pattern $pattern
}

function Test-RootEntryProgressReadingOrder {
    param([string]$Content)
    $tick = [char]0x60
    $progressLabel = [string]::Concat((New-UnicodeString @(0x5df2, 0x542f, 0x7528)), ' ', $tick, 'PROGRESS.md', $tick, ' ', (New-UnicodeString @(0x7684, 0x4e3b, 0x9898)))
    $readingPath = [string]::Concat($tick, 'README.md -> PROGRESS.md -> ', (New-UnicodeString @(0x5f53, 0x524d, 0x4e3b, 0x7ebf, 0x6587, 0x6863)), $tick)
    return (Test-LiteralReference -Content $Content -Literal $progressLabel) -and
        (Test-LiteralReference -Content $Content -Literal $readingPath)
}

function Test-RootEntryReadmeOnlyReadingOrder {
    param([string]$Content)
    $tick = [char]0x60
    $readmeOnlyLabel = [string]::Concat($tick, 'README-only', $tick, ' ', (New-UnicodeString @(0x4e3b, 0x9898)))
    $readingPath = [string]::Concat($tick, 'README.md -> docs/', $tick, ' ', (New-UnicodeString @(0x6839, 0x76ee, 0x5f55, 0x4e3b, 0x7ebf, 0x6587, 0x6863)))
    return (Test-LiteralReference -Content $Content -Literal $readmeOnlyLabel) -and
        (Test-LiteralReference -Content $Content -Literal $readingPath)
}

function Test-ReadmeOnlyTopicReadingOrder {
    param([string]$Content)
    return (Test-LiteralReference -Content $Content -Literal "README-only") -and
        (Test-LiteralReference -Content $Content -Literal "README.md -> docs/") -and
        (Test-LiteralReference -Content $Content -Literal (New-UnicodeString @(0x6839, 0x76ee, 0x5f55, 0x4e3b, 0x7ebf, 0x6587, 0x6863)))
}

function Add-Violation {
    param(
        [ref]$Bucket,
        [string]$Type,
        [string]$Target,
        [string]$Reason
    )
    $Bucket.Value += [pscustomobject]@{
        type = $Type
        target = $Target
        reason = $Reason
    }
}

function Resolve-TopicState {
    param(
        [bool]$HasReadme,
        [bool]$HasProgress,
        [bool]$HasTasks,
        [bool]$HasRuns,
        [bool]$HasArchive,
        [object[]]$UnexpectedFiles,
        [object[]]$UnexpectedDirs
    )
    if (-not $HasReadme) {
        return "missing_readme"
    }
    if ($UnexpectedFiles.Count -gt 0 -or $UnexpectedDirs.Count -gt 0) {
        return "contract_violation"
    }
    if (-not $HasProgress -and -not $HasTasks -and -not $HasRuns -and -not $HasArchive) {
        return "readme_only"
    }
    return "workspace_enabled"
}

function Test-WorkspaceSubdirReadme {
    param(
        [string]$TopicPath,
        [string]$DirectoryName
    )
    $subdirReadmePath = Join-Path (Join-Path $TopicPath $DirectoryName) "README.md"
    return Test-Path -LiteralPath $subdirReadmePath
}

$repoRootPath = Resolve-RepoRootPath -Value $RepoRoot
$docsRootPath = Join-Path $repoRootPath "docs"
$readmePath = Join-Path $repoRootPath "README.md"
$startupGuidePath = Join-Path $repoRootPath "STARTUP_GUIDE.md"
$docsReadmePath = Join-Path $docsRootPath "README.md"
$wakePath = Join-Path $repoRootPath "WAKE.md"
$agentsPath = Join-Path $repoRootPath "AGENTS.md"

if (-not (Test-Path -LiteralPath $docsRootPath)) {
    throw "docs directory not found under repo root: $repoRootPath"
}
if (-not (Test-Path -LiteralPath $readmePath)) {
    throw "README.md not found under repo root: $repoRootPath"
}
if (-not (Test-Path -LiteralPath $startupGuidePath)) {
    throw "STARTUP_GUIDE.md not found under repo root: $repoRootPath"
}
if (-not (Test-Path -LiteralPath $docsReadmePath)) {
    throw "docs/README.md not found under repo root: $repoRootPath"
}
if (-not (Test-Path -LiteralPath $wakePath)) {
    throw "WAKE.md not found under repo root: $repoRootPath"
}
if (-not (Test-Path -LiteralPath $agentsPath)) {
    throw "AGENTS.md not found under repo root: $repoRootPath"
}

$readmeContent = Read-Utf8Text -Path $readmePath
$startupGuideContent = Read-Utf8Text -Path $startupGuidePath
$docsReadmeContent = Read-Utf8Text -Path $docsReadmePath
$wakeContent = Read-Utf8Text -Path $wakePath
$agentsContent = Read-Utf8Text -Path $agentsPath
$topicDirs = @(Get-ChildItem -LiteralPath $docsRootPath -Directory | Sort-Object Name)
$allowedTopicFiles = @("README.md", "PROGRESS.md")
$allowedTopicDirs = @("tasks", "runs", "archive")
$signalsHeading = New-UnicodeString @(0x547d, 0x4e2d, 0x4fe1, 0x53f7)
$readingOrderHeading = New-UnicodeString @(0x6700, 0x5c0f, 0x9605, 0x8bfb, 0x987a, 0x5e8f)
$currentLineHeading = New-UnicodeString @(0x5f53, 0x524d, 0x4e3b, 0x7ebf, 0x6587, 0x6863)
$writebackHeading = New-UnicodeString @(0x5199, 0x56de, 0x987a, 0x5e8f)
$historyHeading = New-UnicodeString @(0x5386, 0x53f2, 0x6750, 0x6599)
$historyUsageHeading = New-UnicodeString @(0x5386, 0x53f2, 0x6750, 0x6599, 0x4f7f, 0x7528, 0x89c4, 0x5219)
$docsNavigationHeading = New-UnicodeString @(0x6587, 0x6863, 0x5bfc, 0x822a)
$startupBoundaryHeading = New-UnicodeString @(0x672c, 0x6587, 0x8fb9, 0x754c)
$docsReadmeRoleHeading = New-UnicodeString @(0x6309, 0x89d2, 0x8272, 0x627e, 0x5165, 0x53e3)
$workspaceJudgmentHeading = New-UnicodeString @(0x5f53, 0x524d, 0x5de5, 0x4f5c, 0x533a, 0x5224, 0x65ad)
$upgradeWhenHeading = New-UnicodeString @(0x4f55, 0x65f6, 0x5347, 0x7ea7)
$subtopicRoutingHeading = New-UnicodeString @(0x5148, 0x505a, 0x5b50, 0x4e3b, 0x9898, 0x5224, 0x65ad)
$entryAdviceHeading = New-UnicodeString @(0x5f53, 0x524d, 0x5165, 0x53e3, 0x5efa, 0x8bae)
$subtopicCurrentQuestionCell = New-UnicodeString @(0x5f53, 0x524d, 0x95ee, 0x9898)
$subtopicReadHereCell = New-UnicodeString @(0x5148, 0x770b, 0x54ea, 0x91cc)
$subtopicDrillDownCell = New-UnicodeString @(0x518d, 0x4e0b, 0x94bb)
$stableBaselineHeading = New-UnicodeString @(0x7a33, 0x5b9a, 0x57fa, 0x7ebf)
$stillTruePhrase = New-UnicodeString @(0x4eca, 0x5929, 0x4ecd, 0x7136, 0x4e3a, 0x771f)
$currentMainlineHeading = New-UnicodeString @(0x5f53, 0x524d, 0x4e3b, 0x7ebf, 0x6587, 0x6863)
$currentSublineLiteral = New-UnicodeString @(0x5f53, 0x524d, 0x5b50, 0x7ebf, 0x6587, 0x6863)
$topicProgressSubheading = New-UnicodeString @(0x4e3b, 0x9898, 0x8fdb, 0x5ea6)
$agentsStartGuardrailsHeading = New-UnicodeString @(0x5f00, 0x5de5, 0x7ea2, 0x7ebf)
$agentsFactMapHeading = New-UnicodeString @(0x9879, 0x76ee, 0x4e8b, 0x5b9e, 0x5165, 0x53e3)
$agentsProjectOverviewHeading = New-UnicodeString @(0x9879, 0x76ee, 0x6982, 0x8ff0)
$agentsTechStackHeading = New-UnicodeString @(0x6280, 0x672f, 0x6808)
$agentsSourceTreeHeading = New-UnicodeString @(0x4ee3, 0x7801, 0x7ec4, 0x7ec7)
$agentsApiCheatsheetHeading = New-UnicodeString @(0x41, 0x50, 0x49, 0x20, 0x7aef, 0x70b9, 0x901f, 0x67e5)
$progressCurrentStatusHeading = New-UnicodeString @(0x5f53, 0x524d, 0x72b6, 0x6001)
$progressCompletedHeading = New-UnicodeString @(0x5df2, 0x5b8c, 0x6210)
$progressActiveTracksHeading = New-UnicodeString @(0x6d3b, 0x8dc3, 0x5b50, 0x7ebf)
$progressNextStepsHeading = New-UnicodeString @(0x4e0b, 0x4e00, 0x6b65)
$progressRisksHeading = New-UnicodeString @(0x98ce, 0x9669)
$readmeOnlyLabel = New-UnicodeString @(0x4ec5)
$metaWritebackChainLiteral = "docs/README.md -> docs/<topic>/README.md -> DOCS_GOVERNANCE.md -> PROGRESS.md / STATE.md / DECISIONS.md"
$governanceRootEntryPathsPresent = $false
$governanceAuditEntryPathsPresent = $false
$datedDocSuffixPattern = '\d{4}-\d{2}-\d{2}\.md$'
$coreDatedDocPatterns = @(
    '.*_EXECUTION_RECORD_\d{4}-\d{2}-\d{2}\.md$',
    '.*_ACCEPTANCE_RECORD_\d{4}-\d{2}-\d{2}\.md$',
    '.*_PRECHECK_\d{4}-\d{2}-\d{2}\.md$'
)
$historicalDatedDocPatterns = @(
    'GITHUB_FIRST_RELEASE_DRY_RUN_\d{4}-\d{2}-\d{2}\.md$',
    'GITHUB_FIRST_RELEASE_STAGE_PREVIEW_[A-Za-z0-9_-]+_\d{4}-\d{2}-\d{2}\.md$',
    'GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_[A-Za-z0-9_-]+_\d{4}-\d{2}-\d{2}\.md$',
    'GITHUB_FIRST_RELEASE_(?:COMMIT_SEQUENCE|STAGE_FILE_LIST|STAGED_SLICE_READY|INDEX_AUDIT)_\d{4}-\d{2}-\d{2}\.md$',
    'GOAL_RUNTIME_LANDING_DIFF_\d{4}-\d{2}-\d{2}\.md$',
    'TASK_3809507EDBBE4231_LONG_TASK_SUCCESS_RATE_DESIGN_2026-05-15\.md$'
)
$requiredTopicReadmeSections = @(
    [pscustomobject]@{ key = "signals"; pattern = "(?m)^##\s+" + [regex]::Escape($signalsHeading) + "\s*$"; reason = "Topic README must declare the signals section." },
    [pscustomobject]@{ key = "reading_order"; pattern = "(?m)^##\s+" + [regex]::Escape($readingOrderHeading) + "\s*$"; reason = "Topic README must declare the minimal reading-order section." },
    [pscustomobject]@{ key = "stable_baseline"; pattern = "(?m)^##\s+" + [regex]::Escape($stableBaselineHeading) + "\s*$"; reason = "Topic README must declare the stable-baseline section." },
    [pscustomobject]@{ key = "current_line"; pattern = "(?m)^##\s+" + [regex]::Escape($currentLineHeading) + "\s*$"; reason = "Topic README must declare the current main-line documents section." },
    [pscustomobject]@{ key = "writeback"; pattern = "(?m)^##\s+" + [regex]::Escape($writebackHeading) + "\s*$"; reason = "Topic README must declare the writeback-order section." },
    [pscustomobject]@{ key = "history"; pattern = "(?m)^##\s+(?:" + [regex]::Escape($historyHeading) + "|" + [regex]::Escape($historyUsageHeading) + ")\s*$"; reason = "Topic README must explain historical-material usage." }
)
$requiredProgressSections = @(
    [pscustomobject]@{ key = "current_status"; pattern = "(?m)^##\s+" + [regex]::Escape($progressCurrentStatusHeading) + "\s*$"; reason = "PROGRESS.md must declare the current-status section." },
    [pscustomobject]@{ key = "completed"; pattern = "(?m)^##\s+" + [regex]::Escape($progressCompletedHeading) + "\s*$"; reason = "PROGRESS.md must declare the completed-work section." },
    [pscustomobject]@{ key = "active_tracks"; pattern = "(?m)^##\s+" + [regex]::Escape($progressActiveTracksHeading) + "\s*$"; reason = "PROGRESS.md must declare the active-tracks section." },
    [pscustomobject]@{ key = "next_steps"; pattern = "(?m)^##\s+" + [regex]::Escape($progressNextStepsHeading) + "\s*$"; reason = "PROGRESS.md must declare the next-steps section." },
    [pscustomobject]@{ key = "risks"; pattern = "(?m)^##\s+" + [regex]::Escape($progressRisksHeading) + "\s*$"; reason = "PROGRESS.md must declare the risks section." }
)
$requiredTopicReadmeOrder = @(
    "## $signalsHeading",
    "## $readingOrderHeading",
    "## $stableBaselineHeading",
    "## $currentLineHeading",
    "## $writebackHeading"
)
$runsGroupingHeading = New-UnicodeString @(0x5f53, 0x524d, 0x5206, 0x7ec4)
$runsUsageHeading = New-UnicodeString @(0x4f7f, 0x7528, 0x89c4, 0x5219)
$runsAggregationEntryLiteral = New-UnicodeString @(0x805a, 0x5408, 0x5165, 0x53e3)
$requiredProgressOrder = @(
    "## $progressCurrentStatusHeading",
    "## $progressCompletedHeading",
    "## $progressActiveTracksHeading",
    "## $progressNextStepsHeading",
    "## $progressRisksHeading"
)
$requiredRunsReadmeSections = @(
    [pscustomobject]@{ key = "signals"; pattern = "(?m)^##\s+" + [regex]::Escape($signalsHeading) + "\s*$"; reason = "runs/README.md must declare the signals section." },
    [pscustomobject]@{ key = "reading_order"; pattern = "(?m)^##\s+" + [regex]::Escape($readingOrderHeading) + "\s*$"; reason = "runs/README.md must declare the minimal reading-order section." },
    [pscustomobject]@{ key = "grouping"; pattern = "(?m)^##\s+" + [regex]::Escape($runsGroupingHeading) + "\s*$"; reason = "runs/README.md must declare the current-grouping section." },
    [pscustomobject]@{ key = "usage"; pattern = "(?m)^##\s+" + [regex]::Escape($runsUsageHeading) + "\s*$"; reason = "runs/README.md must declare the usage-rules section." }
)

$violations = @()
$topicEntries = @()
$topicStates = @()
$workspaceRows = @()
$auditSources = @(
    [pscustomobject]@{
        path = "README.md"
        content = $docsReadmeContent
    }
)

$readmeNavigationHeadingPresent = Test-RegexReference -Content $readmeContent -Pattern ('(?m)^##\s+.*' + [regex]::Escape($docsNavigationHeading) + '.*$')
$readmeDocsIndexPresent = Test-LiteralReference -Content $readmeContent -Literal "docs/README.md"
$readmeMetaGovernancePresent = (Test-LiteralReference -Content $readmeContent -Literal "docs/meta/README.md") -and
    (Test-LiteralReference -Content $readmeContent -Literal "docs/DOCS_GOVERNANCE.md")
$readmeAgentEntryPresent = (Test-LiteralReference -Content $readmeContent -Literal "WAKE.md") -and
    (Test-LiteralReference -Content $readmeContent -Literal "AGENTS.md")
$readmeStateDecisionsPresent = (Test-LiteralReference -Content $readmeContent -Literal "STATE.md") -and
    (Test-LiteralReference -Content $readmeContent -Literal "DECISIONS.md")

if (-not $readmeNavigationHeadingPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "root_readme_navigation_heading_missing" -Target "README.md" -Reason "README.md must preserve the docs navigation section."
}
if (-not $readmeDocsIndexPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "root_readme_docs_index_missing" -Target "README.md" -Reason "README.md must route readers to docs/README.md."
}
if (-not $readmeMetaGovernancePresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "root_readme_meta_governance_missing" -Target "README.md" -Reason "README.md must route readers to docs/meta/README.md and docs/DOCS_GOVERNANCE.md."
}
if (-not $readmeAgentEntryPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "root_readme_agent_entry_missing" -Target "README.md" -Reason "README.md must route agent readers to WAKE.md and AGENTS.md."
}
if (-not $readmeStateDecisionsPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "root_readme_state_decisions_missing" -Target "README.md" -Reason "README.md must route readers to STATE.md and DECISIONS.md."
}

$startupBoundaryHeadingPresent = Test-RegexReference -Content $startupGuideContent -Pattern ('(?m)^##\s+' + [regex]::Escape($startupBoundaryHeading) + '\s*$')
$startupDocsRedirectPresent = Test-LiteralReference -Content $startupGuideContent -Literal "docs/README.md"
$startupAgentRedirectPresent = (Test-LiteralReference -Content $startupGuideContent -Literal "WAKE.md") -and
    (Test-LiteralReference -Content $startupGuideContent -Literal "AGENTS.md")
$startupDialogueRedirectPresent = Test-LiteralReference -Content $startupGuideContent -Literal "docs/dialogue/README.md"
$startupProviderRedirectPresent = Test-LiteralReference -Content $startupGuideContent -Literal "docs/provider/README.md"
$startupStateDecisionsPresent = (Test-LiteralReference -Content $startupGuideContent -Literal "STATE.md") -and
    (Test-LiteralReference -Content $startupGuideContent -Literal "DECISIONS.md")
$docsReadmeRoleHeadingPresent = Test-RegexReference -Content $docsReadmeContent -Pattern ('(?m)^##\s+' + [regex]::Escape($docsReadmeRoleHeading) + '\s*$')
$docsReadmeStartupRoleEntryPresent = Test-LiteralReference -Content $docsReadmeContent -Literal "../STARTUP_GUIDE.md"
$docsReadmeGovernanceRoleEntryPresent = (Test-LiteralReference -Content $docsReadmeContent -Literal "meta/README.md") -and
    (Test-LiteralReference -Content $docsReadmeContent -Literal "DOCS_GOVERNANCE.md") -and
    (Test-LiteralReference -Content $docsReadmeContent -Literal "meta/PROGRESS.md")
$docsReadmeAgentRoleEntryPresent = (Test-LiteralReference -Content $docsReadmeContent -Literal "../WAKE.md") -and
    (Test-LiteralReference -Content $docsReadmeContent -Literal "../AGENTS.md")
$docsReadmeStateRoleEntryPresent = (Test-LiteralReference -Content $docsReadmeContent -Literal "../STATE.md") -and
    (Test-LiteralReference -Content $docsReadmeContent -Literal "../DECISIONS.md")
$docsReadmeMetaEntryPresent = Test-LiteralReference -Content $docsReadmeContent -Literal "meta/README.md"
$docsReadmeGovernanceEntryPresent = Test-LiteralReference -Content $docsReadmeContent -Literal "DOCS_GOVERNANCE.md"
$docsReadmeAuditScriptPresent = Test-LiteralReference -Content $docsReadmeContent -Literal "Run-DocsIndexAudit.ps1"
$docsReadmeFocusedRegressionPresent = (Test-LiteralReference -Content $docsReadmeContent -Literal "DocsStructureContractTest") -and
    (Test-LiteralReference -Content $docsReadmeContent -Literal "DocsIndexAuditScriptTest")
$metaWritebackChainPresent = $false
$subtopicRoutingCoverageCount = 0
$entryAdviceCoverageCount = 0
$subtopicRoutingTableCoverageCount = 0
$stableBaselineCoverageCount = 0
$stableBaselineNarrativeCoverageCount = 0
$mainlineGroupingCoverageCount = 0
$progressLaneCoverageCount = 0
$agentsStartGuardrailsPresent = Test-RegexReference -Content $agentsContent -Pattern ('(?m)^##\s+' + [regex]::Escape($agentsStartGuardrailsHeading) + '\s*$')
$agentsFactMapPresent = Test-RegexReference -Content $agentsContent -Pattern ('(?m)^##\s+' + [regex]::Escape($agentsFactMapHeading) + '\s*$')
$agentsFactBaselinesPresent = (Test-LiteralReference -Content $agentsContent -Literal "docs/ARCHITECTURE.md") -and
    (Test-LiteralReference -Content $agentsContent -Literal "docs/API_CONTRACTS.md") -and
    (Test-LiteralReference -Content $agentsContent -Literal "docs/SPEC.md") -and
    (Test-LiteralReference -Content $agentsContent -Literal "docs/TROUBLESHOOT.md")
$agentsProjectOverviewAbsent = -not (Test-RegexReference -Content $agentsContent -Pattern ('(?m)^##\s+' + [regex]::Escape($agentsProjectOverviewHeading) + '\s*$'))
$agentsTechStackAbsent = -not (Test-RegexReference -Content $agentsContent -Pattern ('(?m)^##\s+' + [regex]::Escape($agentsTechStackHeading) + '\s*$'))
$agentsSourceTreeAbsent = -not (Test-RegexReference -Content $agentsContent -Pattern ('(?m)^##\s+' + [regex]::Escape($agentsSourceTreeHeading) + '\s*$'))
$agentsApiCheatsheetAbsent = -not (Test-RegexReference -Content $agentsContent -Pattern ('(?m)^##\s+' + [regex]::Escape($agentsApiCheatsheetHeading) + '\s*$'))
$docsGovernancePathClarityPresent = $false

if (-not $startupBoundaryHeadingPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "startup_boundary_heading_missing" -Target "STARTUP_GUIDE.md" -Reason "STARTUP_GUIDE.md must preserve the boundary section."
}
if (-not $startupDocsRedirectPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "startup_docs_redirect_missing" -Target "STARTUP_GUIDE.md" -Reason "STARTUP_GUIDE.md must route non-startup work to docs/README.md."
}
if (-not $startupAgentRedirectPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "startup_agent_redirect_missing" -Target "STARTUP_GUIDE.md" -Reason "STARTUP_GUIDE.md must route agent work to WAKE.md and AGENTS.md."
}
if (-not $startupDialogueRedirectPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "startup_dialogue_redirect_missing" -Target "STARTUP_GUIDE.md" -Reason "STARTUP_GUIDE.md must route UI/browser work to docs/dialogue/README.md."
}
if (-not $startupProviderRedirectPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "startup_provider_redirect_missing" -Target "STARTUP_GUIDE.md" -Reason "STARTUP_GUIDE.md must route provider/worker work to docs/provider/README.md."
}
if (-not $startupStateDecisionsPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "startup_state_decisions_missing" -Target "STARTUP_GUIDE.md" -Reason "STARTUP_GUIDE.md must route continuity reads to STATE.md and DECISIONS.md."
}
if (-not $docsReadmeRoleHeadingPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_role_heading_missing" -Target "docs/README.md" -Reason "docs/README.md must keep the by-role entry section."
}
if (-not $docsReadmeStartupRoleEntryPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_startup_role_entry_missing" -Target "docs/README.md" -Reason "docs/README.md must keep the startup/verify role entry."
}
if (-not $docsReadmeGovernanceRoleEntryPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_governance_role_entry_missing" -Target "docs/README.md" -Reason "docs/README.md must keep the docs-governance role entry and its follow-up reads."
}
if (-not $docsReadmeAgentRoleEntryPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_agent_role_entry_missing" -Target "docs/README.md" -Reason "docs/README.md must keep the agent handoff role entry."
}
if (-not $docsReadmeStateRoleEntryPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_state_role_entry_missing" -Target "docs/README.md" -Reason "docs/README.md must keep the continuity-read role entry."
}
if (-not $docsReadmeMetaEntryPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_meta_entry_missing" -Target "docs/README.md" -Reason "docs/README.md must keep the meta topic entry."
}
if (-not $docsReadmeGovernanceEntryPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_governance_entry_missing" -Target "docs/README.md" -Reason "docs/README.md must route governance reads to DOCS_GOVERNANCE.md."
}
if (-not $docsReadmeAuditScriptPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_audit_script_missing" -Target "docs/README.md" -Reason "docs/README.md must keep the docs audit script entry."
}
if (-not $docsReadmeFocusedRegressionPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_focused_regression_missing" -Target "docs/README.md" -Reason "docs/README.md must keep the focused docs regression command entry."
}
if (-not (Test-Path -LiteralPath (Join-Path $docsRootPath "DOCS_GOVERNANCE.md"))) {
    throw "docs/DOCS_GOVERNANCE.md not found under docs root: $docsRootPath"
}
$docsGovernanceContent = Read-Utf8Text -Path (Join-Path $docsRootPath "DOCS_GOVERNANCE.md")
$governanceRootEntryPathsPresent = (Test-LiteralReference -Content $docsGovernanceContent -Literal "../README.md") -and
    (Test-LiteralReference -Content $docsGovernanceContent -Literal "../STARTUP_GUIDE.md") -and
    (Test-LiteralReference -Content $docsGovernanceContent -Literal "../WAKE.md") -and
    (Test-LiteralReference -Content $docsGovernanceContent -Literal "../AGENTS.md") -and
    (Test-LiteralReference -Content $docsGovernanceContent -Literal "../STATE.md") -and
    (Test-LiteralReference -Content $docsGovernanceContent -Literal "../DECISIONS.md")
$governanceAuditEntryPathsPresent = (Test-LiteralReference -Content $docsGovernanceContent -Literal "README.md") -and
    (Test-LiteralReference -Content $docsGovernanceContent -Literal "meta/README.md") -and
    (Test-LiteralReference -Content $docsGovernanceContent -Literal "../scripts/Run-DocsIndexAudit.ps1")
$docsGovernancePathClarityPresent = $governanceRootEntryPathsPresent -and $governanceAuditEntryPathsPresent
if (-not $docsGovernancePathClarityPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "docs_governance_path_clarity_missing" -Target "docs/DOCS_GOVERNANCE.md" -Reason "docs/DOCS_GOVERNANCE.md must use file-local relative paths for root entries and governance audit entrypoints."
}
if (-not $agentsStartGuardrailsPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_start_guardrails_missing" -Target "AGENTS.md" -Reason "AGENTS.md must keep the start-work guardrails section."
}
if (-not $agentsFactMapPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_fact_map_missing" -Target "AGENTS.md" -Reason "AGENTS.md must keep the project-facts entry section."
}
if (-not $agentsFactBaselinesPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_fact_baselines_missing" -Target "AGENTS.md" -Reason "AGENTS.md must route stable project facts to the dedicated docs baselines."
}
if (-not $agentsProjectOverviewAbsent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_project_overview_returned" -Target "AGENTS.md" -Reason "AGENTS.md should not grow back into a project overview document."
}
if (-not $agentsTechStackAbsent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_tech_stack_returned" -Target "AGENTS.md" -Reason "AGENTS.md should not duplicate the project technical-stack baseline."
}
if (-not $agentsSourceTreeAbsent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_source_tree_returned" -Target "AGENTS.md" -Reason "AGENTS.md should not duplicate the source-tree baseline."
}
if (-not $agentsApiCheatsheetAbsent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_api_cheatsheet_returned" -Target "AGENTS.md" -Reason "AGENTS.md should not duplicate the API contracts baseline."
}

foreach ($topicDir in $topicDirs) {
    $topicPath = $topicDir.FullName
    $topicName = $topicDir.Name
    $topicReadmePath = Join-Path $topicPath "README.md"
    $topicReadmeRelative = Normalize-RelativePath -BasePath $docsRootPath -TargetPath $topicReadmePath
    $hasReadme = Test-Path -LiteralPath $topicReadmePath
    $topicReadmeContent = if ($hasReadme) { Read-Utf8Text -Path $topicReadmePath } else { "" }

    $topLevelItems = @(Get-ChildItem -LiteralPath $topicPath -Force)
    $unexpectedFiles = @(
        $topLevelItems |
            Where-Object { -not $_.PSIsContainer -and $_.Name -notin $allowedTopicFiles } |
            Sort-Object Name |
            Select-Object -ExpandProperty Name
    )
    $unexpectedDirs = @(
        $topLevelItems |
            Where-Object { $_.PSIsContainer -and $_.Name -notin $allowedTopicDirs } |
            Sort-Object Name |
            Select-Object -ExpandProperty Name
    )

    $hasProgress = Test-Path -LiteralPath (Join-Path $topicPath "PROGRESS.md")
    $hasTasks = Test-Path -LiteralPath (Join-Path $topicPath "tasks")
    $hasRuns = Test-Path -LiteralPath (Join-Path $topicPath "runs")
    $hasArchive = Test-Path -LiteralPath (Join-Path $topicPath "archive")
    $hasTasksReadme = Test-WorkspaceSubdirReadme -TopicPath $topicPath -DirectoryName "tasks"
    $hasRunsReadme = Test-WorkspaceSubdirReadme -TopicPath $topicPath -DirectoryName "runs"
    $hasArchiveReadme = Test-WorkspaceSubdirReadme -TopicPath $topicPath -DirectoryName "archive"
    $docsReadmeReferencesTopic = Test-LiteralReference -Content $docsReadmeContent -Literal $topicReadmeRelative
    $topicState = Resolve-TopicState -HasReadme:$hasReadme -HasProgress:$hasProgress -HasTasks:$hasTasks -HasRuns:$hasRuns -HasArchive:$hasArchive -UnexpectedFiles $unexpectedFiles -UnexpectedDirs $unexpectedDirs
    $readmeOnlyWorkspaceJudgmentPresent = $true
    $readmeOnlyUpgradeGatePresent = $true
    $readmeOnlyReadingOrderPresent = $true
    $subtopicRoutingHeadingPresent = $true
    $entryAdviceHeadingPresent = $true
    $subtopicRoutingTablePresent = $true
    $stableBaselineHeadingPresent = $true
    $stableBaselineNarrativePresent = $true
    $topicReadmeOrderPresent = $true
    $progressSectionOrderPresent = $true
    $mainlineGroupingPresent = $true
    $progressLanePresent = $true
    $runsReadmeSectionCoveragePresent = $true

    $topicEntries += [pscustomobject]@{
        topic = $topicName
        readme = $topicReadmeRelative
        referenced_from_docs_readme = $docsReadmeReferencesTopic
    }

    if (-not $docsReadmeReferencesTopic) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_entry_unreferenced_from_docs_readme" -Target $topicReadmeRelative -Reason "docs/README.md does not reference this topic entry."
    }
    if (-not $hasReadme) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_missing_readme" -Target $topicName -Reason "Topic directory is missing README.md."
    }
    foreach ($name in $unexpectedFiles) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_unexpected_file" -Target ("{0}/{1}" -f $topicName, $name) -Reason "Top-level topic files must stay within README.md or PROGRESS.md."
    }
    foreach ($name in $unexpectedDirs) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_unexpected_dir" -Target ("{0}/{1}" -f $topicName, $name) -Reason "Top-level topic directories must stay within tasks/, runs/, or archive/."
    }
    if ($hasProgress -and -not (Test-LiteralReference -Content $topicReadmeContent -Literal "PROGRESS.md")) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_progress_missing_entry" -Target ("{0}/PROGRESS.md" -f $topicName) -Reason "Topic README must mention PROGRESS.md once the workspace is upgraded."
    }
    if ($hasProgress -and -not (Test-LiteralReference -Content $topicReadmeContent -Literal "README.md -> PROGRESS.md ->")) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_progress_reading_order_missing" -Target ("{0}/README.md" -f $topicName) -Reason "Workspace-enabled topic README must preserve the default README.md -> PROGRESS.md -> current-line reading order."
    }
    $runsReadmeEntryPresent = if ($hasRuns) {
        Test-LiteralReference -Content $topicReadmeContent -Literal "runs/README.md"
    } else {
        $true
    }
    $runsReadingOrderPresent = if ($hasRuns) {
        Test-LiteralReference -Content $topicReadmeContent -Literal ([string]::Concat("README.md -> PROGRESS.md -> ", $currentSublineLiteral, " -> runs/README.md"))
    } else {
        $true
    }
    if ($hasRuns -and -not $runsReadmeEntryPresent) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_runs_readme_entry_missing" -Target ("{0}/README.md" -f $topicName) -Reason "Topic README must explicitly expose runs/README.md once runs/ is enabled."
    }
    if ($hasRuns -and -not $runsReadingOrderPresent) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_runs_reading_order_missing" -Target ("{0}/README.md" -f $topicName) -Reason "Runs-enabled topic README must expose runs/README.md in the default reading order, not just as a directory mention."
    }
    foreach ($folderName in @("tasks", "runs", "archive")) {
        $folderPath = Join-Path $topicPath $folderName
        if ((Test-Path -LiteralPath $folderPath) -and -not (Test-LiteralReference -Content $topicReadmeContent -Literal ("{0}/" -f $folderName))) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_workspace_dir_missing_entry" -Target ("{0}/{1}" -f $topicName, $folderName) -Reason "Topic README must explain enabled workspace directories."
        }
    }
    if ($hasTasks -and -not $hasTasksReadme) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_workspace_dir_missing_readme" -Target ("{0}/tasks" -f $topicName) -Reason "Enabled tasks/ workspace directories must keep a README.md entry."
    }
    if ($hasRuns -and -not $hasRunsReadme) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_workspace_dir_missing_readme" -Target ("{0}/runs" -f $topicName) -Reason "Enabled runs/ workspace directories must keep a README.md entry."
    }
    if ($hasArchive -and -not $hasArchiveReadme) {
        Add-Violation -Bucket ([ref]$violations) -Type "topic_contract_workspace_dir_missing_readme" -Target ("{0}/archive" -f $topicName) -Reason "Enabled archive/ workspace directories must keep a README.md entry."
    }
    if ($hasReadme) {
        foreach ($section in $requiredTopicReadmeSections) {
            if (-not (Test-RegexReference -Content $topicReadmeContent -Pattern $section.pattern)) {
                Add-Violation -Bucket ([ref]$violations) -Type "topic_readme_missing_required_section" -Target ("{0}/README.md" -f $topicName) -Reason $section.reason
            }
        }
        $topicReadmeOrderPresent = Test-HeadingOrder -Content $topicReadmeContent -Headings $requiredTopicReadmeOrder
        if (-not $topicReadmeOrderPresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_readme_section_order_invalid" -Target ("{0}/README.md" -f $topicName) -Reason "Topic README must keep the stable heading order: signals -> minimal reading order -> stable baseline -> current mainline documents -> writeback order."
        }
        if (-not (Test-LiteralReference -Content $topicReadmeContent -Literal $stillTruePhrase)) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_readme_missing_still_true_narrative" -Target ("{0}/README.md" -f $topicName) -Reason "Topic README must explain which baseline docs are still true today."
        }
    }
    if ($hasProgress) {
        $progressPath = Join-Path $topicPath "PROGRESS.md"
        $progressContent = Read-Utf8Text -Path $progressPath
        foreach ($section in $requiredProgressSections) {
            if (-not (Test-RegexReference -Content $progressContent -Pattern $section.pattern)) {
                Add-Violation -Bucket ([ref]$violations) -Type "topic_progress_missing_required_section" -Target ("{0}/PROGRESS.md" -f $topicName) -Reason $section.reason
            }
        }
        $progressSectionOrderPresent = Test-HeadingOrder -Content $progressContent -Headings $requiredProgressOrder
        if (-not $progressSectionOrderPresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_progress_section_order_invalid" -Target ("{0}/PROGRESS.md" -f $topicName) -Reason "PROGRESS.md must keep the stable heading order: current status -> completed -> active tracks -> next steps -> risks."
        }
    }
    if ($hasRuns -and $hasRunsReadme) {
        $runsReadmePath = Join-Path (Join-Path $topicPath "runs") "README.md"
        $runsReadmeContent = Read-Utf8Text -Path $runsReadmePath
        foreach ($section in $requiredRunsReadmeSections) {
            if (-not (Test-RegexReference -Content $runsReadmeContent -Pattern $section.pattern)) {
                Add-Violation -Bucket ([ref]$violations) -Type "topic_runs_readme_missing_required_section" -Target ("{0}/runs/README.md" -f $topicName) -Reason $section.reason
                $runsReadmeSectionCoveragePresent = $false
            }
        }
        if (-not (Test-LiteralReference -Content $runsReadmeContent -Literal $runsAggregationEntryLiteral)) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_runs_readme_missing_aggregation_role" -Target ("{0}/runs/README.md" -f $topicName) -Reason "runs/README.md must explain that it acts as the topic-level evidence aggregation entry."
            $runsReadmeSectionCoveragePresent = $false
        }
    }
    if ($topicName -eq "meta") {
        $metaWritebackChainPresent = Test-LiteralReference -Content $topicReadmeContent -Literal $metaWritebackChainLiteral
        if (-not $metaWritebackChainPresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "meta_writeback_chain_missing" -Target "meta/README.md" -Reason "docs/meta/README.md must keep the default docs-governance writeback chain."
        }
    }
    if ($topicName -in @("continuity", "provider", "dialogue", "evaluation", "release")) {
        $subtopicRoutingHeadingPresent = Test-RegexReference -Content $topicReadmeContent -Pattern ('(?m)^##\s+' + [regex]::Escape($subtopicRoutingHeading) + '\s*$')
        $entryAdviceHeadingPresent = Test-RegexReference -Content $topicReadmeContent -Pattern ('(?m)^##\s+' + [regex]::Escape($entryAdviceHeading) + '\s*$')
        $subtopicRoutingTablePattern = '(?m)^\|\s*' + [regex]::Escape($subtopicCurrentQuestionCell) + '\s*\|\s*' + [regex]::Escape($subtopicReadHereCell) + '\s*\|\s*' + [regex]::Escape($subtopicDrillDownCell) + '\s*\|\s*$'
        $subtopicRoutingTablePresent = Test-RegexReference -Content $topicReadmeContent -Pattern $subtopicRoutingTablePattern
        $stableBaselineHeadingPresent = Test-RegexReference -Content $topicReadmeContent -Pattern ('(?m)^##\s+' + [regex]::Escape($stableBaselineHeading) + '\s*$')
        $stableBaselineNarrativePresent = Test-LiteralReference -Content $topicReadmeContent -Literal $stillTruePhrase
        $mainlineGroupingPresent = (Test-RegexReference -Content $topicReadmeContent -Pattern ('(?m)^##\s+' + [regex]::Escape($currentMainlineHeading) + '\s*$')) -and
            (Test-RegexReference -Content $topicReadmeContent -Pattern '(?m)^###\s+.+$')
        $progressLanePresent = if ($hasProgress) {
            Test-RegexReference -Content $topicReadmeContent -Pattern ('(?m)^###\s+' + [regex]::Escape($topicProgressSubheading) + '\s*$')
        } else {
            $true
        }

        if (-not $subtopicRoutingHeadingPresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_subtopic_routing_heading_missing" -Target ("{0}/README.md" -f $topicName) -Reason "Business topic README must keep the subtopic-routing section."
        }
        if (-not $entryAdviceHeadingPresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_entry_advice_heading_missing" -Target ("{0}/README.md" -f $topicName) -Reason "Business topic README must keep the current-entry-advice section."
        }
        if (-not $subtopicRoutingTablePresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_subtopic_routing_table_missing" -Target ("{0}/README.md" -f $topicName) -Reason "Business topic README must keep the subtopic-routing decision table."
        }
        if (-not $mainlineGroupingPresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_current_mainline_grouping_missing" -Target ("{0}/README.md" -f $topicName) -Reason "Business topic README must keep grouped subsections under the current-mainline-documents section."
        }
        if (-not $progressLanePresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_progress_lane_missing" -Target ("{0}/README.md" -f $topicName) -Reason "Workspace-enabled business topic README must keep the theme-progress subsection under current mainline documents."
        }
    }
    if ($topicState -eq "readme_only") {
        $readmeOnlyWorkspaceJudgmentPresent = Test-RegexReference -Content $topicReadmeContent -Pattern ('(?m)^##\s+' + [regex]::Escape($workspaceJudgmentHeading) + '\s*$')
        $readmeOnlyUpgradeGatePresent = Test-RegexReference -Content $topicReadmeContent -Pattern ('(?m)^##\s+' + [regex]::Escape($upgradeWhenHeading) + '\s*$')
        $readmeOnlyReadingOrderPresent = Test-ReadmeOnlyTopicReadingOrder -Content $topicReadmeContent

        if (-not $readmeOnlyWorkspaceJudgmentPresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_readme_only_workspace_judgment_missing" -Target ("{0}/README.md" -f $topicName) -Reason "README-only topic README must explain the current workspace judgment."
        }
        if (-not $readmeOnlyUpgradeGatePresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_readme_only_upgrade_gate_missing" -Target ("{0}/README.md" -f $topicName) -Reason "README-only topic README must explain when the topic should be upgraded."
        }
        if (-not $readmeOnlyReadingOrderPresent) {
            Add-Violation -Bucket ([ref]$violations) -Type "topic_readme_only_reading_order_missing" -Target ("{0}/README.md" -f $topicName) -Reason "README-only topic README must preserve the default README.md -> docs/ root-line reading order."
        }
    }

    $docsReadmeWorkspaceRowPresent = Test-DocsReadmeWorkspaceRow -Content $docsReadmeContent -TopicName $topicName -TopicState $topicState -ReadmeOnlyLabel $readmeOnlyLabel
    if (-not $docsReadmeWorkspaceRowPresent) {
        Add-Violation -Bucket ([ref]$violations) -Type "docs_readme_workspace_state_out_of_sync" -Target ("docs/README.md -> {0}" -f $topicName) -Reason "docs/README.md current workspace status table does not match the real topic state."
    }

    if ($hasReadme) {
        $auditSources += [pscustomobject]@{
            path = $topicReadmeRelative
            content = $topicReadmeContent
        }
    }

    $topicStates += [pscustomobject]@{
        topic = $topicName
        state = $topicState
        readme = $hasReadme
        progress = $hasProgress
        tasks = $hasTasks
        runs = $hasRuns
        archive = $hasArchive
        tasks_readme = $hasTasksReadme
        runs_readme = $hasRunsReadme
        archive_readme = $hasArchiveReadme
        readme_only_workspace_judgment_present = $readmeOnlyWorkspaceJudgmentPresent
        readme_only_upgrade_gate_present = $readmeOnlyUpgradeGatePresent
        readme_only_reading_order_present = $readmeOnlyReadingOrderPresent
        subtopic_routing_heading_present = $subtopicRoutingHeadingPresent
        entry_advice_heading_present = $entryAdviceHeadingPresent
        subtopic_routing_table_present = $subtopicRoutingTablePresent
        stable_baseline_heading_present = $stableBaselineHeadingPresent
        stable_baseline_narrative_present = $stableBaselineNarrativePresent
        topic_readme_order_present = $topicReadmeOrderPresent
        progress_section_order_present = $progressSectionOrderPresent
        current_mainline_grouping_present = $mainlineGroupingPresent
        progress_lane_present = $progressLanePresent
        runs_readme_entry_present = $runsReadmeEntryPresent
        runs_reading_order_present = $runsReadingOrderPresent
        runs_readme_section_coverage_present = $runsReadmeSectionCoveragePresent
        unexpected_files = @($unexpectedFiles)
        unexpected_dirs = @($unexpectedDirs)
    }
    $workspaceRows += [pscustomobject]@{
        topic = $topicName
        state = $topicState
        docs_readme_row_present = $docsReadmeWorkspaceRowPresent
    }
}

$rootMarkdownFiles = @(
    Get-ChildItem -LiteralPath $docsRootPath -File -Filter "*.md" |
        Where-Object { $_.Name -ne "README.md" } |
        Sort-Object Name
)

$rootDocs = @()
$datedDocs = @()
foreach ($rootFile in $rootMarkdownFiles) {
    $references = @()
    $topicReferences = @()
    foreach ($source in $auditSources) {
        if (Test-LiteralReference -Content $source.content -Literal $rootFile.Name) {
            $references += $source.path
            if ($source.path -ne "README.md") {
                $topicReferences += $source.path
            }
        }
    }

    $referenced = $references.Count -gt 0
    if (-not $referenced) {
        Add-Violation -Bucket ([ref]$violations) -Type "orphan_root_doc" -Target $rootFile.Name -Reason "Root-level docs Markdown is not reachable from docs/README.md or any topic README."
    }
    if ($topicReferences.Count -eq 0) {
        Add-Violation -Bucket ([ref]$violations) -Type "root_doc_missing_topic_entry" -Target $rootFile.Name -Reason "Root-level docs Markdown must be reachable from at least one topic README, not only from docs/README.md."
    }

    $rootDocs += [pscustomobject]@{
        name = $rootFile.Name
        referenced = $referenced
        referenced_by = @($references)
        referenced_by_topics = @($topicReferences)
    }

    if ([regex]::IsMatch($rootFile.Name, $datedDocSuffixPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        $isCoreDatedDoc = Test-AnyRegexMatch -Value $rootFile.Name -Patterns $coreDatedDocPatterns
        $isHistoricalDatedDoc = Test-AnyRegexMatch -Value $rootFile.Name -Patterns $historicalDatedDocPatterns

        if (-not $isCoreDatedDoc -and -not $isHistoricalDatedDoc) {
            Add-Violation -Bucket ([ref]$violations) -Type "dated_doc_name_contract_violation" -Target $rootFile.Name -Reason "Dated docs must use the current core naming contract or an explicitly grandfathered historical exception."
        }

        $datedDocs += [pscustomobject]@{
            name = $rootFile.Name
            category = if ($isCoreDatedDoc) { "core_contract" } elseif ($isHistoricalDatedDoc) { "historical_exception" } else { "violation" }
        }
    }
}

$referencedRootDocs = @($rootDocs | Where-Object { $_.referenced })
$orphanRootDocs = @($rootDocs | Where-Object { -not $_.referenced })
$topicLinkedRootDocs = @($rootDocs | Where-Object { $_.referenced_by_topics.Count -gt 0 })
$docsReadmeOnlyRootDocs = @($rootDocs | Where-Object { $_.referenced -and $_.referenced_by_topics.Count -eq 0 })
$readmeOnlyTopics = @($topicStates | Where-Object { $_.state -eq "readme_only" })
$workspaceEnabledTopics = @($topicStates | Where-Object { $_.state -eq "workspace_enabled" })
$coreDatedDocs = @($datedDocs | Where-Object { $_.category -eq "core_contract" })
$historicalDatedDocs = @($datedDocs | Where-Object { $_.category -eq "historical_exception" })
$datedDocViolations = @($datedDocs | Where-Object { $_.category -eq "violation" })
$wakeWorkspaceRows = @()
$agentsWorkspaceRows = @()
$businessTopics = @($topicStates | Where-Object { $_.topic -in @("continuity", "provider", "dialogue", "evaluation", "release") })
$subtopicRoutingCoverageCount = @($businessTopics | Where-Object { $_.subtopic_routing_heading_present }).Count
$entryAdviceCoverageCount = @($businessTopics | Where-Object { $_.entry_advice_heading_present }).Count
$subtopicRoutingTableCoverageCount = @($businessTopics | Where-Object { $_.subtopic_routing_table_present }).Count
$stableBaselineCoverageCount = @($businessTopics | Where-Object { $_.stable_baseline_heading_present }).Count
$stableBaselineNarrativeCoverageCount = @($businessTopics | Where-Object { $_.stable_baseline_narrative_present }).Count
$topicReadmeOrderCoverageCount = @($topicStates | Where-Object { $_.topic_readme_order_present }).Count
$progressSectionOrderCoverageCount = @($topicStates | Where-Object { $_.progress -and $_.progress_section_order_present }).Count
$mainlineGroupingCoverageCount = @($businessTopics | Where-Object { $_.current_mainline_grouping_present }).Count
$progressLaneCoverageCount = @($businessTopics | Where-Object { $_.progress -and $_.progress_lane_present }).Count
$runsReadmeEntryCoverageCount = @($topicStates | Where-Object { $_.runs -and $_.runs_readme_entry_present }).Count
$runsReadingOrderCoverageCount = @($topicStates | Where-Object { $_.runs -and $_.runs_reading_order_present }).Count
$runsReadmeSectionCoverageCount = @($topicStates | Where-Object { $_.runs -and $_.runs_readme_section_coverage_present }).Count

foreach ($topicState in $topicStates) {
    if ($topicState.state -notin @("workspace_enabled", "readme_only")) {
        continue
    }

    $wakeRowPresent = Test-RootEntryWorkspaceRow -Content $wakeContent -TopicName $topicState.topic -TopicState $topicState.state
    if (-not $wakeRowPresent) {
        Add-Violation -Bucket ([ref]$violations) -Type "wake_workspace_state_out_of_sync" -Target ("WAKE.md -> {0}" -f $topicState.topic) -Reason "WAKE.md current workspace state block does not match the real topic state."
    }
    $wakeWorkspaceRows += [pscustomobject]@{
        topic = $topicState.topic
        state = $topicState.state
        row_present = $wakeRowPresent
    }

    $agentsRowPresent = Test-RootEntryWorkspaceRow -Content $agentsContent -TopicName $topicState.topic -TopicState $topicState.state
    if (-not $agentsRowPresent) {
        Add-Violation -Bucket ([ref]$violations) -Type "agents_workspace_state_out_of_sync" -Target ("AGENTS.md -> {0}" -f $topicState.topic) -Reason "AGENTS.md current workspace state block does not match the real topic state."
    }
    $agentsWorkspaceRows += [pscustomobject]@{
        topic = $topicState.topic
        state = $topicState.state
        row_present = $agentsRowPresent
    }
}

$wakeProgressReadingOrderPresent = if ($workspaceEnabledTopics.Count -gt 0) { Test-RootEntryProgressReadingOrder -Content $wakeContent } else { $true }
$agentsProgressReadingOrderPresent = if ($workspaceEnabledTopics.Count -gt 0) { Test-RootEntryProgressReadingOrder -Content $agentsContent } else { $true }
$wakeReadmeOnlyReadingOrderPresent = if ($readmeOnlyTopics.Count -gt 0) { Test-RootEntryReadmeOnlyReadingOrder -Content $wakeContent } else { $true }
$agentsReadmeOnlyReadingOrderPresent = if ($readmeOnlyTopics.Count -gt 0) { Test-RootEntryReadmeOnlyReadingOrder -Content $agentsContent } else { $true }

if (-not $wakeProgressReadingOrderPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "wake_progress_reading_order_missing" -Target "WAKE.md" -Reason "WAKE.md must preserve the default reading order for topics that already enabled PROGRESS.md."
}
if (-not $agentsProgressReadingOrderPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_progress_reading_order_missing" -Target "AGENTS.md" -Reason "AGENTS.md must preserve the default reading order for topics that already enabled PROGRESS.md."
}
if (-not $wakeReadmeOnlyReadingOrderPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "wake_readme_only_reading_order_missing" -Target "WAKE.md" -Reason "WAKE.md must preserve the default reading order for README-only topics."
}
if (-not $agentsReadmeOnlyReadingOrderPresent) {
    Add-Violation -Bucket ([ref]$violations) -Type "agents_readme_only_reading_order_missing" -Target "AGENTS.md" -Reason "AGENTS.md must preserve the default reading order for README-only topics."
}

$summary = [pscustomobject]@{
    topic_count = $topicStates.Count
    readme_only_topics = $readmeOnlyTopics.Count
    workspace_enabled_topics = $workspaceEnabledTopics.Count
    readme_navigation_heading_present = $readmeNavigationHeadingPresent
    readme_docs_index_present = $readmeDocsIndexPresent
    readme_meta_governance_present = $readmeMetaGovernancePresent
    readme_agent_entry_present = $readmeAgentEntryPresent
    readme_state_decisions_present = $readmeStateDecisionsPresent
    startup_boundary_heading_present = $startupBoundaryHeadingPresent
    startup_docs_redirect_present = $startupDocsRedirectPresent
    startup_agent_redirect_present = $startupAgentRedirectPresent
    startup_dialogue_redirect_present = $startupDialogueRedirectPresent
    startup_provider_redirect_present = $startupProviderRedirectPresent
    startup_state_decisions_present = $startupStateDecisionsPresent
    docs_readme_role_heading_present = $docsReadmeRoleHeadingPresent
    docs_readme_startup_role_entry_present = $docsReadmeStartupRoleEntryPresent
    docs_readme_governance_role_entry_present = $docsReadmeGovernanceRoleEntryPresent
    docs_readme_agent_role_entry_present = $docsReadmeAgentRoleEntryPresent
    docs_readme_state_role_entry_present = $docsReadmeStateRoleEntryPresent
    docs_readme_meta_entry_present = $docsReadmeMetaEntryPresent
    docs_readme_governance_entry_present = $docsReadmeGovernanceEntryPresent
    docs_readme_audit_script_present = $docsReadmeAuditScriptPresent
    docs_readme_focused_regression_present = $docsReadmeFocusedRegressionPresent
    docs_governance_path_clarity_present = $docsGovernancePathClarityPresent
    meta_writeback_chain_present = $metaWritebackChainPresent
    agents_start_guardrails_present = $agentsStartGuardrailsPresent
    agents_fact_map_present = $agentsFactMapPresent
    agents_fact_baselines_present = $agentsFactBaselinesPresent
    agents_project_overview_absent = $agentsProjectOverviewAbsent
    agents_tech_stack_absent = $agentsTechStackAbsent
    agents_source_tree_absent = $agentsSourceTreeAbsent
    agents_api_cheatsheet_absent = $agentsApiCheatsheetAbsent
    root_markdown_count = $rootDocs.Count
    dated_doc_count = $datedDocs.Count
    core_dated_doc_count = $coreDatedDocs.Count
    historical_dated_doc_count = $historicalDatedDocs.Count
    referenced_root_markdown_count = $referencedRootDocs.Count
    topic_linked_root_markdown_count = $topicLinkedRootDocs.Count
    orphan_root_markdown_count = $orphanRootDocs.Count
    docs_readme_only_root_markdown_count = $docsReadmeOnlyRootDocs.Count
    workspace_row_coverage_count = @($workspaceRows | Where-Object { $_.docs_readme_row_present }).Count
    wake_workspace_row_coverage_count = @($wakeWorkspaceRows | Where-Object { $_.row_present }).Count
    agents_workspace_row_coverage_count = @($agentsWorkspaceRows | Where-Object { $_.row_present }).Count
    subtopic_routing_coverage_count = $subtopicRoutingCoverageCount
    entry_advice_coverage_count = $entryAdviceCoverageCount
    subtopic_routing_table_coverage_count = $subtopicRoutingTableCoverageCount
    stable_baseline_coverage_count = $stableBaselineCoverageCount
    stable_baseline_narrative_coverage_count = $stableBaselineNarrativeCoverageCount
    topic_readme_order_coverage_count = $topicReadmeOrderCoverageCount
    progress_section_order_coverage_count = $progressSectionOrderCoverageCount
    current_mainline_grouping_coverage_count = $mainlineGroupingCoverageCount
    progress_lane_coverage_count = $progressLaneCoverageCount
    runs_readme_entry_coverage_count = $runsReadmeEntryCoverageCount
    runs_reading_order_coverage_count = $runsReadingOrderCoverageCount
    runs_readme_section_coverage_count = $runsReadmeSectionCoverageCount
    readme_only_workspace_judgment_coverage_count = @($topicStates | Where-Object { $_.state -eq "readme_only" -and $_.readme_only_workspace_judgment_present }).Count
    readme_only_upgrade_gate_coverage_count = @($topicStates | Where-Object { $_.state -eq "readme_only" -and $_.readme_only_upgrade_gate_present }).Count
    readme_only_reading_order_coverage_count = @($topicStates | Where-Object { $_.state -eq "readme_only" -and $_.readme_only_reading_order_present }).Count
    wake_progress_reading_order_present = $wakeProgressReadingOrderPresent
    agents_progress_reading_order_present = $agentsProgressReadingOrderPresent
    wake_readme_only_reading_order_present = $wakeReadmeOnlyReadingOrderPresent
    agents_readme_only_reading_order_present = $agentsReadmeOnlyReadingOrderPresent
    dated_doc_violation_count = $datedDocViolations.Count
    violation_count = $violations.Count
    passed = ($violations.Count -eq 0)
}

$result = [pscustomobject]@{
    generated_at = (Get-Date).ToString("s")
    repo_root = $repoRootPath
    docs_root = $docsRootPath
    summary = $summary
    topic_entries = @($topicEntries)
    topic_states = @($topicStates)
    workspace_rows = @($workspaceRows)
    wake_workspace_rows = @($wakeWorkspaceRows)
    agents_workspace_rows = @($agentsWorkspaceRows)
    root_entry_contracts = [pscustomobject]@{
        readme = [pscustomobject]@{
            navigation_heading_present = $readmeNavigationHeadingPresent
            docs_index_present = $readmeDocsIndexPresent
            meta_governance_present = $readmeMetaGovernancePresent
            agent_entry_present = $readmeAgentEntryPresent
            state_decisions_present = $readmeStateDecisionsPresent
        }
        startup_guide = [pscustomobject]@{
            boundary_heading_present = $startupBoundaryHeadingPresent
            docs_redirect_present = $startupDocsRedirectPresent
            agent_redirect_present = $startupAgentRedirectPresent
            dialogue_redirect_present = $startupDialogueRedirectPresent
            provider_redirect_present = $startupProviderRedirectPresent
            state_decisions_present = $startupStateDecisionsPresent
        }
        docs_readme = [pscustomobject]@{
            role_heading_present = $docsReadmeRoleHeadingPresent
            startup_role_entry_present = $docsReadmeStartupRoleEntryPresent
            governance_role_entry_present = $docsReadmeGovernanceRoleEntryPresent
            agent_role_entry_present = $docsReadmeAgentRoleEntryPresent
            state_role_entry_present = $docsReadmeStateRoleEntryPresent
            meta_entry_present = $docsReadmeMetaEntryPresent
            governance_entry_present = $docsReadmeGovernanceEntryPresent
            audit_script_present = $docsReadmeAuditScriptPresent
            focused_regression_present = $docsReadmeFocusedRegressionPresent
        }
        docs_governance = [pscustomobject]@{
            path_clarity_present = $docsGovernancePathClarityPresent
        }
        meta = [pscustomobject]@{
            writeback_chain_present = $metaWritebackChainPresent
        }
        agents = [pscustomobject]@{
            start_guardrails_present = $agentsStartGuardrailsPresent
            fact_map_present = $agentsFactMapPresent
            fact_baselines_present = $agentsFactBaselinesPresent
            project_overview_absent = $agentsProjectOverviewAbsent
            tech_stack_absent = $agentsTechStackAbsent
            source_tree_absent = $agentsSourceTreeAbsent
            api_cheatsheet_absent = $agentsApiCheatsheetAbsent
        }
    }
    root_docs = @($rootDocs)
    dated_docs = @($datedDocs)
    orphan_root_docs = @($orphanRootDocs)
    violations = @($violations)
}

if ($WriteMarkdown) {
    $markdownTick = [char]0x60
    if (-not $MarkdownPath) {
        $MarkdownPath = Join-Path $repoRootPath ".tmp/docs-index-audit.md"
    }
    elseif (-not [System.IO.Path]::IsPathRooted($MarkdownPath)) {
        $MarkdownPath = Join-Path $repoRootPath $MarkdownPath
    }

    $markdownParent = Split-Path -Parent $MarkdownPath
    if ($markdownParent) {
        New-Item -ItemType Directory -Force -Path $markdownParent | Out-Null
    }

    $out = @()
    $out += "# Docs Index Audit"
    $out += ""
    $out += "> Generated by `scripts/Run-DocsIndexAudit.ps1` against the current workspace."
    $out += ""
    $out += "## Summary"
    $out += ""
    $out += ("- topic_count: {0}" -f $summary.topic_count)
    $out += ("- readme_only_topics: {0}" -f $summary.readme_only_topics)
    $out += ("- workspace_enabled_topics: {0}" -f $summary.workspace_enabled_topics)
    $out += ("- readme_navigation_heading_present: {0}" -f $summary.readme_navigation_heading_present.ToString().ToLowerInvariant())
    $out += ("- readme_docs_index_present: {0}" -f $summary.readme_docs_index_present.ToString().ToLowerInvariant())
    $out += ("- readme_meta_governance_present: {0}" -f $summary.readme_meta_governance_present.ToString().ToLowerInvariant())
    $out += ("- readme_agent_entry_present: {0}" -f $summary.readme_agent_entry_present.ToString().ToLowerInvariant())
    $out += ("- readme_state_decisions_present: {0}" -f $summary.readme_state_decisions_present.ToString().ToLowerInvariant())
    $out += ("- startup_boundary_heading_present: {0}" -f $summary.startup_boundary_heading_present.ToString().ToLowerInvariant())
    $out += ("- startup_docs_redirect_present: {0}" -f $summary.startup_docs_redirect_present.ToString().ToLowerInvariant())
    $out += ("- startup_agent_redirect_present: {0}" -f $summary.startup_agent_redirect_present.ToString().ToLowerInvariant())
    $out += ("- startup_dialogue_redirect_present: {0}" -f $summary.startup_dialogue_redirect_present.ToString().ToLowerInvariant())
    $out += ("- startup_provider_redirect_present: {0}" -f $summary.startup_provider_redirect_present.ToString().ToLowerInvariant())
    $out += ("- startup_state_decisions_present: {0}" -f $summary.startup_state_decisions_present.ToString().ToLowerInvariant())
    $out += ("- meta_writeback_chain_present: {0}" -f $summary.meta_writeback_chain_present.ToString().ToLowerInvariant())
    $out += ("- docs_governance_path_clarity_present: {0}" -f $summary.docs_governance_path_clarity_present.ToString().ToLowerInvariant())
    $out += ("- root_markdown_count: {0}" -f $summary.root_markdown_count)
    $out += ("- dated_doc_count: {0}" -f $summary.dated_doc_count)
    $out += ("- core_dated_doc_count: {0}" -f $summary.core_dated_doc_count)
    $out += ("- historical_dated_doc_count: {0}" -f $summary.historical_dated_doc_count)
    $out += ("- referenced_root_markdown_count: {0}" -f $summary.referenced_root_markdown_count)
    $out += ("- topic_linked_root_markdown_count: {0}" -f $summary.topic_linked_root_markdown_count)
    $out += ("- orphan_root_markdown_count: {0}" -f $summary.orphan_root_markdown_count)
    $out += ("- docs_readme_only_root_markdown_count: {0}" -f $summary.docs_readme_only_root_markdown_count)
    $out += ("- workspace_row_coverage_count: {0}" -f $summary.workspace_row_coverage_count)
    $out += ("- wake_workspace_row_coverage_count: {0}" -f $summary.wake_workspace_row_coverage_count)
    $out += ("- agents_workspace_row_coverage_count: {0}" -f $summary.agents_workspace_row_coverage_count)
    $out += ("- subtopic_routing_coverage_count: {0}" -f $summary.subtopic_routing_coverage_count)
    $out += ("- entry_advice_coverage_count: {0}" -f $summary.entry_advice_coverage_count)
    $out += ("- subtopic_routing_table_coverage_count: {0}" -f $summary.subtopic_routing_table_coverage_count)
    $out += ("- stable_baseline_coverage_count: {0}" -f $summary.stable_baseline_coverage_count)
    $out += ("- stable_baseline_narrative_coverage_count: {0}" -f $summary.stable_baseline_narrative_coverage_count)
    $out += ("- topic_readme_order_coverage_count: {0}" -f $summary.topic_readme_order_coverage_count)
    $out += ("- progress_section_order_coverage_count: {0}" -f $summary.progress_section_order_coverage_count)
$out += ("- current_mainline_grouping_coverage_count: {0}" -f $summary.current_mainline_grouping_coverage_count)
$out += ("- progress_lane_coverage_count: {0}" -f $summary.progress_lane_coverage_count)
$out += ("- runs_readme_entry_coverage_count: {0}" -f $summary.runs_readme_entry_coverage_count)
$out += ("- runs_reading_order_coverage_count: {0}" -f $summary.runs_reading_order_coverage_count)
$out += ("- runs_readme_section_coverage_count: {0}" -f $summary.runs_readme_section_coverage_count)
    $out += ("- readme_only_workspace_judgment_coverage_count: {0}" -f $summary.readme_only_workspace_judgment_coverage_count)
    $out += ("- readme_only_upgrade_gate_coverage_count: {0}" -f $summary.readme_only_upgrade_gate_coverage_count)
    $out += ("- readme_only_reading_order_coverage_count: {0}" -f $summary.readme_only_reading_order_coverage_count)
    $out += ("- wake_progress_reading_order_present: {0}" -f $summary.wake_progress_reading_order_present.ToString().ToLowerInvariant())
    $out += ("- agents_progress_reading_order_present: {0}" -f $summary.agents_progress_reading_order_present.ToString().ToLowerInvariant())
    $out += ("- wake_readme_only_reading_order_present: {0}" -f $summary.wake_readme_only_reading_order_present.ToString().ToLowerInvariant())
    $out += ("- agents_readme_only_reading_order_present: {0}" -f $summary.agents_readme_only_reading_order_present.ToString().ToLowerInvariant())
    $out += ("- dated_doc_violation_count: {0}" -f $summary.dated_doc_violation_count)
    $out += ("- violation_count: {0}" -f $summary.violation_count)
    $out += ""
    $out += "## Root Public Entry Contracts"
    $out += ""
    $out += ("- " + $markdownTick + "README.md" + $markdownTick + ": navigation_heading=" + $markdownTick + $readmeNavigationHeadingPresent.ToString().ToLowerInvariant() + $markdownTick + ", docs_index=" + $markdownTick + $readmeDocsIndexPresent.ToString().ToLowerInvariant() + $markdownTick + ", meta_governance=" + $markdownTick + $readmeMetaGovernancePresent.ToString().ToLowerInvariant() + $markdownTick + ", agent_entry=" + $markdownTick + $readmeAgentEntryPresent.ToString().ToLowerInvariant() + $markdownTick + ", state_decisions=" + $markdownTick + $readmeStateDecisionsPresent.ToString().ToLowerInvariant() + $markdownTick)
    $out += ("- " + $markdownTick + "STARTUP_GUIDE.md" + $markdownTick + ": boundary_heading=" + $markdownTick + $startupBoundaryHeadingPresent.ToString().ToLowerInvariant() + $markdownTick + ", docs_redirect=" + $markdownTick + $startupDocsRedirectPresent.ToString().ToLowerInvariant() + $markdownTick + ", agent_redirect=" + $markdownTick + $startupAgentRedirectPresent.ToString().ToLowerInvariant() + $markdownTick + ", dialogue_redirect=" + $markdownTick + $startupDialogueRedirectPresent.ToString().ToLowerInvariant() + $markdownTick + ", provider_redirect=" + $markdownTick + $startupProviderRedirectPresent.ToString().ToLowerInvariant() + $markdownTick + ", state_decisions=" + $markdownTick + $startupStateDecisionsPresent.ToString().ToLowerInvariant() + $markdownTick)
    $out += ("- " + $markdownTick + "docs/README.md" + $markdownTick + ": role_heading=" + $markdownTick + $docsReadmeRoleHeadingPresent.ToString().ToLowerInvariant() + $markdownTick + ", startup_role=" + $markdownTick + $docsReadmeStartupRoleEntryPresent.ToString().ToLowerInvariant() + $markdownTick + ", governance_role=" + $markdownTick + $docsReadmeGovernanceRoleEntryPresent.ToString().ToLowerInvariant() + $markdownTick + ", agent_role=" + $markdownTick + $docsReadmeAgentRoleEntryPresent.ToString().ToLowerInvariant() + $markdownTick + ", state_role=" + $markdownTick + $docsReadmeStateRoleEntryPresent.ToString().ToLowerInvariant() + $markdownTick + ", meta_entry=" + $markdownTick + $docsReadmeMetaEntryPresent.ToString().ToLowerInvariant() + $markdownTick + ", governance_entry=" + $markdownTick + $docsReadmeGovernanceEntryPresent.ToString().ToLowerInvariant() + $markdownTick + ", audit_script=" + $markdownTick + $docsReadmeAuditScriptPresent.ToString().ToLowerInvariant() + $markdownTick + ", focused_regression=" + $markdownTick + $docsReadmeFocusedRegressionPresent.ToString().ToLowerInvariant() + $markdownTick)
    $out += ("- " + $markdownTick + "docs/DOCS_GOVERNANCE.md" + $markdownTick + ": path_clarity=" + $markdownTick + $docsGovernancePathClarityPresent.ToString().ToLowerInvariant() + $markdownTick)
    $out += ("- " + $markdownTick + "docs/meta/README.md" + $markdownTick + ": writeback_chain=" + $markdownTick + $metaWritebackChainPresent.ToString().ToLowerInvariant() + $markdownTick)
    $out += ("- " + $markdownTick + "AGENTS.md" + $markdownTick + ": start_guardrails=" + $markdownTick + $agentsStartGuardrailsPresent.ToString().ToLowerInvariant() + $markdownTick + ", fact_map=" + $markdownTick + $agentsFactMapPresent.ToString().ToLowerInvariant() + $markdownTick + ", fact_baselines=" + $markdownTick + $agentsFactBaselinesPresent.ToString().ToLowerInvariant() + $markdownTick + ", project_overview_absent=" + $markdownTick + $agentsProjectOverviewAbsent.ToString().ToLowerInvariant() + $markdownTick + ", tech_stack_absent=" + $markdownTick + $agentsTechStackAbsent.ToString().ToLowerInvariant() + $markdownTick + ", source_tree_absent=" + $markdownTick + $agentsSourceTreeAbsent.ToString().ToLowerInvariant() + $markdownTick + ", api_cheatsheet_absent=" + $markdownTick + $agentsApiCheatsheetAbsent.ToString().ToLowerInvariant() + $markdownTick)
    $out += ""
    $out += "## Topic States"
    $out += ""
    foreach ($topicState in $topicStates) {
        $enabled = @()
        if ($topicState.progress) { $enabled += "PROGRESS.md" }
        if ($topicState.tasks) { $enabled += "tasks/" }
        if ($topicState.runs) { $enabled += "runs/" }
        if ($topicState.archive) { $enabled += "archive/" }
        $enabledText = if ($enabled.Count -eq 0) { "none" } else { ($enabled -join ", ") }
        $line = ("- " + $markdownTick + $topicState.topic + $markdownTick + ": state=" + $markdownTick + $topicState.state + $markdownTick + ", enabled=" + $markdownTick + $enabledText + $markdownTick)
        $line += ", readme_order=" + $markdownTick + $topicState.topic_readme_order_present.ToString().ToLowerInvariant() + $markdownTick
        if ($topicState.progress) {
            $line += ", progress_order=" + $markdownTick + $topicState.progress_section_order_present.ToString().ToLowerInvariant() + $markdownTick
        }
        if ($topicState.state -eq "readme_only") {
            $line += ", workspace_judgment=" + $markdownTick + $topicState.readme_only_workspace_judgment_present.ToString().ToLowerInvariant() + $markdownTick
            $line += ", upgrade_gate=" + $markdownTick + $topicState.readme_only_upgrade_gate_present.ToString().ToLowerInvariant() + $markdownTick
            $line += ", reading_order=" + $markdownTick + $topicState.readme_only_reading_order_present.ToString().ToLowerInvariant() + $markdownTick
        }
        if ($topicState.topic -in @("continuity", "provider", "dialogue", "evaluation", "release")) {
            $line += ", subtopic_routing=" + $markdownTick + $topicState.subtopic_routing_heading_present.ToString().ToLowerInvariant() + $markdownTick
            $line += ", entry_advice=" + $markdownTick + $topicState.entry_advice_heading_present.ToString().ToLowerInvariant() + $markdownTick
            $line += ", routing_table=" + $markdownTick + $topicState.subtopic_routing_table_present.ToString().ToLowerInvariant() + $markdownTick
            $line += ", stable_baseline=" + $markdownTick + $topicState.stable_baseline_heading_present.ToString().ToLowerInvariant() + $markdownTick
            $line += ", baseline_narrative=" + $markdownTick + $topicState.stable_baseline_narrative_present.ToString().ToLowerInvariant() + $markdownTick
            $line += ", mainline_grouping=" + $markdownTick + $topicState.current_mainline_grouping_present.ToString().ToLowerInvariant() + $markdownTick
            if ($topicState.progress) {
                $line += ", progress_lane=" + $markdownTick + $topicState.progress_lane_present.ToString().ToLowerInvariant() + $markdownTick
            }
        }
        $out += $line
    }
    $out += ""
    $out += "## Workspace Rows"
    $out += ""
    foreach ($workspaceRow in $workspaceRows) {
        $out += ("- " + $markdownTick + $workspaceRow.topic + $markdownTick + ": state=" + $markdownTick + $workspaceRow.state + $markdownTick + ", docs_readme_row_present=" + $markdownTick + $workspaceRow.docs_readme_row_present.ToString().ToLowerInvariant() + $markdownTick)
    }
    $out += ""
    $out += "## Root Entry Rows"
    $out += ""
    foreach ($workspaceRow in $wakeWorkspaceRows) {
        $out += ("- " + $markdownTick + "WAKE.md -> " + $workspaceRow.topic + $markdownTick + ": state=" + $markdownTick + $workspaceRow.state + $markdownTick + ", row_present=" + $markdownTick + $workspaceRow.row_present.ToString().ToLowerInvariant() + $markdownTick)
    }
    foreach ($workspaceRow in $agentsWorkspaceRows) {
        $out += ("- " + $markdownTick + "AGENTS.md -> " + $workspaceRow.topic + $markdownTick + ": state=" + $markdownTick + $workspaceRow.state + $markdownTick + ", row_present=" + $markdownTick + $workspaceRow.row_present.ToString().ToLowerInvariant() + $markdownTick)
    }
    $out += ""
    $out += "## Root Entry Reading Order"
    $out += ""
    $out += ("- " + $markdownTick + "WAKE.md" + $markdownTick + ": progress_path=" + $markdownTick + $wakeProgressReadingOrderPresent.ToString().ToLowerInvariant() + $markdownTick + ", readme_only_path=" + $markdownTick + $wakeReadmeOnlyReadingOrderPresent.ToString().ToLowerInvariant() + $markdownTick)
    $out += ("- " + $markdownTick + "AGENTS.md" + $markdownTick + ": progress_path=" + $markdownTick + $agentsProgressReadingOrderPresent.ToString().ToLowerInvariant() + $markdownTick + ", readme_only_path=" + $markdownTick + $agentsReadmeOnlyReadingOrderPresent.ToString().ToLowerInvariant() + $markdownTick)
    $out += ""
    $out += "## Dated Docs"
    $out += ""
    foreach ($datedDoc in $datedDocs) {
        $out += ("- " + $markdownTick + $datedDoc.name + $markdownTick + ": category=" + $markdownTick + $datedDoc.category + $markdownTick)
    }
    $out += ""
    $out += "## Orphan Root Docs"
    $out += ""
    if ($orphanRootDocs.Count -eq 0) {
        $out += ("- " + $markdownTick + "ORPHANS=<none>" + $markdownTick)
    }
    else {
        foreach ($item in $orphanRootDocs) {
            $out += ("- " + $markdownTick + $item.name + $markdownTick)
        }
    }
    $out += ""
    $out += "## Docs-Readme-Only Root Docs"
    $out += ""
    if ($docsReadmeOnlyRootDocs.Count -eq 0) {
        $out += ("- " + $markdownTick + "DOCS_README_ONLY=<none>" + $markdownTick)
    }
    else {
        foreach ($item in $docsReadmeOnlyRootDocs) {
            $out += ("- " + $markdownTick + $item.name + $markdownTick)
        }
    }
    $out += ""
    $out += "## Violations"
    $out += ""
    if ($violations.Count -eq 0) {
        $out += "- none"
    }
    else {
        foreach ($item in $violations) {
            $out += ("- " + $markdownTick + $item.type + $markdownTick + " -> " + $markdownTick + $item.target + $markdownTick + ": " + $item.reason)
        }
    }

    Write-Utf8Lines -Path $MarkdownPath -Lines $out
    $result | Add-Member -NotePropertyName markdown_path -NotePropertyValue $MarkdownPath
}

$result | ConvertTo-Json -Depth 8

if ($FailOnViolation -and $violations.Count -gt 0) {
    exit 1
}
