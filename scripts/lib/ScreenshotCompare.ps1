function Compare-ScreenshotSimilarity {
    param(
        [Parameter(Mandatory = $true)][string]$BaselinePath,
        [Parameter(Mandatory = $true)][string]$CurrentPath,
        [int]$ToleranceBytesPerPixel = 32
    )

    if (-not (Test-Path -LiteralPath $BaselinePath)) {
        return [pscustomobject]@{ error = 'baseline_not_found'; similarity = 0.0; matched = $false }
    }
    if (-not (Test-Path -LiteralPath $CurrentPath)) {
        return [pscustomobject]@{ error = 'current_not_found'; similarity = 0.0; matched = $false }
    }

    Add-Type -AssemblyName System.Drawing

    $baselineBytes = [System.IO.File]::ReadAllBytes($BaselinePath)
    $currentBytes = [System.IO.File]::ReadAllBytes($CurrentPath)

    if ($baselineBytes.Length -ne $currentBytes.Length) {
        return [pscustomobject]@{
            error = 'size_mismatch'
            similarity = 0.0
            matched = $false
            baseline_size = $baselineBytes.Length
            current_size = $currentBytes.Length
        }
    }

    $maxDiff = 0
    $diffCount = 0
    $sampled = 0
    for ($i = 0; $i -lt $baselineBytes.Length; $i += 100) {
        $sampled++
        $b = $baselineBytes[$i]
        $c = $currentBytes[$i]
        $abs = [Math]::Abs($b - $c)
        if ($abs -gt $maxDiff) { $maxDiff = $abs }
        if ($abs -gt $ToleranceBytesPerPixel) { $diffCount++ }
    }

    $similarity = if ($sampled -eq 0) { 1.0 } else { [math]::Round(1.0 - ($diffCount / $sampled), 4) }

    return [pscustomobject]@{
        error = $null
        similarity = $similarity
        max_byte_diff = $maxDiff
        bytes_sampled = $sampled
        bytes_over_tolerance = $diffCount
        tolerance_bytes_per_pixel = $ToleranceBytesPerPixel
        matched = $similarity -ge 0.95
    }
}