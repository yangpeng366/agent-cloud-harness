function Invoke-JevHeuristicComparison {
    param(
        [Parameter(Mandatory = $true)][string]$PatrolMdPath,
        [string]$OutputDirectory = '.tmp\jev-heuristic-comparison',
        [string]$ApiKey = $env:TYPESAFE_API_KEY
    )

    if (-not (Test-Path -LiteralPath $PatrolMdPath)) {
        return [pscustomobject]@{
            error = 'patrol_md_not_found'
            passed = $false
            heuristic_decisions = 0
            jev_decisions = 0
        }
    }

    if (-not (Test-Path -LiteralPath $OutputDirectory)) {
        New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    }

    $stamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $heuristicDir = Join-Path $OutputDirectory "heuristic-$stamp"
    $jevDir = Join-Path $OutputDirectory "jev-$stamp"

    $extractScript = Join-Path (Get-Location) 'scripts\Run-JevPatrolL3Extract.ps1'

    Write-Host '[heuristic] running ...'
    & pwsh -NoProfile -File $extractScript -PatrolMdPath $PatrolMdPath -OutputDirectory $heuristicDir -Mode 'heuristic' 2>$null | Out-Null
    $heuristicJson = Get-ChildItem -Path $heuristicDir -Filter 'patrol-decisions-*.json' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $heuristicJson) {
        return [pscustomobject]@{
            error = 'heuristic_extract_failed'
            passed = $false
            heuristic_decisions = 0
            jev_decisions = 0
        }
    }
    $heuristicReport = Get-Content -LiteralPath $heuristicJson.FullName -Raw | ConvertFrom-Json

    $jevReport = $null
    $jevError = $null
    if ($ApiKey) {
        Write-Host '[jev] running ...'
        & pwsh -NoProfile -File $extractScript -PatrolMdPath $PatrolMdPath -OutputDirectory $jevDir -Mode 'jev' 2>$null | Out-Null
        $jevJson = Get-ChildItem -Path $jevDir -Filter 'patrol-decisions-*.json' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $jevJson) {
            $jevError = 'jev_extract_failed'
        } else {
            $jevReport = Get-Content -LiteralPath $jevJson.FullName -Raw | ConvertFrom-Json
        }
    } else {
        $jevError = 'api_key_missing'
    }

    $heuristicCount = @($heuristicReport.key_decisions).Count
    $jevCount = if ($jevReport) { @($jevReport.key_decisions).Count } else { 0 }
    $heuristicBlockers = @($heuristicReport.key_decisions | Where-Object { $_.category -eq 'blocker' }).Count
    $jevBlockers = if ($jevReport) {
        @($jevReport.key_decisions | Where-Object { $_.category -eq 'blocker' }).Count
    } else { 0 }

    $summary = [ordered]@{
        schema_version = 1
        stamp = $stamp
        patrol_md = $PatrolMdPath
        heuristic = [ordered]@{
            mode = 'heuristic'
            decisions_count = $heuristicCount
            blockers = $heuristicBlockers
            json_path = $heuristicJson.FullName
        }
        jev = if ($jevReport) {
            [ordered]@{
                mode = 'jev'
                decisions_count = $jevCount
                blockers = $jevBlockers
                api_base = $jevReport.jev_api_base
                json_path = $jevJson.FullName
                jev_decisions_with_probability = @($jevReport.key_decisions | Where-Object { $_.PSObject.Properties.Name -contains 'jev_probability' }).Count
            }
        } else {
            [ordered]@{
                mode = 'jev'
                error = $jevError
            }
        }
        divergences = [ordered]@{
            decisions_count_delta = $jevCount - $heuristicCount
            blockers_delta = $jevBlockers - $heuristicBlockers
            jev_blockers_with_api_call = if ($jevReport) {
                @($jevReport.key_decisions | Where-Object { $_.category -eq 'blocker' -and $_.jev_probability }).Count
            } else { 0 }
        }
    }

    $summaryPath = Join-Path $OutputDirectory ("jev-heuristic-comparison-$stamp.json")
    $json = $summary | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($summaryPath, $json, [System.Text.UTF8Encoding]::new($false))

    return [pscustomobject]@{
        error = $null
        passed = $true
        summary_path = $summaryPath
        heuristic_decisions = $heuristicCount
        jev_decisions = $jevCount
        heuristic_blockers = $heuristicBlockers
        jev_blockers = $jevBlockers
    }
}