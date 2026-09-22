<#
.SYNOPSIS
    多源 release 版本漂移校验脚本(本地 pre-tag gate)。

.DESCRIPTION
    适配自 EchoBird scripts/verify-release-version.mjs(EchoBird 增量 2 沉淀,见
    D:\BaiduSyncdisk\Obsidian Vault\当前项目\02_项目推进\EchoBird发布工程参考_增量2_2026-08-17.md §一)。

    在打 v* tag 之前,本地跑一次,暴露 Agent Cloud Harness 多个版本源之间的漂移:
    - pom.xml            <version>          (Java / Maven 权威源)
    - dependency-reduced-pom.xml            (shade 产物,必须等于 pom)
    - package.json       "version"          (Node 前端 dialog;若无则记 N/A)
    - CHANGELOG.md       [Unreleased] 段 + 最新已发布段 ## [X.Y.Z] - YYYY-MM-DD
    - git tag            v*                 (与最新已发布段去 v 前缀比对)
    - harness-config*.yml 顶层 "version:"   (影子版本源,出现则警告)

    行为:
    - 参考源 = pom.xml <version>(权威)
    - 其它版本字段向参考源对齐;不一致即 exit 1
    - "未找到 / N/A" 也单独列出,避免静默跳过
    - 不自动修复任何文件

.PARAMETER RepoRoot
    仓库根目录;不传则默认本脚本所在目录的父目录。

.PARAMETER Quiet
    仅输出汇总行,适合 CI 收尾打印。

.EXAMPLE
    pwsh -NoProfile -File .\scripts\Verify-HarnessReleaseVersion.ps1

.EXAMPLE
    pwsh -NoProfile -File .\scripts\Verify-HarnessReleaseVersion.ps1 -RepoRoot D:\gitAll\agent-cloud-harness -Quiet

.NOTES
    UTF-8 无 BOM,LF 换行;使用 System.IO.File.ReadAllText 默认 UTF-8。
    不修改任何源文件;不动 git status;只读勘察。
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = "",
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Read-TextUtf8 {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return [System.IO.File]::ReadAllText($Path, $script:Utf8NoBom)
}

function Get-ProjectVersionFromPom {
    param([string]$PomPath)
    $text = Read-TextUtf8 -Path $PomPath
    if (-not $text) { return $null }
    # 仅取 <project> 顶层 <version>,不匹配 dependency 的 <version>。
    if ($text -match "(?s)<project\b[^>]*>(?<body>.*?)</project>") {
        $body = $matches["body"]
        if ($body -match "(?m)^[ \t]*<version>(?<v>[^<]+)</version>") {
            return $matches["v"].Trim()
        }
    }
    return $null
}

function Get-PackageJsonVersion {
    param([string]$PackageJsonPath)
    $text = Read-TextUtf8 -Path $PackageJsonPath
    if (-not $text) { return $null }
    try {
        $obj = $text | ConvertFrom-Json -ErrorAction Stop
        if ($obj.PSObject.Properties.Match("version").Count -gt 0) {
            return [string]$obj.version
        }
    } catch {
        return $null
    }
    return $null
}

function Get-ChangelogLatestReleased {
    param([string]$ChangelogPath)
    $text = Read-TextUtf8 -Path $ChangelogPath
    if (-not $text) { return @{ version = $null; date = $null } }
    $rx = New-Object System.Text.RegularExpressions.Regex(
        "(?m)^## \[(?<ver>[0-9A-Za-z\.\-\+]+)\] - (?<date>\d{4}-\d{2}-\d{2})",
        [System.Text.RegularExpressions.RegexOptions]::Compiled
    )
    $m = $rx.Match($text)
    if ($m.Success) {
        return @{ version = $m.Groups["ver"].Value; date = $m.Groups["date"].Value }
    }
    return @{ version = $null; date = $null }
}

function Test-ChangelogUnreleasedSection {
    param([string]$ChangelogPath)
    $text = Read-TextUtf8 -Path $ChangelogPath
    if (-not $text) { return $false }
    return ($text -match "(?m)^## \[Unreleased\]")
}

function Test-HarnessConfigHasVersionField {
    param([string]$Path)
    $text = Read-TextUtf8 -Path $Path
    if (-not $text) { return $false }
    return ($text -match "(?m)^version\s*:")
}

function Get-GitLatestVTag {
    param([string]$WorkDir)
    try {
        $tags = & git -C $WorkDir tag --list 'v*' 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        $semverSorted = $tags | Where-Object { $_ -match '^v\d+\.\d+\.\d+' } |
            ForEach-Object { [version]($_ -replace '^v', '') } |
            Sort-Object -Descending
        if (-not $semverSorted) { return $null }
        return ("v{0}" -f $semverSorted[0].ToString())
    } catch {
        return $null
    }
}

function Test-SemVerLiteral {
    param([string]$Literal)
    if (-not $Literal) { return $false }
    return ($Literal -match '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$')
}

# --- 收集各源版本 ---
$pomPath            = Join-Path $RepoRoot "pom.xml"
$reducedPomPath     = Join-Path $RepoRoot "dependency-reduced-pom.xml"
$packageJsonPath    = Join-Path $RepoRoot "package.json"
$changelogPath      = Join-Path $RepoRoot "CHANGELOG.md"
$harnessConfigPath  = Join-Path $RepoRoot "harness-config.yml"
$harnessConfigExPath= Join-Path $RepoRoot "harness-config.example.yml"

$pomVersion         = Get-ProjectVersionFromPom -PomPath $pomPath
$reducedVersion     = Get-ProjectVersionFromPom -PomPath $reducedPomPath
$pkgVersion         = Get-PackageJsonVersion -PackageJsonPath $packageJsonPath
$unreleasedPresent  = Test-ChangelogUnreleasedSection -ChangelogPath $changelogPath
$latestReleased     = Get-ChangelogLatestReleased -ChangelogPath $changelogPath
$latestGitTag       = Get-GitLatestVTag -WorkDir $RepoRoot
$hcHasVersion       = Test-HarnessConfigHasVersionField -Path $harnessConfigPath
$hcExHasVersion     = Test-HarnessConfigHasVersionField -Path $harnessConfigExPath

# --- 汇总输出 ---
$rows = @()
$rows += [pscustomobject]@{ Source = "pom.xml";                    Value = if ($pomVersion)   { $pomVersion }   else { "<missing>" } }
$rows += [pscustomobject]@{ Source = "dependency-reduced-pom.xml"; Value = if ($reducedVersion){ $reducedVersion } else { "<missing or absent>" } }
$rows += [pscustomobject]@{ Source = "package.json";               Value = if ($pkgVersion)   { $pkgVersion }   else { "<no version field>" } }
$rows += [pscustomobject]@{ Source = "CHANGELOG.md [Unreleased]";  Value = if ($unreleasedPresent) { "present" } else { "MISSING" } }
$rows += [pscustomobject]@{ Source = "CHANGELOG.md latest released"; Value = if ($latestReleased.version) { ("{0} ({1})" -f $latestReleased.version, $latestReleased.date) } else { "<none>" } }
$rows += [pscustomobject]@{ Source = "git tag latest v*";          Value = if ($latestGitTag) { $latestGitTag } else { "<none>" } }
$rows += [pscustomobject]@{ Source = "harness-config.yml version:"; Value = if ($hcHasVersion)  { "PRESENT (shadow source)" } else { "absent (good)" } }
$rows += [pscustomobject]@{ Source = "harness-config.example.yml version:"; Value = if ($hcExHasVersion) { "PRESENT (shadow source)" } else { "absent (good)" } }

if (-not $Quiet) {
    Write-Host ""
    Write-Host "[Verify-HarnessReleaseVersion] multi-source version check"
    Write-Host ("RepoRoot: {0}" -f $RepoRoot)
    Write-Host ("Reference source: pom.xml <version> = {0}" -f $pomVersion)
    Write-Host ""
    $maxSourceLen = ($rows | ForEach-Object { $_.Source.Length } | Measure-Object -Maximum).Maximum
    foreach ($r in $rows) {
        $pad = " " * ($maxSourceLen - $r.Source.Length)
        Write-Host ("  {0}{1}  =  {2}" -f $r.Source, $pad, $r.Value)
    }
    Write-Host ""
}

$failures = @()

if (-not $pomVersion) {
    $failures += "pom.xml missing or has no top-level <version>."
}
if ($pomVersion -and -not (Test-SemVerLiteral -Literal $pomVersion)) {
    $failures += ("pom.xml version '{0}' is not a valid SemVer literal." -f $pomVersion)
}
if ($reducedVersion -and $pomVersion -and ($reducedVersion -ne $pomVersion)) {
    $failures += ("dependency-reduced-pom.xml version '{0}' != pom.xml '{1}'." -f $reducedVersion, $pomVersion)
}
if ($pkgVersion -and $pomVersion -and ($pkgVersion -ne $pomVersion)) {
    $failures += ("package.json version '{0}' != pom.xml '{1}'." -f $pkgVersion, $pomVersion)
}
if (-not $unreleasedPresent) {
    $failures += "CHANGELOG.md has no '## [Unreleased]' section."
}
if ($latestGitTag -and $latestReleased.version) {
    $tagWithoutV = $latestGitTag -replace '^v', ''
    if ($tagWithoutV -ne $latestReleased.version) {
        $failures += ("git tag '{0}' does not match CHANGELOG.md latest released '{1}'." -f $latestGitTag, $latestReleased.version)
    }
}
if ($hcHasVersion) {
    $failures += "harness-config.yml has a top-level 'version:' field; remove it to keep a single source of truth (pom.xml)."
}
if ($hcExHasVersion) {
    $failures += "harness-config.example.yml has a top-level 'version:' field; remove it to keep a single source of truth (pom.xml)."
}

if ($failures.Count -gt 0) {
    Write-Host ("[Verify-HarnessReleaseVersion] FAIL ({0} issue{1}):" -f $failures.Count, $(if ($failures.Count -gt 1) { "s" } else { "" }))
    foreach ($f in $failures) {
        Write-Host ("  - {0}" -f $f)
    }
    Write-Host ""
    Write-Host "Do not tag until these are resolved. This script does not auto-edit any file."
    exit 1
}

if (-not $Quiet) {
    Write-Host "[Verify-HarnessReleaseVersion] PASS - release version metadata is consistent."
    Write-Host ""
}
exit 0