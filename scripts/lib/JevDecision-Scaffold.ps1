function Get-JevDecisionEnabled {
    param($Config)
    if ($null -eq $Config) { return $false }
    if ($Config.decision -and $Config.decision.enabled -is [bool]) { return [bool]$Config.decision.enabled }
    return $false
}

function Get-JevDecisionThresholds {
    param($Config)
    $t = @{ low = 0.30; high = 0.70 }
    if ($Config -and $Config.decision -and $Config.decision.thresholds) {
        try { $t.low = [double]$Config.decision.thresholds.low } catch {}
        try { $t.high = [double]$Config.decision.thresholds.high } catch {}
    }
    return $t
}

function Resolve-JevItemDecision {
    param(
        [Parameter(Mandatory = $true)]$Item,
        [Parameter(Mandatory = $true)]$ShadowDecision,
        $DecisionConfig
    )
    if (-not (Get-JevDecisionEnabled -Config $DecisionConfig)) {
        return [pscustomobject]@{ action = 'FALLBACK'; reason = 'decision_disabled' }
    }
    if ($null -ne $ShadowDecision.error -and $ShadowDecision.error -ne '') {
        return [pscustomobject]@{ action = 'FALLBACK'; reason = 'jev_error' }
    }
    if ($null -eq $ShadowDecision.probability) {
        return [pscustomobject]@{ action = 'FALLBACK'; reason = 'no_probability' }
    }
    $t = Get-JevDecisionThresholds -Config $DecisionConfig
    if ($ShadowDecision.probability -lt $t.low) {
        return [pscustomobject]@{ action = 'KEEP_VERBATIM'; reason = 'low_band'; probability = $ShadowDecision.probability; threshold_low = $t.low }
    }
    if ($ShadowDecision.probability -gt $t.high) {
        return [pscustomobject]@{ action = 'TRUNCATE_HEAD'; reason = 'high_band'; probability = $ShadowDecision.probability; threshold_high = $t.high }
    }
    return [pscustomobject]@{ action = 'HUMAN_REVIEW'; reason = 'uncertainty_band'; probability = $ShadowDecision.probability; threshold_low = $t.low; threshold_high = $t.high }
}

function Format-JevItemDecisionForPrompt {
    param([Parameter(Mandatory = $true)]$Decision)
    if ($null -eq $Decision) { return '' }
    $parts = @("decision=$($Decision.action)")
    if ($Decision.reason) { $parts += "reason=$($Decision.reason)" }
    if ($null -ne $Decision.probability) { $parts += ('probability=' + [math]::Round($Decision.probability, 4)) }
    return ($parts -join '; ')
}