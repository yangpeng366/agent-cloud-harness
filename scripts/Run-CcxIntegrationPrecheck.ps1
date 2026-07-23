<#
.SYNOPSIS
    CCX 端到端集成 precheck：验证 CCX 网关可用性、模型列表、provider auth。
.DESCRIPTION
    P2 端到端集成验证的前置检查。验证 CCX 网关即可用于 harness 调度 worker。
    检查三个层级：健康、模型、completion 请求。
    ProxyKey 优先从 codex config.toml 的 [model_providers.ccx].experimental_bearer_token 读取，
    其次从 ccx .env 的 PROXY_ACCESS_KEY 读取。
.PARAMETER CcxUrl
    CCX 网关地址，默认 http://127.0.0.1:3688
.PARAMETER AdminKey
    CCX Admin Key（仅用于查看渠道状态）
.PARAMETER ProxyKey
    CCX Proxy Access Key，用于 /v1/models 和 completion 请求
.PARAMETER TestModel
    测试用模型名，默认 codex（CCX 网关内部路由名）
.PARAMETER SkipCompletionTest
    跳过 completion 请求测试（只验证健康和模型列表）
.EXAMPLE
    .\Run-CcxIntegrationPrecheck.ps1
.EXAMPLE
    .\Run-CcxIntegrationPrecheck.ps1 -CcxUrl http://127.0.0.1:3688 -TestModel codex
#>
param(
    [string]$CcxUrl = "http://127.0.0.1:3688",
    [string]$AdminKey,
    [string]$ProxyKey,
    [string]$TestModel = "codex",
    [switch]$SkipCompletionTest
)

$ErrorActionPreference = "Stop"

# Try to read proxy key from codex config.toml first, then ccx .env
if (-not $ProxyKey) {
    $configToml = Join-Path $env:USERPROFILE ".codex\config.toml"
    if (Test-Path $configToml) {
        $cfgContent = Get-Content $configToml -Raw
        $match = [regex]::Match($cfgContent, 'experimental_bearer_token\s*=\s*"([^"]+)"')
        if ($match.Success) {
            # Find the ccx provider section
            $ccxSection = [regex]::Match($cfgContent, 'model_providers\.ccx\][\s\S]*?experimental_bearer_token\s*=\s*"([^"]+)"')
            if ($ccxSection.Success) {
                $ProxyKey = $ccxSection.Groups[1].Value.Trim()
            }
        }
    }
}
if (-not $ProxyKey) {
    $envFile = Join-Path $env:USERPROFILE ".codex\ccx\.env"
    if (Test-Path $envFile) {
        $envContent = Get-Content $envFile -Raw
        $match = [regex]::Match($envContent, 'PROXY_ACCESS_KEY\s*=\s*(.+)')
        if ($match.Success) { $ProxyKey = $match.Groups[1].Value.Trim() }
    }
}

# Try to read admin key from ccx .env
if (-not $AdminKey) {
    $envFile = Join-Path $env:USERPROFILE ".codex\ccx\.env"
    if (Test-Path $envFile) {
        $envContent = Get-Content $envFile -Raw
        $match = [regex]::Match($envContent, 'ADMIN_ACCESS_KEY\s*=\s*(.+)')
        if ($match.Success) { $AdminKey = $match.Groups[1].Value.Trim() }
    }
}

$results = [ordered]@{}
$allPass = $true

# 1. Health check
Write-Host "`n[1/3] CCX Health Check..." -ForegroundColor Cyan
try {
    $health = Invoke-RestMethod -Uri "$CcxUrl/health" -Method GET -TimeoutSec 10
    $upstreamCount = $health.config.upstreamCount
    $results["health"] = "PASS"
    Write-Host "  PASS: CCX healthy at $CcxUrl (v$($health.version.version), uptime=$([math]::Round($health.uptime, 0))s)" -ForegroundColor Green
    if ($upstreamCount -eq 0) {
        Write-Host "  WARN: upstreamCount=0, completion may fail if no channel routes the model" -ForegroundColor Yellow
    }
} catch {
    $results["health"] = "FAIL: $($_.Exception.Message)"
    $allPass = $false
    Write-Host "  FAIL: CCX not reachable at $CcxUrl - $($_.Exception.Message)" -ForegroundColor Red
}

# 2. Model list
Write-Host "`n[2/3] CCX Model List..." -ForegroundColor Cyan
if ($ProxyKey) {
    try {
        $models = Invoke-RestMethod -Uri "$CcxUrl/v1/models" -Method GET -Headers @{ "Authorization" = "Bearer $ProxyKey" } -TimeoutSec 10
        $modelCount = 0
        if ($models.data) { $modelCount = @($models.data).Count }
        if ($modelCount -gt 0) {
            $results["models"] = "PASS ($modelCount models)"
            Write-Host "  PASS: $modelCount models available" -ForegroundColor Green
            $testModelAvailable = $false
            foreach ($m in $models.data) {
                if ($m.id -eq $TestModel) { $testModelAvailable = $true; break }
            }
            if ($testModelAvailable) {
                Write-Host "  Test model '$TestModel' is available" -ForegroundColor Green
                $results["test_model"] = "PASS"
            } else {
                Write-Host "  WARN: Test model '$TestModel' not in exact model list (CCX may route it)" -ForegroundColor Yellow
                $results["test_model"] = "WARN: not in exact list"
            }
        } else {
            $results["models"] = "FAIL: empty model list"
            $allPass = $false
            Write-Host "  FAIL: No models returned" -ForegroundColor Red
        }
    } catch {
        $results["models"] = "FAIL: $($_.Exception.Message)"
        $allPass = $false
        Write-Host "  FAIL: Cannot list models - $($_.Exception.Message)" -ForegroundColor Red
    }
} else {
    $results["models"] = "SKIP: no proxy key"
    Write-Host "  SKIP: No ProxyKey available" -ForegroundColor Yellow
}

# 3. Completion test
if (-not $SkipCompletionTest -and $ProxyKey -and ($results["models"] -like "PASS*")) {
    Write-Host "`n[3/3] CCX Completion Test..." -ForegroundColor Cyan
    try {
        $body = @{
            model = $TestModel
            messages = @(@{ role = "user"; content = "Say 'precheck ok' and nothing else." })
            max_tokens = 50
            stream = $false
        } | ConvertTo-Json -Depth 5

        $response = Invoke-RestMethod -Uri "$CcxUrl/v1/chat/completions" -Method POST -Headers @{ "Authorization" = "Bearer $ProxyKey" } -ContentType "application/json" -Body $body -TimeoutSec 30
        if ($response.choices -and $response.choices.Count -gt 0) {
            $results["completion"] = "PASS"
            $allPass = $true
            Write-Host "  PASS: Completion request succeeded (model=$($response.model))" -ForegroundColor Green
            $content = $response.choices[0].message.content
            Write-Host "  Response: $content" -ForegroundColor Gray
        } else {
            $results["completion"] = "FAIL: no choices in response"
            $allPass = $false
            Write-Host "  FAIL: No choices in response" -ForegroundColor Red
        }
    } catch {
        $results["completion"] = "FAIL: $($_.Exception.Message)"
        $allPass = $false
        Write-Host "  FAIL: Completion request failed - $($_.Exception.Message)" -ForegroundColor Red
    }
} else {
    Write-Host "`n[3/3] CCX Completion Test (skipped)" -ForegroundColor Yellow
    $results["completion"] = "SKIP"
}

# Summary
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "CCX Integration Precheck Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
foreach ($key in $results.Keys) {
    $val = $results[$key]
    $color = if ($val -like "PASS*") { "Green" } elseif ($val -like "SKIP*" -or $val -like "WARN*") { "Yellow" } else { "Red" }
    if ($val -notlike "PASS*" -and $val -notlike "SKIP*" -and $val -notlike "WARN*") { $allPass = $false }
    Write-Host "  ${key}: ${val}" -ForegroundColor $color
}

if ($allPass) {
    Write-Host "`nRESULT: ALL PASS - CCX is ready for harness integration`n" -ForegroundColor Green

    # Output structured result for downstream scripts
    $resultObj = [ordered]@{
        timestamp = (Get-Date -Format "o")
        ccx_url = $CcxUrl
        results = $results
        passed = $true
    }
    $resultObj | ConvertTo-Json | Write-Output
    exit 0
} else {
    Write-Host "`nRESULT: FAILURES DETECTED - CCX not fully ready`n" -ForegroundColor Red

    $resultObj = [ordered]@{
        timestamp = (Get-Date -Format "o")
        ccx_url = $CcxUrl
        results = $results
        passed = $false
    }
    $resultObj | ConvertTo-Json | Write-Output
    exit 1
}
