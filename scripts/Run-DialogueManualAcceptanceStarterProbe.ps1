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
Assert-True -Condition (-not [string]::IsNullOrWhiteSpace([string]$manual.result_json_path)) -Message "manual_acceptance.result_json_path missing"
Assert-True -Condition (-not [string]::IsNullOrWhiteSpace([string]$manual.browser_probe_screenshot_dir)) -Message "manual_acceptance.browser_probe_screenshot_dir missing"
Assert-True -Condition ($manual.scripted_probe_guidance.allow_both_in_one_run -eq $true) -Message "starter guidance still reports allow_both_in_one_run=false"

$browserProbe = $payload.browser_probe
Assert-True -Condition ($null -ne $browserProbe) -Message "browser_probe missing from starter json"
Assert-True -Condition ($browserProbe.surface -eq 'both') -Message "starter browser_probe.surface is not both"
Assert-True -Condition ($null -ne $browserProbe.chat_surface) -Message "starter browser_probe.chat_surface missing"
Assert-True -Condition ($null -ne $browserProbe.responses_surface) -Message "starter browser_probe.responses_surface missing"

$chatPropCount = @($browserProbe.chat_surface.PSObject.Properties).Count
$responsesPropCount = @($browserProbe.responses_surface.PSObject.Properties).Count
Assert-True -Condition ($chatPropCount -gt 0) -Message "starter browser_probe.chat_surface is empty"
Assert-True -Condition ($responsesPropCount -gt 0) -Message "starter browser_probe.responses_surface is empty"

$screenshotDir = [string]$manual.browser_probe_screenshot_dir
Assert-True -Condition (Test-Path -LiteralPath $screenshotDir) -Message "starter screenshot dir not found: $screenshotDir"

$chatScreens = @(Get-ChildItem -LiteralPath $screenshotDir -Filter 'chat-*.png' -File -ErrorAction SilentlyContinue)
$responsesScreens = @(Get-ChildItem -LiteralPath $screenshotDir -Filter 'responses-*.png' -File -ErrorAction SilentlyContinue)
Assert-True -Condition ($chatScreens.Count -gt 0) -Message "starter screenshot dir missing chat-*.png"
Assert-True -Condition ($responsesScreens.Count -gt 0) -Message "starter screenshot dir missing responses-*.png"

[pscustomobject]@{
    input_json_path = [System.IO.Path]::GetFullPath($InputJsonPath)
    screenshot_dir = [System.IO.Path]::GetFullPath($screenshotDir)
    allow_both_in_one_run = $true
    browser_probe_surface = [string]$browserProbe.surface
    chat_surface_property_count = $chatPropCount
    responses_surface_property_count = $responsesPropCount
    chat_png_count = $chatScreens.Count
    responses_png_count = $responsesScreens.Count
} | ConvertTo-Json -Depth 4
