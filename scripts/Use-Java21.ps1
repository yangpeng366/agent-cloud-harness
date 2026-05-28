param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [switch]$Quiet
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
    throw $Message
}

function Write-Info {
    param([string]$Message)
    Write-Host "`n[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

Write-Info "Configuring Java 21 environment..."

if (-not (Test-Path -LiteralPath $JdkHome)) {
    Write-ErrorWithHelp `
        -Message "Java 21 installation directory not found: $JdkHome" `
        -HelpMessage @"
1. Download and install Java 21 first:
   - Official download: https://jdk.java.net/21/
   - Or use SDKMAN (recommended): https://sdkman.io/

2. After installation, you have the following options:
   - Option A: Install to default path (recommended)
     Install to: C:\Program Files\Java\jdk-21.0.9+10

   - Option B: Specify custom path
     Use command: .\scripts\Use-Java21.ps1 -JdkHome "your JDK path"

3. Verify installation:
   Open new terminal, run: java -version
   Should show: openjdk version "21.x.x"
"@
}

$javaExe = Join-Path $JdkHome "bin\java.exe"
if (-not (Test-Path -LiteralPath $javaExe)) {
    Write-ErrorWithHelp `
        -Message "Java executable not found: $javaExe" `
        -HelpMessage @"
JDK directory exists but java.exe is missing.
Installation may be incomplete or path is incorrect.

Suggestions:
1. Reinstall Java 21
2. Ensure JdkHome points to correct JDK installation directory (not JRE)
"@
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
    Write-Success "JAVA_HOME set to: $env:JAVA_HOME"

    try {
        $javaVersion = & $javaExe -version 2>&1 | Select-Object -First 1
        Write-Success "Java version: $javaVersion"
    }
    catch {
        Write-Info "Java environment set but version detection failed"
    }

    if ($env:AGENTCLOUD_PREVIOUS_CLASSPATH) {
        Write-Info "Cleaned inherited CLASSPATH to avoid old JDK/runtime conflicts"
    }

    Write-Host @"

Usage tips:
- To keep this environment in current shell, use dot prefix:
  . .\scripts\Use-Java21.ps1

- To specify custom JDK path:
  . .\scripts\Use-Java21.ps1 -JdkHome "C:\path\to\jdk-21"

- Verify environment:
  java -version
"@
}
