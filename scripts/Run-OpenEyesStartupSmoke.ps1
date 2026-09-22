# Run-OpenEyesStartupSmoke.ps1
#
# Verifies that `eyes-mcp` stdio transport + `eyes` CLI are reachable in the
# current host. Default mode is dry-run (no probe); -Go launches a single
# probe and reports eyes_mcp status / tool_count / first subcommand.
#
# Usage:
#   pwsh -File scripts/Run-OpenEyesStartupSmoke.ps1            # dry-run (just checks binaries)
#   pwsh -File scripts/Run-OpenEyesStartupSmoke.ps1 -Go        # launch single probe
#   pwsh -File scripts/Run-OpenEyesStartupSmoke.ps1 -ToolName openeyes_cli -Go
#

[CmdletBinding()]
param(
    [switch]$Go,
    [string]$ToolName = "eyes",
    [string]$EyesMcpBin = "eyes-mcp",
    [int]$ProbeTimeoutSeconds = 5,
    [string]$CaptureDirectory = ".tmp\openeyes-startup-smoke"
)

$ErrorActionPreference = "Stop"

function Write-JsonLine {
    param([hashtable]$Payload)
    $Payload | ConvertTo-Json -Compress -Depth 6
}

$result = [ordered]@{
    schema_version = 1
    mode = if ($Go) { "real_probe" } else { "dry_run" }
    tool_name = $ToolName
    probe_timeout_seconds = $ProbeTimeoutSeconds
    eyes_cli_available = $false
    eyes_mcp_available = $false
    tool_count = 0
    server_name = ""
    server_version = ""
    duration_ms = $null
    error = $null
}

try {
    Get-Command -Name $ToolName -ErrorAction Stop | Out-Null
    $result.eyes_cli_available = $true
    Get-Command -Name $EyesMcpBin -ErrorAction Stop | Out-Null
    $result.eyes_mcp_available = $true

    if (-not $Go) {
        Write-JsonLine -Payload $result
        return
    }

    if (-not (Test-Path $CaptureDirectory)) {
        New-Item -ItemType Directory -Force -Path $CaptureDirectory | Out-Null
    }
    $requestFile = Join-Path $CaptureDirectory ("eyes-mcp-probe-" + [guid]::NewGuid().ToString("N") + ".jsonl")
    $initialize = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"agent-cloud-harness","version":"0.2.0"}}}'
    $initialized = '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    $toolsList = '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
    [System.IO.File]::WriteAllLines($requestFile, @($initialize, $initialized, $toolsList), [System.Text.UTF8Encoding]::new($false))

    $startedAt = [System.Diagnostics.Stopwatch]::StartNew()
    $process = Start-Process -FilePath $EyesMcpBin -RedirectStandardInput $requestFile -RedirectStandardOutput "$CaptureDirectory\probe.out" -RedirectStandardError "$CaptureDirectory\probe.err" -PassThru -WindowStyle Hidden
    if (-not $process.WaitForExit($ProbeTimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force
        throw "probe timeout after $ProbeTimeoutSeconds s"
    }
    $startedAt.Stop()
    $result.duration_ms = [int]$startedAt.ElapsedMilliseconds

    $probeOutput = ""
    if (Test-Path "$CaptureDirectory\probe.out") {
        $probeOutput = Get-Content "$CaptureDirectory\probe.out" -Raw -ErrorAction SilentlyContinue
    }

    foreach ($line in ($probeOutput -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        try {
            $response = $line | ConvertFrom-Json -ErrorAction Stop
        } catch {
            continue
        }
        if ($response.id -eq 1 -and $response.result.serverInfo) {
            $result.server_name = [string]$response.result.serverInfo.name
            $result.server_version = [string]$response.result.serverInfo.version
        }
        if ($response.id -eq 2 -and $response.result.tools) {
            $result.tool_count = @($response.result.tools).Count
            $result.eyes_mcp_available = $true
        }
    }

    Remove-Item -LiteralPath $requestFile -Force -ErrorAction SilentlyContinue

    Write-JsonLine -Payload $result
} catch {
    $result.error = $_.Exception.Message
    Write-JsonLine -Payload $result
    exit 1
}
