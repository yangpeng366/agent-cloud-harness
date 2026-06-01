param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "",
    [int]$Port = 8080,
    [switch]$Background,
    [string]$StdOutPath = ".tmp\server.out.log",
    [string]$StdErrPath = ".tmp\server.err.log",
    [string[]]$JavaArgs = @(),
    [switch]$AutoStop = $true
)

$ErrorActionPreference = "Stop"

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

function Write-Info {
    param([string]$Message)
    Write-Host "`n[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Warning {
    param([string]$Message)
    Write-Host "`n[WARN] $Message" -ForegroundColor Yellow
}

function Resolve-HarnessJar {
    param([string]$RequestedJarPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedJarPath)) {
        if (-not (Test-Path -LiteralPath $RequestedJarPath)) {
            Write-ErrorWithHelp `
                -Message "Specified JAR file not found: $RequestedJarPath" `
                -HelpMessage @"
Please verify the path is correct, or run build first:
.\scripts\Build-WithJava21.ps1 -SkipTests
"@
        }
        return (Resolve-Path -LiteralPath $RequestedJarPath).Path
    }

    $candidates = @(
        "target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar",
        "target\agent-cloud-harness-0.1.0-SNAPSHOT.jar"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    Write-ErrorWithHelp `
        -Message "No runnable Harness JAR file found" `
        -HelpMessage @"
Checked locations:
- $($candidates -join "`n- ")

Solutions:
1. Run build first:
   .\scripts\Build-WithJava21.ps1 -SkipTests

2. Or specify custom JAR path:
   .\scripts\Run-HarnessWithJava21.ps1 -JarPath "path/to/your.jar"
"@
}

function New-RuntimeJarCopy {
    param(
        [string]$SourceJarPath,
        [int]$PortNumber
    )

    $runtimeDir = Join-Path (Get-Location) ".tmp\runtime-jars"
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

    $jarName = [System.IO.Path]::GetFileNameWithoutExtension($SourceJarPath)
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $runtimeJar = Join-Path $runtimeDir "$jarName-port$PortNumber-$timestamp.jar"
    Copy-Item -LiteralPath $SourceJarPath -Destination $runtimeJar -Force
    return (Resolve-Path -LiteralPath $runtimeJar).Path
}

function Stop-ProcessByPort {
    param([int]$PortNumber)

    $listeners = Get-NetTCPConnection -State Listen -LocalPort $PortNumber -ErrorAction SilentlyContinue
    if (-not $listeners) {
        return $null
    }

    $owningProcess = ($listeners | Select-Object -First 1).OwningProcess
    if (-not $owningProcess) {
        return $null
    }

    $processInfo = $null
    try {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $owningProcess" -ErrorAction SilentlyContinue
    }
    catch {
        Write-Warning "Failed to get process info for PID $owningProcess"
    }

    $commandLine = $processInfo.CommandLine
    if ([string]::IsNullOrWhiteSpace($commandLine)) {
        $commandLine = "<unavailable>"
    }

    Write-Warning "Port $PortNumber is already in use by another process"
    Write-Host "  PID: $owningProcess" -ForegroundColor Gray
    Write-Host "  Command Line: $commandLine" -ForegroundColor Gray

    try {
        Write-Info "Stopping existing process (PID: $owningProcess)..."
        Stop-Process -Id $owningProcess -Force -ErrorAction Stop
        Write-Success "Successfully stopped process (PID: $owningProcess)"
        Start-Sleep -Milliseconds 500
        return $owningProcess
    }
    catch {
        Write-ErrorWithHelp `
            -Message "Failed to stop process (PID: $owningProcess): $_" `
            -HelpMessage @"
Solutions:
1. Manually stop the process:
   Stop-Process -Id $owningProcess -Force

2. Use a different port:
   .\scripts\Run-HarnessWithJava21.ps1 -Port 8081

3. Find and stop process:
   Get-NetTCPConnection -LocalPort $PortNumber | Select-Object OwningProcess
   Stop-Process -Id <PID> -Force
"@
    }
}

function Assert-PortAvailable {
    param([int]$PortNumber, [switch]$AutoStop)

    $listeners = Get-NetTCPConnection -State Listen -LocalPort $PortNumber -ErrorAction SilentlyContinue
    if ($listeners) {
        if ($AutoStop) {
            Stop-ProcessByPort -PortNumber $PortNumber

            $maxRetries = 10
            $retryCount = 0
            $portReleased = $false

            while ($retryCount -lt $maxRetries) {
                Start-Sleep -Milliseconds 500
                $listeners = Get-NetTCPConnection -State Listen -LocalPort $PortNumber -ErrorAction SilentlyContinue
                if (-not $listeners) {
                    $portReleased = $true
                    break
                }
                $retryCount++
                Write-Host "Waiting for port $PortNumber to be released... ($retryCount/$maxRetries)" -ForegroundColor Yellow
            }

            if (-not $portReleased) {
                Write-ErrorWithHelp `
                    -Message "Port $PortNumber is still in use after stopping the process" `
                    -HelpMessage @"
The port may be held by the system or another process. Try:
1. Use a different port: .\scripts\Run-HarnessWithJava21.ps1 -Port 8081
2. Restart your computer
"@
            }
        }
        else {
            $owningProcess = ($listeners | Select-Object -First 1).OwningProcess
            $processInfo = $null
            if ($owningProcess) {
                $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $owningProcess" -ErrorAction SilentlyContinue
            }

            $commandLine = $processInfo.CommandLine
            if ([string]::IsNullOrWhiteSpace($commandLine)) {
                $commandLine = "<unavailable>"
            }

            Write-ErrorWithHelp `
                -Message "Port $PortNumber is already in use" `
                -HelpMessage @"
Process info using the port:
- PID: $owningProcess
- Command Line: $commandLine

Solutions:
1. Use -AutoStop parameter to automatically stop the process:
   .\scripts\Run-HarnessWithJava21.ps1 -Port $PortNumber -AutoStop

2. Stop the process manually:
   Stop-Process -Id $owningProcess -Force

3. Use a different port:
   .\scripts\Run-HarnessWithJava21.ps1 -Port 8081
"@
        }
    }
}

Write-Info "Starting Agent Cloud Harness..."

try {
    Write-Info "Step 1/3: Configure Java 21 environment"
    . (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet
    Write-Success "Java environment configured successfully"
}
catch {
    Write-ErrorWithHelp `
        -Message "Failed to configure Java environment: $_" `
        -HelpMessage @"
1. Ensure Java 21 is installed
2. Use -JdkHome parameter to specify correct path
"@
}

Write-Info "Step 2/3: Resolve JAR file path"
$resolvedJar = Resolve-HarnessJar -RequestedJarPath $JarPath
Write-Success "Found JAR: $resolvedJar"

if ($Background) {
    Write-Info "Step 3/3: Start in background mode (port check + launch)"
    Assert-PortAvailable -PortNumber $Port -AutoStop:$AutoStop
    New-Item -ItemType Directory -Force -Path ".tmp" | Out-Null
    $runtimeJar = New-RuntimeJarCopy -SourceJarPath $resolvedJar -PortNumber $Port

    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    $argumentList = @("--enable-preview", "-Dserver.port=$Port") + $JavaArgs + @("-jar", $runtimeJar)

    $process = Start-Process `
        -FilePath $javaExe `
        -ArgumentList $argumentList `
        -WorkingDirectory (Get-Location) `
        -RedirectStandardOutput $StdOutPath `
        -RedirectStandardError $StdErrPath `
        -PassThru

    Write-Host @"

Agent Cloud Harness started (background mode)
------------------------------------------------------
PID:        $($process.Id)
Port:       $Port
Jar:        $resolvedJar
RuntimeJar: $runtimeJar
Stdout:     $StdOutPath
Stderr:     $StdErrPath
------------------------------------------------------

Access URLs:
- Dialogue:  http://localhost:$Port/dialogue/
- Console:   http://localhost:$Port/console/
- Health:    http://localhost:$Port/api/v1/health

Management commands:
- View logs:  Get-Content -Path "$StdOutPath" -Wait
- Stop service:  Stop-Process -Id $($process.Id) -Force
- Check port:  Get-NetTCPConnection -LocalPort $Port
"@
}
else {
    Write-Info "Step 3/3: Start in foreground mode"
    Assert-PortAvailable -PortNumber $Port -AutoStop:$AutoStop

    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    $argumentList = @("--enable-preview", "-Dserver.port=$Port") + $JavaArgs + @("-jar", $resolvedJar)

    Write-Host @"

Starting Agent Cloud Harness (foreground mode)
------------------------------------------------------
Command: $javaExe $($argumentList -join ' ')
------------------------------------------------------

Press Ctrl+C to stop the service
"@

    & $javaExe @argumentList

    if ($LASTEXITCODE -ne 0) {
        Write-ErrorWithHelp `
            -Message "Service failed to start with exit code: $LASTEXITCODE" `
            -HelpMessage @"
Common startup failure reasons:
1. Port is in use - use -AutoStop parameter to automatically stop existing process
2. JAR file is corrupted - rebuild the project
3. Java version incompatible - ensure using Java 21
4. Check error logs for detailed information
"@
    }
}
