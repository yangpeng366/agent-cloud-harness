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
Assert-True -Condition (-not [string]::IsNullOrWhiteSpace($manual.record_seed_output_path)) -Message "record_seed_output_path missing from starter json"
Assert-True -Condition ($null -ne $manual.command_examples) -Message "manual_acceptance.command_examples missing"
Assert-True -Condition (-not [string]::IsNullOrWhiteSpace($manual.command_examples.render_record_seed_to_file)) -Message "render_record_seed_to_file missing from command_examples"

$outputPath = [string]$manual.record_seed_output_path
if (Test-Path -LiteralPath $outputPath) {
    Remove-Item -LiteralPath $outputPath -Force
}

Invoke-Expression ([string]$manual.command_examples.render_record_seed_to_file)

Assert-True -Condition (Test-Path -LiteralPath $outputPath) -Message "record seed output file was not created: $outputPath"
$content = Get-Content -LiteralPath $outputPath -Raw
$contentLines = @($content -split "`r?`n")
Assert-True -Condition ($content.Contains("## Run Metadata")) -Message "record seed output missing run metadata header"
Assert-True -Condition ($content.Contains("## Useful Commands")) -Message "record seed output missing useful commands header"
Assert-True -Condition ($content.Contains("- Base URL:")) -Message "record seed output missing base URL line"
Assert-True -Condition ($content.Contains("- Result JSON:")) -Message "record seed output missing result json line"
Assert-True -Condition ($content.Contains("- Completion Gate:")) -Message "record seed output missing completion gate line"
Assert-True -Condition ($content.Contains("### A. default task_auto")) -Message "record seed output missing section A"
Assert-True -Condition ($content.Contains("### H. #facade=responses + task_required")) -Message "record seed output missing section H"
Assert-True -Condition ($content.Contains("- Entry URL:")) -Message "record seed output missing entry URL lines"
Assert-True -Condition ($content.Contains("- Path Note:")) -Message "record seed output missing path note lines"
Assert-True -Condition ($content.Contains("current task_note_attach seam")) -Message "record seed output missing updated task_note_attach path note"
if (($manual.command_examples.PSObject.Properties.Name -contains 'probe_starter_output') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.probe_starter_output))) {
    Assert-True -Condition ($content.Contains("- Starter aggregate probe:")) -Message "record seed output missing starter aggregate probe header"
    Assert-True -Condition ($content.Contains("Run-DialogueManualAcceptanceStarterProbe.ps1")) -Message "record seed output missing starter aggregate probe command"
}
if (($manual.command_examples.PSObject.Properties.Name -contains 'render_manual_backfill_template_to_file') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.render_manual_backfill_template_to_file))) {
    Assert-True -Condition ($content.Contains("- Manual backfill template:")) -Message "record seed output missing manual backfill template header"
    Assert-True -Condition ($content.Contains("Render-DialogueAcceptanceManualBackfillTemplate.ps1")) -Message "record seed output missing manual backfill template command"
}
if (($manual.command_examples.PSObject.Properties.Name -contains 'apply_manual_backfill_to_record') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.apply_manual_backfill_to_record))) {
    Assert-True -Condition ($content.Contains("- Apply manual backfill:")) -Message "record seed output missing apply manual backfill header"
    Assert-True -Condition ($content.Contains("Apply-DialogueAcceptanceManualBackfill.ps1")) -Message "record seed output missing apply manual backfill command"
}
if (($manual.command_examples.PSObject.Properties.Name -contains 'probe_manual_backfill_output') -and (-not [string]::IsNullOrWhiteSpace([string]$manual.command_examples.probe_manual_backfill_output))) {
    Assert-True -Condition ($content.Contains("- Manual backfill probe:")) -Message "record seed output missing manual backfill probe header"
    Assert-True -Condition ($content.Contains("Run-DialogueAcceptanceManualBackfillProbe.ps1")) -Message "record seed output missing manual backfill probe command"
}
Assert-True -Condition ($contentLines -contains "## Run Metadata") -Message "record seed output collapsed run metadata line structure"
Assert-True -Condition ($contentLines -contains "### A. default task_auto") -Message "record seed output collapsed section A line structure"
Assert-True -Condition (($contentLines | Where-Object { $_ -like "- Path Note:*" }).Count -ge 1) -Message "record seed output missing standalone path note lines"
Assert-True -Condition (($contentLines | Where-Object { $_ -like "- Path Note: *current task_note_attach seam*" }).Count -ge 2) -Message "record seed output missing task_note_attach path notes"

$previewLines = @($content -split "`r?`n" | Select-Object -First 8)

[pscustomobject]@{
    input_json_path = [System.IO.Path]::GetFullPath($InputJsonPath)
    output_path = [System.IO.Path]::GetFullPath($outputPath)
    bytes = (Get-Item -LiteralPath $outputPath).Length
    has_run_metadata = $true
    has_useful_commands = $true
    has_base_url = $true
    has_result_json = $true
    has_completion_gate = $true
    has_section_a = $true
    has_section_h = $true
    has_entry_urls = $true
    preview = $previewLines
} | ConvertTo-Json -Depth 4
