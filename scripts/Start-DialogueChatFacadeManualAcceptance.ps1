param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar",
    [int]$Port = 18130,
    [int]$StartupTimeoutSec = 45,
    [switch]$SkipBuild,
    [switch]$NoOpenBrowser,
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
$argumentList = @("--enable-preview", "-Dserver.port=$Port", "-jar", $resolvedJar)
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

    $dialogueUrl = "$baseUrl/dialogue/"
    $responsesDialogueUrl = "$baseUrl/dialogue/#facade=responses"
    $runbookPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) "docs\DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md"))
    $recordTemplatePath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) "docs\DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_TEMPLATE.md"))

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
        dialogue_shell_probe = ($dialogueShellProbe | ConvertFrom-Json)
        chat_probe = ($chatProbe | ConvertFrom-Json)
        responses_probe = ($responsesProbe | ConvertFrom-Json)
        browser_opened = (-not $NoOpenBrowser)
    }
} finally {
    if (-not $effectiveKeepHarnessRunning -and $harness -and -not $harness.HasExited) {
        Stop-Process -Id $harness.Id -Force -ErrorAction SilentlyContinue
    }
}

if ($result -ne $null) {
    Write-Output ($result | ConvertTo-Json -Depth 8)
}
