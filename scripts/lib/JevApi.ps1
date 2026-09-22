function Invoke-JevDecision {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Id,
        [string]$Source = 'powershel-lib',
        [string]$BaseUrl = 'https://api.typesafe.ai',
        [string]$ApiKey = $env:TYPESAFE_API_KEY,
        [int]$TimeoutMs = 5000,
        [string]$Model = 'jev-latest'
    )

    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        return [pscustomobject]@{
            error = 'api_key_missing'
            probability = $null
            action = $null
            duration_ms = $null
        }
    }

    $startedAt = [DateTimeOffset]::UtcNow
    try {
        Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

        $body = @{
            model = $Model
            state = @{ id = $Id; source = $Source }
            questions = @{
                $Id = @{
                    type = 'noul'
                    instructions = 'Is this content relevant and concrete enough to keep verbatim in context? content: ' + $Text
                }
            }
        } | ConvertTo-Json -Depth 6 -Compress

        $uri = "$BaseUrl/v1/systemone"
        $request = [System.Net.HttpWebRequest]::Create($uri)
        $request.Method = 'POST'
        $request.ContentType = 'application/json'
        $request.Headers['Authorization'] = "Bearer $ApiKey"
        $request.Timeout = $TimeoutMs

        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Close()

        $response = $request.GetResponse()
        $reader = New-Object System.IO.StreamReader $response.GetResponseStream()
        $responseBody = $reader.ReadToEnd()
        $reader.Close()
        $response.Close()

        $endedAt = [DateTimeOffset]::UtcNow
        $durationMs = [int]($endedAt - $startedAt).TotalMilliseconds

        $parsed = $responseBody | ConvertFrom-Json
        $answers = $parsed.answers
        if (-not $answers) {
            return [pscustomobject]@{
                error = 'answers_missing'
                probability = $null
                action = $null
                duration_ms = $durationMs
            }
        }
        $firstAnswer = ($answers.PSObject.Properties | Select-Object -First 1).Value
        $probability = [double]$firstAnswer.noul
        $action = if ($probability -ge 0.5) { 'KEEP_VERBATIM' } else { 'TRUNCATE_HEAD' }

        return [pscustomobject]@{
            error = $null
            probability = $probability
            action = $action
            duration_ms = $durationMs
        }
    } catch {
        $endedAt = [DateTimeOffset]::UtcNow
        $durationMs = [int]($endedAt - $startedAt).TotalMilliseconds
        return [pscustomobject]@{
            error = $_.Exception.Message
            probability = $null
            action = $null
            duration_ms = $durationMs
        }
    }
}