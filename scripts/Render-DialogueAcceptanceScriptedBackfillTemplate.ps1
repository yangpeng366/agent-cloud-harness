param(
    [Parameter(Mandatory = $true)]
    [string]$InputJsonPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $InputJsonPath)) {
    throw "input json not found: $InputJsonPath"
}

$payload = Get-Content -LiteralPath $InputJsonPath -Raw | ConvertFrom-Json
$manual = $payload.manual_acceptance
if ($null -eq $manual) {
    throw "manual_acceptance missing from input json"
}
$browserProbe = $payload.browser_probe
if ($null -eq $browserProbe) {
    throw "browser_probe missing from input json"
}

function Join-ObservedParts {
    param([string[]]$Parts)

    $filtered = @($Parts | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    return ($filtered -join '; ')
}

function New-ProbeBackfillPath {
    param(
        [string]$Id,
        [string]$Label,
        [string]$Surface,
        [string]$EntryUrl,
        [string]$EvidenceMode,
        [bool]$Passed,
        [string]$Input,
        [string]$ObservedResult,
        [string[]]$Notes
    )

    [pscustomobject]@{
        id = $Id
        label = $Label
        surface = $Surface
        entry_url = $EntryUrl
        passed = $Passed
        evidence_mode = $EvidenceMode
        input = $Input
        observed_result = $ObservedResult
        notes = @($Notes)
    }
}

function New-ScriptedProbePath {
    param(
        [object]$Entry,
        [string]$SourceKey,
        [object]$ProbeData,
        [string]$ObservedResult,
        [string[]]$ExtraNotes
    )

    if ($null -eq $ProbeData) {
        return New-ProbeBackfillPath `
            -Id ([string]$Entry.id) `
            -Label ([string]$Entry.path) `
            -Surface ([string]$Entry.surface) `
            -EntryUrl ([string]$Entry.entry_url) `
            -EvidenceMode 'scripted_browser_evidence_missing' `
            -Passed $false `
            -Input '' `
            -ObservedResult '' `
            -Notes @("Expected scripted browser evidence source missing: $SourceKey")
    }

    $notes = New-Object System.Collections.Generic.List[string]
    $notes.Add("Evidence source: $SourceKey")
    if (-not [string]::IsNullOrWhiteSpace([string]$ProbeData.screenshot_path)) {
        $notes.Add(("Screenshot: {0}" -f [string]$ProbeData.screenshot_path))
    }
    foreach ($extra in @($ExtraNotes)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$extra)) {
            $notes.Add([string]$extra)
        }
    }

    return New-ProbeBackfillPath `
        -Id ([string]$Entry.id) `
        -Label ([string]$Entry.path) `
        -Surface ([string]$Entry.surface) `
        -EntryUrl ([string]$Entry.entry_url) `
        -EvidenceMode 'scripted_browser_evidence_available' `
        -Passed $true `
        -Input 'scripted browser probe bundle' `
        -ObservedResult $ObservedResult `
        -Notes $notes
}

$paths = @()
foreach ($entry in @($manual.recommended_order)) {
    if ($null -eq $entry) {
        continue
    }

    $entryId = [string]$entry.id
    switch ($entryId) {
        'A' {
            $probeData = $browserProbe.chat_surface.default_task_auto
            $observed = Join-ObservedParts @(
                ("inline_ack={0}" -f [string]$probeData.inline_ack),
                ("task_cards={0}" -f [string]$probeData.task_cards)
            )
            $paths += New-ScriptedProbePath -Entry $entry -SourceKey 'browser_probe.chat_surface.default_task_auto' -ProbeData $probeData -ObservedResult $observed -ExtraNotes @()
        }
        'B' {
            $probeData = $browserProbe.chat_surface.task_note_attach
            $observed = Join-ObservedParts @(
                ("inline_ack={0}" -f [string]$probeData.inline_ack),
                ("selected_task_id={0}" -f [string]$probeData.selected_task_id),
                ("task_cards={0}" -f [string]$probeData.task_cards),
                ("task_note_message_type={0}" -f [string]$probeData.task_note_message_type)
            )
            $paths += New-ScriptedProbePath -Entry $entry -SourceKey 'browser_probe.chat_surface.task_note_attach' -ProbeData $probeData -ObservedResult $observed -ExtraNotes @(
                'This path now maps to the current task_note_attach seam instead of the older always-visible attach checkbox flow.'
            )
        }
        'C' {
            $probeData = $browserProbe.chat_surface.auto_start_task
            $observed = Join-ObservedParts @(
                ("inline_ack={0}" -f [string]$probeData.inline_ack),
                ("selected_task_id={0}" -f [string]$probeData.selected_task_id),
                ("selected_status={0}" -f [string]$probeData.selected_status)
            )
            $paths += New-ScriptedProbePath -Entry $entry -SourceKey 'browser_probe.chat_surface.auto_start_task' -ProbeData $probeData -ObservedResult $observed -ExtraNotes @()
        }
        'D' {
            $probeData = $browserProbe.chat_surface.followup_manual_start
            $observed = Join-ObservedParts @(
                ("inline_ack={0}" -f [string]$probeData.inline_ack),
                ("child_parent_task_id={0}" -f [string]$probeData.child_parent_task_id),
                ("followup_message_type={0}" -f [string]$probeData.followup_message_type)
            )
            $paths += New-ScriptedProbePath -Entry $entry -SourceKey 'browser_probe.chat_surface.followup_manual_start' -ProbeData $probeData -ObservedResult $observed -ExtraNotes @()
        }
        'E' {
            $probeData = $browserProbe.chat_surface.manual_start_continuity
            $observed = Join-ObservedParts @(
                ("inline_ack={0}" -f [string]$probeData.inline_ack),
                ("continuity_message_type={0}" -f [string]$probeData.continuity_message_type),
                ("continuity_task_mode={0}" -f [string]$probeData.continuity_task_mode),
                ("continuity_auto_start={0}" -f [string]$probeData.continuity_auto_start)
            )
            $paths += New-ScriptedProbePath -Entry $entry -SourceKey 'browser_probe.chat_surface.manual_start_continuity' -ProbeData $probeData -ObservedResult $observed -ExtraNotes @()
        }
        'F' {
            $probeData = $browserProbe.chat_surface.stream_fallback
            $observed = Join-ObservedParts @(
                ("inline_ack={0}" -f [string]$probeData.inline_ack),
                ("request_count_delta={0}" -f [string]$probeData.request_count_delta),
                ("response_content_type={0}" -f [string]$probeData.response_content_type),
                ("override_mode={0}" -f [string]$probeData.override_mode)
            )
            $paths += New-ScriptedProbePath -Entry $entry -SourceKey 'browser_probe.chat_surface.stream_fallback' -ProbeData $probeData -ObservedResult $observed -ExtraNotes @()
        }
        'G' {
            $probeData = $browserProbe.responses_surface.task_note_attach
            $observed = Join-ObservedParts @(
                ("inline_ack={0}" -f [string]$probeData.inline_ack),
                ("selected_task_id={0}" -f [string]$probeData.selected_task_id),
                ("task_cards={0}" -f [string]$probeData.task_cards),
                ("task_note_message_type={0}" -f [string]$probeData.task_note_message_type)
            )
            $paths += New-ScriptedProbePath -Entry $entry -SourceKey 'browser_probe.responses_surface.task_note_attach' -ProbeData $probeData -ObservedResult $observed -ExtraNotes @(
                'This path now maps to the current responses-surface task_note_attach seam instead of a separate responses-message-only screenshot.'
            )
        }
        'H' {
            $probeData = $browserProbe.responses_surface.auto_start_task
            $observed = Join-ObservedParts @(
                ("inline_ack={0}" -f [string]$probeData.inline_ack),
                ("selected_task_id={0}" -f [string]$probeData.selected_task_id),
                ("selected_status={0}" -f [string]$probeData.selected_status)
            )
            $paths += New-ScriptedProbePath -Entry $entry -SourceKey 'browser_probe.responses_surface.auto_start_task' -ProbeData $probeData -ObservedResult $observed -ExtraNotes @()
        }
        default {
            $paths += New-ProbeBackfillPath `
                -Id $entryId `
                -Label ([string]$entry.path) `
                -Surface ([string]$entry.surface) `
                -EntryUrl ([string]$entry.entry_url) `
                -EvidenceMode 'unclassified' `
                -Passed $false `
                -Input '' `
                -ObservedResult '' `
                -Notes @('No scripted or human gate classification was assigned.')
        }
    }
}

[pscustomobject]@{
    base_url = [string]$payload.base_url
    result_json_path = [string]$manual.result_json_path
    record_path = [string]$payload.record_suggestion
    recommended_screenshot_dir = [string]$manual.recommended_screenshot_dir
    note = "A-H are prefilled from the starter browser-probe bundle against the current reachable dialogue seam. Applying this template does not close the final release gate by itself."
    paths = $paths
} | ConvertTo-Json -Depth 6
