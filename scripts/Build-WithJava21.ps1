param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [switch]$SkipTests,
    [switch]$QuietMaven
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet

$mavenArgs = @()
if ($QuietMaven) {
    $mavenArgs += "-q"
}
if ($SkipTests) {
    $mavenArgs += "-DskipTests"
}
$mavenArgs += "package"

Write-Host "Using JAVA_HOME: $env:JAVA_HOME"
Write-Host ("Running: mvn {0}" -f ($mavenArgs -join " "))

& mvn @mavenArgs

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
