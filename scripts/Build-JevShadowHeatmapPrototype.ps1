[CmdletBinding()]
param(
    # 目录下每个 *.json 是一条 judgment sidecar（需含 context.project 与 jev.* 摘要字段）
    [Parameter(Mandatory)][string]$ShadowDirectory,
    [string]$OutputFile,
    # 延迟归一化预算：p95(duration_ms) / LatencyBudgetMs，权重 25 分
    [double]$LatencyBudgetMs = 5000,
    # 滑动窗口天数；0 = 不限（全量）。默认 7 天，与设计稿一致
    [int]$WindowDays = 7,
    # 输出 markdown 热力表（默认输出 JSON）
    [switch]$Markdown,
    # 参考时间（用于窗口过滤，默认当前时间；测试可注入固定值）
    [datetime]$AsOf = (Get-Date)
)
$ErrorActionPreference = 'Stop'

# ---- 口径 v1（2026-11-18 定稿，见 docs/JEV_PATROL_PHASE6_HEATMAP_DESIGN.md §1）----
# score = clamp(0,100, 100 - 60*(1 - prob_avg) - 25*(p95/latencyBudgetMs) - 15*errorRate)
#   prob_avg   : 有数值 probability 且非 error 的样本均值（无样本记 0）
#   p95        : 每条 duration_ms 先硬截断到 [0, latencyBudgetMs]，再取 nearest-rank p95
#   errorRate  : 该 project 内 error 为真的样本数 / 样本总数
#   grade      : score >= 80 -> B；>= 60 -> C；否则 F
# 设计稿里的 "+10 * window_count_in_due" 项在 v1 未启用：sidecar 无 due 字段，
# 一旦硬加即凭空造分；等接入 cascade window 数据后作为 v2 单独开关。

function Get-Prop {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $p = $Object.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

function ConvertTo-NullableDouble {
    param($Value)
    if ($null -eq $Value) { return $null }
    $parsed = 0.0
    if ([double]::TryParse([string]$Value, [ref]$parsed)) { return $parsed }
    return $null
}

function Get-NearestRankP95 {
    param([double[]]$Sorted)
    if (-not $Sorted -or $Sorted.Count -eq 0) { return 0.0 }
    $rank = [int][math]::Ceiling(0.95 * $Sorted.Count)
    if ($rank -lt 1) { $rank = 1 }
    if ($rank -gt $Sorted.Count) { $rank = $Sorted.Count }
    return [double]$Sorted[$rank - 1]
}

function Get-SidecarOccurredAt {
    param($Sidecar, [System.IO.FileInfo]$File)
    $raw = Get-Prop $Sidecar 'generated_at'
    if ($raw) {
        $parsed = [datetimeoffset]::MinValue
        if ([datetimeoffset]::TryParse([string]$raw, [ref]$parsed)) { return $parsed.LocalDateTime }
    }
    return $File.LastWriteTime
}

$files = @(Get-ChildItem -LiteralPath $ShadowDirectory -Filter '*.json' -File)
$sidecars = foreach ($f in $files) {
    $doc = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    [pscustomobject]@{
        project     = [string](Get-Prop (Get-Prop $doc 'context') 'project')
        probability = ConvertTo-NullableDouble (Get-Prop (Get-Prop $doc 'jev') 'probability')
        durationMs  = ConvertTo-NullableDouble (Get-Prop (Get-Prop $doc 'jev') 'duration_ms')
        error       = [bool](Get-Prop (Get-Prop $doc 'jev') 'error')
        occurredAt  = Get-SidecarOccurredAt -Sidecar $doc -File $f
    }
}

if ($WindowDays -gt 0) {
    $cutoff = $AsOf.AddDays(-1 * $WindowDays)
    $sidecars = @($sidecars | Where-Object { $_.occurredAt -ge $cutoff })
} else {
    $sidecars = @($sidecars)
}

$rows = @($sidecars | Group-Object { if ([string]::IsNullOrWhiteSpace($_.project)) { 'unknown' } else { $_.project } } | ForEach-Object {
    $group = @($_.Group)
    $probSamples = @($group | Where-Object { (-not $_.error) -and ($null -ne $_.probability) })
    $probability = if ($probSamples.Count) {
        [double](@($probSamples | ForEach-Object { $_.probability } | Measure-Object -Average).Average)
    } else { 0.0 }

    $latencies = @($group |
        Where-Object { $null -ne $_.durationMs } |
        ForEach-Object { [math]::Min([math]::Max([double]$_.durationMs, 0.0), $LatencyBudgetMs) } |
        Sort-Object)
    $p95 = Get-NearestRankP95 -Sorted $latencies

    $errorCount = @($group | Where-Object { $_.error }).Count
    $errorRate = if ($group.Count) { $errorCount / $group.Count } else { 0.0 }

    $raw = 100.0 - (60.0 * (1.0 - $probability)) - (25.0 * ($p95 / $LatencyBudgetMs)) - (15.0 * $errorRate)
    $score = [math]::Round([math]::Min(100.0, [math]::Max(0.0, $raw)), 1)
    [pscustomobject]@{
        project        = $_.Name
        score          = $score
        grade          = if ($score -ge 80) { 'B' } elseif ($score -ge 60) { 'C' } else { 'F' }
        probability    = [math]::Round($probability, 3)
        latency_p95_ms = [math]::Round($p95, 1)
        error_rate     = [math]::Round($errorRate, 3)
        samples        = $group.Count
    }
} | Sort-Object -Property @{ Expression = 'score'; Descending = $true }, @{ Expression = 'project'; Descending = $false })

$utf8 = [System.Text.UTF8Encoding]::new($false)
if ($Markdown) {
    $stamp = $AsOf.ToString('yyyy-MM-dd HH:mm')
    $lines = @("## 项目健康度（截至 $stamp，窗口 ${WindowDays}d，口径 v1）", '', '| 项目 | score | 等级 | prob_avg | latency_p95 | err | samples |', '|---|---|---|---|---|---|---|')
    foreach ($row in $rows) {
        $lines += ('| {0} | {1} | {2} | {3} | {4}ms | {5} | {6} |' -f $row.project, $row.score, $row.grade, $row.probability, $row.latency_p95_ms, $row.error_rate, $row.samples)
    }
    $text = ($lines -join "`n") + "`n"
} else {
    $text = [ordered]@{
        schema_version = 1
        formula        = 'v1(2026-11-18)'
        window_days    = $WindowDays
        latency_budget_ms = $LatencyBudgetMs
        total_projects = $rows.Count
        rows           = $rows
    } | ConvertTo-Json -Depth 4
}

if ($OutputFile) { [System.IO.File]::WriteAllText($OutputFile, $text, $utf8) } else { $text }
