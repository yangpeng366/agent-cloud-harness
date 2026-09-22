function Read-JevShadowEvaluationInputs {
    param(
        [string]$ShadowDir,
        [string]$ProjectResultsDir
    )
    $shadows = @()
    if ($ShadowDir -and (Test-Path -LiteralPath $ShadowDir)) {
        foreach ($path in (Get-ChildItem -LiteralPath $ShadowDir -Filter '*.json' -File)) {
            try {
                $raw = [System.IO.File]::ReadAllText($path.FullName, [System.Text.UTF8Encoding]::new($false))
                $obj = $raw | ConvertFrom-Json
                $shadows += [pscustomobject]@{
                    path = $path.FullName
                    project = [string]$obj.context.project
                    probability = $obj.jev.probability
                    action = [string]$obj.jev.action
                    error = [string]$obj.jev.error
                    latency_ms = $obj.jev.duration_ms
                    observed_status = [string]$obj.observed.status
                    observed_stage = [string]$obj.observed.current_stage
                    human_label = if ($obj.observed.human_label) { [string]$obj.observed.human_label } else { '' }
                    generated_at = $obj.generated_at
                }
            } catch {}
        }
    }
    $results = @()
    if ($ProjectResultsDir -and (Test-Path -LiteralPath $ProjectResultsDir)) {
        foreach ($path in (Get-ChildItem -LiteralPath $ProjectResultsDir -Filter '*.json' -File | Select-Object -First 200)) {
            try {
                $raw = [System.IO.File]::ReadAllText($path.FullName, [System.Text.UTF8Encoding]::new($false))
                $obj = $raw | ConvertFrom-Json
                $results += [pscustomobject]@{
                    project = [string]$obj.context.project
                    last_status = [string]$obj.status
                    last_summary = [string]$obj.summary
                    last_current_stage = [string]$obj.current_stage
                    path = $path.FullName
                }
            } catch {}
        }
    }
    return [pscustomobject]@{ Shadows = $shadows; Results = $results }
}

function Build-JevShadowEvaluation {
    param(
        [Parameter(Mandatory = $true)]$Inputs,
        [double]$ThresholdLow = 0.30,
        [double]$ThresholdHigh = 0.70
    )
    $total = @($Inputs.Shadows).Count
    $withScore = @($Inputs.Shadows | Where-Object { $_.error -in @('', $null) -and $null -ne $_.probability }).Count
    $errored = @($Inputs.Shadows | Where-Object { $_.error -notin @('', $null) }).Count
    $tp = 0; $fp = 0; $fn = 0; $tn = 0; $unresolved = 0
    $inUncertaintyBand = 0; $bandKept = 0; $bandTruncated = 0
    $autoKeptCorrect = 0; $autoKeptWrong = 0; $autoTruncatedCorrect = 0; $autoTruncatedWrong = 0
    $perProject = @{}
    foreach ($s in $Inputs.Shadows) {
        $action = $s.action
        $obsStatus = $s.observed_status
        $positives = @('in_progress', 'completed')
        $negatives = @('blocked', 'failed')
        $statusOk = $true
        if (-not $s.human_label -and $obsStatus -notin @('completed','blocked','failed','in_progress')) {
            $unresolved++
            $statusOk = $false
        }
        if (-not $statusOk) { continue }
        $observedPositive = $obsStatus -in $positives -or ($s.human_label -eq 'positive')
        $observedNegative = $obsStatus -in $negatives -or ($s.human_label -eq 'negative')
        $modelPositive = $action -eq 'KEEP_VERBATIM'
        $modelNegative = $action -eq 'TRUNCATE_HEAD'
        if ($observedPositive -and $modelPositive) { $tp++ }
        elseif ($observedNegative -and $modelNegative) { $tn++ }
        elseif ($observedNegative -and $modelPositive) { $fp++ }
        elseif ($observedPositive -and $modelNegative) { $fn++ }
        if ($null -ne $s.probability -and $s.error -in @('', $null)) {
            if ($s.probability -ge $ThresholdLow -and $s.probability -le $ThresholdHigh) {
                $inUncertaintyBand++
                if ($s.action -eq 'KEEP_VERBATIM') { $bandKept++ } elseif ($s.action -eq 'TRUNCATE_HEAD') { $bandTruncated++ }
            } elseif ($s.probability -lt $ThresholdLow -and $s.action -eq 'KEEP_VERBATIM') {
                if ($observedPositive) { $autoKeptCorrect++ } else { $autoKeptWrong++ }
            } elseif ($s.probability -gt $ThresholdHigh -and $s.action -eq 'TRUNCATE_HEAD') {
                if ($observedNegative) { $autoTruncatedCorrect++ } else { $autoTruncatedWrong++ }
            }
        }
        $key = $s.project
        if (-not $perProject.ContainsKey($key)) {
            $perProject[$key] = [pscustomobject]@{ project = $key; count = 0; tp = 0; fp = 0; fn = 0; tn = 0 }
        }
        $perProject[$key].count++
        if ($observedPositive -and $modelPositive) { $perProject[$key].tp++ }
        elseif ($observedNegative -and $modelNegative) { $perProject[$key].tn++ }
        elseif ($observedNegative -and $modelPositive) { $perProject[$key].fp++ }
        elseif ($observedPositive -and $modelNegative) { $perProject[$key].fn++ }
    }
    $resolved = $tp + $fp + $fn + $tn
    $hit = if ($resolved -gt 0) { ($tp + $tn) / $resolved } else { $null }
    $perProjectArr = @($perProject.Values | Sort-Object project)
    return [pscustomobject]@{
        threshold_low = $ThresholdLow
        threshold_high = $ThresholdHigh
        total = $total
        with_score = $withScore
        errored = $errored
        unresolved = $unresolved
        tp = $tp; fp = $fp; fn = $fn; tn = $tn
        hit_rate = $hit
        in_uncertainty_band = $inUncertaintyBand
        uncertainty_band_kept = $bandKept
        uncertainty_band_truncated = $bandTruncated
        auto_kept_correct = $autoKeptCorrect
        auto_kept_wrong = $autoKeptWrong
        auto_truncated_correct = $autoTruncatedCorrect
        auto_truncated_wrong = $autoTruncatedWrong
        per_project = $perProjectArr
    }
}

function Format-JevShadowEvalMarkdown {
    param(
        [Parameter(Mandatory = $true)]$Eval,
        [string]$GeneratedAt = ([DateTimeOffset]::UtcNow.ToString('o'))
    )
    $lines = @()
    $lines += '# Jev shadow evaluation'
    $lines += ''
    $lines += ('- generated_at: ' + $GeneratedAt)
    $lines += ('- threshold_low: ' + $Eval.threshold_low + ', threshold_high: ' + $Eval.threshold_high)
    $lines += ('- total: ' + $Eval.total + ', with_score: ' + $Eval.with_score + ', errored: ' + $Eval.errored + ', unresolved: ' + $Eval.unresolved)
    $lines += ('- TP: ' + $Eval.tp + ', FP: ' + $Eval.fp + ', FN: ' + $Eval.fn + ', TN: ' + $Eval.tn)
    if ($null -ne $Eval.hit_rate) { $lines += ('- hit_rate: ' + [math]::Round($Eval.hit_rate, 4)) }
    if ($Eval.in_uncertainty_band) {
        $lines += ('- in_uncertainty_band: ' + $Eval.in_uncertainty_band + ' (kept=' + $Eval.uncertainty_band_kept + ', truncated=' + $Eval.uncertainty_band_truncated + ')')
    }
    if ($Eval.auto_kept_correct -or $Eval.auto_kept_wrong -or $Eval.auto_truncated_correct -or $Eval.auto_truncated_wrong) {
        $lines += ('- auto_decision: kept correct=' + $Eval.auto_kept_correct + ', kept wrong=' + $Eval.auto_kept_wrong + ', truncated correct=' + $Eval.auto_truncated_correct + ', truncated wrong=' + $Eval.auto_truncated_wrong)
    }
    if ($Eval.per_project -and @($Eval.per_project).Count -gt 0) {
        $lines += ''
        $lines += '## per project'
        foreach ($p in $Eval.per_project) {
            $resolved = $p.tp + $p.fp + $p.fn + $p.tn
            $hit = if ($resolved -gt 0) { [math]::Round(($p.tp + $p.tn) / $resolved, 4) } else { $null }
            $hitStr = if ($null -ne $hit) { $hit } else { 'n/a' }
            $lines += ('- ' + $p.project + ' (count=' + $p.count + ', TP=' + $p.tp + ', FP=' + $p.fp + ', FN=' + $p.fn + ', TN=' + $p.tn + ', hit_rate=' + $hitStr + ')')
        }
    }
    return ($lines -join "`n")
}