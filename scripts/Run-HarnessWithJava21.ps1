param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar",
    [int]$Port = 8080,
    [switch]$Background,
    [string]$StdOutPath = ".tmp\server.out.log",
    [string]$StdErrPath = ".tmp\server.err.log",
    [string[]]$JavaArgs = @()
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet

$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
$argumentList = @("--enable-preview", "-Dserver.port=$Port") + $JavaArgs + @("-jar", $resolvedJar)

if ($Background) {
    New-Item -ItemType Directory -Force -Path ".tmp" | Out-Null

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
    Write-Host "Stdout: $StdOutPath"
    Write-Host "Stderr: $StdErrPath"
}
else {
    & $javaExe @argumentList
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
