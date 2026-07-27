<#
.SYNOPSIS
    通过 CCX Desktop 本地网关启动 Agent Cloud Harness。

.DESCRIPTION
    Run-HarnessWithJava21.ps1 的包装器，专门用于通过 CCX Desktop 本地网关启动 harness。
    CCX bearer token 读取优先级：
      1. -ApiKey 参数
      2. $env:CCX_BEARER_TOKEN
      3. codex config.toml 的 [model_providers.ccx].experimental_bearer_token

    启动前探测 CCX 网关可达性（GET {BaseUrl}/models），CCX Desktop 需自行先启动。
    临时设置 OPENAI_* 环境变量后调用 Run-HarnessWithJava21.ps1，结束后还原。

.PARAMETER ApiKey
    CCX bearer token；未传时按优先级自动读取（$env:CCX_BEARER_TOKEN -> codex config.toml）。

.PARAMETER BaseUrl
    CCX API Base URL。默认 http://127.0.0.1:3688/v1

.PARAMETER Model
    默认执行模型（CCX 路由名）。默认 codex

.PARAMETER ReviewModel
    judgment / completion review 模型。默认 codex

.PARAMETER WireApi
    wire 协议。默认 chat_completions

.PARAMETER Port
    harness 服务端口。默认 9090

.PARAMETER Background
    是否后台运行。

.PARAMETER StdOutPath
    标准输出日志路径。默认 .tmp\harness-ccx.out.log

.PARAMETER StdErrPath
    标准错误日志路径。默认 .tmp\harness-ccx.err.log

.PARAMETER JavaArgs
    额外的 Java 参数。

.PARAMETER DisableDispatchPreflightWarmup
    跳过启动时 worker dispatch preflight 预热。

.PARAMETER AutoStop
    Harness 端口已占用时是否自动停止占用进程。默认 true

.PARAMETER SkipCcxReachabilityCheck
    跳过启动前 CCX 网关可达性探测（高级用法）。

.PARAMETER CcxStartupTimeoutSeconds
    CCX 网关可达性探测超时秒数。默认 15

.EXAMPLE
    .\scripts\Run-HarnessWithCcx.ps1 -Port 9090

.EXAMPLE
    .\scripts\Run-HarnessWithCcx.ps1 -ApiKey "ccx-xxxxxxxx" -Port 9090 -Background
#>
param(
    [string]$ApiKey = $env:CCX_BEARER_TOKEN,
    [string]$BaseUrl = "http://127.0.0.1:3688/v1",
    [string]$Model = "codex",
    [string]$ReviewModel = "codex",
    [ValidateSet("chat_completions", "responses")]
    [string]$WireApi = "chat_completions",
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "",
    [int]$Port = 9090,
    [switch]$Background,
    [string]$StdOutPath = ".tmp\harness-ccx.out.log",
    [string]$StdErrPath = ".tmp\harness-ccx.err.log",
    [string[]]$JavaArgs = @(),
    [switch]$DisableDispatchPreflightWarmup,
    [bool]$AutoStop = $true,
    [switch]$SkipCcxReachabilityCheck,
    [int]$CcxStartupTimeoutSeconds = 15
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

function Resolve-CodexConfigPath {
    if (-not [string]::IsNullOrWhiteSpace($env:CODEX_HOME)) {
        $candidate = Join-Path $env:CODEX_HOME "config.toml"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $defaultCandidate = Join-Path $env:USERPROFILE ".codex\config.toml"
    if (Test-Path -LiteralPath $defaultCandidate) {
        return $defaultCandidate
    }

    return $null
}

function Read-CodexConfigSection {
    param(
        [string]$ConfigPath,
        [string]$SectionName
    )

    $result = @{}
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        return $result
    }

    $lines = Get-Content -LiteralPath $ConfigPath -Encoding UTF8
    $sectionHeader = "[$SectionName]"
    $inSection = $false

    foreach ($rawLine in $lines) {
        $line = $rawLine.Trim()
        if ($line -match '^\[.+\]$') {
            if ($line -eq $sectionHeader) {
                $inSection = $true
                continue
            }
            if ($inSection) {
                break
            }
            continue
        }

        if (-not $inSection) {
            continue
        }

        if ($line -match '^([A-Za-z0-9_]+)\s*=\s*(.*)$') {
            $key = $matches[1]
            $rawValue = $matches[2].Trim()
            if ($rawValue -match '^"(.*)"$') {
                $rawValue = $matches[1]
            }
            elseif ($rawValue -match "^'(.*)'$") {
                $rawValue = $matches[1]
            }
            $result[$key] = $rawValue
        }
    }

    return $result
}

function Resolve-CcxApiKey {
    param(
        [string]$RequestedApiKey,
        [hashtable]$CcxSection
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedApiKey)) {
        return $RequestedApiKey
    }

    if ($CcxSection.ContainsKey("experimental_bearer_token") -and -not [string]::IsNullOrWhiteSpace($CcxSection["experimental_bearer_token"])) {
        return $CcxSection["experimental_bearer_token"]
    }
    if ($CcxSection.ContainsKey("api_key") -and -not [string]::IsNullOrWhiteSpace($CcxSection["api_key"])) {
        return $CcxSection["api_key"]
    }

    return $null
}

function Test-PortListening {
    param([int]$PortNumber)
    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $PortNumber -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Invoke-CcxReachabilityProbe {
    param(
        [string]$ResolvedBaseUrl,
        [string]$ResolvedApiKey,
        [int]$TimeoutSeconds
    )

    $probeUrl = "$ResolvedBaseUrl/models"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastProbe = $null

    do {
        try {
            $headers = @{}
            if (-not [string]::IsNullOrWhiteSpace($ResolvedApiKey)) {
                $headers["Authorization"] = "Bearer $ResolvedApiKey"
            }
            $response = Invoke-RestMethod -Uri $probeUrl -Headers $headers -TimeoutSec 10 -ErrorAction Stop
            $models = @()
            if ($null -ne $response -and $null -ne $response.data) {
                $models = @($response.data)
            }
            return @{
                Reachable = $true
                Url = $probeUrl
                ModelCount = $models.Count
                ErrorMessage = $null
            }
        }
        catch {
            $lastProbe = @{
                Reachable = $false
                Url = $probeUrl
                ModelCount = 0
                ErrorMessage = $_.Exception.Message
            }
        }
        Start-Sleep -Milliseconds 1000
    } while ((Get-Date) -lt $deadline)

    return $lastProbe
}

# --- 主流程 ---

Write-Info "Starting Agent Cloud Harness via CCX Desktop gateway"

$codexConfigPath = Resolve-CodexConfigPath
$ccxSection = @{}
if ($null -ne $codexConfigPath) {
    Write-Info "Reading codex config: $codexConfigPath"
    $ccxSection = Read-CodexConfigSection -ConfigPath $codexConfigPath -SectionName "model_providers.ccx"
}
else {
    Write-WarningMessage "codex config.toml not found; CCX bearer token must come from -ApiKey or `$env:CCX_BEARER_TOKEN"
}

$resolvedApiKey = Resolve-CcxApiKey -RequestedApiKey $ApiKey -CcxSection $ccxSection
if ([string]::IsNullOrWhiteSpace($resolvedApiKey)) {
    Write-ErrorWithHelp `
        -Message "CCX bearer token could not be resolved" `
        -HelpMessage @"
Token resolution priority:
  1. -ApiKey parameter
  2. `$env:CCX_BEARER_TOKEN
  3. codex config.toml [model_providers.ccx].experimental_bearer_token

Provide it explicitly:
  .\scripts\Run-HarnessWithCcx.ps1 -ApiKey "ccx-xxxxxxxx" -Port 9090

Or set the env var:
  `$env:CCX_BEARER_TOKEN = "ccx-xxxxxxxx"
"@
}

$resolvedBaseUrl = $BaseUrl.TrimEnd("/")

if ($SkipCcxReachabilityCheck) {
    Write-WarningMessage "Skipping CCX reachability check (-SkipCcxReachabilityCheck)"
}
else {
    Write-Info "Probing CCX gateway reachability: $resolvedBaseUrl"
    $probe = Invoke-CcxReachabilityProbe `
        -ResolvedBaseUrl $resolvedBaseUrl `
        -ResolvedApiKey $resolvedApiKey `
        -TimeoutSeconds $CcxStartupTimeoutSeconds

    if ($null -eq $probe -or -not $probe.Reachable) {
        $errMsg = if ($null -ne $probe) { $probe.ErrorMessage } else { "no response" }
        Write-ErrorWithHelp `
            -Message "CCX gateway not reachable at $resolvedBaseUrl ($errMsg)" `
            -HelpMessage @"
CCX Desktop must be running and listening on port 3688 before harness starts.

Start CCX Desktop first, then re-run this script. Verify with:
  Invoke-RestMethod http://127.0.0.1:3688/v1/models -Headers @{ Authorization = "Bearer $resolvedApiKey" }

If CCX is running on another base URL, pass it explicitly:
  .\scripts\Run-HarnessWithCcx.ps1 -BaseUrl "http://127.0.0.1:<port>/v1"

To bypass this check (advanced):
  .\scripts\Run-HarnessWithCcx.ps1 -SkipCcxReachabilityCheck
"@
    }

    Write-Success "CCX gateway reachable (models=$($probe.ModelCount)) at $resolvedBaseUrl"
}

$runScript = Join-Path $PSScriptRoot "Run-HarnessWithJava21.ps1"
if (-not (Test-Path -LiteralPath $runScript)) {
    Write-ErrorWithHelp `
        -Message "Base startup script not found: $runScript" `
        -HelpMessage "Run this script from the repository checkout and keep scripts\Run-HarnessWithJava21.ps1 in place."
}

$previousEnv = @{}
try {
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_API_KEY" -Value $resolvedApiKey
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_BASE_URL" -Value $resolvedBaseUrl
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_MODEL" -Value $Model
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_REVIEW_MODEL" -Value $ReviewModel
    Set-TemporaryEnv -PreviousValues $previousEnv -Name "OPENAI_WIRE_API" -Value $WireApi

    Write-Info "Launching Agent Cloud Harness with CCX OpenAI-compatible gateway"
    Write-Host "  OPENAI_API_KEY=[set]" -ForegroundColor Gray
    Write-Host "  OPENAI_BASE_URL=$resolvedBaseUrl" -ForegroundColor Gray
    Write-Host "  OPENAI_MODEL=$Model" -ForegroundColor Gray
    Write-Host "  OPENAI_REVIEW_MODEL=$ReviewModel" -ForegroundColor Gray
    Write-Host "  OPENAI_WIRE_API=$WireApi" -ForegroundColor Gray
    Write-Host "  Harness Port=$Port" -ForegroundColor Gray

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
    Write-Success "CCX harness startup command finished"
}
finally {
    Restore-TemporaryEnv -PreviousValues $previousEnv
}