function Get-JevShadowEnabled {
    param($Config)
    if ($null -eq $Config) { return $false }
    $value = $Config.enabled
    if ($value -is [bool]) { return [bool]$value }
    return ([string]$value).Trim().ToLowerInvariant() -in @('true', '1', 'yes', 'on')
}

function New-JevShadowContext {
    param(
        [Parameter(Mandatory = $true)]$Row,
        $Promotion
    )
    $fields = $Row.Fields
    $project = [string]$fields['项目名']
    $recordId = [string]$Row.RecordId
    $priority = [string]$fields['优先级']
    $stage = [string]$fields['当前阶段']
    $nextStep = [string]$fields['下一步']
    $description = [string]$fields['简要描述']
    $text = @(
        "project=$project"
        "priority=$priority"
        "current_stage=$stage"
        "next_step=$nextStep"
        "description=$description"
    ) -join '; '
    [pscustomobject]@{
        id = "patrol:$recordId"
        project = $project
        record_id = $recordId
        text = $text
        promotion_model = if ($Promotion) { [string]$Promotion.Model } else { '' }
    }
}

function Invoke-JevShadowDecision {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Config
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
        $decision = Invoke-JevDecision -Text $Context.text -Id $Context.id -Source 'feishu-projects-patrol-shadow' -TimeoutMs $timeoutMs
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

function New-JevShadowArtifact {
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
            record_id = $Context.record_id
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

function Complete-JevShadowArtifact {
    param(
        [Parameter(Mandatory = $true)]$Artifact,
        [Parameter(Mandatory = $true)]$Result
    )
    $Artifact.observed = [ordered]@{
        status = [string]$Result.status
        current_stage = [string]$Result.current_stage
        action = [string]$Result.action
        summary = [string]$Result.summary
        risk = [string]$Result.risk
    }
    return $Artifact
}