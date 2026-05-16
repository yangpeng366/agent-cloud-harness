param()

$ErrorActionPreference = "Stop"

function Resolve-MavenCommand {
    $command = Get-Command mvn -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidatePaths = @(
        "C:\ProgramData\chocolatey\bin\mvn.bat",
        "C:\ProgramData\chocolatey\bin\mvn.cmd",
        "$env:USERPROFILE\scoop\shims\mvn.cmd",
        "$env:USERPROFILE\scoop\apps\maven\current\bin\mvn.cmd",
        "$env:USERPROFILE\scoop\apps\maven\current\bin\mvn.bat",
        "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.3.5\plugins\maven\lib\maven3\bin\mvn.cmd",
        "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.3.5\plugins\maven\lib\maven3\bin\mvn.bat",
        "C:\Program Files\JetBrains\IntelliJ IDEA Ultimate\plugins\maven\lib\maven3\bin\mvn.cmd",
        "C:\Program Files\JetBrains\IntelliJ IDEA Ultimate\plugins\maven\lib\maven3\bin\mvn.bat"
    ) | ForEach-Object { [Environment]::ExpandEnvironmentVariables($_) }

    foreach ($candidate in $candidatePaths) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $searchRoots = @(
        "C:\Program Files\JetBrains",
        "C:\Program Files",
        "C:\tools",
        "C:\dev"
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($root in $searchRoots) {
        foreach ($filter in @("mvn.cmd", "mvn.bat")) {
            $match = Get-ChildItem -Path $root -Recurse -Filter $filter -File -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($match) {
                return $match.FullName
            }
        }
    }

    throw "Maven executable not found. Install Maven or add mvn to PATH."
}

Resolve-MavenCommand
