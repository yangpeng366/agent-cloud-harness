function Read-JevShadowArtifactsForLabeling {
    param([Parameter(Mandatory)][string]$ShadowDir)
    $rows = @()
    if (-not (Test-Path -LiteralPath $ShadowDir)) { return ,$rows }
    foreach ($path in (Get-ChildItem -LiteralPath $ShadowDir -Filter '*.json' -File)) {
        try {
            $raw = [System.IO.File]::ReadAllText($path.FullName, [System.Text.UTF8Encoding]::new($false))
            $obj = $raw | ConvertFrom-Json
            $human = ''
            if ($obj.observed -and $obj.observed.human_label) { $human = [string]$obj.observed.human_label }
            $rows += [pscustomobject]@{
                path = $path.FullName
                file = $path.Name
                project = if ($obj.context) { [string]$obj.context.project } else { '' }
                probability = if ($obj.jev) { $obj.jev.probability } else { $null }
                action = if ($obj.jev) { [string]$obj.jev.action } else { '' }
                error = if ($obj.jev) { [string]$obj.jev.error } else { '' }
                observed_status = if ($obj.observed) { [string]$obj.observed.status } else { '' }
                observed_stage = if ($obj.observed) { [string]$obj.observed.current_stage } else { '' }
                human_label = $human
                decision_hint = if ($obj.decision_hint) { [string]$obj.decision_hint.action } else { '' }
            }
        } catch {}
    }
    return ,$rows
}

function Get-JevLabelGapSummary {
    param([Parameter(Mandatory)]$Rows)
    $labeled = @($Rows | Where-Object { $_.human_label -in @('positive','negative') })
    $unlabeled = @($Rows | Where-Object { $_.human_label -notin @('positive','negative') })
    $statusNeg = @($Rows | Where-Object { $_.observed_status -in @('blocked','failed') })
    $statusPos = @($Rows | Where-Object { $_.observed_status -in @('completed','in_progress') })
    $tnCandidates = @($Rows | Where-Object {
        ($_.human_label -eq 'negative' -or $_.observed_status -in @('blocked','failed')) -and $_.action -eq 'TRUNCATE_HEAD'
    })
    $needNegativeLabels = @($unlabeled | Where-Object {
        $_.decision_hint -eq 'HUMAN_REVIEW' -or $_.action -eq 'KEEP_VERBATIM'
    } | Select-Object -First 20)
    return [pscustomobject]@{
        total = @($Rows).Count
        labeled = @($labeled).Count
        unlabeled = @($unlabeled).Count
        status_positive = @($statusPos).Count
        status_negative = @($statusNeg).Count
        tn_candidates = @($tnCandidates).Count
        need_negative_review = @($needNegativeLabels)
    }
}

function New-JevBalancedLabelQueue {
    param(
        [Parameter(Mandatory)]$Rows,
        [string]$OutputPath,
        [int]$TargetNegative = 10
    )
    $queue = @()
    $negHave = @($Rows | Where-Object { $_.human_label -eq 'negative' -or $_.observed_status -in @('blocked','failed') }).Count
    $need = [Math]::Max(0, $TargetNegative - $negHave)
    $candidates = @($Rows | Where-Object { $_.human_label -notin @('positive','negative') } | Sort-Object {
        if ($_.decision_hint -eq 'HUMAN_REVIEW') { 0 } elseif ($_.action -eq 'KEEP_VERBATIM') { 1 } else { 2 }
    }, probability)
    $i = 0
    foreach ($c in $candidates) {
        if ($i -ge [Math]::Max($need, 5)) { break }
        $queue += [pscustomobject]@{
            file = $c.file
            path = $c.path
            project = $c.project
            probability = $c.probability
            action = $c.action
            observed_status = $c.observed_status
            suggested_focus = if ($c.decision_hint -eq 'HUMAN_REVIEW') { 'uncertainty_band' } else { 'positive_bias_check' }
            human_label = ''
            note = ''
        }
        $i++
    }
    if ($OutputPath) {
        $dir = Split-Path -Parent $OutputPath
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        [System.IO.File]::WriteAllText($OutputPath, ($queue | ConvertTo-Json -Depth 5 -AsArray), [System.Text.UTF8Encoding]::new($false))
    }
    return ,$queue
}

function Apply-JevHumanLabels {
    param(
        [Parameter(Mandatory)][string]$LabelMapPath,
        [Parameter(Mandatory)][string]$OutputDir,
        [switch]$InPlace
    )
    if (-not (Test-Path -LiteralPath $LabelMapPath)) { throw "LabelMapPath not found: $LabelMapPath" }
    $map = Get-Content -LiteralPath $LabelMapPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $InPlace) {
        if (-not (Test-Path -LiteralPath $OutputDir)) { New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null }
    }
    $applied = 0
    foreach ($entry in @($map)) {
        $label = [string]$entry.human_label
        if ($label -notin @('positive','negative')) { continue }
        $src = [string]$entry.path
        if (-not $src -or -not (Test-Path -LiteralPath $src)) { continue }
        $obj = Get-Content -LiteralPath $src -Raw -Encoding UTF8 | ConvertFrom-Json
        if (-not $obj.observed) { $obj | Add-Member -NotePropertyName observed -NotePropertyValue ([pscustomobject]@{}) -Force }
        $obs = $obj.observed
        if ($obs -is [hashtable] -or $obs -is [System.Collections.IDictionary]) {
            $obs['human_label'] = $label
        } else {
            $obs | Add-Member -NotePropertyName human_label -NotePropertyValue $label -Force
            $obj.observed = $obs
        }
        $dest = if ($InPlace) { $src } else { Join-Path $OutputDir ([IO.Path]::GetFileName($src)) }
        [System.IO.File]::WriteAllText($dest, ($obj | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
        $applied++
    }
    return $applied
}

function Format-JevLabelGapMarkdown {
    param(
        [Parameter(Mandatory)]$Summary,
        [Parameter(Mandatory)]$Queue,
        [string]$GeneratedAt = ([DateTimeOffset]::Now.ToString('o'))
    )
    $lines = @()
    $lines += '# Jev balanced labeling gap'
    $lines += ''
    $lines += ('- generated_at: ' + $GeneratedAt)
    $lines += ('- total: ' + $Summary.total + ', labeled: ' + $Summary.labeled + ', unlabeled: ' + $Summary.unlabeled)
    $lines += ('- status_positive: ' + $Summary.status_positive + ', status_negative: ' + $Summary.status_negative)
    $lines += ('- tn_candidates: ' + $Summary.tn_candidates)
    $lines += ''
    $lines += '## review queue'
    if (@($Queue).Count -eq 0) {
        $lines += '- (empty)'
    } else {
        foreach ($q in $Queue) {
            $lines += ('- ' + $q.file + ' | project=' + $q.project + ' | p=' + $q.probability + ' | action=' + $q.action + ' | focus=' + $q.suggested_focus)
        }
    }
    $lines += ''
    $lines += '## next'
    $lines += '- Fill `human_label` = positive|negative in the queue JSON.'
    $lines += '- Apply with Apply-JevHumanLabels (default writes labeled copies, not in-place).'
    $lines += '- Re-run Run-BuildJevShadowEval.ps1; do not flip decision.enabled until TN exists and hit_rate stays credible.'
    return ($lines -join "`n")
}