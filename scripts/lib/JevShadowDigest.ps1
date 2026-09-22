function Read-PatrolJevShadowArtifacts {
    param([Parameter(Mandatory = $true)][string]$ShadowDir)
    if (-not (Test-Path -LiteralPath $ShadowDir)) { return @() }
    $out = @()
    foreach ($path in (Get-ChildItem -LiteralPath $ShadowDir -Filter '*.json' -File | Sort-Object LastWriteTime)) {
        try {
            $raw = [System.IO.File]::ReadAllText($path.FullName, [System.Text.UTF8Encoding]::new($false))
            $obj = $raw | ConvertFrom-Json
            $out += [pscustomobject]@{
                path = $path.FullName
                project = [string]$obj.context.project
                item_id = [string]$obj.context.item_id
                probability = $obj.jev.probability
                action = [string]$obj.jev.action
                error = [string]$obj.jev.error
                latency_ms = $obj.jev.duration_ms
                observed_status = [string]$obj.observed.status
                observed_stage = [string]$obj.observed.current_stage
                worker_mode = [string]$obj.observed.worker_mode
                generated_at = [datetime]$obj.generated_at
            }
        } catch {}
    }
    return ,$out
}

function Build-PatrolJevShadowSummary {
    param([Parameter(Mandatory = $true)]$Artifacts)
    $total = @($Artifacts).Count
    if ($total -eq 0) {
        return [pscustomobject]@{
            total = 0
            with_score = 0
            errored = 0
            avg_probability = $null
            avg_latency_ms = $null
            action_distribution = @{}
            projects = @()
        }
    }
    $scored = @($Artifacts | Where-Object { $_.error -in @('', $null) -and $null -ne $_.probability })
    $errored = @($Artifacts | Where-Object { $_.error -notin @('', $null) })
    $avgProbability = if ($scored.Count -gt 0) { ($scored | Measure-Object -Property probability -Average).Average } else { $null }
    $avgLatency = if ($scored.Count -gt 0) { ($scored | Measure-Object -Property latency_ms -Average).Average } else { $null }
    $actionDist = @{}
    foreach ($a in $Artifacts) {
        $key = if ($a.error -notin @('', $null)) { 'error' } else { $a.action }
        if (-not $actionDist.ContainsKey($key)) { $actionDist[$key] = 0 }
        $actionDist[$key]++
    }
    $projects = @($Artifacts | Group-Object project | ForEach-Object {
            [pscustomobject]@{
                project = $_.Name
                count = $_.Count
                last_at = ($_.Group | Sort-Object generated_at -Descending | Select-Object -First 1).generated_at
            }
        })
    return [pscustomobject]@{
        total = $total
        with_score = $scored.Count
        errored = $errored.Count
        avg_probability = $avgProbability
        avg_latency_ms = $avgLatency
        action_distribution = $actionDist
        projects = $projects
    }
}

function Get-PatrolJevShadowActiveAdvice {
    param(
        [Parameter(Mandatory = $true)][double]$ThresholdLow = 0.30,
        [Parameter(Mandatory = $true)][double]$ThresholdHigh = 0.70,
        [double]$KeptFloor = 0.50
    )
    [pscustomobject]@{
        threshold_low = $ThresholdLow
        threshold_high = $ThresholdHigh
        kept_floor = $KeptFloor
        explanation = 'Active routing gates by uncertainty band: prob<low -> KEEP_VERBATIM skip; prob>high -> TRUNCATE_HEAD dispatch (init prompt degradation only); band -> HUMAN_REVIEW (pause this project until manual review).'
    }
}

function Format-PatrolJevShadowMarkdown {
    param(
        [Parameter(Mandatory = $true)]$Summary,
        [string]$GeneratedAt = ([DateTimeOffset]::UtcNow.ToString('o')),
        [int]$TopProjects = 20
    )
    $lines = @()
    $lines += '# Patrol-scaffold Jev shadow summary'
    $lines += ''
    $lines += ('- generated_at: ' + $GeneratedAt)
    $lines += ('- total: ' + $Summary.total)
    $lines += ('- with_score: ' + $Summary.with_score)
    $lines += ('- errored: ' + $Summary.errored)
    if ($null -ne $Summary.avg_probability) { $lines += ('- avg_probability: ' + [math]::Round($Summary.avg_probability, 4)) }
    if ($null -ne $Summary.avg_latency_ms) { $lines += ('- avg_latency_ms: ' + [math]::Round($Summary.avg_latency_ms, 1)) }
    if ($Summary.action_distribution -and $Summary.action_distribution.Count -gt 0) {
        $lines += ''
        $lines += '## action distribution'
        foreach ($k in ($Summary.action_distribution.Keys | Sort-Object)) {
            $lines += ('- ' + $k + ': ' + $Summary.action_distribution[$k])
        }
    }
    if ($Summary.projects -and $Summary.projects.Count -gt 0) {
        $lines += ''
        $lines += '## projects (first ' + [Math]::Min($TopProjects, $Summary.projects.Count) + ')'
        foreach ($p in ($Summary.projects | Select-Object -First $TopProjects)) {
            $lines += ('- ' + $p.project + ' (count=' + $p.count + ', last_at=' + $p.last_at + ')')
        }
    }
    return ($lines -join "`n")
}