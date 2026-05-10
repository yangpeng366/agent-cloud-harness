param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [string]$JarPath = "target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar",
    [int]$Port = 18080,
    [int]$StartupTimeoutSec = 45,
    [switch]$SkipBuild,
    [switch]$KeepServerLogs,
    [string]$StdOutPath = ".tmp\chat-facade-acceptance.out.log",
    [string]$StdErrPath = ".tmp\chat-facade-acceptance.err.log"
)

$ErrorActionPreference = "Stop"

function Assert-PortFree {
    param([int]$TargetPort)

    $listener = Get-NetTCPConnection -LocalPort $TargetPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        throw "port $TargetPort is already in use by PID $($listener.OwningProcess)"
    }
}

function Wait-Health {
    param(
        [string]$BaseUrl,
        [int]$TimeoutSec
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/api/v1/health" -TimeoutSec 5
            if ($health.status -eq "up" -or $health.data.status -eq "up") {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 750
        }
    }
    throw "harness did not become healthy within $TimeoutSec seconds"
}

function Remove-FileWithRetry {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    foreach ($attempt in 1..10) {
        try {
            Remove-Item -LiteralPath $Path -Force -ErrorAction Stop
            return
        } catch {
            Start-Sleep -Milliseconds 300
        }
    }
}

function Start-DeferredCleanup {
    param(
        [string[]]$Paths
    )

    $escaped = $Paths |
        Where-Object { $_ } |
        ForEach-Object { "'" + ($_ -replace "'", "''") + "'" }
    if (-not $escaped -or $escaped.Count -eq 0) {
        return
    }

    $cleanupCommand = "Start-Sleep -Milliseconds 750; Remove-Item -LiteralPath @(" + ($escaped -join ",") + ") -Force -ErrorAction SilentlyContinue"
    $powershellExe = Join-Path $PSHOME "powershell.exe"
    Start-Process `
        -FilePath $powershellExe `
        -WindowStyle Hidden `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $cleanupCommand) | Out-Null
}

if (-not $SkipBuild) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Build-WithJava21.ps1") -JdkHome $JdkHome -SkipTests -QuietMaven
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

New-Item -ItemType Directory -Force -Path ".tmp" | Out-Null
$workingDir = (Get-Location).Path
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$useDefaultStdOut = $StdOutPath -eq ".tmp\chat-facade-acceptance.out.log"
$useDefaultStdErr = $StdErrPath -eq ".tmp\chat-facade-acceptance.err.log"
$effectiveStdOutPath = if ($useDefaultStdOut) {
    ".tmp\chat-facade-acceptance-$Port-$timestamp.out.log"
} else {
    $StdOutPath
}
$effectiveStdErrPath = if ($useDefaultStdErr) {
    ".tmp\chat-facade-acceptance-$Port-$timestamp.err.log"
} else {
    $StdErrPath
}
$resolvedStdOutPath = [System.IO.Path]::GetFullPath((Join-Path $workingDir $effectiveStdOutPath))
$resolvedStdErrPath = [System.IO.Path]::GetFullPath((Join-Path $workingDir $effectiveStdErrPath))
Remove-Item -LiteralPath $resolvedStdOutPath, $resolvedStdErrPath -Force -ErrorAction SilentlyContinue
Assert-PortFree -TargetPort $Port
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
. (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet
$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
$argumentList = @("--enable-preview", "-Dserver.port=$Port", "-jar", $resolvedJar)
$harness = Start-Process `
    -FilePath $javaExe `
    -ArgumentList $argumentList `
    -WorkingDirectory (Get-Location) `
    -RedirectStandardOutput $resolvedStdOutPath `
    -RedirectStandardError $resolvedStdErrPath `
    -PassThru
$harnessPid = $harness.Id
$baseUrl = "http://localhost:$Port"

try {
    Wait-Health -BaseUrl $baseUrl -TimeoutSec $StartupTimeoutSec

    $dialogueShellProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-DialogueShellAcceptanceProbe.ps1") -BaseUrl $baseUrl
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $chatProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-ChatFacadeAcceptanceProbe.ps1") -BaseUrl $baseUrl
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $responsesProbe = & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Run-ChatFacadeAcceptanceProbe.ps1") -BaseUrl $baseUrl -UseResponsesSurface
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $result = [ordered]@{
        base_url = $baseUrl
        dialogue_shell_probe = ($dialogueShellProbe | ConvertFrom-Json)
        chat_probe = ($chatProbe | ConvertFrom-Json)
        responses_probe = ($responsesProbe | ConvertFrom-Json)
    }
    if ($KeepServerLogs) {
        $result.stdout_log = $resolvedStdOutPath
        $result.stderr_log = $resolvedStdErrPath
    }
    [pscustomobject]$result | ConvertTo-Json -Depth 8
}
finally {
    try {
        Stop-Process -Id $harnessPid -ErrorAction SilentlyContinue
        Wait-Process -Id $harnessPid -Timeout 10 -ErrorAction SilentlyContinue
    } catch {
    }
    if (-not $KeepServerLogs) {
        Remove-FileWithRetry -Path $resolvedStdOutPath
        Remove-FileWithRetry -Path $resolvedStdErrPath
        Start-DeferredCleanup -Paths @($resolvedStdOutPath, $resolvedStdErrPath)
    }
}
