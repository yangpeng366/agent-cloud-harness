# FlakyComparisonArgs.ps1
#
# Argument normalisation for scripts/Run-FlakyComparison.ps1.
#
# `pwsh -File scripts/Run-FlakyComparison.ps1 -CaseKeys short-001,short-002` binds the
# whole comma list as ONE [string[]] element: native `-File` argument binding does not
# split on commas. The old behaviour only surfaced much later as
# `unknown baseline case: short-001,short-002` (HTTP 400) from the harness, after the
# experiment name had already been consumed. Splitting here makes both invocation
# styles (`-File ... -CaseKeys a,b` and in-process `-CaseKeys @('a','b')`) identical.

function Expand-FlakyComparisonArgValues {
    param([string[]]$Values)

    $expanded = New-Object System.Collections.Generic.List[string]
    foreach ($value in @($Values)) {
        if ($null -eq $value) { continue }
        foreach ($part in ($value -split ',')) {
            $trimmed = $part.Trim()
            if (-not [string]::IsNullOrWhiteSpace($trimmed)) { $expanded.Add($trimmed) }
        }
    }
    # Emit the entries (not a wrapped array): callers normalize with @(...).
    # The `,$array` idiom would look right on direct assignment but turns into a single
    # nested element as soon as it is captured with @(), which is exactly the bug this
    # helper exists to prevent.
    return $expanded.ToArray()
}
