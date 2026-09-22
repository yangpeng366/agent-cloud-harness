[CmdletBinding(DefaultParameterSetName='InProcess')]
param(
    [Parameter(Mandatory = $true, ParameterSetName='InProcess')]$Item,
    [Parameter(Mandatory = $true, ParameterSetName='JsonPath')][string]$ItemJsonPath,
    [Parameter(Mandatory = $true)][string]$JevShadowConfigPath,
    [string]$JevDecisionConfigPath = '',
    [string]$DecisionLogPath = ''
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

if ($PSCmdlet.ParameterSetName -eq 'JsonPath') {
    if (-not (Test-Path -LiteralPath $ItemJsonPath)) { throw "ItemJsonPath not found: $ItemJsonPath" }
    $raw = Get-Content -LiteralPath $ItemJsonPath -Raw -Encoding UTF8
    $obj = $raw | ConvertFrom-Json
    $Item = [pscustomobject]@{
        id = [string]$obj.id
        title = [string]$obj.title
        repoPath = [string]$obj.repoPath
        description = [string]$obj.description
        nextStep = [string]$obj.nextStep
        priority = [string]$obj.priority
        intervalMin = [int]$obj.intervalMin
        threadId = [string]$obj.threadId
        fake = [bool]$obj.fake
    }
}

$decisionLib = Join-Path $PSScriptRoot 'JevDecision.ps1'
if (-not (Test-Path -LiteralPath $decisionLib)) { throw "JevDecision.ps1 not found at $decisionLib" }
. $decisionLib

$shadowLib = Join-Path $PSScriptRoot 'JevShadow.ps1'
$shadowDotSourced = $false
if (Test-Path -LiteralPath $shadowLib) { . $shadowLib; $shadowDotSourced = $true }

$shadowConfig = $null
if (Test-Path -LiteralPath $JevShadowConfigPath) {
    try { $shadowConfig = Get-Content -LiteralPath $JevShadowConfigPath -Raw | ConvertFrom-Json } catch {}
}
if (-not $shadowConfig) { $shadowConfig = [pscustomobject]@{ enabled = $false } }

if (-not $JevDecisionConfigPath -or -not (Test-Path -LiteralPath $JevDecisionConfigPath)) {
    $JevDecisionConfigPath = Join-Path $PSScriptRoot '..\config\jev-decision.json'
}
$decisionConfig = [pscustomobject]@{ decision = @{ enabled = $false; thresholds = @{ low = 0.30; high = 0.70 } } }
if (Test-Path -LiteralPath $JevDecisionConfigPath) {
    try { $decisionConfig = Get-Content -LiteralPath $JevDecisionConfigPath -Raw | ConvertFrom-Json } catch {}
}

$shadowDecision = [pscustomobject]@{ error = 'shadow_disabled'; probability = $null; action = $null; duration_ms = $null }
$shadowEnabled = $false
if ($shadowDotSourced -and (Get-Command Get-JevShadowEnabled -ErrorAction SilentlyContinue)) {
    $shadowEnabled = [bool](Get-JevShadowEnabled -Config $shadowConfig)
}
if ($shadowEnabled -and $shadowDotSourced) {
    try {
        $context = New-PatrolItemJevShadowContext -Item $Item -PromotionModel ''
        $shadowDecision = Invoke-PatrolItemJevShadow -Context $context -Config $shadowConfig
    } catch {
        $shadowDecision = [pscustomobject]@{
            error = $_.Exception.Message
            probability = $null
            action = $null
            duration_ms = $null
        }
    }
}

$decision = Resolve-JevItemDecision -Item $Item -ShadowDecision $shadowDecision -DecisionConfig $decisionConfig

$artifact = [ordered]@{
    schema_version = 1
    generated_at = [DateTimeOffset]::UtcNow.ToString('o')
    item_id = [string]$Item.id
    title = [string]$Item.title
    shadow = $shadowDecision
    decision = $decision
}

if ($DecisionLogPath) {
    $dir = Split-Path $DecisionLogPath -Parent
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($DecisionLogPath, ($artifact | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))
}

return [pscustomobject]@{
    item_id = [string]$Item.id
    decision = $decision
    shadow = $shadowDecision
    artifact_path = $DecisionLogPath
}