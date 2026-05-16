param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [switch]$SkipTests,
    [switch]$QuietMaven
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet
$mavenExecutable = & (Join-Path $PSScriptRoot "Resolve-MavenCommand.ps1")

$mavenArgs = @()
if ($QuietMaven) {
    $mavenArgs += "-q"
}
if ($SkipTests) {
    $mavenArgs += "-DskipTests"
}
$mavenArgs += "package"

Write-Host "Using JAVA_HOME: $env:JAVA_HOME"
Write-Host "Using Maven: $mavenExecutable"
Write-Host ("Running: {0} {1}" -f $mavenExecutable, ($mavenArgs -join " "))

& $mavenExecutable @mavenArgs

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
