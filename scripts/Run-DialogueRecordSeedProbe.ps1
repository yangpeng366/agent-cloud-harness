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
Assert-True -Condition ($content.Contains("## Run Metadata")) -Message "record seed output missing run metadata header"
Assert-True -Condition ($content.Contains("## Useful Commands")) -Message "record seed output missing useful commands header"
Assert-True -Condition ($content.Contains("- Base URL:")) -Message "record seed output missing base URL line"
Assert-True -Condition ($content.Contains("- Result JSON:")) -Message "record seed output missing result json line"
Assert-True -Condition ($content.Contains("- Completion Gate:")) -Message "record seed output missing completion gate line"
Assert-True -Condition ($content.Contains("### A. message_only")) -Message "record seed output missing section A"
Assert-True -Condition ($content.Contains("### H. #facade=responses + task_required")) -Message "record seed output missing section H"
Assert-True -Condition ($content.Contains("- Entry URL:")) -Message "record seed output missing entry URL lines"

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
