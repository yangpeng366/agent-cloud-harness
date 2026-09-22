[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ShadowDirectory,
    [string]$OutputFile,
    [double]$LatencyBudgetMs = 5000
)
$ErrorActionPreference = 'Stop'
$artifacts = @(Get-ChildItem -LiteralPath $ShadowDirectory -Filter '*.json' -File | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })
$rows = @($artifacts | Group-Object { [string]$_.context.project } | ForEach-Object {
    $scored = @($_.Group | Where-Object { -not $_.jev.error })
    $latencies = @($scored | ForEach-Object { [math]::Min([double]$_.jev.duration_ms, $LatencyBudgetMs) } | Sort-Object)
    $p95 = if ($latencies.Count) { $latencies[[math]::Min($latencies.Count - 1, [math]::Floor($latencies.Count * 0.95))] } else { 0 }
    $probability = if ($scored.Count) { @($scored | ForEach-Object { $_.jev.probability } | Measure-Object -Average).Average } else { 0 }
    $errorRate = if ($_.Group.Count) { @($_.Group | Where-Object { $_.jev.error }).Count / $_.Group.Count } else { 0 }
    $score = [math]::Max(0, [math]::Round(100 - (60 * (1 - $probability)) - (25 * $p95 / $LatencyBudgetMs) - (15 * $errorRate), 1))
    [pscustomobject]@{ project = $_.Name; score = $score; grade = if ($score -ge 80) { 'B' } elseif ($score -ge 60) { 'C' } else { 'F' }; probability = [math]::Round($probability, 3); latency_p95_ms = $p95; error_rate = [math]::Round($errorRate, 3) }
} | Sort-Object score -Descending)
$json = [ordered]@{ total_projects = $rows.Count; rows = $rows } | ConvertTo-Json -Depth 4
if ($OutputFile) { [System.IO.File]::WriteAllText($OutputFile, $json, [System.Text.UTF8Encoding]::new($false)) } else { $json }
