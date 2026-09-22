function Get-Matches {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Elements,
        [Parameter(Mandatory = $true)][string]$Field,
        [Parameter(Mandatory = $true)][string]$Expected
    )

    return @($Elements | Where-Object {
        $actual = [string]$_.$Field
        if ([string]::IsNullOrEmpty($actual)) { return $false }
        $actual.IndexOf($Expected, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    })
}

function Test-ElementState {
    param(
        [Parameter(Mandatory = $true)][AllowNull()][object]$Element,
        [switch]$AllowInvisible,
        [switch]$AllowDisabled
    )

    if ($null -eq $Element -or $null -eq $Element.state) { return $false }
    if (-not $AllowInvisible.IsPresent -and -not [bool]$Element.state.visible) { return $false }
    if (-not $AllowDisabled.IsPresent -and -not [bool]$Element.state.enabled) { return $false }
    return $true
}
