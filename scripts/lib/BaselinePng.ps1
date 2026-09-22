function Save-BaselinePng {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$BaselinePath,
        [switch]$Overwrite
    )

    if (-not (Test-Path -LiteralPath $SourcePath)) {
        return [pscustomobject]@{
            error = 'source_not_found'
            saved = $false
            baseline_path = $BaselinePath
        }
    }

    $baselineDir = Split-Path -Parent $BaselinePath
    if ($baselineDir -and -not (Test-Path -LiteralPath $baselineDir)) {
        New-Item -ItemType Directory -Force -Path $baselineDir | Out-Null
    }

    if ((Test-Path -LiteralPath $BaselinePath) -and -not $Overwrite) {
        return [pscustomobject]@{
            error = 'already_exists'
            saved = $false
            baseline_path = $BaselinePath
            note = 'use -Overwrite to replace'
        }
    }

    [System.IO.File]::Copy($SourcePath, $BaselinePath, $true)
    $info = Get-Item -LiteralPath $BaselinePath
    return [pscustomobject]@{
        error = $null
        saved = $true
        baseline_path = $BaselinePath
        baseline_size = $info.Length
    }
}

function Get-BaselinePng {
    param(
        [Parameter(Mandatory = $true)][string]$BaselinePath
    )

    if (-not (Test-Path -LiteralPath $BaselinePath)) {
        return [pscustomobject]@{
            exists = $false
            path = $BaselinePath
            size = 0
            age_seconds = 0
        }
    }

    $info = Get-Item -LiteralPath $BaselinePath
    $age = (New-TimeSpan -Start $info.LastWriteTime -End (Get-Date)).TotalSeconds

    return [pscustomobject]@{
        exists = $true
        path = $BaselinePath
        size = $info.Length
        age_seconds = [int]$age
        last_modified = $info.LastWriteTime.ToString('o')
    }
}
