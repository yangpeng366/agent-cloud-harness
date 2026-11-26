function Compare-ScreenshotSimilarity {
    param(
        [Parameter(Mandatory = $true)][string]$BaselinePath,
        [Parameter(Mandatory = $true)][string]$CurrentPath,
        # Per-channel tolerance, keep the historical name/meaning: a pixel counts as
        # differing when any channel moves by more than this many levels (0-255).
        [int]$ToleranceBytesPerPixel = 32,
        # Pixel grid stride for the similarity sample. 1 compares every pixel;
        # 2 keeps the cost low on retina-sized captures without changing the verdict.
        [int]$SampleStride = 1
    )

    if (-not (Test-Path -LiteralPath $BaselinePath)) {
        return [pscustomobject]@{ error = 'baseline_not_found'; similarity = 0.0; matched = $false }
    }
    if (-not (Test-Path -LiteralPath $CurrentPath)) {
        return [pscustomobject]@{ error = 'current_not_found'; similarity = 0.0; matched = $false }
    }

    Add-Type -AssemblyName System.Drawing

    $baselineSize = (Get-Item -LiteralPath $BaselinePath).Length
    $currentSize = (Get-Item -LiteralPath $CurrentPath).Length

    $baseline = $null
    $current = $null
    try {
        $baseline = [System.Drawing.Bitmap]::FromFile((Resolve-Path -LiteralPath $BaselinePath).Path)
        $current = [System.Drawing.Bitmap]::FromFile((Resolve-Path -LiteralPath $CurrentPath).Path)

        # Compare geometry, not encoded file length: two PNGs of the same window with
        # different content compress to different byte counts, and gating on that made
        # every content change a false `size_mismatch` failure.
        if ($baseline.Width -ne $current.Width -or $baseline.Height -ne $current.Height) {
            return [pscustomobject]@{
                error = 'size_mismatch'
                similarity = 0.0
                matched = $false
                baseline_size = $baselineSize
                current_size = $currentSize
                baseline_width = $baseline.Width
                baseline_height = $baseline.Height
                current_width = $current.Width
                current_height = $current.Height
            }
        }

        $width = $baseline.Width
        $height = $baseline.Height
        $stride = [Math]::Max(1, $SampleStride)

        $rect = [System.Drawing.Rectangle]::new(0, 0, $width, $height)
        $format = [System.Drawing.Imaging.PixelFormat]::Format24bppRgb

        $baselineData = $baseline.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, $format)
        $currentData = $current.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, $format)
        try {
            $baselineStride = $baselineData.Stride
            $currentStride = $currentData.Stride
            $baselineBytes = New-Object byte[] ($baselineStride * $height)
            $currentBytes = New-Object byte[] ($currentStride * $height)
            [System.Runtime.InteropServices.Marshal]::Copy($baselineData.Scan0, $baselineBytes, 0, $baselineBytes.Length)
            [System.Runtime.InteropServices.Marshal]::Copy($currentData.Scan0, $currentBytes, 0, $currentBytes.Length)
        } finally {
            $baseline.UnlockBits($baselineData)
            $current.UnlockBits($currentData)
        }

        $sampled = 0
        $diffCount = 0
        $maxChannelDiff = 0
        for ($y = 0; $y -lt $height; $y += $stride) {
            $baselineRow = $y * $baselineStride
            $currentRow = $y * $currentStride
            for ($x = 0; $x -lt $width; $x += $stride) {
                $offsetB = $baselineRow + ($x * 3)
                $offsetC = $currentRow + ($x * 3)
                $db = [Math]::Abs([int]$baselineBytes[$offsetB] - [int]$currentBytes[$offsetC])
                $dg = [Math]::Abs([int]$baselineBytes[$offsetB + 1] - [int]$currentBytes[$offsetC + 1])
                $dr = [Math]::Abs([int]$baselineBytes[$offsetB + 2] - [int]$currentBytes[$offsetC + 2])
                $pixelDiff = [Math]::Max($db, [Math]::Max($dg, $dr))
                if ($pixelDiff -gt $maxChannelDiff) { $maxChannelDiff = $pixelDiff }
                $sampled++
                if ($pixelDiff -gt $ToleranceBytesPerPixel) { $diffCount++ }
            }
        }

        $similarity = if ($sampled -eq 0) { 1.0 } else { [math]::Round(1.0 - ($diffCount / $sampled), 4) }

        return [pscustomobject]@{
            error = $null
            similarity = $similarity
            matched = $similarity -ge 0.95
            sampled_pixels = $sampled
            differing_pixels = $diffCount
            # Historical field names kept so ScreenshotDiffAssertion.ps1 keeps reporting
            # without a second rename round; they now mean sampled/differing pixels.
            bytes_sampled = $sampled
            bytes_over_tolerance = $diffCount
            tolerance_bytes_per_pixel = $ToleranceBytesPerPixel
            sample_stride = $stride
            max_byte_diff = $maxChannelDiff
            baseline_size = $baselineSize
            current_size = $currentSize
            width = $width
            height = $height
        }
    } finally {
        if ($null -ne $baseline) { $baseline.Dispose() }
        if ($null -ne $current) { $current.Dispose() }
    }
}