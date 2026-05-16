param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "",
    [int]$Port = 8080,
    [switch]$Background,
    [string]$StdOutPath = ".tmp\server.out.log",
    [string]$StdErrPath = ".tmp\server.err.log",
    [string[]]$JavaArgs = @()
)

$ErrorActionPreference = "Stop"

function Resolve-HarnessJar {
    param([string]$RequestedJarPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedJarPath)) {
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

    throw "no runnable harness jar found. Checked: $($candidates -join ', ')"
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

function Assert-PortAvailable {
    param([int]$PortNumber)

    $listeners = Get-NetTCPConnection -State Listen -LocalPort $PortNumber -ErrorAction SilentlyContinue
    if ($listeners) {
        $owningProcess = ($listeners | Select-Object -First 1).OwningProcess
        $processInfo = $null
        if ($owningProcess) {
            $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $owningProcess" -ErrorAction SilentlyContinue
        }

        $commandLine = $processInfo.CommandLine
        if ([string]::IsNullOrWhiteSpace($commandLine)) {
            $commandLine = "<unavailable>"
        }

        throw "port $PortNumber is already in use by PID $owningProcess. command: $commandLine"
    }
}

. (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet

$resolvedJar = Resolve-HarnessJar -RequestedJarPath $JarPath
$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
$argumentList = @("--enable-preview", "-Dserver.port=$Port") + $JavaArgs + @("-jar", $resolvedJar)

if ($Background) {
    Assert-PortAvailable -PortNumber $Port
    New-Item -ItemType Directory -Force -Path ".tmp" | Out-Null
    $runtimeJar = New-RuntimeJarCopy -SourceJarPath $resolvedJar -PortNumber $Port
    $argumentList = @("--enable-preview", "-Dserver.port=$Port") + $JavaArgs + @("-jar", $runtimeJar)

    $process = Start-Process `
        -FilePath $javaExe `
        -ArgumentList $argumentList `
        -WorkingDirectory (Get-Location) `
        -RedirectStandardOutput $StdOutPath `
        -RedirectStandardError $StdErrPath `
        -PassThru

    Write-Host "Started agent-cloud-harness."
    Write-Host "PID: $($process.Id)"
    Write-Host "Port: $Port"
    Write-Host "Jar: $resolvedJar"
    Write-Host "RuntimeJar: $runtimeJar"
    Write-Host "Stdout: $StdOutPath"
    Write-Host "Stderr: $StdErrPath"
}
else {
    & $javaExe @argumentList
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
