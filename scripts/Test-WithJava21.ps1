param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [switch]$QuietMaven,
    [string[]]$MavenArgs = @()
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet

$argsToRun = @()
if ($QuietMaven) {
    $argsToRun += "-q"
}
$argsToRun += "test"
$argsToRun += $MavenArgs

Write-Host "Using JAVA_HOME: $env:JAVA_HOME"
Write-Host ("Running: mvn {0}" -f ($argsToRun -join " "))

& mvn @argsToRun

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
