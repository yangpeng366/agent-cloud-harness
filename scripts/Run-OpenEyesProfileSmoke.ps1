[CmdletBinding()]
param(
    [int]$Port = 19331,
    [string]$ProfileDir = '.tmp\openeyes-profile-smoke',
    [string]$Url = 'about:blank',
    [ValidateRange(1, 20)]
    [int]$RepeatCount = 5,
    [switch]$Seed,
    [switch]$NoSeed,
    [switch]$Headless
)

$ErrorActionPreference = 'Stop'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-PortFree {
    param([int]$Value)
    $listener = Get-NetTCPConnection -LocalPort $Value -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        throw "debug port $Value is already in use by PID $($listener.OwningProcess)"
    }
}

function Stop-SmokeBrowser {
    param($Launch)
    if ($null -eq $Launch -or -not $Launch.pid) { return }
    $process = Get-Process -Id $Launch.pid -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $Launch.pid -Force -ErrorAction SilentlyContinue
        try { $process.WaitForExit(3000) | Out-Null } catch { }
    }
    Start-Sleep -Milliseconds 300
}

function Invoke-ProfileRun {
    param(
        [string]$RunMode,
        [int]$RunPort,
        [string]$RunProfile,
        [string]$MarkerPath
    )
    Assert-PortFree -Value $RunPort
    $launchArgs = @(
        'browser', 'launch',
        '--url', $Url,
        '--port', $RunPort,
        '--profile-dir', $RunProfile
    )
    if ($RunMode -eq 'seed') { $launchArgs += '--seed' } else { $launchArgs += '--no-seed' }
    if ($Headless) { $launchArgs += '--headless' }

    $raw = & eyes @launchArgs | Out-String
    if ($LASTEXITCODE -ne 0) { throw "eyes browser launch failed mode=$RunMode exit=$LASTEXITCODE" }
    $launch = $raw | ConvertFrom-Json
    if ($null -eq $launch.pid) { throw "eyes browser launch returned no pid mode=$RunMode" }

    try {
        if ($RunMode -eq 'seed') {
            $marker = [ordered]@{
                created_at = (Get-Date).ToUniversalTime().ToString('o')
                port = $RunPort
                pid = $launch.pid
                marker_version = 1
            }
            [System.IO.File]::WriteAllText(
                $MarkerPath,
                ($marker | ConvertTo-Json -Compress),
                [System.Text.UTF8Encoding]::new($false)
            )
        }
        $preserved = Test-Path -LiteralPath $MarkerPath
        return [pscustomobject]@{
            mode = $RunMode
            port = $RunPort
            pid = $launch.pid
            profile_preserved = $preserved
        }
    } finally {
        Stop-SmokeBrowser -Launch $launch
    }
}

$eyesCommand = Get-Command eyes -ErrorAction SilentlyContinue
Assert-True -Condition ($null -ne $eyesCommand) -Message 'OpenEyes CLI not found: eyes'
Assert-True -Condition (-not ($Seed -and $NoSeed)) -Message '--seed and --no-seed are mutually exclusive'
Assert-True -Condition ($RepeatCount -ge 1) -Message 'RepeatCount must be at least 1'

$repoRoot = (Get-Location).Path
$resolvedProfile = if ([System.IO.Path]::IsPathRooted($ProfileDir)) {
    [System.IO.Path]::GetFullPath($ProfileDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $ProfileDir))
}
$markerPath = Join-Path $resolvedProfile 'openeyes-profile-smoke.marker.json'
$profileExisted = Test-Path -LiteralPath $resolvedProfile
New-Item -ItemType Directory -Force -Path $resolvedProfile | Out-Null

$seedRun = $null
$repeatRuns = @()
if (-not $NoSeed) {
    $seedRun = Invoke-ProfileRun -RunMode 'seed' -RunPort $Port -RunProfile $resolvedProfile -MarkerPath $markerPath
    if ($seedRun.profile_preserved) { Write-Host 'PROFILE_PRESERVED seed=1' }
} else {
    Assert-True -Condition (Test-Path -LiteralPath $markerPath) -Message "profile marker missing before no-seed run: $markerPath"
}

$failures = 0
for ($index = 1; $index -le $RepeatCount; $index++) {
    $repeatPort = if ($Seed -and -not $NoSeed) { $Port + $index } else { $Port }
    $result = Invoke-ProfileRun -RunMode 'no-seed' -RunPort $repeatPort -RunProfile $resolvedProfile -MarkerPath $markerPath
    if ($result.profile_preserved) {
        Write-Host "PROFILE_PRESERVED run=$index/$RepeatCount"
    } else {
        $failures++
        Write-Host "PROFILE_LOST run=$index/$RepeatCount"
    }
    $repeatRuns += $result
}

$preservedCount = @($repeatRuns | Where-Object { $_.profile_preserved }).Count
$status = if ($preservedCount -eq $RepeatCount -and $failures -eq 0) { 'PASS' } else { 'FAIL' }
$result = [ordered]@{
    status = $status
    backend = 'openeyes_cli'
    browser = 'msedge'
    profile_dir = $resolvedProfile
    profile_marker = $markerPath
    profile_existed_before = $profileExisted
    headless = [bool]$Headless
    seed_run = $seedRun
    acceptance_runs = $RepeatCount
    profile_preserved_count = $preservedCount
    repeats = $repeatRuns
}
$result | ConvertTo-Json -Depth 6
if ($status -ne 'PASS') { exit 1 }
