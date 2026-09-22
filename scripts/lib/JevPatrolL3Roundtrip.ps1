function Test-JevPatrolL3Roundtrip {
    param(
        [Parameter(Mandatory = $true)][string]$PatrolMdPath,
        [string]$OutputDirectory = '.tmp\jev-patrol-roundtrip',
        [string]$Mode = 'heuristic',
        [string]$ApiKey = $env:TYPESAFE_API_KEY
    )

    if (-not (Test-Path -LiteralPath $PatrolMdPath)) {
        return [pscustomobject]@{
            error = 'patrol_md_not_found'
            passed = $false
            decisions_count = 0
        }
    }

    if (-not (Test-Path -LiteralPath $OutputDirectory)) {
        New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    }

    $extractScript = Join-Path (Get-Location) 'scripts\Run-JevPatrolL3Extract.ps1'
    $renderScript = Join-Path (Get-Location) 'scripts\Run-JevPatrolL3Render.ps1'

    $rawJson = & pwsh -NoProfile -File $extractScript -PatrolMdPath $PatrolMdPath -OutputDirectory $OutputDirectory -Mode $Mode 2>$null
    $extractExit = $LASTEXITCODE

    if ($extractExit -ne 0) {
        return [pscustomobject]@{
            error = "extract_exit_$extractExit"
            passed = $false
            decisions_count = 0
        }
    }

    $jsonPath = Get-ChildItem -Path $OutputDirectory -Filter 'patrol-decisions-*.json' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jsonPath) {
        return [pscustomobject]@{
            error = 'json_not_produced'
            passed = $false
            decisions_count = 0
        }
    }

    $report = Get-Content -LiteralPath $jsonPath.FullName -Raw | ConvertFrom-Json
    if ($report.schema_version -ne 1) {
        return [pscustomobject]@{
            error = "schema_version_$($report.schema_version)"
            passed = $false
            decisions_count = $report.key_decisions.Count
            json_path = $jsonPath.FullName
        }
    }

    $mdOut = Join-Path $OutputDirectory 'rendered.md'
    $renderJson = & pwsh -NoProfile -File $renderScript -InputJsonPath $jsonPath.FullName -OutputMdPath $mdOut 2>$null
    $renderExit = $LASTEXITCODE

    if ($renderExit -ne 0) {
        return [pscustomobject]@{
            error = "render_exit_$renderExit"
            passed = $false
            decisions_count = $report.key_decisions.Count
            json_path = $jsonPath.FullName
        }
    }

    if (-not (Test-Path -LiteralPath $mdOut)) {
        return [pscustomobject]@{
            error = 'markdown_not_written'
            passed = $false
            decisions_count = $report.key_decisions.Count
            json_path = $jsonPath.FullName
        }
    }

    $md = Get-Content -LiteralPath $mdOut -Raw
    $missingSections = @()
    foreach ($sec in @('## Summary', '## Key Decisions')) {
        if (-not $md.Contains($sec)) { $missingSections += $sec }
    }
    if ($missingSections.Count -gt 0) {
        return [pscustomobject]@{
            error = "missing_sections:$($missingSections -join ',')"
            passed = $false
            decisions_count = $report.key_decisions.Count
            json_path = $jsonPath.FullName
            markdown_path = $mdOut
        }
    }

    return [pscustomobject]@{
        error = $null
        passed = $true
        mode = $Mode
        decisions_count = $report.key_decisions.Count
        high_priority = $report.summary.high_priority
        blockers = $report.summary.blockers
        extraction_mode = $report.extraction_mode
        jev_api_base = $report.jev_api_base
        json_path = $jsonPath.FullName
        markdown_path = $mdOut
    }
}