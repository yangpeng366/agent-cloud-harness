param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$BrowserPath = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    [int]$DebugPort = 19231,
    [string]$UserDataDir = '.tmp\edge-dialogue-browser-probe',
    [int]$StartupTimeoutSec = 20,
    [string]$ScreenshotDir = '',
    [ValidateSet('both', 'chat', 'responses')]
    [string]$Surface = 'both'
)

$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Assert-PortFree {
    param([int]$Port)

    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        throw "debug port $Port is already in use by PID $($listener.OwningProcess)"
    }
}

function Wait-DebugEndpoint {
    param(
        [int]$Port,
        [int]$TimeoutSec
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            return Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json/version" -TimeoutSec 3
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Edge debug endpoint did not become ready within $TimeoutSec seconds"
}

function Invoke-BrowserProbeRunner {
    param(
        [string]$ScriptPath,
        [hashtable]$Payload
    )

    $payloadPath = Join-Path '.tmp' ('dialogue-browser-probe-' + [Guid]::NewGuid().ToString('N') + '.json')
    $resolvedPayloadPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $payloadPath))
    try {
        [System.IO.File]::WriteAllText(
            $resolvedPayloadPath,
            ($Payload | ConvertTo-Json -Depth 12 -Compress),
            (New-Object System.Text.UTF8Encoding($false))
        )
        $nodeArgs = @("--max-old-space-size=256", $ScriptPath, $resolvedPayloadPath)
        $output = & node @nodeArgs
    } finally {
        Remove-Item -LiteralPath $resolvedPayloadPath -Force -ErrorAction SilentlyContinue
    }
    if ($LASTEXITCODE -ne 0) {
        throw "browser probe runner failed"
    }
    return $output | ConvertFrom-Json
}

function Assert-Health {
    param([string]$TargetBaseUrl)

    $health = Invoke-RestMethod -Uri ($TargetBaseUrl + '/api/v1/health') -TimeoutSec 5
    $status = if ($null -ne $health.data) { $health.data.status } else { $health.status }
    if ($status -ne 'up') {
        throw "harness at $TargetBaseUrl is not healthy"
    }
}

Assert-Health -TargetBaseUrl $BaseUrl
Assert-True -Condition (Test-Path -LiteralPath $BrowserPath) -Message "browser not found: $BrowserPath"
Assert-PortFree -Port $DebugPort

New-Item -ItemType Directory -Force -Path '.tmp' | Out-Null
$resolvedUserDataDir = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $UserDataDir))
New-Item -ItemType Directory -Force -Path $resolvedUserDataDir | Out-Null
$resolvedScreenshotDir = $null
if (-not [string]::IsNullOrWhiteSpace($ScreenshotDir)) {
    $resolvedScreenshotDir = if ([System.IO.Path]::IsPathRooted($ScreenshotDir)) {
        [System.IO.Path]::GetFullPath($ScreenshotDir)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $ScreenshotDir))
    }
    New-Item -ItemType Directory -Force -Path $resolvedScreenshotDir | Out-Null
}

$runnerScript = Join-Path $PSScriptRoot 'dialogue-browser-acceptance-probe-runner.cjs'
$edgeArgs = @(
    '--headless=new',
    "--remote-debugging-port=$DebugPort",
    "--user-data-dir=$resolvedUserDataDir",
    'about:blank'
)

$edgeProcess = $null
try {
    $edgeProcess = Start-Process -FilePath $BrowserPath -ArgumentList $edgeArgs -PassThru
    $versionInfo = Wait-DebugEndpoint -Port $DebugPort -TimeoutSec $StartupTimeoutSec

    $chatResult = $null
    $responsesResult = $null
    if ($Surface -in @('both', 'chat')) {
        $chatResult = Invoke-BrowserProbeRunner -ScriptPath $runnerScript -Payload @{
            wsUrl = $versionInfo.webSocketDebuggerUrl
            dialogueUrl = "$BaseUrl/dialogue/"
            expectedSurface = 'chat_completions'
            mode = 'chat'
            screenshotDir = $resolvedScreenshotDir
        }
    }
    if ($Surface -in @('both', 'responses')) {
        $responsesResult = Invoke-BrowserProbeRunner -ScriptPath $runnerScript -Payload @{
            wsUrl = $versionInfo.webSocketDebuggerUrl
            dialogueUrl = "$BaseUrl/dialogue/#facade=responses"
            expectedSurface = 'responses'
            mode = 'responses'
            screenshotDir = $resolvedScreenshotDir
        }
    }

    [pscustomobject]@{
        browser = 'msedge'
        base_url = $BaseUrl
        dialogue_url = "$BaseUrl/dialogue/"
        responses_dialogue_url = "$BaseUrl/dialogue/#facade=responses"
        surface = $Surface
        debug_port = $DebugPort
        screenshot_dir = $resolvedScreenshotDir
        chat_surface = $chatResult
        responses_surface = $responsesResult
    } | ConvertTo-Json -Depth 10
} finally {
    if ($edgeProcess -and -not $edgeProcess.HasExited) {
        Stop-Process -Id $edgeProcess.Id -Force -ErrorAction SilentlyContinue
    }
}
