[CmdletBinding()]
param(
    [string]$ShadowDir,
    [string]$OutputDirectory,
    [string]$BaseDir = 'D:\gitAll\patrol-scaffold',
    [int]$TopProjects = 20
)
if (-not $ShadowDir) { $ShadowDir = Join-Path $BaseDir 'downloads\jev-shadow' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $BaseDir 'downloads\reports' }
if (-not (Test-Path -LiteralPath $OutputDirectory)) {
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
}
$lib = Join-Path $PSScriptRoot 'lib\JevShadowDigest.ps1'
. $lib
$artifacts = Read-PatrolJevShadowArtifacts -ShadowDir $ShadowDir
$summary = Build-PatrolJevShadowSummary -Artifacts $artifacts
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jsonPath = Join-Path $OutputDirectory ("jev-shadow-summary-{0}.json" -f $stamp)
$adviceJsonPath = Join-Path $OutputDirectory ("jev-shadow-active-advice-{0}.json" -f $stamp)
$activeAdvice = Get-PatrolJevShadowActiveAdvice -ThresholdLow 0.30 -ThresholdHigh 0.70 -KeptFloor 0.50
[System.IO.File]::WriteAllText($adviceJsonPath, ($activeAdvice | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))
$mdPath = Join-Path $OutputDirectory ("jev-shadow-summary-{0}.md" -f $stamp)
$adviceMdPath = Join-Path $OutputDirectory ("jev-shadow-active-advice-{0}.md" -f $stamp)
$adviceMd = (("## Active routing advice (current window)" + [Environment]::NewLine + [Environment]::NewLine + "- threshold_low: " + $activeAdvice.threshold_low + [Environment]::NewLine + "- threshold_high: " + $activeAdvice.threshold_high + [Environment]::NewLine + "- kept_floor: " + $activeAdvice.kept_floor + [Environment]::NewLine + "- explanation: " + $activeAdvice.explanation + [Environment]::NewLine + [Environment]::NewLine + "NOTE: Default disabled. Maintenance turn-on: set decision.enabled=true on both concrete_"))
[System.IO.File]::WriteAllText($jsonPath, ($summary | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($mdPath, (Format-PatrolJevShadowMarkdown -Summary $summary -TopProjects $TopProjects), [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($adviceMdPath, $adviceMd, [System.Text.UTF8Encoding]::new($false))
Write-Host "json=$jsonPath"
Write-Host "md=$mdPath"
Write-Host "advice_md=$adviceMdPath"