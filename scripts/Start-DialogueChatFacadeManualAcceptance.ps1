param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar",
    [int]$Port = 18130,
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

if (-not $SkipBuild) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Build-WithJava21.ps1") -JdkHome $JdkHome -SkipTests -QuietMaven
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

New-Item -ItemType Directory -Force -Path ".tmp" | Out-Null
$resolvedStdOutPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $StdOutPath))
$resolvedStdErrPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $StdErrPath))
Assert-PortFree -TargetPort $Port
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$effectiveKeepHarnessRunning = $KeepHarnessRunning.IsPresent -or (-not $NoOpenBrowser.IsPresent)

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

$baseUrl = "http://localhost:$Port"
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
    $resultJsonPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-manual-{0}.json" -f $Port)))
    $recommendedScreenshotDir = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-browser-screens-{0}" -f $Port)))
    $recordSeedOutputPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-record-seed-{0}.md" -f $Port)))
    $effectiveBrowserProbeScreenshotDir = if ([string]::IsNullOrWhiteSpace($BrowserProbeScreenshotDir)) {
        $recommendedScreenshotDir
    } else {
        [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $BrowserProbeScreenshotDir))
    }

    if ($RunBrowserProbes) {
        $browserProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-DialogueBrowserAcceptanceProbe.ps1") `
            -BaseUrl $baseUrl `
            -Surface $BrowserProbeSurface `
            -ScreenshotDir $effectiveBrowserProbeScreenshotDir
        if ($LASTEXITCODE -ne 0) {
            throw "dialogue browser probe failed"
        }
    }

    $manualAcceptance = [pscustomobject]@{
        recommended_order = @(
            [pscustomobject]@{
                id = "A"; path = "message_only"; surface = "chat"; entry_url = $dialogueUrl
                note = "Default auto or message mode. Record a session message only."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-message-only.png"))
            }
            [pscustomobject]@{
                id = "B"; path = "message_only + task_id"; surface = "chat"; entry_url = $dialogueUrl
                note = "Attach to the current task. Write a task_note only."
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
                note = "Verify message_only on the responses surface still records a message only."
                candidate_pngs = @((Join-Path $recommendedScreenshotDir "responses-message-only.png"))
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
        command_examples = [pscustomobject]@{
            keep_running = "powershell -ExecutionPolicy Bypass -File .\scripts\Start-DialogueChatFacadeManualAcceptance.ps1 -SkipBuild -KeepHarnessRunning -Port $Port"
            chat_browser_probe = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl $baseUrl -Surface chat -ScreenshotDir `"$effectiveBrowserProbeScreenshotDir`""
            responses_browser_probe = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl $baseUrl -Surface responses -ScreenshotDir `"$effectiveBrowserProbeScreenshotDir`""
            render_record_seed = "powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 -InputJsonPath `"$resultJsonPath`""
            render_record_seed_to_file = "powershell -ExecutionPolicy Bypass -File .\scripts\Render-DialogueAcceptanceRecordSeed.ps1 -InputJsonPath `"$resultJsonPath`" > `"$recordSeedOutputPath`""
            probe_record_seed_output = "powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueRecordSeedProbe.ps1 -InputJsonPath `"$resultJsonPath`""
        }
        record_seed = @(
            [pscustomobject]@{ id = "A"; label = "message_only"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-message-only.png"), (Join-Path $recommendedScreenshotDir "responses-message-only.png")) }
            [pscustomobject]@{ id = "B"; label = "message_only + task_id"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-task-note-attach.png"), (Join-Path $recommendedScreenshotDir "responses-task-note-attach.png")) }
            [pscustomobject]@{ id = "C"; label = "task_required"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-auto-start-task.png"), (Join-Path $recommendedScreenshotDir "responses-auto-start-task.png")) }
            [pscustomobject]@{ id = "D"; label = "follow-up + manual-start"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-followup-manual-start.png"), (Join-Path $recommendedScreenshotDir "responses-followup-manual-start.png")) }
            [pscustomobject]@{ id = "E"; label = "manual-start continuity"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-manual-start-continuity.png"), (Join-Path $recommendedScreenshotDir "responses-manual-start-continuity.png")) }
            [pscustomobject]@{ id = "F"; label = "stream fallback"; entry_url = $dialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "chat-stream-fallback.png"), (Join-Path $recommendedScreenshotDir "responses-stream-fallback.png")) }
            [pscustomobject]@{ id = "G"; label = "#facade=responses + message_only"; entry_url = $responsesDialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "responses-message-only.png")) }
            [pscustomobject]@{ id = "H"; label = "#facade=responses + task_required"; entry_url = $responsesDialogueUrl; candidate_pngs = @((Join-Path $recommendedScreenshotDir "responses-auto-start-task.png")) }
        )
        entry_points = [pscustomobject]@{
            chat = $dialogueUrl
            responses = $responsesDialogueUrl
        }
        scripted_probe_guidance = [pscustomobject]@{
            preferred_surface_order = @("chat", "responses")
            allow_both_in_one_run = $false
            note = "Run scripted browser evidence one surface at a time. Treat BrowserProbeSurface=both as exploratory only, not a stable green gate."
        }
        record_template = $recordTemplatePath
        record_suggestion = $recordSuggestionPath
        completion_gate = "Runbook section 3 still requires manual A-H browser checks and a written record. Automation does not replace that step."
    }

    if (-not $NoOpenBrowser) {
        if (-not (Test-Path -LiteralPath $BrowserPath)) {
            throw "browser not found: $BrowserPath"
        }
        Start-Process -FilePath $BrowserPath -ArgumentList @($dialogueUrl, $responsesDialogueUrl) | Out-Null
    }

    $result = [pscustomobject]@{
        base_url = $baseUrl
        harness_pid = $harness.Id
        harness_kept_running = $effectiveKeepHarnessRunning
        harness_keep_reason = if ($KeepHarnessRunning.IsPresent) {
            "explicit_keep"
        } elseif (-not $NoOpenBrowser.IsPresent) {
            "browser_opened"
        } else {
            "auto_shutdown_after_probes"
        }
        dialogue_url = $dialogueUrl
        responses_dialogue_url = $responsesDialogueUrl
        stdout_log = $resolvedStdOutPath
        stderr_log = $resolvedStdErrPath
        runbook = $runbookPath
        record_template = $recordTemplatePath
        record_suggestion = $recordSuggestionPath
        manual_acceptance = $manualAcceptance
        dialogue_shell_probe = ($dialogueShellProbe | ConvertFrom-Json)
        chat_probe = ($chatProbe | ConvertFrom-Json)
        responses_probe = ($responsesProbe | ConvertFrom-Json)
        browser_probe = if ($browserProbe) { $browserProbe | ConvertFrom-Json } else { $null }
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
    Write-Output $resultJson
}
