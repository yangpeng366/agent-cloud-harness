[CmdletBinding()]
param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [switch]$QuietMaven,
    [string[]]$MavenArgs = @(),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$PassthroughMavenArgs = @()
)

$ErrorActionPreference = "Stop"

if ($JdkHome -like "-*" -and -not (Test-Path -LiteralPath $JdkHome)) {
    # 兼容历史调用：把误绑定到 JdkHome 的 Maven 参数回收到透传参数里。
    $PassthroughMavenArgs = @($JdkHome) + $PassthroughMavenArgs
    $JdkHome = "C:\Program Files\Java\jdk-21.0.9+10"
}

$normalizedPassthroughArgs = @($PassthroughMavenArgs | Where-Object { $_ -and $_ -ne "test" })

. (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet
$mavenExecutable = & (Join-Path $PSScriptRoot "Resolve-MavenCommand.ps1")

$argsToRun = @()
if ($QuietMaven) {
    $argsToRun += "-q"
}
$argsToRun += "test"
$argsToRun += $MavenArgs
$argsToRun += $normalizedPassthroughArgs

Write-Host "Using JAVA_HOME: $env:JAVA_HOME"
Write-Host "Using Maven: $mavenExecutable"
Write-Host ("Running: {0} {1}" -f $mavenExecutable, ($argsToRun -join " "))

& $mavenExecutable @argsToRun

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
