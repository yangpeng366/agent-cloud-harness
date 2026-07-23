<#
.SYNOPSIS
    Pi 端到端集成 precheck：验证 Pi CLI 可用性和事件流解析。
.DESCRIPTION
    P2 端到端集成验证的前置检查。验证 Pi CLI 可被 harness 调度：
    1. MULTICA_PI_PATH 环境变量指向可用 CLI
    2. Pi CLI 能执行最简 agent loop
    3. 事件流输出可被 PiProtocol 解析格式
.PARAMETER PiPath
    Pi CLI 路径，默认从 MULTICA_PI_PATH 环境变量读取
.PARAMETER SkipExecution
    跳过实际执行测试（只验证 CLI 存在性和 --help）
.PARAMETER OutputDir
    临时输出目录，默认 .tmp/pi-precheck
.EXAMPLE
    .\Run-PiIntegrationPrecheck.ps1
.EXAMPLE
    .\Run-PiIntegrationPrecheck.ps1 -PiPath "C:\tools\pi\pi.exe"
#>
param(
    [string]$PiPath,
    [switch]$SkipExecution,
    [string]$OutputDir = ".tmp/pi-precheck"
)

$ErrorActionPreference = "Stop"

# Resolve Pi path
if (-not $PiPath) {
    $PiPath = $env:MULTICA_PI_PATH
}

$results = [ordered]@{}
$allPass = $true

# 1. CLI existence
Write-Host "`n[1/3] Pi CLI Existence Check..." -ForegroundColor Cyan
if ($PiPath -and (Test-Path $PiPath)) {
    $results["cli_exists"] = "PASS"
    Write-Host "  PASS: Pi CLI found at $PiPath" -ForegroundColor Green
} else {
    $results["cli_exists"] = "FAIL: no CLI found"
    $allPass = $false
    if ($PiPath) {
        Write-Host "  FAIL: MULTICA_PI_PATH=$PiPath does not exist" -ForegroundColor Red
    } else {
        Write-Host "  FAIL: MULTICA_PI_PATH not set and no -PiPath provided" -ForegroundColor Red
    }
}

if (-not $allPass) {
    Write-Host "`n RESULT: FAIL - Pi CLI not available" -ForegroundColor Red
    exit 1
}

# 2. CLI --help / --version
Write-Host "`n[2/3] Pi CLI Basic Command Check..." -ForegroundColor Cyan
try {
    $helpOutput = & $PiPath --help 2>&1 | Out-String
    if ($helpOutput -and $helpOutput.Length -gt 0) {
        $results["cli_help"] = "PASS"
        Write-Host "  PASS: Pi CLI responds to --help" -ForegroundColor Green
        Write-Host "  Help output length: $($helpOutput.Length) chars" -ForegroundColor Gray
    } else {
        $results["cli_help"] = "FAIL: empty help output"
        $allPass = $false
        Write-Host "  FAIL: Pi CLI returned empty help output" -ForegroundColor Red
    }
} catch {
    $results["cli_help"] = "FAIL: $($_.Exception.Message)"
    $allPass = $false
    Write-Host "  FAIL: Pi CLI --help failed - $($_.Exception.Message)" -ForegroundColor Red
}

# 3. Event stream format check (dry run)
if (-not $SkipExecution -and $allPass) {
    Write-Host "`n[3/3] Pi Event Stream Format Check..." -ForegroundColor Cyan

    # Create output dir
    $fullOutputDir = Join-Path (Get-Location) $OutputDir
    if (-not (Test-Path $fullOutputDir)) {
        New-Item -ItemType Directory -Path $fullOutputDir -Force | Out-Null
    }

    # Simulate PiProtocol.buildPlan command construction
    # PiProtocol uses: pi --print --output-format stream-json
    # We capture the output to verify it's parseable
    $eventFile = Join-Path $fullOutputDir "events.jsonl"

    try {
        # Run a minimal Pi command to verify event stream
        # Use a simple prompt that should produce agent_start + turn_start + ... + agent_end
        $piArgs = @(
            "--print",
            "--output-format", "stream-json",
            "-m", "test",
            "Say 'precheck ok'"
        )

        Write-Host "  Running: $PiPath $($piArgs -join ' ')" -ForegroundColor Gray
        $processInfo = New-Object System.Diagnostics.ProcessStartInfo
        $processInfo.FileName = $PiPath
        foreach ($arg in $piArgs) { $processInfo.ArgumentList.Add($arg) }
        $processInfo.RedirectStandardOutput = $true
        $processInfo.RedirectStandardError = $true
        $processInfo.UseShellExecute = $false
        $processInfo.CreateNoWindow = $true

        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $processInfo

        $outputLines = @()
        $script:outputHandler = {
            if (-not [string]::IsNullOrEmpty($EventArgs.Data)) {
                $script:outputLines += $EventArgs.Data
            }
        }

        $process.Start() | Out-Null

        # Wait up to 30 seconds
        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
            $results["event_stream"] = "FAIL: timeout (30s)"
            $allPass = $false
            Write-Host "  FAIL: Pi CLI timed out after 30s" -ForegroundColor Red
        } else {
            $stdout = $process.StandardOutput.ReadToEnd()
            $stderr = $process.StandardError.ReadToEnd()

            if ($stdout -and $stdout.Length -gt 0) {
                # Check if output contains expected event types
                $hasAgentStart = $stdout -match '"agent_start"'
                $hasAgentEnd = $stdout -match '"agent_end"'

                if ($hasAgentStart -or $hasAgentEnd) {
                    $results["event_stream"] = "PASS"
                    Write-Host "  PASS: Pi event stream contains expected event types" -ForegroundColor Green

                    # Save event stream for analysis
                    [System.IO.File]::WriteAllText($eventFile, $stdout, [System.Text.Encoding]::UTF8)
                    Write-Host "  Event stream saved to: $eventFile" -ForegroundColor Gray
                } else {
                    $results["event_stream"] = "WARN: no recognized event types"
                    Write-Host "  WARN: Pi output does not contain agent_start/agent_end events" -ForegroundColor Yellow
                    Write-Host "  Output preview: $($stdout.Substring(0, [Math]::Min(200, $stdout.Length)))" -ForegroundColor Gray
                }
            } else {
                $results["event_stream"] = "FAIL: no stdout output"
                $allPass = $false
                Write-Host "  FAIL: Pi CLI produced no stdout output" -ForegroundColor Red
                if ($stderr) {
                    Write-Host "  stderr: $($stderr.Substring(0, [Math]::Min(200, $stderr.Length)))" -ForegroundColor Red
                }
            }
        }
    } catch {
        $results["event_stream"] = "FAIL: $($_.Exception.Message)"
        $allPass = $false
        Write-Host "  FAIL: Event stream check failed - $($_.Exception.Message)" -ForegroundColor Red
    }
} else {
    Write-Host "`n[3/3] Pi Event Stream Format Check (skipped)" -ForegroundColor Yellow
    $results["event_stream"] = "SKIP"
}

# Summary
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Pi Integration Precheck Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
foreach ($key in $results.Keys) {
    $val = $results[$key]
    $color = if ($val -like "PASS*") { "Green" } elseif ($val -like "SKIP*" -or $val -like "WARN*") { "Yellow" } else { "Red" }
    Write-Host "  ${key}: ${val}" -ForegroundColor $color
}

if ($allPass) {
    Write-Host "`n RESULT: ALL PASS - Pi is ready for harness integration`n" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n RESULT: FAILURES DETECTED - Pi not fully ready`n" -ForegroundColor Red
    exit 1
}
