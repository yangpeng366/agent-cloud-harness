# scripts/lib/OpenEyesCli.ps1
#
# Shared eyes CLI adapter for the OpenEyes acceptance scripts
# (Run-OpenEyesUiAssertion / Run-NativeAppAcceptanceProbe / Run-OpenEyesTypeVerified).
#
# Why this exists (2026-11-17):
#   The eyes CLI drifted away from what the ACH scripts assume:
#     1. default output is a human table now; machine parsing needs `--json`
#     2. `--json` wraps payloads in an envelope: {ok,count,windows|elements}
#     3. `PYTHONIOENCODING` is gbk on zh-CN consoles, so listing windows whose
#        title carries a non-GBK glyph (U+200B etc.) raises UnicodeEncodeError
#        and kills the whole command
#     4. an editable install whose mapping still points at a moved repo
#        (E:\gitAll\openeyes) fails with "No module named 'openeyes'"
#
# Everything here is offline-testable: classification and payload parsing are
# pure functions; only Initialize-OpenEyesCliEnvironment and the probe touch
# the real process.

function Find-OpenEyesSourceRoot {
    param(
        [string[]]$CandidatePaths = @()
    )

    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($candidate in @($CandidatePaths)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate)) { $candidates.Add($candidate) }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:OPENEYES_REPO)) { $candidates.Add($env:OPENEYES_REPO) }
    foreach ($fallback in @('D:\gitAll\openeyes', 'E:\gitAll\openeyes', 'D:\gitAll\openeyes-staging')) {
        $candidates.Add($fallback)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $candidates.Add((Join-Path $env:USERPROFILE 'openeyes'))
    }

    foreach ($candidate in $candidates) {
        $init = Join-Path $candidate 'openeyes\__init__.py'
        if (Test-Path -LiteralPath $init) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Resolve-OpenEyesCliStatus {
    param(
        [bool]$ExecutableFound,
        [int]$ProbeExitCode = 0,
        [string]$ProbeOutput = '',
        [string]$SourceRoot = ''
    )

    if (-not $ExecutableFound) {
        return [pscustomobject]@{
            status      = 'not_found'
            reason      = 'eyes CLI is not on PATH'
            remediation = 'install it (pip install -e <openeyes repo>) or pass -EyesBin <path to eyes.exe>'
        }
    }

    if ($ProbeExitCode -eq 0) {
        return [pscustomobject]@{
            status      = 'ok'
            reason      = 'probe exited 0'
            remediation = $null
        }
    }

    $text = [string]$ProbeOutput

    if ($text -match "No module named\s+'?openeyes'?") {
        $remediation = 'stale editable install: re-run pip install -e <openeyes repo>'
        if (-not [string]::IsNullOrWhiteSpace($SourceRoot)) {
            $remediation = "stale editable install: ACH exports PYTHONPATH=$SourceRoot for the eyes child process; permanent fix is pip install -e `"$SourceRoot`""
        }
        return [pscustomobject]@{
            status      = 'module_missing'
            reason      = "python cannot import the openeyes package (editable mapping points at a moved repo)"
            remediation = $remediation
        }
    }

    if ($text -match 'UnicodeEncodeError') {
        return [pscustomobject]@{
            status      = 'console_encoding'
            reason      = 'eyes stdout cannot encode the current console code page'
            remediation = 'ACH sets PYTHONIOENCODING=utf-8 for the child process; a manual shell needs the same variable'
        }
    }

    return [pscustomobject]@{
        status      = 'failed'
        reason      = 'eyes probe exited non-zero'
        remediation = 'run the probe command manually and inspect stderr'
    }
}

function ConvertFrom-OpenEyesPayload {
    param(
        [AllowNull()][AllowEmptyString()][string]$Text,
        [string[]]$EnvelopeKeys = @()
    )

    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    $trimmed = $Text.Trim()

    $parsed = $null
    $parsedOk = $false
    try {
        $parsed = $trimmed | ConvertFrom-Json -ErrorAction Stop
        $parsedOk = $true
    }
    catch { $parsedOk = $false }

    if ($parsedOk) {
        if ($parsed -is [System.Collections.IEnumerable] -and -not ($parsed -is [string]) -and -not ($parsed -is [pscustomobject])) {
            return @($parsed)
        }
        if ($parsed -is [pscustomobject]) {
            $hasOk = $parsed.PSObject.Properties.Name -contains 'ok'
            if ($hasOk -and -not [bool]$parsed.ok) {
                $detail = ''
                foreach ($key in @('error', 'message', 'detail')) {
                    if ($parsed.PSObject.Properties.Name -contains $key -and -not [string]::IsNullOrWhiteSpace([string]$parsed.$key)) {
                        $detail = [string]$parsed.$key
                        break
                    }
                }
                throw "eyes reported ok=false: $detail"
            }
            foreach ($key in @($EnvelopeKeys)) {
                if ([string]::IsNullOrWhiteSpace($key)) { continue }
                if ($parsed.PSObject.Properties.Name -contains $key) {
                    $inner = $parsed.$key
                    if ($null -eq $inner) { return @() }
                    return @($inner)
                }
            }
            if ($parsed.PSObject.Properties.Name -contains 'hwnd') { return @($parsed) }
        }
    }

    # NDJSON fallback: one JSON document per line.
    $items = New-Object System.Collections.Generic.List[object]
    $lineFailed = $false
    foreach ($line in ($trimmed -split "`r?`n")) {
        $candidate = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $head = $candidate.Substring(0, 1)
        if ($head -ne '{' -and $head -ne '[') { $lineFailed = $true; break }
        try { $items.Add(($candidate | ConvertFrom-Json -ErrorAction Stop)) }
        catch { $lineFailed = $true; break }
    }
    if (-not $lineFailed -and $items.Count -gt 0) { return @($items.ToArray()) }

    $preview = if ($trimmed.Length -gt 200) { $trimmed.Substring(0, 200) } else { $trimmed }
    throw "cannot parse eyes output as JSON (expected --json envelope or NDJSON): $preview"
}

function Invoke-OpenEyesCliProcess {
    param(
        [Parameter(Mandatory = $true)][string]$EyesBin,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [int]$TimeoutSec = 60
    )

    $resolved = $null
    $command = Get-Command -Name $EyesBin -ErrorAction SilentlyContinue
    if ($command) { $resolved = $command.Source }
    if ([string]::IsNullOrWhiteSpace($resolved)) { $resolved = $EyesBin }

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $quotedArguments = (($Arguments | ForEach-Object { '"' + ([string]$_ -replace '"', '\"') + '"' }) -join ' ')
    if ($resolved -match '\.(cmd|bat)$') {
        $startInfo.FileName = if ($env:ComSpec) { $env:ComSpec } else { 'cmd.exe' }
        $startInfo.Arguments = "/c ""$resolved"" $quotedArguments"
    }
    else {
        $startInfo.FileName = $resolved
        # Quote every argument so paths with spaces ("D:\gitAll\my repo") survive;
        # this stays compatible with Windows PowerShell 5.1, which has no ArgumentList.
        $startInfo.Arguments = $quotedArguments
    }
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $startInfo.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $exited = $process.WaitForExit([Math]::Max(1, $TimeoutSec) * 1000)
    if (-not $exited) {
        try { $process.Kill($true) } catch { }
        $watch.Stop()
        return [pscustomobject]@{
            exit_code  = -1
            timed_out  = $true
            output     = ''
            stderr     = "eyes command timed out after ${TimeoutSec}s"
            elapsed_ms = [int]$watch.ElapsedMilliseconds
        }
    }
    $process.WaitForExit()
    $watch.Stop()
    return [pscustomobject]@{
        exit_code  = $process.ExitCode
        timed_out  = $false
        output     = [string]$stdoutTask.Result
        stderr     = [string]$stderrTask.Result
        elapsed_ms = [int]$watch.ElapsedMilliseconds
    }
}

function Invoke-OpenEyesJson {
    param(
        [string]$EyesBin = 'eyes',
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string[]]$EnvelopeKeys = @(),
        [int]$TimeoutSec = 60
    )

    $run = Invoke-OpenEyesCliProcess -EyesBin $EyesBin -Arguments $Arguments -TimeoutSec $TimeoutSec
    $combined = (@($run.output, $run.stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join [Environment]::NewLine
    if ($run.exit_code -ne 0) {
        $trimmed = if ($combined.Length -gt 600) { $combined.Substring(0, 600) } else { $combined }
        throw "eyes failed: $($Arguments -join ' ') exit=$($run.exit_code) output=$trimmed"
    }
    $items = @(ConvertFrom-OpenEyesPayload -Text $run.output -EnvelopeKeys $EnvelopeKeys)
    return [pscustomobject]@{
        items      = $items
        raw        = $run.output
        elapsed_ms = $run.elapsed_ms
    }
}

function Initialize-OpenEyesCliEnvironment {
    param(
        [string]$EyesBin = 'eyes',
        [string[]]$SourceRootCandidates = @(),
        [switch]$SkipProbe,
        [int]$TimeoutSec = 60
    )

    $previousPythonPath = $env:PYTHONPATH
    $env:PYTHONIOENCODING = 'utf-8'
    if ([string]::IsNullOrWhiteSpace($env:PYTHONUTF8)) { $env:PYTHONUTF8 = '1' }

    $command = Get-Command -Name $EyesBin -ErrorAction SilentlyContinue
    $executable = if ($command) { $command.Source } else { $null }
    $sourceRoot = $null
    $version = $null
    $recovered = $false
    $probe = $null

    if ($command -and -not $SkipProbe) {
        $probe = Invoke-OpenEyesCliProcess -EyesBin $EyesBin -Arguments @('--version') -TimeoutSec $TimeoutSec
        $status = Resolve-OpenEyesCliStatus -ExecutableFound $true -ProbeExitCode $probe.exit_code -ProbeOutput ($probe.output + $probe.stderr)
        if ($status.status -eq 'module_missing') {
            $sourceRoot = Find-OpenEyesSourceRoot -CandidatePaths $SourceRootCandidates
            if (-not [string]::IsNullOrWhiteSpace($sourceRoot)) {
                $env:PYTHONPATH = if ([string]::IsNullOrWhiteSpace($previousPythonPath)) { $sourceRoot } else { "$sourceRoot;$previousPythonPath" }
                $probe = Invoke-OpenEyesCliProcess -EyesBin $EyesBin -Arguments @('--version') -TimeoutSec $TimeoutSec
                $status = Resolve-OpenEyesCliStatus -ExecutableFound $true -ProbeExitCode $probe.exit_code -ProbeOutput ($probe.output + $probe.stderr) -SourceRoot $sourceRoot
                $recovered = ($status.status -eq 'ok')
            }
        }
        if ($probe.exit_code -eq 0 -and $probe.output -match 'version\s+(?<v>\d+\.\d+\.\d+)') {
            $version = $Matches.v
        }
    }
    else {
        $status = Resolve-OpenEyesCliStatus -ExecutableFound ([bool]$command) -ProbeExitCode 0 -ProbeOutput ''
    }

    return [pscustomobject]@{
        status           = $status.status
        reason           = $status.reason
        remediation      = $status.remediation
        executable       = $executable
        version          = $version
        source_root      = $sourceRoot
        pythonpath       = $env:PYTHONPATH
        pythonioencoding = $env:PYTHONIOENCODING
        recovered        = $recovered
        probe_exit_code  = if ($probe) { $probe.exit_code } else { $null }
    }
}
