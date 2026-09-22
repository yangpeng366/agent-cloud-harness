# scripts/lib/ — Pure Functions for ACH Scripts

This directory holds **pure functions** extracted from the IO wrappers in `../Run-*.ps1`. They are loaded via dot-source so the Run-* scripts can stay thin and the functions can be unit-tested in isolation.

## Contract

Every `.ps1` file in this directory must:

1. Define only **pure functions** — no IO, no `Set-Content`, no `Invoke-Command`, no `& <binary>` calls.
2. Be **dot-source-loadable** — `pwsh -File scripts/Run-*.ps1` runs the script in a new scope; functions must be available after `. <lib-file>` inside that same scope.
3. Declare `[Parameter(Mandatory)]` and `[AllowNull()]` / `[AllowEmptyString()]` / `[AllowEmptyCollection()]` as needed to make invalid input surface as a clear error rather than a silent `0` / `$null` result.
4. Return **structured objects** (`[pscustomobject]` / `[ordered]@{}`) — never free-form text — so callers can read fields by name.
5. Use the `[switch]` attribute for boolean flags (not `[bool]` with default `$false`) so the param accepts `-AllowInvisible` without an explicit value.
6. Avoid PowerShell **automatic variables** (`$Latest`, `$Error`, `$Input`, etc.) as parameter or function names — they collide with the engine and produce hard-to-diagnose binding errors.

## Existing libraries

| File | Functions | Tested by |
|---|---|---|
| `JevPatrolL3Extract.ps1` | `Resolve-JevPatrolL3Project`, `Resolve-JevPatrolL3Stamp`, `Build-JevPatrolL3Decisions` | `tests/Test-JevPatrolL3Extract.ps1` |
| `JevPatrolL3Markdown.ps1` | `Build-JevPatrolL3Markdown` | `tests/Test-JevPatrolL3Markdown.ps1` |
| `OpenEyesDelivery.ps1` | `Resolve-DeliveryStatus` | `tests/Test-OpenEyesDelivery.ps1` |
| `OpenEyesElement.ps1` | `Get-Matches`, `Test-ElementState` | `tests/Test-OpenEyesElement.ps1` |
| `ScreenshotCompare.ps1` | `Compare-ScreenshotSimilarity` | `tests/Test-ScreenshotCompare.ps1` |
| `BaselineScreenshotStub.ps1` | `Resolve-BaselinePngPath`, `New-ScreenshotDiffStubReport`, `New-ScreenshotDiffComparisonReport` | `tests/Test-BaselineScreenshotStub.ps1` |
| `BaselinePng.ps1` | `Save-BaselinePng`, `Get-BaselinePng` | `tests/Test-BaselinePng.ps1` |

## Adding a new library

1. **Identify the pure function** inside the Run-* script (the one that takes inputs and returns a result without subprocess / file IO).
2. **Extract it** into a new file `scripts/lib/<Topic>.ps1`. Add `[Parameter(Mandatory=$true)]` annotations and explicit `[AllowNull()]` / `[AllowEmptyString()]` where the function should accept empty input without throwing.
3. **Return a structured object** (`[pscustomobject]@{}` or `[ordered]@{}`) — not strings.
4. **Avoid PowerShell automatic variables** in param names (`$Latest`, `$Error`, `$Input`, `$Matches`, `$?`, `$null`, `$true`, `$false` are reserved).
5. **Write a test file** `tests/Test-<Topic>.ps1` that dot-sources the lib, exercises the function, and writes `=== Test-<Topic> summary: pass=N fail=N ===` to stdout. Use `[OK]` / `[FAIL]` markers.
6. **Add the lib to a Run-* script** that needs it: `$libPath = Join-Path $PSScriptRoot 'lib/<Topic>.ps1'; if (Test-Path -LiteralPath $libPath) { . $libPath }`. The `if (Test-Path)` guard avoids breaking tests that call the script via path lookup that does not include `lib/`.
7. **Register the new test** in `tests/Run-JevPatrolL3Tests.ps1`'s `$suites` array. The runner aggregates `pass=N fail=N` per suite.
8. **Run** `pwsh -File tests/Run-JevPatrolL3Tests.ps1` and confirm the new suite is included in the aggregate.

## Pattern template (copy-paste starter)

```powershell
# scripts/lib/<Topic>.ps1

function Invoke-<Verb>-<Noun> {
    param(
        [Parameter(Mandatory = $true)][string]$Input,
        [switch]$Flag
    )

    # Pure logic, no IO.
    return [pscustomobject]@{
        result = ...
        flag_used = [bool]$Flag
    }
}
```

```powershell
# tests/Test-<Topic>.ps1

$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\<Topic>.ps1'
if (-not (Test-Path -LiteralPath $libPath)) { throw "Lib not found: $libPath" }
. $libPath

$pass = 0; $fail = 0

function Assert-Eq { param($Expected, $Actual, [string]$Label)
    if ($Expected -eq $Actual) { Write-Host "[OK]  $Label"; $script:pass++ }
    else { Write-Host "[FAIL] $Label"; $script:fail++ }
}

# Add your test cases here.

Write-Host "=== Test-<Topic> summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
```

## Common pitfalls (and fixes)

- **`pwsh -File` script scope** does not expose functions to the parent shell. Always dot-source from a lib file rather than defining functions inline in the Run-* script.
- **Function returns `List[object]` with single element** get auto-unrolled. Wrap the return: `return ,$result`.
- **`Mandatory` rejects empty strings** by default. Add `[AllowEmptyString()]`.
- **`Split-Path -LiteralPath` and `-Parent`** are parameter-set incompatible. Use `Split-Path -Parent $path`.
- **`pwsh -File` does not pass array args cleanly** when using `@(...)` syntax. Tests that exercise the Run-* wrapper should set up `pscustomobject` test fixtures directly rather than calling `pwsh -File`.
- **Byte array comparison** with `-eq` produces string comparison. Use `Compare-Object` with `-eq $null` or `(Compare-Object ... | Measure-Object).Count -eq 0`.
- **PowerShell `$Latest`** is an automatic variable for the most-recent pipeline item. Rename to `$UseLatest` or similar.

## See also

- `CONTRIBUTING.md` §PowerShell 脚本 + lib + test 模式 — high-level overview
- `docs/INITIATIVES/Jev-Patrol-L3.md` — reference implementation of the pattern
