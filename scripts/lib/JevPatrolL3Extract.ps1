function Resolve-JevPatrolL3Project {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )

    $project = 'unknown'
    if ($FileName -match '^patrol-last-(.+?)-(\d{8}-\d{6})$') {
        $project = $Matches[1]
    }
    if ($project -eq 'unknown') {
        if ($Content -match '>\s*项目:\s*(.+)') {
            $project = $Matches[1].Trim()
        } elseif ($Content -match '^#\s+([\w\-]+)\s+(?:巡检|patrol)') {
            $project = $Matches[1].Trim()
        }
    }
    return $project
}

function Resolve-JevPatrolL3Stamp {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [string]$DefaultStamp = ''
    )
    if (-not $DefaultStamp) {
        $DefaultStamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
    }
    if ($FileName -match '^patrol-last-(.+?-)?(\d{8}-\d{6})$') {
        return $Matches[2]
    }
    return $DefaultStamp
}

function Build-JevPatrolL3Decisions {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content,
        [int]$MaxDecisions = 15,
        [int]$MaxSummaryLength = 120
    )

    $decisions = New-Object System.Collections.Generic.List[object]
    $decisionId = 0
    $lines = $Content -split "`n"
    $currentSection = ''

    foreach ($line in $lines) {
        $trimmed = $line.Trim()

        if ($trimmed -match '^#{1,3}\s+(.+)$') {
            $currentSection = $Matches[1].Trim()
            continue
        }

        if ($trimmed.Length -lt 10) { continue }
        if ($trimmed.StartsWith('>') -or $trimmed.StartsWith('---') -or $trimmed.StartsWith('|')) { continue }

        $category = $null
        $priority = 'low'

        if ($currentSection -match '本轮推进|推进') {
            if ($trimmed -match '(?i)block|阻塞|failed|失败|error|错误') {
                $category = 'blocker'; $priority = 'high'
            } elseif ($trimmed -match '(?i)fix|修复|merge|合并|完成|pass|通过|success') {
                $category = 'progress'; $priority = 'medium'
            } else {
                $category = 'observation'
            }
        } elseif ($currentSection -match '下一步|下轮|next') {
            $category = 'next_step'; $priority = 'medium'
        } elseif ($trimmed -match '(?i)^-\s+(head|commit|sha|HEAD)') {
            $category = 'observation'
        }

        if ($null -eq $category) { continue }

        $decisionId++
        $summary = if ($trimmed.Length -gt $MaxSummaryLength) {
            $trimmed.Substring(0, $MaxSummaryLength - 3) + '...'
        } else {
            $trimmed
        }
        $decisions.Add([PSCustomObject]@{
            id = 'kd-{0:D3}' -f $decisionId
            category = $category
            summary = $summary
            evidence = $currentSection
            priority = $priority
        })

        if ($decisions.Count -ge $MaxDecisions) { break }
    }

    return ,$decisions
}
