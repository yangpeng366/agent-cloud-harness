param(
    [string]$ApiKey = $env:OPENAI_API_KEY,
    [string]$BaseUrl = "https://maas-coding-api.cn-huabei-1.xf-yun.com/v2",
    [string]$Model = "xopglm51",
    [string]$ReviewModel = "xopglm51",
    [ValidateSet("chat_completions", "responses")]
    [string]$WireApi = "chat_completions",
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "",
    [int]$Port = 8080,
    [switch]$Background,
    [string]$StdOutPath = ".tmp\xfyun-glm51.out.log",
    [string]$StdErrPath = ".tmp\xfyun-glm51.err.log",
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

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-ErrorWithHelp `
        -Message "OPENAI_API_KEY is required for xfyun glm5.1 startup" `
        -HelpMessage @"
Pass it for the current run:
  .\scripts\Run-HarnessWithXfyunGlm51.ps1 -ApiKey "<your-xfyun-api-key>"

Or set it before starting:
  `$env:OPENAI_API_KEY="<your-xfyun-api-key>"
  .\scripts\Run-HarnessWithXfyunGlm51.ps1
"@
}

$previousEnv = @{}
$runScript = Join-Path $PSScriptRoot "Run-HarnessWithJava21.ps1"
if (-not (Test-Path -LiteralPath $runScript)) {
    Write-ErrorWithHelp `
        -Message "Base startup script not found: $runScript" `
        -HelpMessage "Run this script from the repository checkout and keep scripts\Run-HarnessWithJava21.ps1 in place."
}

try {
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_API_KEY" -Value $ApiKey
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_BASE_URL" -Value $BaseUrl
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_MODEL" -Value $Model
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_REVIEW_MODEL" -Value $ReviewModel
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_WIRE_API" -Value $WireApi

    Write-Info "Starting Agent Cloud Harness with xfyun glm5.1 LLM config"
    Write-Host "  OPENAI_API_KEY=[set]" -ForegroundColor Gray
    Write-Host "  OPENAI_BASE_URL=$BaseUrl" -ForegroundColor Gray
    Write-Host "  OPENAI_MODEL=$Model" -ForegroundColor Gray
    Write-Host "  OPENAI_REVIEW_MODEL=$ReviewModel" -ForegroundColor Gray
    Write-Host "  OPENAI_WIRE_API=$WireApi" -ForegroundColor Gray

    $runArgs = @(
        "-JdkHome", $JdkHome,
        "-Port", $Port,
        "-StdOutPath", $StdOutPath,
        "-StdErrPath", $StdErrPath
    )

    if (-not [string]::IsNullOrWhiteSpace($JarPath)) {
        $runArgs += @("-JarPath", $JarPath)
    }
    if ($Background) {
        $runArgs += "-Background"
    }
    if ($DisableDispatchPreflightWarmup) {
        $runArgs += "-DisableDispatchPreflightWarmup"
    }
    if (-not $AutoStop) {
        $runArgs += "-AutoStop:`$false"
    }
    if ($JavaArgs.Count -gt 0) {
        $runArgs += "-JavaArgs"
        $runArgs += $JavaArgs
    }

    & $runScript @runArgs
    Write-Success "xfyun glm5.1 startup command finished"
}
finally {
    Restore-TemporaryEnv -PreviousValues $previousEnv
}
