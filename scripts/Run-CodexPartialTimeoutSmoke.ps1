param(
    [switch]$SkipJavaTests,
    [switch]$SkipNodeTests,
    [string]$ReportPath = ".tmp/codex-partial-timeout-smoke/report.json"
)

$ErrorActionPreference = "Stop"

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host ("[codex-partial-smoke] {0}" -f $Name)
    & $Action
}

function Ensure-LastExitCodeZero {
    param([string]$Message)
    if ($LASTEXITCODE -ne 0) {
        throw $Message
    }
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

$report = [ordered]@{
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    java_tests = [ordered]@{
        skipped = [bool]$SkipJavaTests
        command = "& { . .\scripts\Use-Java21.ps1 -Quiet; `$mvn = & .\scripts\Resolve-MavenCommand.ps1; & `$mvn -q '-Dtest=CodexAppServerWorkerExecutorTest#appServerErrorAfterPartialOutputReturnsPartialTimeoutAndKeepsOutput,CodexAppServerWorkerExecutorTest#maxDurationRemainsHardLimitEvenWhenActivityTimeoutIsLarger,ControlNodeGraphOrchestrationFlowTest#partialTimeoutProviderRoundStopsAtHumanGateInsteadOfAutoContinuing,TaskServiceControlActionProjectionTest#continueTaskAppliesProviderThreadContinuationMetadataBeforeEnteringGraph' test }"
        exit_code = $null
    }
    node_tests = [ordered]@{
        skipped = [bool]$SkipNodeTests
        command = "node --test src/test/js/dialogue-worker-round-action-plan.test.mjs"
        exit_code = $null
    }
    passed = $false
}

try {
    if (-not $SkipJavaTests) {
        Invoke-Step -Name "java partial-timeout regression" -Action {
            & {
                . .\scripts\Use-Java21.ps1 -Quiet
                $mvn = & .\scripts\Resolve-MavenCommand.ps1
                & $mvn -q '-Dtest=CodexAppServerWorkerExecutorTest#appServerErrorAfterPartialOutputReturnsPartialTimeoutAndKeepsOutput,CodexAppServerWorkerExecutorTest#maxDurationRemainsHardLimitEvenWhenActivityTimeoutIsLarger,ControlNodeGraphOrchestrationFlowTest#partialTimeoutProviderRoundStopsAtHumanGateInsteadOfAutoContinuing,TaskServiceControlActionProjectionTest#continueTaskAppliesProviderThreadContinuationMetadataBeforeEnteringGraph' test
            }
            $report.java_tests.exit_code = $LASTEXITCODE
            Ensure-LastExitCodeZero "Codex partial-timeout Java regression failed."
        }
    }

    if (-not $SkipNodeTests) {
        Invoke-Step -Name "dialogue worker-round action plan" -Action {
            node --test src/test/js/dialogue-worker-round-action-plan.test.mjs
            $report.node_tests.exit_code = $LASTEXITCODE
            Ensure-LastExitCodeZero "Dialogue worker-round action plan test failed."
        }
    }

    $report.passed = $true
}
finally {
    $report.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $parent = Split-Path -Parent $ReportPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
}

$report | ConvertTo-Json -Depth 6
