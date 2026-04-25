param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"

$javaExe = Join-Path $JdkHome "bin\java.exe"
if (-not (Test-Path -LiteralPath $javaExe)) {
    throw "Java 21 not found at: $JdkHome"
}

$env:JAVA_HOME = $JdkHome
$jdkBin = Join-Path $JdkHome "bin"
$existingPathParts = @()

if ($env:Path) {
    $existingPathParts = $env:Path -split ";" | Where-Object { $_ -and ($_ -ne $jdkBin) }
}

$env:Path = ($jdkBin + ";" + ($existingPathParts -join ";")).TrimEnd(";")

if ($env:CLASSPATH) {
    $env:AGENTCLOUD_PREVIOUS_CLASSPATH = $env:CLASSPATH
    Remove-Item Env:CLASSPATH
}

if (-not $Quiet) {
    Write-Host "JAVA_HOME set to: $env:JAVA_HOME"
    if ($env:AGENTCLOUD_PREVIOUS_CLASSPATH) {
        Write-Host "Cleared inherited CLASSPATH to avoid old JDK/runtime conflicts."
    }
    Write-Host "To keep this in the current shell, dot-source the script:"
    Write-Host "  . .\scripts\Use-Java21.ps1"
}
