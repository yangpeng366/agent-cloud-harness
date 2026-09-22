param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$BrowserPath = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    [int]$DebugPort = 19231,
    [string]$UserDataDir = '.tmp\edge-dialogue-browser-probe',
    [int]$StartupTimeoutSec = 20,
    [string]$ScreenshotDir = '',
    [int]$NodeMaxOldSpaceMb = 768,
    [string]$NodePath = '',
    [ValidateSet('real', 'ui_seam')]
    [string]$LifecycleMode = 'real',
    [ValidateSet('both', 'chat', 'responses')]
    [string]$Surface = 'both',
    [switch]$UseOpenEyesProfile,
    [string]$OpenEyesProfileDir = "$env:USERPROFILE\.openeyes\profiles\dialogue-acceptance",
    [switch]$OpenEyesNoSeed
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

function Resolve-NodeExecutable {
    param([string]$PreferredPath)

    if (-not [string]::IsNullOrWhiteSpace($PreferredPath)) {
        $resolvedPreferred = [System.IO.Path]::GetFullPath($PreferredPath)
        Assert-True -Condition (Test-Path -LiteralPath $resolvedPreferred) -Message "node not found: $resolvedPreferred"
        return $resolvedPreferred
    }

    $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
    if ($null -ne $nodeCommand -and -not [string]::IsNullOrWhiteSpace($nodeCommand.Source)) {
        return $nodeCommand.Source
    }

    $candidatePaths = @(
        "$env:ProgramFiles\nodejs\node.exe",
        "$env:LOCALAPPDATA\Programs\nodejs\node.exe",
        "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe",
        "$env:USERPROFILE\.trae-cn\sdks\workspaces\1b5d04cd\versions\node\current\node.exe",
        "$env:USERPROFILE\.trae-cn\sdks\versions\node\current\node.exe",
        'C:\nvm4w\nodejs\node.exe'
    )

    foreach ($candidate in $candidatePaths) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }

    throw "node executable not found. Install Node.js, add it to PATH, or pass -NodePath <path-to-node.exe>."
}

function Invoke-BrowserProbeRunner {
    param(
        [string]$ScriptPath,
        [hashtable]$Payload,
        [string]$NodeExecutable,
        [string]$SurfaceName = ''
    )

    $payloadPath = Join-Path '.tmp' ('dialogue-browser-probe-' + [Guid]::NewGuid().ToString('N') + '.json')
    $resolvedPayloadPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $payloadPath))
    try {
        [System.IO.File]::WriteAllText(
            $resolvedPayloadPath,
            ($Payload | ConvertTo-Json -Depth 12 -Compress),
            (New-Object System.Text.UTF8Encoding($false))
        )
        $nodeArgs = @("--max-old-space-size=$NodeMaxOldSpaceMb", $ScriptPath, $resolvedPayloadPath)
        $output = & $NodeExecutable @nodeArgs
    } finally {
        Remove-Item -LiteralPath $resolvedPayloadPath -Force -ErrorAction SilentlyContinue
    }
    if ($LASTEXITCODE -ne 0) {
        throw "browser probe runner failed surface=$SurfaceName"
    }
    $outputText = ($output | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($outputText)) {
        throw "browser probe runner produced empty output surface=$SurfaceName"
    }
    return $outputText | ConvertFrom-Json
}

function Assert-Health {
    param([string]$TargetBaseUrl)

    $health = Invoke-RestMethod -Uri ($TargetBaseUrl + '/api/v1/health') -TimeoutSec 5
    $status = if ($null -ne $health.data) { $health.data.status } else { $health.status }
    if ($status -ne 'up') {
        throw "harness at $TargetBaseUrl is not healthy"
    }
}

function Start-ProbeBrowser {
    param(
        [int]$Port,
        [string]$ProfileDir,
        [string]$OpenEyesSeedMode = 'direct'
    )

    Assert-PortFree -Port $Port
    New-Item -ItemType Directory -Force -Path $ProfileDir | Out-Null
    if ($OpenEyesSeedMode -eq 'direct') {
        $args = @(
            '--headless=new',
            "--remote-debugging-port=$Port",
            "--user-data-dir=$ProfileDir",
            'about:blank'
        )
        $process = Start-Process -FilePath $BrowserPath -ArgumentList $args -PassThru
    } else {
        $eyesCommand = Get-Command eyes -ErrorAction SilentlyContinue
        Assert-True -Condition ($null -ne $eyesCommand) -Message 'OpenEyes CLI not found: eyes'
        $launchArgs = @(
            'browser', 'launch',
            '--url', 'about:blank',
            '--port', $Port,
            '--profile-dir', $ProfileDir
        )
        $launchArgs += if ($OpenEyesSeedMode -eq 'seed') { '--seed' } else { '--no-seed' }
        $launchArgs += '--headless'
        $raw = & $eyesCommand @launchArgs | Out-String
        if ($LASTEXITCODE -ne 0) { throw "OpenEyes browser launch failed seed=$OpenEyesSeedMode exit=$LASTEXITCODE" }
        $launch = $raw | ConvertFrom-Json
        $process = Get-Process -Id $launch.pid -ErrorAction Stop
    }
    $version = Wait-DebugEndpoint -Port $Port -TimeoutSec $StartupTimeoutSec
    return [pscustomobject]@{
        Process = $process
        Version = $version
    }
}

function Test-OpenEyesSeedMarker {
    param([string]$MarkerPath)
    Test-Path -LiteralPath $MarkerPath
}

function Save-OpenEyesSeedMarker {
    param([string]$MarkerPath)
    $marker = [ordered]@{
        created_at = (Get-Date).ToUniversalTime().ToString('o')
        marker_version = 1
    }
    [System.IO.File]::WriteAllText(
        $MarkerPath,
        ($marker | ConvertTo-Json -Compress),
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Stop-ProbeBrowser {
    param($Browser)

    $process = $Browser.Process
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-SurfaceProbe {
    param(
        [string]$SurfaceName,
        [int]$Port,
        [string]$ProfileDir,
        [string]$DialoguePath,
        [string]$ExpectedSurface,
        [string]$OpenEyesSeedMode = 'direct'
    )

    $browser = $null
    try {
        $browser = Start-ProbeBrowser -Port $Port -ProfileDir $ProfileDir -OpenEyesSeedMode $OpenEyesSeedMode
        if ($UseOpenEyesProfile -and $OpenEyesSeedMode -eq 'seed' -and $openEyesMarkerPath) {
            Save-OpenEyesSeedMarker -MarkerPath $openEyesMarkerPath
        }
        return Invoke-BrowserProbeRunner -ScriptPath $runnerScript -Payload @{
            wsUrl = $browser.Version.webSocketDebuggerUrl
            dialogueUrl = "$BaseUrl$DialoguePath"
            expectedSurface = $ExpectedSurface
            mode = $SurfaceName
            screenshotDir = $resolvedScreenshotDir
            lifecycleMode = $LifecycleMode
        } -NodeExecutable $nodeExecutable -SurfaceName $SurfaceName
    } finally {
        if ($browser) {
            Stop-ProbeBrowser -Browser $browser
        }
    }
}

Assert-Health -TargetBaseUrl $BaseUrl
Assert-True -Condition (Test-Path -LiteralPath $BrowserPath) -Message "browser not found: $BrowserPath"
Assert-PortFree -Port $DebugPort
$nodeExecutable = Resolve-NodeExecutable -PreferredPath $NodePath

New-Item -ItemType Directory -Force -Path '.tmp' | Out-Null
$resolvedUserDataDir = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $UserDataDir))
New-Item -ItemType Directory -Force -Path $resolvedUserDataDir | Out-Null
$openEyesSeedMode = 'direct'
$openEyesMarkerPath = $null
if ($UseOpenEyesProfile) {
    $resolvedOpenEyesProfile = if ([System.IO.Path]::IsPathRooted($OpenEyesProfileDir)) {
        [System.IO.Path]::GetFullPath($OpenEyesProfileDir)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OpenEyesProfileDir))
    }
    $openEyesMarkerPath = Join-Path $resolvedOpenEyesProfile 'dialogue-acceptance.profile-seeded.json'
    $openEyesSeedMode = if ($OpenEyesNoSeed) { 'no-seed' } elseif (Test-OpenEyesSeedMarker -MarkerPath $openEyesMarkerPath) { 'no-seed' } else { 'seed' }
    if ($openEyesSeedMode -eq 'no-seed') {
        Assert-True -Condition (Test-Path -LiteralPath $openEyesMarkerPath) -Message "OpenEyes profile seed marker missing: $openEyesMarkerPath"
    }
}
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
$chatResult = $null
$responsesResult = $null
if ($Surface -in @('both', 'chat')) {
    $chatResult = Invoke-SurfaceProbe `
        -SurfaceName 'chat' `
        -Port $DebugPort `
        -ProfileDir $(if ($UseOpenEyesProfile) { $resolvedOpenEyesProfile } else { $resolvedUserDataDir }) `
        -DialoguePath '/dialogue/' `
        -ExpectedSurface 'chat_completions' `
        -OpenEyesSeedMode $openEyesSeedMode
}
if ($Surface -in @('both', 'responses')) {
    $responsesPort = if ($Surface -eq 'both') { $DebugPort + 1 } else { $DebugPort }
    $responsesProfileDir = if ($Surface -eq 'both') {
            if ($UseOpenEyesProfile) { $resolvedOpenEyesProfile } else { [System.IO.Path]::GetFullPath("$resolvedUserDataDir-responses") }
        } else {
            if ($UseOpenEyesProfile) { $resolvedOpenEyesProfile } else { $resolvedUserDataDir }
        }
    $responsesResult = Invoke-SurfaceProbe `
        -SurfaceName 'responses' `
        -Port $responsesPort `
        -ProfileDir $responsesProfileDir `
        -DialoguePath '/dialogue/#facade=responses' `
        -ExpectedSurface 'responses' `
        -OpenEyesSeedMode $(if ($UseOpenEyesProfile) { 'no-seed' } else { 'direct' })
}

    $chatSurface = if ($null -ne $chatResult -and $null -ne $chatResult.chat_surface) {
        $chatResult.chat_surface
    } else {
        $chatResult
    }
    $responsesSurface = if ($null -ne $responsesResult -and $null -ne $responsesResult.responses_surface) {
        $responsesResult.responses_surface
    } else {
        $responsesResult
    }

    if ($Surface -eq 'both') {
        if ($null -eq $chatSurface) {
            throw "browser probe surface=both did not produce chat_surface"
        }
        if ($null -eq $responsesSurface) {
            throw "browser probe surface=both did not produce responses_surface"
        }
        $chatPropCount = @($chatSurface.PSObject.Properties).Count
        $responsesPropCount = @($responsesSurface.PSObject.Properties).Count
        if ($chatPropCount -eq 0) {
            throw "browser probe surface=both produced empty chat_surface"
        }
        if ($responsesPropCount -eq 0) {
            throw "browser probe surface=both produced empty responses_surface"
        }
    }

    [pscustomobject]@{
        browser = 'msedge'
        base_url = $BaseUrl
        dialogue_url = "$BaseUrl/dialogue/"
        responses_dialogue_url = "$BaseUrl/dialogue/#facade=responses"
        surface = $Surface
        lifecycle_mode = $LifecycleMode
        debug_port = $DebugPort
        node_path = $nodeExecutable
        screenshot_dir = $resolvedScreenshotDir
        openeyes_profile_enabled = [bool]$UseOpenEyesProfile
        openeyes_profile_dir = $resolvedOpenEyesProfile
        openeyes_seed_mode = $openEyesSeedMode
        chat_surface = $chatSurface
        responses_surface = $responsesSurface
    } | ConvertTo-Json -Depth 10
if ($UseOpenEyesProfile -and $openEyesSeedMode -eq 'seed') {
    Save-OpenEyesSeedMarker -MarkerPath $openEyesMarkerPath
}
