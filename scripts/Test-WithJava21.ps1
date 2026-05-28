param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [switch]$QuietMaven,
    [string[]]$MavenArgs = @(),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$PassthroughMavenArgs = @()
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet
$mavenExecutable = & (Join-Path $PSScriptRoot "Resolve-MavenCommand.ps1")

$argsToRun = @()
if ($QuietMaven) {
    $argsToRun += "-q"
}
$argsToRun += "test"
$argsToRun += $MavenArgs
$argsToRun += $PassthroughMavenArgs

Write-Host "Using JAVA_HOME: $env:JAVA_HOME"
Write-Host "Using Maven: $mavenExecutable"
Write-Host ("Running: {0} {1}" -f $mavenExecutable, ($argsToRun -join " "))

& $mavenExecutable @argsToRun

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
