[CmdletBinding()]
param(
    [string]$BaseDir = 'D:\gitAll\patrols\feishu-projects-patrol',
    [string]$ShadowDir,
    [string]$ProjectResultsDir,
    [string]$OutputDirectory,
    [double]$ThresholdLow = 0.30,
    [double]$ThresholdHigh = 0.70,
    [string]$Mode = 'manual'
)
if (-not $ShadowDir) { $ShadowDir = Join-Path $BaseDir 'state\jev-shadow' }
if (-not $ProjectResultsDir) { $ProjectResultsDir = Join-Path $BaseDir 'state\project-results' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $BaseDir 'reports' }
if (-not (Test-Path -LiteralPath $OutputDirectory)) {
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
}
$lib = Join-Path $BaseDir 'scripts\JevShadowEval.ps1'
. $lib
$inputs = Read-JevShadowEvaluationInputs -ShadowDir $ShadowDir -ProjectResultsDir $ProjectResultsDir
$eval = Build-JevShadowEvaluation -Inputs $inputs -ThresholdLow $ThresholdLow -ThresholdHigh $ThresholdHigh
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jsonPath = Join-Path $OutputDirectory ("jev-shadow-eval-{0}.json" -f $stamp)
$mdPath = Join-Path $OutputDirectory ("jev-shadow-eval-{0}.md" -f $stamp)
$eval | Add-Member -NotePropertyName mode '($Mode)' -Force
[System.IO.File]::WriteAllText($jsonPath, ($eval | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($mdPath, (Format-JevShadowEvalMarkdown -Eval $eval), [System.Text.UTF8Encoding]::new($false))
Write-Host "json=$jsonPath"
Write-Host "md=$mdPath"