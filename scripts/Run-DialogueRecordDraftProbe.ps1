param(
    [Parameter(Mandatory = $true)]
    [string]$InputJsonPath
)

$ErrorActionPreference = "Stop"

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

Assert-True -Condition (Test-Path -LiteralPath $InputJsonPath) -Message "starter json not found: $InputJsonPath"

$payload = Get-Content -LiteralPath $InputJsonPath -Raw | ConvertFrom-Json
$manual = $payload.manual_acceptance
Assert-True -Condition ($null -ne $manual) -Message "manual_acceptance missing from starter json"
$outputPath = if (($manual.PSObject.Properties.Name -contains 'record_draft_output_path') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.record_draft_output_path))) {
    [string]$manual.record_draft_output_path
} else {
    [System.IO.Path]::GetFullPath((Join-Path (Get-Location) (".tmp\dialogue-record-draft-{0}.md" -f (($payload.base_url -replace '^https?://localhost:', '')))))
}
if (Test-Path -LiteralPath $outputPath) {
    Remove-Item -LiteralPath $outputPath -Force
}

$rendererPath = Join-Path $PSScriptRoot "Render-DialogueAcceptanceRecordDraft.ps1"
& powershell -ExecutionPolicy Bypass -File $rendererPath -InputJsonPath $InputJsonPath |
    Out-File -FilePath $outputPath -Encoding utf8

Assert-True -Condition (Test-Path -LiteralPath $outputPath) -Message "record draft output file was not created: $outputPath"
$content = Get-Content -LiteralPath $outputPath -Raw
$contentLines = @($content -split "`r?`n")
Assert-True -Condition ($content.Contains("# Dialogue Chat Facade Acceptance Record")) -Message "record draft missing title"
Assert-True -Condition ($content.Contains("## 2. Automation Evidence")) -Message "record draft missing automation section"
Assert-True -Condition ($content.Contains("### 2.1 Local Harness Runner")) -Message "record draft missing local harness section"
Assert-True -Condition ($content.Contains("## 3. Manual Browser Acceptance")) -Message "record draft missing manual acceptance section"
Assert-True -Condition ($content.Contains("strict manual gates stay unchecked until a human review records the result")) -Message "record draft missing top-level manual gate boundary"
Assert-True -Condition ($content.Contains("strict manual Passed checkboxes must remain a human review decision")) -Message "record draft missing strict manual review boundary"
Assert-True -Condition (-not $content.Contains("Only leave Passed unchecked when the underlying bundle is stale")) -Message "record draft still suggests scripted seam evidence can close manual Passed"
Assert-True -Condition (-not $content.Contains("only leave a gate unchecked when the underlying bundle")) -Message "record draft top note still suggests scripted seam evidence can close manual gates"
Assert-True -Condition ($content.Contains("### A. default task_auto")) -Message "record draft missing section A"
Assert-True -Condition ($content.Contains("### H. #facade=responses + task_required")) -Message "record draft missing section H"
Assert-True -Condition ($content.Contains("## 4. Gaps And Conclusion")) -Message "record draft missing conclusion section"
Assert-True -Condition ($content.Contains("manual browser acceptance is complete")) -Message "record draft missing final gate line"
Assert-True -Condition ($content.Contains("- [ ] token-level streaming is still not accepted")) -Message "record draft should keep token-level streaming gap unchecked"
Assert-True -Condition (($contentLines | Where-Object { $_ -match '^- \[ \] full .*responses.* tool-call surface is still not accepted; minimal message item lifecycle has regression coverage$' }).Count -eq 1) -Message "record draft should keep responses tool-call gap unchecked while noting item lifecycle coverage"
Assert-True -Condition (-not $content.Contains("- [x] token-level streaming not yet accepted")) -Message "record draft must not mark token-level streaming gap as checked"
if (($manual.PSObject.Properties.Name -contains 'record_seed_probe') -and ($null -ne $manual.record_seed_probe)) {
    Assert-True -Condition ($content.Contains("record_seed_probe:")) -Message "record draft missing embedded record seed probe summary"
}
if (($manual.PSObject.Properties.Name -contains 'record_draft_probe') -and ($null -ne $manual.record_draft_probe)) {
    Assert-True -Condition ($content.Contains("record_draft_probe:")) -Message "record draft missing embedded record draft probe summary"
}
Assert-True -Condition ($content.Contains("- Path Note:")) -Message "record draft missing path note lines"
Assert-True -Condition ($content.Contains("current task_note_attach seam")) -Message "record draft missing updated task_note_attach path note"
Assert-True -Condition ($contentLines -contains "## Run Metadata") -Message "record draft collapsed run metadata line structure"
Assert-True -Condition ($contentLines -contains "### A. default task_auto") -Message "record draft collapsed section A line structure"
Assert-True -Condition (($contentLines | Where-Object { $_ -like "- Path Note:*" }).Count -ge 1) -Message "record draft missing standalone path note lines"
Assert-True -Condition (($contentLines | Where-Object { $_ -like "- Path Note: *current task_note_attach seam*" }).Count -ge 2) -Message "record draft missing task_note_attach path notes"
if (($manual.PSObject.Properties.Name -contains 'starter_probe') -and ($null -ne $manual.starter_probe) -and ($null -eq $manual.starter_probe.error)) {
    Assert-True -Condition ($content.Contains("starter_probe:")) -Message "record draft missing embedded starter probe summary"
    Assert-True -Condition (($content.Contains("manual_acceptance.starter_probe.allow_both_in_one_run = true")) -or ($content.Contains("manual_acceptance.starter_probe.allow_both_in_one_run = True"))) -Message "record draft missing starter probe allow_both_in_one_run summary"
    Assert-True -Condition ($content.Contains("manual_acceptance.starter_probe.browser_probe_surface = both")) -Message "record draft missing starter probe surface summary"
}
if (($manual.command_examples.PSObject.Properties.Name -contains 'render_manual_backfill_template_to_file') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.render_manual_backfill_template_to_file))) {
    Assert-True -Condition ($content.Contains("manual_backfill_helpers:")) -Message "record draft missing manual backfill helper summary header"
    Assert-True -Condition ($content.Contains("- render_manual_backfill_template_to_file = present")) -Message "record draft missing render manual backfill helper summary"
}
if (($manual.command_examples.PSObject.Properties.Name -contains 'apply_manual_backfill_to_record') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.apply_manual_backfill_to_record))) {
    Assert-True -Condition ($content.Contains("- apply_manual_backfill_to_record = present")) -Message "record draft missing apply manual backfill helper summary"
}
if (($manual.command_examples.PSObject.Properties.Name -contains 'probe_manual_backfill_output') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.probe_manual_backfill_output))) {
    Assert-True -Condition ($content.Contains("- probe_manual_backfill_output = present")) -Message "record draft missing manual backfill probe helper summary"
}

$previewLines = @($content -split "`r?`n" | Select-Object -First 10)

[pscustomobject]@{
    input_json_path = [System.IO.Path]::GetFullPath($InputJsonPath)
    output_path = [System.IO.Path]::GetFullPath($outputPath)
    bytes = (Get-Item -LiteralPath $outputPath).Length
    has_title = $true
    has_automation_section = $true
    has_local_harness_section = $true
    has_manual_acceptance_section = $true
    has_section_a = $true
    has_section_h = $true
    has_conclusion_section = $true
    has_final_gate = $true
    preview = $previewLines
} | ConvertTo-Json -Depth 4
