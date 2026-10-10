#requires -Version 7
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$lib = Join-Path $root 'scripts\lib\JevBalancedLabel.ps1'
. $lib
$pass = 0; $fail = 0
function Assert-True {
    param([bool]$Condition,[string]$Label)
    if ($Condition) { Write-Host "PASS $Label"; $script:pass++ }
    else { Write-Host "FAIL $Label"; $script:fail++ }
}

$tmp = Join-Path $env:TEMP ('jev-label-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$src = Join-Path $tmp 'sample.json'
$payload = @{
    schema_version = 1
    context = @{ project = 'demo' }
    jev = @{ error = $null; probability = 0.62; action = 'KEEP_VERBATIM'; duration_ms = 10 }
    observed = @{ status = 'completed'; current_stage = 'x' }
    decision_hint = @{ action = 'HUMAN_REVIEW' }
} | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($src, $payload, [System.Text.UTF8Encoding]::new($false))

$rows = Read-JevShadowArtifactsForLabeling -ShadowDir $tmp
Assert-True (@($rows).Count -eq 1) 'read one artifact'
$summary = Get-JevLabelGapSummary -Rows $rows
Assert-True ($summary.total -eq 1) 'summary total'
Assert-True ($summary.unlabeled -eq 1) 'summary unlabeled'
Assert-True ($summary.status_positive -eq 1) 'summary status_positive'
Assert-True ($summary.tn_candidates -eq 0) 'summary tn_candidates zero'

$queuePath = Join-Path $tmp 'queue.json'
$queue = New-JevBalancedLabelQueue -Rows $rows -OutputPath $queuePath -TargetNegative 10
Assert-True (@($queue).Count -ge 1) 'queue non-empty'
Assert-True (Test-Path -LiteralPath $queuePath) 'queue file written'

$map = @([pscustomobject]@{ path = $src; human_label = 'negative'; file = 'sample.json' })
$mapPath = Join-Path $tmp 'map.json'
[System.IO.File]::WriteAllText($mapPath, ($map | ConvertTo-Json -Depth 4 -AsArray), [System.Text.UTF8Encoding]::new($false))
$outDir = Join-Path $tmp 'labeled'
$applied = Apply-JevHumanLabels -LabelMapPath $mapPath -OutputDir $outDir
Assert-True ($applied -eq 1) 'applied one label'
$labeled = Get-Content -LiteralPath (Join-Path $outDir 'sample.json') -Raw | ConvertFrom-Json
Assert-True ($labeled.observed.human_label -eq 'negative') 'human_label persisted'

$md = Format-JevLabelGapMarkdown -Summary $summary -Queue $queue
Assert-True ($md -match 'Jev balanced labeling gap') 'markdown title'
Assert-True ($md -match 'review queue') 'markdown queue section'

$bytes = [System.IO.File]::ReadAllBytes($lib)
Assert-True (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) 'lib no BOM'

Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "summary pass=$pass fail=$fail"
if ($fail -gt 0) { exit 1 }