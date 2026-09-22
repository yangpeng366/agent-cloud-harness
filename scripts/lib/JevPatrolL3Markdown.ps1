function Build-JevPatrolL3Markdown {
    param(
        [Parameter(Mandatory = $true)][PSCustomObject]$Report,
        [Parameter(Mandatory = $true)][string]$SourceFileName
    )
    if (-not $Report.schema_version) { throw "Missing schema_version (unsupported input)" }
    if ($Report.schema_version -ne 1) { throw "Unsupported schema_version: $($Report.schema_version)" }

    $decisions = if ($Report.key_decisions) { @($Report.key_decisions) } else { @() }
    $summary = if ($Report.summary) { $Report.summary } else {
        @{ total_decisions = $decisions.Count; high_priority = 0; blockers = 0 }
    }

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Patrol Decisions — $($Report.project)")
    $lines.Add("")
    $lines.Add("> Source: ``$SourceFileName``  ")
    $lines.Add("> Schema: v$($Report.schema_version)  ")
    $lines.Add("> Extraction: $($Report.extraction_mode)  ")
    $lines.Add("> Generated: $($Report.generated_at)")
    $lines.Add("")
    $lines.Add("## Summary")
    $lines.Add("")
    $lines.Add("- Total decisions: **$($summary.total_decisions)**")
    $lines.Add("- High priority: **$($summary.high_priority)**")
    $lines.Add("- Blockers: **$($summary.blockers)**")
    $lines.Add("")

    if ($decisions.Count -eq 0) {
        $lines.Add("_No key_decisions extracted from this patrol round._")
    } else {
        $lines.Add("## Key Decisions")
        $lines.Add("")
        $lines.Add("| ID | Category | Priority | Summary | Section |")
        $lines.Add("| --- | --- | --- | --- | --- |")
        foreach ($d in $decisions) {
            $summaryText = if ($d.summary) { $d.summary.Replace("|","\|").Replace("`n"," ") } else { '' }
            $lines.Add("| $($d.id) | $($d.category) | $($d.priority) | $summaryText | $($d.evidence) |")
        }

        $blockers = @($decisions | Where-Object { $_.category -eq 'blocker' })
        if ($blockers.Count -gt 0) {
            $lines.Add("")
            $lines.Add("## Blockers (drill down)")
            $lines.Add("")
            foreach ($b in $blockers) {
                $lines.Add("- **$($b.id)** — $($b.summary)")
            }
        }
    }

    return ($lines -join "`n")
}
