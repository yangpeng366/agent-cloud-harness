[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SummaryDirectory,
    [string]$OutputFile,
    [double]$DivergenceThreshold = 0.15
)
$ErrorActionPreference = 'Stop'
$summaries = @(Get-ChildItem -LiteralPath $SummaryDirectory -Filter '*.json' -File | Sort-Object Name | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json -AsHashTable })
if ($summaries.Count -lt 2) { throw 'Cascade prototype needs at least 2 window summaries.' }
$current = @($summaries)[-1]; $previous = @($summaries)[-2]
$divergence = [math]::Abs([double]$current.avg_probability - [double]$previous.avg_probability)
$signal = if ($divergence -gt $DivergenceThreshold) { 1 } else { 0 }
$output = [ordered]@{ mean_keep_probability = [double]$current.avg_probability; divergence_from_previous_window = [math]::Round($divergence, 4); divergence_threshold = $DivergenceThreshold; cascade_signal = $signal }
$json = $output | ConvertTo-Json -Depth 3
if ($OutputFile) { [System.IO.File]::WriteAllText($OutputFile, $json, [System.Text.UTF8Encoding]::new($false)) } else { $json }
