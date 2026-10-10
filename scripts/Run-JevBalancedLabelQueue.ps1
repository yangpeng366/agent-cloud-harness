[CmdletBinding()]
param(
    [string]$ShadowDir = 'D:\gitAll\patrol-scaffold\downloads\jev-shadow-bitable-fake-20260922-201125',
    [string]$OutputDirectory = '',
    [int]$TargetNegative = 10
)
$ErrorActionPreference = 'Stop'
if (-not $OutputDirectory) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDirectory = Join-Path $PSScriptRoot ("..\.tmp\jev-balanced-label-" + $stamp)
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath $OutputDirectory)) { New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null }

$lib = Join-Path $PSScriptRoot 'lib\JevBalancedLabel.ps1'
. $lib

$rows = Read-JevShadowArtifactsForLabeling -ShadowDir $ShadowDir
$summary = Get-JevLabelGapSummary -Rows $rows
$queuePath = Join-Path $OutputDirectory 'label-queue.json'
$queue = New-JevBalancedLabelQueue -Rows $rows -OutputPath $queuePath -TargetNegative $TargetNegative
$mdPath = Join-Path $OutputDirectory 'label-gap.md'
[System.IO.File]::WriteAllText($mdPath, (Format-JevLabelGapMarkdown -Summary $summary -Queue $queue), [System.Text.UTF8Encoding]::new($false))
$summaryPath = Join-Path $OutputDirectory 'label-gap-summary.json'
[System.IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 5), [System.Text.UTF8Encoding]::new($false))

Write-Host ("shadow_dir=" + $ShadowDir)
Write-Host ("total=" + $summary.total + " labeled=" + $summary.labeled + " unlabeled=" + $summary.unlabeled)
Write-Host ("status_pos=" + $summary.status_positive + " status_neg=" + $summary.status_negative + " tn_candidates=" + $summary.tn_candidates)
Write-Host ("queue=" + $queuePath)
Write-Host ("md=" + $mdPath)