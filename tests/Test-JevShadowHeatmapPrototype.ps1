# Test-JevShadowHeatmapPrototype.ps1
# 锁定 Phase6 heatmap 口径 v1（2026-11-18）：脚本与设计稿必须给出同一组分数/等级。
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot '..\scripts\Build-JevShadowHeatmapPrototype.ps1'
if (-not (Test-Path -LiteralPath $scriptPath)) { throw "Script not found: $scriptPath" }
$pass = 0
$fail = 0
function Assert-Eq { param($Expected, $Actual, [string]$Label); if ($Expected -eq $Actual) { Write-Host "[OK]  $Label"; $script:pass++ } else { Write-Host "[FAIL] $Label (expected='$Expected' actual='$Actual')"; $script:fail++ } }
function Assert-True { param([bool]$Condition, [string]$Label); if ($Condition) { Write-Host "[OK]  $Label"; $script:pass++ } else { Write-Host "[FAIL] $Label"; $script:fail++ } }

$asOf = [datetime]'2026-11-18 12:00:00'
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("jev-heatmap-fixture-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)

function New-Sidecar {
    param([string]$Name, [string]$Project, $Probability, $DurationMs, $Error, [string]$GeneratedAt)
    $doc = [ordered]@{
        context = @{ project = $Project }
        jev     = [ordered]@{ probability = $Probability; duration_ms = $DurationMs; error = $Error }
        generated_at = $GeneratedAt
    }
    [System.IO.File]::WriteAllText((Join-Path $fixture "$Name.json"), ($doc | ConvertTo-Json -Depth 4), $utf8)
}

$now = $asOf.AddDays(-1).ToString('o')
$stale = $asOf.AddDays(-30).ToString('o')
# score = 100 - 60*(1-p) - 25*(p95/5000) - 15*errorRate
New-Sidecar -Name 's1' -Project 'healthy'   -Probability 0.90 -DurationMs 1000 -Error $null -GeneratedAt $now   # 100-6-5   = 89.0 B
New-Sidecar -Name 's2' -Project 'edgeB'     -Probability 0.75 -DurationMs 1000 -Error $null -GeneratedAt $now   # 100-15-5  = 80.0 B
New-Sidecar -Name 's3' -Project 'edgeC'     -Probability 0.50 -DurationMs 2000 -Error $null -GeneratedAt $now   # 100-30-10 = 60.0 C
New-Sidecar -Name 's4' -Project 'edgeF'     -Probability 0.499 -DurationMs 2000 -Error $null -GeneratedAt $now  # 59.94    = 59.9 F
New-Sidecar -Name 's5' -Project 'erroneous' -Probability $null -DurationMs 0   -Error $true -GeneratedAt $now   # 100-60-0-15 = 25.0 F
New-Sidecar -Name 's6a' -Project 'p95case' -Probability 0.60 -DurationMs 100   -Error $null -GeneratedAt $now
New-Sidecar -Name 's6b' -Project 'p95case' -Probability 0.60 -DurationMs 200   -Error $null -GeneratedAt $now
New-Sidecar -Name 's6c' -Project 'p95case' -Probability 0.60 -DurationMs 300   -Error $null -GeneratedAt $now
New-Sidecar -Name 's6d' -Project 'p95case' -Probability 0.60 -DurationMs 400   -Error $null -GeneratedAt $now
New-Sidecar -Name 's6e' -Project 'p95case' -Probability 0.60 -DurationMs 500   -Error $null -GeneratedAt $now   # p95=500（nearest-rank ceil(4.75)=5）→ 100-24-2.5 = 73.5 C
New-Sidecar -Name 's7'  -Project 'clampcase' -Probability 1.0 -DurationMs 10000 -Error $null -GeneratedAt $now  # 延迟截断 5000 → 75.0 C
New-Sidecar -Name 's8'  -Project 'floorcase' -Probability 0.0 -DurationMs 5000 -Error $true -GeneratedAt $now    # 100-60-25-15 = 0 → clamp 0.0 F
New-Sidecar -Name 's9'  -Project 'stale'    -Probability 0.90 -DurationMs 1000 -Error $null -GeneratedAt $stale # 仅 WindowDays=0 时出现

$json7 = & $scriptPath -ShadowDirectory $fixture -WindowDays 7 -AsOf $asOf
$r7 = $json7 | ConvertFrom-Json
$byProject = @{}
foreach ($row in $r7.rows) { $byProject[$row.project] = $row }

Assert-Eq 1 $r7.schema_version 'schema_version = 1'
Assert-Eq 'v1(2026-11-18)' $r7.formula 'formula tag pinned to v1'
Assert-Eq 7 $r7.window_days 'window_days echoed'
Assert-Eq 5000 $r7.latency_budget_ms 'latency budget echoed'
Assert-Eq 8 $r7.total_projects 'stale project filtered out by 7d window'
Assert-True (-not $byProject.ContainsKey('stale')) 'stale sidecar excluded at WindowDays=7'

Assert-Eq 89.0 $byProject['healthy'].score 'healthy score = 100-6-5'
Assert-Eq 'B' $byProject['healthy'].grade 'healthy grade B'
Assert-Eq 80.0 $byProject['edgeB'].score 'edgeB score = exactly 80'
Assert-Eq 'B' $byProject['edgeB'].grade 'score 80 is still B (>=80 inclusive)'
Assert-Eq 60.0 $byProject['edgeC'].score 'edgeC score = exactly 60'
Assert-Eq 'C' $byProject['edgeC'].grade 'score 60 is C (60 <= s < 80)'
Assert-Eq 59.9 $byProject['edgeF'].score 'edgeF score 59.94 rounds to 59.9'
Assert-Eq 'F' $byProject['edgeF'].grade 'score < 60 is F'
Assert-Eq 25.0 $byProject['erroneous'].score 'error-only project: no prob samples -> prob 0, errorRate 1'
Assert-Eq 0.0 $byProject['erroneous'].probability 'error sample excluded from prob_avg'
Assert-Eq 73.5 $byProject['p95case'].score 'nearest-rank p95 of 100..500 is 500'
Assert-Eq 500.0 $byProject['p95case'].latency_p95_ms 'p95 latency reported in ms'
Assert-Eq 5 $byProject['p95case'].samples 'samples counted per project'
Assert-Eq 75.0 $byProject['clampcase'].score 'duration above budget clipped to budget'
Assert-Eq 5000.0 $byProject['clampcase'].latency_p95_ms 'clipped p95 equals budget'
Assert-Eq 0.0 $byProject['floorcase'].score 'score clamped at 0'
Assert-Eq 'F' $byProject['floorcase'].grade 'clamped score grade F'

# 排序：分数降序
$scores = @($r7.rows | ForEach-Object { [double]$_.score })
$sorted = @($scores | Sort-Object -Descending)
Assert-Eq ($sorted -join ',') ($scores -join ',') 'rows sorted by score desc'

# 全量窗口把 stale 找回
$jsonAll = & $scriptPath -ShadowDirectory $fixture -WindowDays 0 -AsOf $asOf
$rAll = $jsonAll | ConvertFrom-Json
Assert-Eq 9 $rAll.total_projects 'WindowDays=0 keeps all sidecars'
Assert-Eq 0 $rAll.window_days 'window_days=0 means unlimited'

# markdown 输出走同一口径
$md = & $scriptPath -ShadowDirectory $fixture -WindowDays 7 -AsOf $asOf -Markdown
Assert-True ($md -match '## 项目健康度（截至 2026-11-18 12:00') 'markdown header carries as-of + window'
Assert-True ($md -match '\| healthy \| 89 \| B \|') 'markdown row for healthy matches JSON'
Assert-True ($md -match '\| edgeF \| 59\.9 \| F \|') 'markdown row for edgeF matches JSON'

# 输出文件写入（UTF-8 无 BOM）
$outFile = Join-Path $fixture 'out.json'
& $scriptPath -ShadowDirectory $fixture -WindowDays 7 -AsOf $asOf -OutputFile $outFile | Out-Null
$bytes = [System.IO.File]::ReadAllBytes($outFile)
Assert-True ($bytes.Length -gt 0 -and -not ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB)) 'output file is UTF-8 without BOM'
$reload = (Get-Content -LiteralPath $outFile -Raw -Encoding UTF8 | ConvertFrom-Json)
Assert-Eq 8 $reload.total_projects 'output file reload parses'

Remove-Item -LiteralPath $fixture -Recurse -Force
Write-Host ""
Write-Host "=== Test-JevShadowHeatmapPrototype summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
