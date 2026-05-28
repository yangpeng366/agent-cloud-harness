param(
    [string]$JdkHome = "C:\Program Files\Java\jdk-21.0.9+10",
    [switch]$SkipTests,
    [switch]$QuietMaven
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
    exit 1
}

function Write-Info {
    param([string]$Message)
    Write-Host "`n[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Warning {
    param([string]$Message)
    Write-Host "`n[WARN] $Message" -ForegroundColor Yellow
}

Write-Info "Starting build for Agent Cloud Harness..."

try {
    Write-Info "Step 1/3: Configure Java 21 environment"
    . (Join-Path $PSScriptRoot "Use-Java21.ps1") -JdkHome $JdkHome -Quiet
    Write-Success "Java environment configured successfully"
}
catch {
    Write-ErrorWithHelp `
        -Message "Failed to configure Java environment: $_" `
        -HelpMessage @"
1. Ensure Java 21 is installed (--enable-preview required)
2. Check if JdkHome parameter is correct
3. Try running manually: .\scripts\Use-Java21.ps1 for detailed error
"@
}

try {
    Write-Info "Step 2/3: Find Maven executable"
    $mavenExecutable = & (Join-Path $PSScriptRoot "Resolve-MavenCommand.ps1")
    Write-Success "Found Maven: $mavenExecutable"
}
catch {
    Write-ErrorWithHelp `
        -Message "Maven not found: $_" `
        -HelpMessage @"
1. Install Maven 3.9+: https://maven.apache.org/download.cgi
2. Add Maven to system PATH
3. Or use IDE built-in Maven (e.g., IntelliJ)

Common installation methods:
- Chocolatey: choco install maven
- Scoop: scoop install maven
- Manual download and configure environment variables
"@
}

Write-Info "Step 3/3: Execute Maven build"

$mavenArgs = @()
if ($QuietMaven) {
    $mavenArgs += "-q"
}
if ($SkipTests) {
    $mavenArgs += "-DskipTests"
    Write-Warning "Skip tests mode enabled"
}
$mavenArgs += "package"

Write-Host "`nExecuting command: $mavenExecutable $($mavenArgs -join ' ')"
Write-Host "------------------------------------------------------"

& $mavenExecutable @mavenArgs

if ($LASTEXITCODE -ne 0) {
    Write-ErrorWithHelp `
        -Message "Build failed with exit code: $LASTEXITCODE" `
        -HelpMessage @"
Common build failure causes and solutions:

1. **Network issues**
   - Check network connection
   - Configure Maven mirror (add Aliyun mirror in ~/.m2/settings.xml)

2. **Dependency conflicts**
   - Run: mvn clean package -DskipTests
   - Clean local repository: delete ~/.m2/repository directory

3. **Java version issues**
   - Ensure using Java 21 (project uses --enable-preview)
   - Run: java -version to check version

4. **Out of memory**
   - Set environment variable: MAVEN_OPTS="-Xmx2048m"

5. **View detailed logs**
   - Re-run and check full error message
"@
}

Write-Host "------------------------------------------------------"
Write-Success "Build successful!"

$outputJar = "target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar"
if (Test-Path $outputJar) {
    Write-Success "Build artifact: $outputJar"
}
else {
    $outputJar = "target/agent-cloud-harness-0.1.0-SNAPSHOT.jar"
    if (Test-Path $outputJar) {
        Write-Success "Build artifact: $outputJar"
    }
    else {
        Write-Warning "Expected JAR file not found"
    }
}

Write-Host @"

Next steps:
- Start service: .\scripts\Run-HarnessWithJava21.ps1
- Or use shortcut: powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 8080

Access URLs:
- Dialogue: http://localhost:8080/dialogue/
- Console: http://localhost:8080/console/
- Health: http://localhost:8080/api/v1/health
"@
