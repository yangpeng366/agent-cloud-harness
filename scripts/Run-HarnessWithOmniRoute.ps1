param(
    [string]$ApiKey = $env:OMNIROUTE_API_KEY,
    [string]$BaseUrl = "",
    [string]$Model = "auto/coding",
    [string]$ReviewModel = "auto",
    [ValidateSet("chat_completions", "responses")]
    [string]$WireApi = "chat_completions",
    [string]$OmniRouteCommand = "omniroute",
    [int]$OmniRoutePort = 20128,
    [int]$OmniRouteStartupTimeoutSeconds = 30,
    [bool]$EnsureOmniRoute = $true,
    [switch]$SkipModelCatalogCheck,
    [string]$OmniRouteStdOutPath = ".tmp\omniroute.out.log",
    [string]$OmniRouteStdErrPath = ".tmp\omniroute.err.log",
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "",
    [int]$Port = 8080,
    [switch]$Background,
    [string]$StdOutPath = ".tmp\omniroute-harness.out.log",
    [string]$StdErrPath = ".tmp\omniroute-harness.err.log",
    [string[]]$JavaArgs = @(),
    [switch]$DisableDispatchPreflightWarmup,
    [bool]$AutoStop = $true
)

$ErrorActionPreference = "Stop"

function Write-Info {
    param([string]$Message)
    Write-Host "`n[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-WarningMessage {
    param([string]$Message)
    Write-Host "`n[WARN] $Message" -ForegroundColor Yellow
}

function Write-ErrorWithHelp {
    param(
        [string]$Message,
        [string]$HelpMessage
    )
    Write-Host "`n[ERROR] $Message" -ForegroundColor Red
    if ($HelpMessage) {
        Write-Host "`nSolution:" -ForegroundColor Cyan
        Write-Host $HelpMessage -ForegroundColor Yellow
    }
    Write-Host ""
    exit 1
}

function Set-TemporaryEnv {
    param(
        [hashtable]$PreviousValues,
        [string]$Name,
        [string]$Value
    )

    $item = Get-Item -Path ("Env:" + $Name) -ErrorAction SilentlyContinue
    $PreviousValues[$Name] = @{
        Exists = $null -ne $item
        Value = if ($null -ne $item) { $item.Value } else { $null }
    }
    Set-Item -Path ("Env:" + $Name) -Value $Value
}

function Restore-TemporaryEnv {
    param([hashtable]$PreviousValues)

    foreach ($name in $PreviousValues.Keys) {
        $snapshot = $PreviousValues[$name]
        if ($snapshot.Exists) {
            Set-Item -Path ("Env:" + $name) -Value $snapshot.Value
        }
        else {
            Remove-Item -Path ("Env:" + $name) -ErrorAction SilentlyContinue
        }
    }
}

function Resolve-OmniRouteBaseUrl {
    param(
        [string]$RequestedBaseUrl,
        [int]$PortNumber
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedBaseUrl)) {
        return $RequestedBaseUrl.TrimEnd("/")
    }

    return "http://localhost:$PortNumber/v1"
}

function Resolve-OmniRouteApiKey {
    param([string]$RequestedApiKey)

    if (-not [string]::IsNullOrWhiteSpace($RequestedApiKey)) {
        return $RequestedApiKey
    }

    return "sk-omniroute"
}

function Test-PortListening {
    param([int]$PortNumber)

    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $PortNumber -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Invoke-OmniRouteModelCatalogProbe {
    param(
        [string]$ResolvedBaseUrl,
        [string]$ResolvedApiKey
    )

    $probeUrl = "$ResolvedBaseUrl/models"
    try {
        $response = Invoke-RestMethod -Uri $probeUrl -Headers @{ Authorization = "Bearer $ResolvedApiKey" } -TimeoutSec 10
        $models = @()
        if ($null -ne $response -and $null -ne $response.data) {
            $models = @($response.data)
        }

        return @{
            Reachable = $true
            Url = $probeUrl
            StatusCode = 200
            ModelCount = $models.Count
            Models = $models
            ErrorMessage = $null
        }
    }
    catch {
        $statusCode = $null
        $errorMessage = $_.Exception.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        return @{
            Reachable = $false
            Url = $probeUrl
            StatusCode = $statusCode
            ModelCount = 0
            Models = @()
            ErrorMessage = $errorMessage
        }
    }
}

function Start-OmniRouteBackground {
    param(
        [string]$CommandName,
        [string]$StdOutLog,
        [string]$StdErrLog
    )

    $commandInfo = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($null -eq $commandInfo) {
        Write-ErrorWithHelp `
            -Message "OmniRoute command not found: $CommandName" `
            -HelpMessage @"
Install OmniRoute first:
  npm install -g omniroute

Or pass a custom command path:
  .\scripts\Run-HarnessWithOmniRoute.ps1 -OmniRouteCommand "C:\path\to\omniroute.ps1"
"@
    }

    New-Item -ItemType Directory -Force -Path ".tmp" | Out-Null

    $process = Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $CommandName) `
        -WorkingDirectory (Get-Location) `
        -RedirectStandardOutput $StdOutLog `
        -RedirectStandardError $StdErrLog `
        -PassThru `
        -WindowStyle Hidden

    return $process
}

function Wait-OmniRouteReady {
    param(
        [string]$ResolvedBaseUrl,
        [string]$ResolvedApiKey,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastProbe = $null
    do {
        $lastProbe = Invoke-OmniRouteModelCatalogProbe -ResolvedBaseUrl $ResolvedBaseUrl -ResolvedApiKey $ResolvedApiKey
        if ($lastProbe.Reachable) {
            return $lastProbe
        }
        Start-Sleep -Milliseconds 1000
    } while ((Get-Date) -lt $deadline)

    return $lastProbe
}

$resolvedBaseUrl = Resolve-OmniRouteBaseUrl -RequestedBaseUrl $BaseUrl -PortNumber $OmniRoutePort
$resolvedApiKey = Resolve-OmniRouteApiKey -RequestedApiKey $ApiKey
$runScript = Join-Path $PSScriptRoot "Run-HarnessWithJava21.ps1"

if (-not (Test-Path -LiteralPath $runScript)) {
    Write-ErrorWithHelp `
        -Message "Base startup script not found: $runScript" `
        -HelpMessage "Run this script from the repository checkout and keep scripts\Run-HarnessWithJava21.ps1 in place."
}

$omniRouteProcess = $null
if (-not (Test-PortListening -PortNumber $OmniRoutePort)) {
    if ($EnsureOmniRoute) {
        Write-Info "OmniRoute is not listening on port $OmniRoutePort. Starting local gateway..."
        $omniRouteProcess = Start-OmniRouteBackground `
            -CommandName $OmniRouteCommand `
            -StdOutLog $OmniRouteStdOutPath `
            -StdErrLog $OmniRouteStdErrPath
        Write-Success "Started OmniRoute bootstrap process (PID: $($omniRouteProcess.Id))"
    }
    else {
        Write-ErrorWithHelp `
            -Message "OmniRoute is not listening on port $OmniRoutePort" `
            -HelpMessage @"
Start OmniRoute first:
  omniroute

Or allow this wrapper to start it automatically:
  .\scripts\Run-HarnessWithOmniRoute.ps1 -EnsureOmniRoute `$true
"@
    }
}
else {
    Write-Info "Reusing existing OmniRoute listener on port $OmniRoutePort"
}

Write-Info "Waiting for OmniRoute API to become reachable: $resolvedBaseUrl"
$probe = Wait-OmniRouteReady `
    -ResolvedBaseUrl $resolvedBaseUrl `
    -ResolvedApiKey $resolvedApiKey `
    -TimeoutSeconds $OmniRouteStartupTimeoutSeconds

if (-not $probe.Reachable) {
    Write-ErrorWithHelp `
        -Message "OmniRoute did not become ready within $OmniRouteStartupTimeoutSeconds seconds" `
        -HelpMessage @"
Last probe:
  URL: $($probe.Url)
  Status: $($probe.StatusCode)
  Error: $($probe.ErrorMessage)

Check OmniRoute logs:
  $OmniRouteStdOutPath
  $OmniRouteStdErrPath

If OmniRoute is already running on another port, pass the base URL explicitly:
  .\scripts\Run-HarnessWithOmniRoute.ps1 -BaseUrl "http://localhost:20129/v1"
"@
}

if ($probe.ModelCount -eq 0) {
    $modelCatalogHelp = @"
OmniRoute is reachable, but $($probe.Url) returned an empty model list.
That usually means the local gateway itself is up, but no upstream provider / combo has been configured yet.

Recommended next step:
1. Open Dashboard: http://localhost:$OmniRoutePort
2. Configure at least one upstream provider or Combo
3. Confirm GET $($probe.Url) returns non-empty data
4. Re-run this script

If you only want to bypass the catalog guard for a local smoke test:
  .\scripts\Run-HarnessWithOmniRoute.ps1 -SkipModelCatalogCheck
"@

    if (-not $SkipModelCatalogCheck) {
        Write-ErrorWithHelp `
            -Message "OmniRoute model catalog is empty" `
            -HelpMessage $modelCatalogHelp
    }

    Write-WarningMessage $modelCatalogHelp
}

$previousEnv = @{}
try {
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_API_KEY" -Value $resolvedApiKey
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_BASE_URL" -Value $resolvedBaseUrl
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_MODEL" -Value $Model
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_REVIEW_MODEL" -Value $ReviewModel
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_WIRE_API" -Value $WireApi

    Write-Info "Starting Agent Cloud Harness with OmniRoute OpenAI-compatible gateway"
    Write-Host "  OPENAI_API_KEY=[set]" -ForegroundColor Gray
    Write-Host "  OPENAI_BASE_URL=$resolvedBaseUrl" -ForegroundColor Gray
    Write-Host "  OPENAI_MODEL=$Model" -ForegroundColor Gray
    Write-Host "  OPENAI_REVIEW_MODEL=$ReviewModel" -ForegroundColor Gray
    Write-Host "  OPENAI_WIRE_API=$WireApi" -ForegroundColor Gray
    Write-Host "  OMNIROUTE_MODELS=$($probe.ModelCount)" -ForegroundColor Gray

    $runParams = @{
        JdkHome = $JdkHome
        Port = $Port
        StdOutPath = $StdOutPath
        StdErrPath = $StdErrPath
    }

    if (-not [string]::IsNullOrWhiteSpace($JarPath)) {
        $runParams["JarPath"] = $JarPath
    }
    if ($Background) {
        $runParams["Background"] = $true
    }
    if ($DisableDispatchPreflightWarmup) {
        $runParams["DisableDispatchPreflightWarmup"] = $true
    }
    if (-not $AutoStop) {
        $runParams["AutoStop"] = $false
    }
    if ($JavaArgs.Count -gt 0) {
        $runParams["JavaArgs"] = $JavaArgs
    }

    & $runScript @runParams
    Write-Success "OmniRoute startup command finished"
}
finally {
    Restore-TemporaryEnv -PreviousValues $previousEnv
}
