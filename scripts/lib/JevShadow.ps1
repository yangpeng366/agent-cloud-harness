function Get-JevShadowEnabled {
    param($Config)
    if ($null -eq $Config) { return $false }
    $value = $Config.enabled
    if ($value -is [bool]) { return [bool]$value }
    return ([string]$value).Trim().ToLowerInvariant() -in @('true', '1', 'yes', 'on')
}

function New-PatrolItemJevShadowContext {
    param(
        [Parameter(Mandatory = $true)]$Item,
        [string]$PromotionModel = ''
    )
    [pscustomobject]@{
        id = "patrol:$($Item.id)"
        project = [string]$Item.title
        item_id = [string]$Item.id
        priority = [string]$Item.priority
        repo_path = [string]$Item.repoPath
        description = [string]$Item.description
        next_step = [string]$Item.nextStep
        text = @(
            "project=$($Item.title)"
            "priority=$($Item.priority)"
            "repo=$($Item.repoPath)"
            "description=$($Item.description)"
            "next_step=$($Item.nextStep)"
        ) -join '; '
        promotion_model = $PromotionModel
    }
}

function Invoke-PatrolItemJevShadow {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Config,
        [pscustomobject]$Promotion = $null
    )
    $startedAt = [DateTimeOffset]::UtcNow
    if ([string]::IsNullOrWhiteSpace($env:TYPESAFE_API_KEY)) {
        return [pscustomobject]@{
            error = 'api_key_missing'
            probability = $null
            action = $null
            duration_ms = $null
        }
    }
    $apiScriptPath = [string]$Config.api_script_path
    if ([string]::IsNullOrWhiteSpace($apiScriptPath) -or -not (Test-Path -LiteralPath $apiScriptPath)) {
        return [pscustomobject]@{
            error = 'api_script_missing'
            probability = $null
            action = $null
            duration_ms = $null
        }
    }
    try {
        . $apiScriptPath
        if (-not (Get-Command Invoke-JevDecision -ErrorAction SilentlyContinue)) {
            throw 'Invoke-JevDecision not found after loading api_script_path'
        }
        $timeoutMs = 5000
        try { $timeoutMs = [int]$Config.timeout_ms } catch {}
        $decision = Invoke-JevDecision -Text $Context.text -Id $Context.id -Source 'patrol-scaffold-shadow' -TimeoutMs $timeoutMs
        return [pscustomobject]@{
            error = $decision.error
            probability = $decision.probability
            action = $decision.action
            duration_ms = $decision.duration_ms
        }
    } catch {
        $durationMs = [int](([DateTimeOffset]::UtcNow - $startedAt).TotalMilliseconds)
        return [pscustomobject]@{
            error = $_.Exception.Message
            probability = $null
            action = $null
            duration_ms = $durationMs
        }
    }
}

function New-PatrolJevShadowArtifact {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Decision,
        [Parameter(Mandatory = $true)][bool]$Enabled
    )
    [ordered]@{
        schema_version = 1
        mode = 'shadow'
        enabled = $Enabled
        generated_at = [DateTimeOffset]::UtcNow.ToString('o')
        context = [ordered]@{
            id = $Context.id
            project = $Context.project
            item_id = $Context.item_id
            priority = $Context.priority
            repo_path = $Context.repo_path
            promotion_model = $Context.promotion_model
        }
        jev = [ordered]@{
            error = $Decision.error
            probability = $Decision.probability
            action = $Decision.action
            duration_ms = $Decision.duration_ms
        }
        observed = $null
    }
}

function Get-PatrolJevShadowDecisionHint {
    param(
        [double]$Probability,
        [string]$Error,
        [double]$ThresholdLow = 0.30,
        [double]$ThresholdHigh = 0.70
    )
    if ($Error -and $Error -ne '') { return [pscustomobject]@{ action = 'FALLBACK'; reason = 'jev_error' } }
    if ($null -eq $Probability -or $Probability -le 0) { return [pscustomobject]@{ action = 'FALLBACK'; reason = 'no_probability' } }
    if ($Probability -lt $ThresholdLow) { return [pscustomobject]@{ action = 'KEEP_VERBATIM'; reason = 'low_band'; probability = $Probability; threshold_low = $ThresholdLow } }
    if ($Probability -gt $ThresholdHigh) { return [pscustomobject]@{ action = 'TRUNCATE_HEAD'; reason = 'high_band'; probability = $Probability; threshold_high = $ThresholdHigh } }
    return [pscustomobject]@{ action = 'HUMAN_REVIEW'; reason = 'uncertainty_band'; probability = $Probability; threshold_low = $ThresholdLow; threshold_high = $ThresholdHigh }
}

function Complete-PatrolJevShadowArtifact {
    param(
        [Parameter(Mandatory = $true)]$Artifact,
        [Parameter(Mandatory = $true)]$WorkerResult,
        [string]$WorkerMode = 'real'
    )
    $hint = Get-PatrolJevShadowDecisionHint -Probability $Artifact.jev.probability -Error $Artifact.jev.error
    $Artifact | Add-Member -NotePropertyName decision_hint -NotePropertyValue $hint -Force
    $Artifact.observed = [ordered]@{
        worker_mode = $WorkerMode
        current_stage = if ($WorkerResult) { [string]$WorkerResult.currentStage } else { '' }
        achievement = if ($WorkerResult) { [string]$WorkerResult.achievement } else { '' }
        duration_sec = if ($WorkerResult) { [double]$WorkerResult.durationSec } else { 0 }
        status = if ($WorkerResult) { [string]$WorkerResult.status } else { '' }
        action = if ($WorkerResult) { [string]$WorkerResult.action } else { '' }
    }
    return $Artifact
}

function Write-PatrolJevShadow {
    param(
        [Parameter(Mandatory = $true)]$Artifact,
        [Parameter(Mandatory = $true)][string]$OutputDir
    )
    if (-not (Test-Path -LiteralPath $OutputDir)) {
        New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    }
    $safeName = ([string]$Artifact.context.project -replace '[^A-Za-z0-9_.-]+', '_')
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $path = Join-Path $OutputDir ("{0}-{1}.json" -f $safeName, $stamp)
    [System.IO.File]::WriteAllText($path, ($Artifact | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    return $path
}
