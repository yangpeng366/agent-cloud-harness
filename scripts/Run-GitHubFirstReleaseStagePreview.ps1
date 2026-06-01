param(
    [ValidateSet("baseline", "product", "harness", "all")]
    [string]$Commit = "all",
    [switch]$WriteMarkdown,
    [string]$MarkdownPath = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

function New-StringList {
    return New-Object System.Collections.Generic.List[string]
}

function Format-ProcessArgument {
    param([string]$Value)

    if ($null -eq $Value) {
        return '""'
    }

    if ($Value -notmatch '[\s"]') {
        return $Value
    }

    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

function Invoke-Git {
    param(
        [string[]]$Arguments,
        [hashtable]$EnvMap,
        [string]$LogTag = "default"
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = "git"
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = (($Arguments | ForEach-Object { Format-ProcessArgument $_ }) -join " ")

    foreach ($key in $EnvMap.Keys) {
        $startInfo.EnvironmentVariables[$key] = [string]$EnvMap[$key]
    }

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo

    if (-not $process.Start()) {
        throw ("Failed to start git process for {0}" -f $LogTag)
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()

    [System.Threading.Tasks.Task]::WaitAll(@($stdoutTask, $stderrTask))

    $output = New-StringList
    foreach ($chunk in @($stdoutTask.Result, $stderrTask.Result)) {
        if ([string]::IsNullOrWhiteSpace($chunk)) {
            continue
        }
        foreach ($line in ($chunk -split "`r?`n")) {
            if (-not [string]::IsNullOrWhiteSpace($line)) {
                $output.Add([string]$line)
            }
        }
    }

    return [pscustomobject]@{
        Output = @($output)
        ExitCode = $process.ExitCode
    }
}

function Ensure-GitOk {
    param(
        [pscustomobject]$Result,
        [string]$Message
    )

    if ($Result.ExitCode -ne 0) {
        throw ("{0}`n{1}" -f $Message, ($Result.Output -join [Environment]::NewLine))
    }
}

$groups = [ordered]@{
    baseline = @(
        ".gitignore",
        "README.md",
        "STARTUP_GUIDE.md",
        "LICENSE",
        "CONTRIBUTING.md",
        "SECURITY.md",
        "CODE_OF_CONDUCT.md",
        ".github",
        "docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md",
        "docs/API_CONTRACTS.md",
        "docs/GITHUB_RELEASE_CHECKLIST.md",
        "docs/GITHUB_RELEASE_SCOPE_PROPOSAL.md",
        "docs/GITHUB_FIRST_RELEASE_FILESET.md",
        "docs/GITHUB_FIRST_RELEASE_STAGING_PLAN.md",
        "docs/GITHUB_FIRST_RELEASE_COMMIT_PLAN.md",
        "docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md",
        "docs/GITHUB_FIRST_RELEASE_COMMIT_COMMANDS.md",
        "docs/GITHUB_FIRST_RELEASE_NEXT_ACTIONS.md",
        "docs/GITHUB_FIRST_RELEASE_STAGED_SLICE_READY_2026-05-11.md",
        "docs/HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md",
        "docs/PROJECT_EVALUATION_AND_NEXT_PLAN.md",
        "docs/GITHUB_SUBMISSION_AND_EVOLUTION_PLAN.md",
        "docs/TROUBLESHOOT.md"
    )
    product = @(
        "src/main/java/com/agentcloud/agent/providers",
        "src/main/java/com/agentcloud/engine/ChatFacadeService.java",
        "src/main/java/com/agentcloud/cli/Main.java",
        "src/main/java/com/agentcloud/engine/TaskService.java",
        "src/main/java/com/agentcloud/engine/router/WorkerRegistry.java",
        "src/main/java/com/agentcloud/engine/router/WorkerRouter.java",
        "src/main/java/com/agentcloud/server/NioHttpServer.java",
        "src/main/java/com/agentcloud/server/TaskHandler.java",
        "src/main/java/com/agentcloud/server/WebConsoleHandler.java",
        "src/main/java/com/agentcloud/server/WorkerHandler.java",
        "src/main/java/com/agentcloud/worker",
        "src/main/resources/web/console/app.js",
        "src/main/resources/web/dialogue",
        "src/test/java/com/agentcloud/agent",
        "src/test/java/com/agentcloud/cli",
        "src/test/java/com/agentcloud/engine/ControlNodeGraphOrchestrationFlowTest.java",
        "src/test/java/com/agentcloud/engine/router",
        "src/test/java/com/agentcloud/server/ChatFacadeHandlerHttpTest.java",
        "src/test/java/com/agentcloud/server/ApiErrorContractHttpTest.java",
        "src/test/java/com/agentcloud/server/TaskHandlerLiveFlowHttpTest.java",
        "src/test/java/com/agentcloud/server/TaskHandlerProviderSelectionHttpTest.java",
        "src/test/java/com/agentcloud/server/WebConsoleHandlerHttpTest.java",
        "src/test/java/com/agentcloud/worker",
        "src/test/js"
    )
    harness = @(
        "scripts/Run-HarnessWithJava21.ps1",
        "scripts/Start-DialogueChatFacadeManualAcceptance.ps1",
        "scripts/Run-ChatFacadeAcceptanceWithLocalHarness.ps1",
        "scripts/Run-ChatFacadePathMatrixProbe.ps1",
        "scripts/Run-DialogueBrowserAcceptanceProbe.ps1",
        "scripts/dialogue-browser-acceptance-probe-runner.cjs",
        "scripts/Render-DialogueAcceptanceRecordSeed.ps1",
        "scripts/Run-DialogueRecordSeedProbe.ps1",
        "scripts/Render-DialogueAcceptanceManualBackfillTemplate.ps1",
        "scripts/Apply-DialogueAcceptanceManualBackfill.ps1",
        "scripts/Run-DialogueAcceptanceManualBackfillProbe.ps1",
        "scripts/Render-DialogueAcceptanceScriptedBackfillTemplate.ps1",
        "scripts/Run-DialogueAcceptanceScriptedBackfillProbe.ps1",
        "scripts/Run-GitHubFirstReleaseDryRun.ps1",
        "scripts/Run-GitHubFirstReleaseCommitDryRun.ps1",
        "scripts/Run-GitHubFirstReleaseStagePreview.ps1",
        "scripts/Run-GitHubFirstReleaseIndexAudit.ps1",
        "scripts/Run-GitHubFirstReleasePrecheck.ps1",
        "scripts/Run-CodexPartialTimeoutSmoke.ps1",
        "scripts/dialogue-business-smoke.js",
        "scripts/provider-discovery-smoke.js",
        "docs/CHAT_FIRST_DIALOGUE_AND_OPENAI_API_ALIGNMENT_PLAN.md",
        "docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md",
        "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md",
        "docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md"
    )
}

$selectedGroups = if ($Commit -eq "all") {
    @("baseline", "product", "harness")
}
else {
    @($Commit)
}

$runTag = "{0}-{1}" -f $PID, ([System.Guid]::NewGuid().ToString("N").Substring(0, 8))

$head = Invoke-Git -Arguments @("rev-parse", "--verify", "HEAD") -EnvMap @{} -LogTag "head"
Ensure-GitOk -Result $head -Message "git rev-parse --verify HEAD failed."
$headCommit = @($head.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
if ($headCommit.Count -eq 0) {
    throw "git rev-parse --verify HEAD returned no usable output."
}
$headCommit = ([string]$headCommit[0]).Trim()

$preview = [ordered]@{
    commit = $Commit
    head = $headCommit
}

foreach ($groupName in $selectedGroups) {
    $indexPath = Join-Path $repoRoot (".tmp/git-first-release-preview-{0}-{1}.index" -f $groupName, $runTag)
    $envMap = @{ GIT_INDEX_FILE = $indexPath }

    $indexParent = Split-Path -Parent $indexPath
    New-Item -ItemType Directory -Force -Path $indexParent | Out-Null
    if (Test-Path -LiteralPath $indexPath) {
        Remove-Item -LiteralPath $indexPath -Force
    }

    try {
        $readTree = Invoke-Git -Arguments @("read-tree", $headCommit) -EnvMap $envMap -LogTag ("{0}-read-tree" -f $groupName)
        Ensure-GitOk -Result $readTree -Message ("git read-tree failed for {0}" -f $groupName)

        $addArgs = @("add", "--") + $groups[$groupName]
        $addResult = Invoke-Git -Arguments $addArgs -EnvMap $envMap -LogTag ("{0}-add" -f $groupName)
        Ensure-GitOk -Result $addResult -Message ("git add failed for {0}" -f $groupName)

        $statResult = Invoke-Git -Arguments @("diff", "--cached", "--stat") -EnvMap $envMap -LogTag ("{0}-stat" -f $groupName)
        Ensure-GitOk -Result $statResult -Message ("git diff --cached --stat failed for {0}" -f $groupName)

        $nameOnlyResult = Invoke-Git -Arguments @("diff", "--cached", "--name-only") -EnvMap $envMap -LogTag ("{0}-name-only" -f $groupName)
        Ensure-GitOk -Result $nameOnlyResult -Message ("git diff --cached --name-only failed for {0}" -f $groupName)

        $preview[$groupName] = [ordered]@{
            paths = @($groups[$groupName])
            staged_files = @($nameOnlyResult.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            diff_stat = @($statResult.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        }
    }
    finally {
        if (Test-Path -LiteralPath $indexPath) {
            Remove-Item -LiteralPath $indexPath -Force
        }
    }
}

if ($WriteMarkdown) {
    if (-not $MarkdownPath) {
        $suffix = if ($Commit -eq "all") { "all" } else { $Commit }
        $MarkdownPath = ("docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_{0}_2026-05-11.md" -f $suffix)
    }

    $parent = Split-Path -Parent $MarkdownPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $out = New-StringList
    $out.Add("# GitHub First Release Stage Preview")
    $out.Add("")
    $out.Add("> Generated from temporary git index simulation; the real index is not modified.")
    $out.Add("")
    $out.Add(('HEAD: `{0}`' -f $headCommit))
    $out.Add("")

    foreach ($groupName in $selectedGroups) {
        if ($groupName -eq "baseline") {
            $title = "Repository Baseline"
        }
        elseif ($groupName -eq "product") {
            $title = "chat-first / facade product line"
        }
        else {
            $title = "acceptance harness and operator docs"
        }

        $section = $preview[$groupName]
        $out.Add(("## {0}" -f $title))
        $out.Add("")
        $out.Add("### Simulated staged files")
        $out.Add("")
        if (@($section.staged_files).Count -eq 0) {
            $out.Add("- none")
        }
        else {
            foreach ($item in $section.staged_files) {
                $out.Add(("- {0}" -f $item))
            }
        }
        $out.Add("")
        $out.Add("### Simulated diff stat")
        $out.Add("")
        if (@($section.diff_stat).Count -eq 0) {
            $out.Add("- none")
        }
        else {
            foreach ($line in $section.diff_stat) {
                $out.Add($line)
            }
        }
        $out.Add("")
    }

    Set-Content -LiteralPath $MarkdownPath -Value $out -Encoding UTF8
    $preview["markdown_path"] = (Resolve-Path -LiteralPath $MarkdownPath).Path
}

$preview | ConvertTo-Json -Depth 6
