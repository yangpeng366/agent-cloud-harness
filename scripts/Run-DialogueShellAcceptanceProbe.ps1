param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

function Invoke-Text {
    param([string]$Path)

    $tempFile = [System.IO.Path]::GetTempFileName()
    try {
        $headers = & curl.exe -sS -D - "$BaseUrl$Path" -o $tempFile
        if ($LASTEXITCODE -ne 0) {
            throw "curl request failed for $Path"
        }
        $body = Get-Content -LiteralPath $tempFile -Raw
        $headerLines = @($headers -split "`r?`n") | Where-Object { $_ -ne "" }
        $statusLine = $headerLines | Where-Object { $_ -match '^HTTP/' } | Select-Object -Last 1
        $contentTypeHeader = $headerLines | Where-Object { $_ -match '^Content-Type:' } | Select-Object -Last 1
        $locationHeader = $headerLines | Where-Object { $_ -match '^Location:' } | Select-Object -Last 1
        $statusCode = if ($statusLine -match '^HTTP/\S+\s+(\d{3})') { [int]$matches[1] } else { 0 }
        $contentType = if ($contentTypeHeader) { ($contentTypeHeader -replace '^Content-Type:\s*', '') } else { $null }
        $location = if ($locationHeader) { ($locationHeader -replace '^Location:\s*', '') } else { $null }
    } finally {
        Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
    }

    [pscustomobject]@{
        StatusCode = $statusCode
        ContentType = $contentType
        Content = $body
        Location = $location
    }
}

function Invoke-TextAllowRedirect {
    param([string[]]$Paths)

    $attempts = @()
    foreach ($path in $Paths) {
        try {
            $response = Invoke-Text -Path $path
            if ($response.StatusCode -eq 200) {
                return $response
            }
            if ($response.StatusCode -eq 302 -and $response.Location) {
                return Invoke-Text -Path $response.Location
            }
            $attempts += [pscustomobject]@{
                path = $path
                status = $response.StatusCode
                content_type = $response.ContentType
                note = "non-200 direct response"
            }
        } catch {
            $attempts += [pscustomobject]@{
                path = $path
                note = $_.Exception.Message
            }
        }
    }

    $summary = $attempts | ConvertTo-Json -Depth 6 -Compress
    throw "none of the candidate dialogue shell paths returned a usable response. attempts=$summary"
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Snippet-Around {
    param(
        [string]$Content,
        [string]$Anchor
    )

    if ([string]::IsNullOrEmpty($Content)) {
        return ""
    }
    $index = if ([string]::IsNullOrEmpty($Anchor)) { -1 } else { $Content.IndexOf($Anchor) }
    if ($index -lt 0) {
        return if ($Content.Length -gt 220) { $Content.Substring(0, 220) } else { $Content }
    }
    $start = [Math]::Max(0, $index - 120)
    $length = [Math]::Min(320, $Content.Length - $start)
    return $Content.Substring($start, $length)
}

$dialogueResponse = Invoke-TextAllowRedirect -Paths @("/dialogue/", "/dialogue")
$dialogueBody = $dialogueResponse.Content
$dialogueContentType = $dialogueResponse.ContentType

Assert-True -Condition ($dialogueResponse.StatusCode -eq 200) -Message "/dialogue/ did not return 200"
Assert-True -Condition ($dialogueContentType -like "text/html*") -Message "/dialogue/ did not return HTML content"
Assert-True -Condition ($dialogueBody.Contains("Session Transcript")) -Message "/dialogue/ shell is missing Session Transcript"
Assert-True -Condition ($dialogueBody.Contains('id="detailsToggleButton"')) -Message ("/dialogue/ shell is missing task details toggle. snippet=" + (Snippet-Around -Content $dialogueBody -Anchor "Session Transcript"))
Assert-True -Condition ($dialogueBody.Contains('data-composer-mode="auto"')) -Message "/dialogue/ shell is missing auto composer mode"
Assert-True -Condition ($dialogueBody.Contains('data-composer-mode="task"')) -Message "/dialogue/ shell is missing task composer mode"
Assert-True -Condition (-not $dialogueBody.Contains('data-composer-mode="followup"')) -Message "/dialogue/ shell still exposes followup as a primary mode"

$appJsResponse = Invoke-Text -Path "/dialogue/app.js"
$appJsBody = $appJsResponse.Content
$appJsContentType = $appJsResponse.ContentType

Assert-True -Condition ($appJsResponse.StatusCode -eq 200) -Message "/dialogue/app.js did not return 200"
Assert-True -Condition ($appJsContentType -like "application/javascript*") -Message "/dialogue/app.js did not return JavaScript content"
Assert-True -Condition ($appJsBody.Contains("Session Transcript")) -Message "/dialogue/app.js is missing Session Transcript label"
Assert-True -Condition ($appJsBody.Contains("messageHint")) -Message "/dialogue/app.js is missing messageHint wiring"

[pscustomobject]@{
    shell = "dialogue"
    base_url = $BaseUrl
    html_status = $dialogueResponse.StatusCode
    js_status = $appJsResponse.StatusCode
    transcript_first = $true
    details_toggle_present = $true
    primary_composer_modes = @("auto", "task")
    followup_mode_hidden = $true
} | ConvertTo-Json -Depth 5
