param(
    [string]$PropertiesPath = ".tmp\java-agent\out\openai-env.properties",
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $PropertiesPath)) {
    throw "OpenAI properties file not found: $PropertiesPath"
}

$loaded = New-Object System.Collections.Generic.List[string]

Get-Content -LiteralPath $PropertiesPath | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#") -or $line.StartsWith("!")) {
        return
    }

    $separatorIndex = $line.IndexOf("=")
    if ($separatorIndex -lt 1) {
        return
    }

    $name = $line.Substring(0, $separatorIndex).Trim()
    if (-not $name.StartsWith("OPENAI_")) {
        return
    }

    $rawValue = $line.Substring($separatorIndex + 1)
    $value = $rawValue `
        -replace "\\:", ":" `
        -replace "\\=", "=" `
        -replace "\\\\", "\"

    Set-Item -Path ("Env:" + $name) -Value $value
    $loaded.Add($name) | Out-Null
}

if ($loaded.Count -eq 0) {
    throw "No OPENAI_* entries were loaded from: $PropertiesPath"
}

if (-not $Quiet) {
    Write-Host "Loaded OpenAI environment variables from: $PropertiesPath"
    foreach ($name in $loaded | Sort-Object) {
        if ($name -eq "OPENAI_API_KEY") {
            Write-Host "  OPENAI_API_KEY=[set]"
        }
        else {
            Write-Host ("  {0}={1}" -f $name, (Get-Item -Path ("Env:" + $name)).Value)
        }
    }
}
