param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "",
    [int]$Port = 18130,
    [string]$BaseUrl = "",
    [int]$StartupTimeoutSec = 45,
    [string[]]$JavaArgs = @("-Xms128m", "-Xmx512m"),
    [switch]$SkipBuild,
    [switch]$NoOpenBrowser,
    [switch]$RunBrowserProbes,
    [ValidateSet('both', 'chat', 'responses')]
    [string]$BrowserProbeSurface = 'both',
    [string]$BrowserProbeScreenshotDir = '',
    [switch]$KeepHarnessRunning,
    [string]$BrowserPath = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    [string]$StdOutPath = ".tmp\dialogue-manual-acceptance.out.log",
    [string]$StdErrPath = ".tmp\dialogue-manual-acceptance.err.log"
)

$ErrorActionPreference = "Stop"

function Resolve-HarnessJar {
    param([string]$RequestedJarPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedJarPath)) {
        return (Resolve-Path -LiteralPath $RequestedJarPath).Path
    }

    $candidates = @(
        "target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar",
        "target\agent-cloud-harness-0.1.0-SNAPSHOT.jar"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "no runnable harness jar found. Checked: $($candidates -join ', ')"
}

function Assert-PortFree {
    param([int]$TargetPort)

    $listener = Get-NetTCPConnection -LocalPort $TargetPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        throw "port $TargetPort is already in use by PID $($listener.OwningProcess)"
    }
}

function Wait-Health {
    param(
        [string]$BaseUrl,
        [int]$TimeoutSec
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/api/v1/health" -TimeoutSec 5
            if ($health.status -eq "up" -or $health.data.status -eq "up") {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 750
        }
    }
    throw "harness did not become healthy within $TimeoutSec seconds"
}

function Resolve-EffectiveBaseUrl {
    param(
        [string]$RequestedBaseUrl,
        [int]$RequestedPort
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedBaseUrl)) {
        return $RequestedBaseUrl.TrimEnd('/')
    }

    return "http://localhost:$RequestedPort"
}

New-Item -ItemType Directory -Force -Path ".tmp" | Out-Null
$resolvedStdOutPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $StdOutPath))
$resolvedStdErrPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $StdErrPath))
$effectiveBaseUrl = Resolve-EffectiveBaseUrl -RequestedBaseUrl $BaseUrl -RequestedPort $Port
$isReusingExistingHarness = -not [string]::IsNullOrWhiteSpace($BaseUrl)
$baseUri = [System.Uri]$effectiveBaseUrl
$effectivePort = $baseUri.Port
$effectiveKeepHarnessRunning = if ($isReusingExistingHarness) {
    $true
} else {
    $KeepHarnessRunning.IsPresent -or (-not $NoOpenBrowser.IsPresent)
}
$harness = $null

if (-not $isReusingExistingHarness) {
    if (-not $SkipBuild) {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Build-WithJava21.ps1") -JdkHome $JdkHome -SkipTests -QuietMaven
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    Assert-PortFree -TargetPort $Port
    $resolvedJar = Resolve-HarnessJar -RequestedJarPath $JarPath

    . (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    $argumentList = @("--enable-preview", "-Dserver.port=$Port") + $JavaArgs + @("-jar", $resolvedJar)
    $harness = Start-Process `
        -FilePath $javaExe `
        -ArgumentList $argumentList `
        -WorkingDirectory (Get-Location) `
        -RedirectStandardOutput $resolvedStdOutPath `
        -RedirectStandardError $resolvedStdErrPath `
        -PassThru
}

$baseUrl = $effectiveBaseUrl
$result = $null

try {
    Wait-Health -BaseUrl $baseUrl -TimeoutSec $StartupTimeoutSec

    $dialogueShellProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-DialogueShellAcceptanceProbe.ps1") -BaseUrl $baseUrl
    if ($LASTEXITCODE -ne 0) {
        throw "dialogue shell probe failed"
    }

    $chatProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-ChatFacadeAcceptanceProbe.ps1") -BaseUrl $baseUrl
    if ($LASTEXITCODE -ne 0) {
        throw "chat facade probe failed"
    }

    $responsesProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-ChatFacadeAcceptanceProbe.ps1") -BaseUrl $baseUrl -UseResponsesSurface
    if ($LASTEXITCODE -ne 0) {
        throw "responses facade probe failed"
    }

    $browserProbe = $null
    $dialogueUrl = "$baseUrl/dialogue/"
    $responsesDialogueUrl = "$baseUrl/dialogue/#facade=responses"
    $runbookPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) "docs\DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md"))
    $recordTemplatePath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) "docs\DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md"))
    $recordSuggestionPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) ("docs\DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_{0}.md" -f (Get-Date -Format "yyyy-MM-dd"))))
    $resultJsonPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-manual-{0}.json" -f $effectivePort)))
    $recommendedScreenshotDir = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-browser-screens-{0}" -f $effectivePort)))
    $recordSeedOutputPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-record-seed-{0}.md" -f $effectivePort)))
    $recordDraftOutputPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-record-draft-{0}.md" -f $effectivePort)))
    $manualBackfillOutputPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-manual-backfill-{0}.json" -f $effectivePort)))
    $effectiveBrowserProbeScreenshotDir = if ([string]::IsNullOrWhiteSpace($BrowserProbeScreenshotDir)) {
        $recommendedScreenshotDir
    } else {
        [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $BrowserProbeScreenshotDir))
    }

    if ($RunBrowserProbes) {
        if ($BrowserProbeSurface -eq 'both') {
            $chatBrowserProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-DialogueBrowserAcceptanceProbe.ps1") `
                -BaseUrl $baseUrl `
                -Surface chat `
                -ScreenshotDir $effectiveBrowserProbeScreenshotDir
            if ($LASTEXITCODE -ne 0) {
                throw "dialogue browser probe failed for chat surface"
            }
            $responsesBrowserProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-DialogueBrowserAcceptanceProbe.ps1") `
                -BaseUrl $baseUrl `
                -Surface responses `
                -ScreenshotDir $effectiveBrowserProbeScreenshotDir
            if ($LASTEXITCODE -ne 0) {
                throw "dialogue browser probe failed for responses surface"
            }
            $chatBrowserProbeObject = $chatBrowserProbe | ConvertFrom-Json
            $responsesBrowserProbeObject = $responsesBrowserProbe | ConvertFrom-Json
            $browserProbeObject = [pscustomobject]@{
                browser = 'msedge'
                base_url = $baseUrl
                dialogue_url = $dialogueUrl
                responses_dialogue_url = $responsesDialogueUrl
                surface = 'both'
                screenshot_dir = $effectiveBrowserProbeScreenshotDir
                chat_surface = $chatBrowserProbeObject.chat_surface
                responses_surface = $responsesBrowserProbeObject.responses_surface
            }
            $browserProbe = $browserProbeObject | ConvertTo-Json -Depth 10
        } else {
            $browserProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-DialogueBrowserAcceptanceProbe.ps1") `
                -BaseUrl $baseUrl `
                -Surface $BrowserProbeSurface `
                -ScreenshotDir $effectiveBrowserProbeScreenshotDir
            if ($LASTEXITCODE -ne 0) {
                throw "dialogue browser probe failed"
            }
            $browserProbeObject = $browserProbe | ConvertFrom-Json
        }
    }

    $manualAcceptance = [pscustomobject]@{
        recommended_order = @(
            [pscustomobject]@{
                id = "A"; path = "default task_auto"; surface = "chat"; entry_url = $dialogueUrl
                note = "Keep the default auto mode. It may materialize a new task and should expose first-screen worker or outcome signals."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-default-task-auto.png"))
            }
            [pscustomobject]@{
                id = "B"; path = "message_only + task_id"; surface = "chat"; entry_url = $dialogueUrl
                note = "Attach to the current task through the current task_note_attach seam. Write a task_note only and keep the task selected."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-task-note-attach.png"))
            }
            [pscustomobject]@{
                id = "C"; path = "task_required"; surface = "chat"; entry_url = $dialogueUrl
                note = "Create a new task with auto_start=true and watch the progress or result affordance."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-auto-start-task.png"))
            }
            [pscustomobject]@{
                id = "D"; path = "follow-up + manual-start"; surface = "chat"; entry_url = $dialogueUrl
                note = "Create a child task but stop at a manual-start receipt."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-followup-manual-start.png"))
            }
            [pscustomobject]@{
                id = "E"; path = "manual-start continuity"; surface = "chat"; entry_url = $dialogueUrl
                note = "Continue the current task with auto_start=false and record note or ack only."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-manual-start-continuity.png"))
            }
            [pscustomobject]@{
                id = "F"; path = "stream fallback"; surface = "chat"; entry_url = $dialogueUrl
                note = "Open Network and confirm event-stream to JSON fallback still uses a single request."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-stream-fallback.png"))
            }
            [pscustomobject]@{
                id = "G"; path = "#facade=responses + message_only"; surface = "responses"; entry_url = $responsesDialogueUrl
                note = "Verify the responses surface can still attach a note to the current task through the current task_note_attach seam without creating a new task."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "responses-task-note-attach.png"))
            }
            [pscustomobject]@{
                id = "H"; path = "#facade=responses + task_required"; surface = "responses"; entry_url = $responsesDialogueUrl
                note = "Verify the responses surface can also create or advance a task and keep the hash."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "responses-auto-start-task.png"))
            }
        )
        recommended_screenshot_dir = $recommendedScreenshotDir
        browser_probe_screenshot_dir = $effectiveBrowserProbeScreenshotDir
        result_json_path = $resultJsonPath
        record_seed_output_path = $recordSeedOutputPath
        record_seed_generated = $false
        record_seed_error = $null
        record_seed_probe = $null
        record_draft_output_path = $recordDraftOutputPath
        record_draft_generated = $false
        record_draft_error = $null
        record_draft_probe = $null
        starter_probe = $null
        command_examples = [pscustomobject]@{
            keep_running = "powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -KeepHarnessRunning -Port $Port"
            keep_existing_instance = "powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -BaseUrl $baseUrl"
            chat_browser_probe = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl $baseUrl -Surface chat -ScreenshotDir `"$effectiveBrowserProbeScreenshotDir`""
            responses_browser_probe = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl $baseUrl -Surface responses -ScreenshotDir `"$effectiveBrowserProbeScreenshotDir`""
            render_record_seed = "powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 -InputJsonPath `"$resultJsonPath`""
            render_record_seed_to_file = "powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 -InputJsonPath `"$resultJsonPath`" > `"$recordSeedOutputPath`""
            probe_record_seed_output = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordSeedProbe.ps1 -InputJsonPath `"$resultJsonPath`""
            render_record_draft_to_file = "powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordDraft.ps1 -InputJsonPath `"$resultJsonPath`" > `"$recordDraftOutputPath`""
            probe_record_draft_output = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordDraftProbe.ps1 -InputJsonPath `"$resultJsonPath`""
            probe_starter_output = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueManualAcceptanceStarterProbe.ps1 -InputJsonPath `"$resultJsonPath`""
            render_manual_backfill_template_to_file = "powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceManualBackfillTemplate.ps1 -InputJsonPath `"$resultJsonPath`" > `"$manualBackfillOutputPath`""
            apply_manual_backfill_to_record = "powershell -ExecutionPolicy Bypass -File .\scripts\Apply-DialogueAcceptanceManualBackfill.ps1 -BackfillJsonPath `"$manualBackfillOutputPath`" -RecordPath `"$recordSuggestionPath`""
            probe_manual_backfill_output = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueAcceptanceManualBackfillProbe.ps1 -InputJsonPath `"$resultJsonPath`""
        }
        record_seed = @(
            [pscustomobject]@{ id = "A"; label = "default task_auto"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-default-task-auto.png")) }
            [pscustomobject]@{ id = "B"; label = "message_only + task_id"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-task-note-attach.png"), (Join-Path $recommendedScreenshotDir "responses-task-note-attach.png")) }
            [pscustomobject]@{ id = "C"; label = "task_required"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-auto-start-task.png"), (Join-Path $recommendedScreenshotDir "responses-auto-start-task.png")) }
            [pscustomobject]@{ id = "D"; label = "follow-up + manual-start"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-followup-manual-start.png"), (Join-Path $recommendedScreenshotDir "responses-followup-manual-start.png")) }
            [pscustomobject]@{ id = "E"; label = "manual-start continuity"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-manual-start-continuity.png"), (Join-Path $recommendedScreenshotDir "responses-manual-start-continuity.png")) }
            [pscustomobject]@{ id = "F"; label = "stream fallback"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-stream-fallback.png"), (Join-Path $recommendedScreenshotDir "responses-stream-fallback.png")) }
            [pscustomobject]@{ id = "G"; label = "#facade=responses + message_only"; entry_url = $responsesDialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "responses-task-note-attach.png")) }
            [pscustomobject]@{ id = "H"; label = "#facade=responses + task_required"; entry_url = $responsesDialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "responses-auto-start-task.png")) }
        )
        entry_points = [pscustomobject]@{
            chat = $dialogueUrl
            responses = $responsesDialogueUrl
        }
        scripted_probe_guidance = [pscustomobject]@{
            preferred_surface_order = @("chat", "responses")
            allow_both_in_one_run = $true
            note = "Raw Run-DialogueBrowserAcceptanceProbe.ps1 -Surface both is still not a stable green gate. Starter-level BrowserProbeSurface=both now works by running chat and responses serially, then aggregating them into one prep bundle."
        }
        record_template = $recordTemplatePath
        record_suggestion = $recordSuggestionPath
        completion_gate = "Runbook section 3 now accepts the richer browser probe as the primary A-H seam evidence source. The written record still must be regenerated and kept consistent with the current starter bundle."
        harness_source = if ($isReusingExistingHarness) { "existing_instance" } else { "starter_managed" }
    }

    if (-not $NoOpenBrowser) {
        if (-not (Test-Path -LiteralPath $BrowserPath)) {
            throw "browser not found: $BrowserPath"
        }
        Start-Process -FilePath $BrowserPath -ArgumentList @($dialogueUrl, $responsesDialogueUrl) | Out-Null
    }

    $result = [pscustomobject]@{
        base_url = $baseUrl
        harness_pid = if ($harness) { $harness.Id } else { $null }
        harness_kept_running = $effectiveKeepHarnessRunning
        harness_keep_reason = if ($isReusingExistingHarness) {
            "existing_instance"
        } elseif ($KeepHarnessRunning.IsPresent) {
            "explicit_keep"
        } elseif (-not $NoOpenBrowser.IsPresent) {
            "browser_opened"
        } else {
            "auto_shutdown_after_probes"
        }
        harness_source = if ($isReusingExistingHarness) { "existing_instance" } else { "starter_managed" }
        dialogue_url = $dialogueUrl
        responses_dialogue_url = $responsesDialogueUrl
        stdout_log = if ($isReusingExistingHarness) { $null } else { $resolvedStdOutPath }
        stderr_log = if ($isReusingExistingHarness) { $null } else { $resolvedStdErrPath }
        runbook = $runbookPath
        record_template = $recordTemplatePath
        record_suggestion = $recordSuggestionPath
        manual_acceptance = $manualAcceptance
        dialogue_shell_probe = ($dialogueShellProbe | ConvertFrom-Json)
        chat_probe = ($chatProbe | ConvertFrom-Json)
        responses_probe = ($responsesProbe | ConvertFrom-Json)
        browser_probe = if ($browserProbe) { $browserProbeObject } else { $null }
        browser_probe_surface = if ($RunBrowserProbes) { $BrowserProbeSurface } else { $null }
        browser_opened = (-not $NoOpenBrowser)
    }
} finally {
    if (-not $effectiveKeepHarnessRunning -and $harness -and -not $harness.HasExited) {
        Stop-Process -Id $harness.Id -Force -ErrorAction SilentlyContinue
    }
}

if ($result -ne $null) {
    $resultJson = $result | ConvertTo-Json -Depth 8
    $resultJson | Out-File -FilePath $result.manual_acceptance.result_json_path -Encoding utf8
    try {
        $rendererPath = Join-Path $PSScriptRoot "Render-DialogueAcceptanceRecordSeed.ps1"
        & powershell -ExecutionPolicy Bypass -File $rendererPath -InputJsonPath $result.manual_acceptance.result_json_path |
            Out-File -FilePath $result.manual_acceptance.record_seed_output_path -Encoding utf8
        $result.manual_acceptance.record_seed_generated = (Test-Path -LiteralPath $result.manual_acceptance.record_seed_output_path)
        if ($result.manual_acceptance.record_seed_generated) {
            $recordSeedProbePath = Join-Path $PSScriptRoot "Run-DialogueRecordSeedProbe.ps1"
            $recordSeedProbe = & powershell -ExecutionPolicy Bypass -File $recordSeedProbePath -InputJsonPath $result.manual_acceptance.result_json_path
            if ($LASTEXITCODE -ne 0) {
                throw "record seed probe failed"
            }
            $result.manual_acceptance.record_seed_probe = $recordSeedProbe | ConvertFrom-Json
        }
    } catch {
        $result.manual_acceptance.record_seed_generated = $false
        $result.manual_acceptance.record_seed_error = $_.Exception.Message
    }
    $resultJson = $result | ConvertTo-Json -Depth 8
    $resultJson | Out-File -FilePath $result.manual_acceptance.result_json_path -Encoding utf8
    try {
        $draftRendererPath = Join-Path $PSScriptRoot "Render-DialogueAcceptanceRecordDraft.ps1"
        & powershell -ExecutionPolicy Bypass -File $draftRendererPath -InputJsonPath $result.manual_acceptance.result_json_path |
            Out-File -FilePath $result.manual_acceptance.record_draft_output_path -Encoding utf8
        $result.manual_acceptance.record_draft_generated = (Test-Path -LiteralPath $result.manual_acceptance.record_draft_output_path)
        if ($result.manual_acceptance.record_draft_generated) {
            $recordDraftProbePath = Join-Path $PSScriptRoot "Run-DialogueRecordDraftProbe.ps1"
            $recordDraftProbe = & powershell -ExecutionPolicy Bypass -File $recordDraftProbePath -InputJsonPath $result.manual_acceptance.result_json_path
            if ($LASTEXITCODE -ne 0) {
                throw "record draft probe failed"
            }
            $result.manual_acceptance.record_draft_probe = $recordDraftProbe | ConvertFrom-Json
        }
    } catch {
        $result.manual_acceptance.record_draft_generated = $false
        $result.manual_acceptance.record_draft_error = $_.Exception.Message
    }
    $resultJson = $result | ConvertTo-Json -Depth 8
    $resultJson | Out-File -FilePath $result.manual_acceptance.result_json_path -Encoding utf8
    try {
        if ($null -ne $result.browser_probe -and [string]$result.browser_probe.surface -eq 'both') {
            $starterProbePath = Join-Path $PSScriptRoot "Run-DialogueManualAcceptanceStarterProbe.ps1"
            $starterProbe = & powershell -ExecutionPolicy Bypass -File $starterProbePath -InputJsonPath $result.manual_acceptance.result_json_path
            if ($LASTEXITCODE -ne 0) {
                throw "starter probe failed"
            }
            $result.manual_acceptance.starter_probe = $starterProbe | ConvertFrom-Json
        }
    } catch {
        # Keep starter generation successful even if the optional aggregate verifier regresses.
        $result.manual_acceptance.starter_probe = [pscustomobject]@{
            error = $_.Exception.Message
        }
    }
    try {
        if (Test-Path -LiteralPath $result.manual_acceptance.record_draft_output_path) {
            $draftRendererPath = Join-Path $PSScriptRoot "Render-DialogueAcceptanceRecordDraft.ps1"
            & powershell -ExecutionPolicy Bypass -File $draftRendererPath -InputJsonPath $result.manual_acceptance.result_json_path |
                Out-File -FilePath $result.manual_acceptance.record_draft_output_path -Encoding utf8
            $result.manual_acceptance.record_draft_generated = (Test-Path -LiteralPath $result.manual_acceptance.record_draft_output_path)
            if ($result.manual_acceptance.record_draft_generated) {
                $recordDraftProbePath = Join-Path $PSScriptRoot "Run-DialogueRecordDraftProbe.ps1"
                $recordDraftProbe = & powershell -ExecutionPolicy Bypass -File $recordDraftProbePath -InputJsonPath $result.manual_acceptance.result_json_path
                if ($LASTEXITCODE -ne 0) {
                    throw "record draft final rerender probe failed"
                }
                $result.manual_acceptance.record_draft_probe = $recordDraftProbe | ConvertFrom-Json
                $result.manual_acceptance.record_draft_error = $null
            }
        }
    } catch {
        $result.manual_acceptance.record_draft_generated = $false
        $result.manual_acceptance.record_draft_error = $_.Exception.Message
    }
    $resultJson = $result | ConvertTo-Json -Depth 8
    $resultJson | Out-File -FilePath $result.manual_acceptance.result_json_path -Encoding utf8
    Write-Output $resultJson
}
