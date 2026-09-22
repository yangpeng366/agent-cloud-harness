function Resolve-DeliveryStatus {
    param(
        [int]$EyesExitCode,
        [Nullable[bool]]$FocusMatchesExpected,
        [string]$FocusBefore,
        [string]$FocusAfter
    )

    if ($EyesExitCode -ne 0) { return 'DISPATCH_FAILED' }
    if ($null -ne $FocusMatchesExpected -and -not $FocusMatchesExpected) { return 'FOCUS_MISMATCH' }
    if ($FocusBefore -ne $FocusAfter) { return 'FOCUS_CHANGED_DURING_TYPE' }
    return 'DISPATCHED_FOCUSED'
}
