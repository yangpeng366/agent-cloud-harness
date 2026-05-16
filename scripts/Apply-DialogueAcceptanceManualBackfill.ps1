param(
    [Parameter(Mandatory = $true)]
    [string]$BackfillJsonPath,

    [Parameter(Mandatory = $true)]
    [string]$RecordPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $BackfillJsonPath)) {
    throw "backfill json not found: $BackfillJsonPath"
}
if (-not (Test-Path -LiteralPath $RecordPath)) {
    throw "record markdown not found: $RecordPath"
}

$backfill = Get-Content -LiteralPath $BackfillJsonPath -Raw | ConvertFrom-Json
$paths = @($backfill.paths)
if ($paths.Count -eq 0) {
    throw "backfill json has no paths"
}

$recordLines = [System.Collections.Generic.List[string]](Get-Content -LiteralPath $RecordPath)

function Find-LineIndex {
    param(
        [System.Collections.Generic.List[string]]$Lines,
        [string]$Pattern,
        [int]$StartIndex = 0
    )

    for ($i = $StartIndex; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -eq $Pattern) {
            return $i
        }
    }
    return -1
}

function Replace-Line {
    param(
        [System.Collections.Generic.List[string]]$Lines,
        [int]$Index,
        [string]$Value
    )
    if ($Index -lt 0 -or $Index -ge $Lines.Count) {
        throw "line index out of range: $Index"
    }
    $Lines[$Index] = $Value
}

foreach ($path in $paths) {
    $heading = "### {0}. {1}" -f [string]$path.id, [string]$path.label
    $headingIndex = Find-LineIndex -Lines $recordLines -Pattern $heading
    if ($headingIndex -lt 0) {
        throw "section heading not found in record: $heading"
    }

    $nextHeadingIndex = -1
    for ($i = $headingIndex + 1; $i -lt $recordLines.Count; $i++) {
        if ($recordLines[$i] -like "### *. *") {
            $nextHeadingIndex = $i
            break
        }
        if ($recordLines[$i] -eq "---") {
            $nextHeadingIndex = $i
            break
        }
    }
    if ($nextHeadingIndex -lt 0) {
        $nextHeadingIndex = $recordLines.Count
    }

    $passedIndex = Find-LineIndex -Lines $recordLines -Pattern "- [ ] Passed" -StartIndex $headingIndex
    if ($passedIndex -lt 0 -or $passedIndex -ge $nextHeadingIndex) {
        $passedIndex = Find-LineIndex -Lines $recordLines -Pattern "- [x] Passed" -StartIndex $headingIndex
    }
    if ($passedIndex -lt 0 -or $passedIndex -ge $nextHeadingIndex) {
        throw "Passed line not found for section: $heading"
    }

    $inputIndex = Find-LineIndex -Lines $recordLines -Pattern "- Input:" -StartIndex $headingIndex
    $observedIndex = Find-LineIndex -Lines $recordLines -Pattern "- Observed result:" -StartIndex $headingIndex
    if ($observedIndex -lt 0 -or $observedIndex -ge $nextHeadingIndex) {
        $observedIndex = Find-LineIndex -Lines $recordLines -Pattern "- Network / Observed result:" -StartIndex $headingIndex
    }
    $notesIndex = Find-LineIndex -Lines $recordLines -Pattern "- Notes:" -StartIndex $headingIndex

    if ($inputIndex -lt 0 -or $inputIndex -ge $nextHeadingIndex) {
        throw "Input line not found for section: $heading"
    }
    if ($observedIndex -lt 0 -or $observedIndex -ge $nextHeadingIndex) {
        throw "Observed result line not found for section: $heading"
    }
    if ($notesIndex -lt 0 -or $notesIndex -ge $nextHeadingIndex) {
        throw "Notes line not found for section: $heading"
    }

    $passedLineValue = if ($path.passed) { "- [x] Passed" } else { "- [ ] Passed" }
    Replace-Line -Lines $recordLines -Index $passedIndex -Value $passedLineValue
    Replace-Line -Lines $recordLines -Index $inputIndex -Value ("- Input: {0}" -f [string]$path.input)
    if ($recordLines[$observedIndex] -eq "- Network / Observed result:") {
        Replace-Line -Lines $recordLines -Index $observedIndex -Value ("- Network / Observed result: {0}" -f [string]$path.observed_result)
    } else {
        Replace-Line -Lines $recordLines -Index $observedIndex -Value ("- Observed result: {0}" -f [string]$path.observed_result)
    }

    $firstCandidateIndex = -1
    for ($i = $notesIndex + 1; $i -lt $nextHeadingIndex; $i++) {
        if ($recordLines[$i] -like "  - Candidate PNG:*") {
            $firstCandidateIndex = $i
            break
        }
    }
    if ($firstCandidateIndex -lt 0) {
        $firstCandidateIndex = $nextHeadingIndex
    }

    $insertStart = $notesIndex + 1
    $removeCount = $firstCandidateIndex - $insertStart
    if ($removeCount -gt 0) {
        $recordLines.RemoveRange($insertStart, $removeCount)
    }

    $notesToInsert = New-Object System.Collections.Generic.List[string]
    foreach ($note in @($path.notes)) {
        $notesToInsert.Add(("  - {0}" -f [string]$note))
    }
    if ($notesToInsert.Count -gt 0) {
        $recordLines.InsertRange($insertStart, $notesToInsert)
    }
}

$recordLines | Set-Content -LiteralPath $RecordPath -Encoding utf8

[pscustomobject]@{
    backfill_json_path = [System.IO.Path]::GetFullPath($BackfillJsonPath)
    record_path = [System.IO.Path]::GetFullPath($RecordPath)
    updated_paths = @($paths | ForEach-Object { [string]$_.id })
} | ConvertTo-Json -Depth 4
