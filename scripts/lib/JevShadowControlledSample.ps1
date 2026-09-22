function New-ControlledSampleProject {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Title,
        [ValidateSet('positive','negative')]
        [string]$GroundTruth = 'positive',
        [ValidateSet('P0','P1','P2')]
        [string]$Priority = 'P1',
        [int]$IntervalMin = 0,
        [string]$NextStep = 'auto-shadow controlled sample'
    )
    return [pscustomobject]@{
        id = $Id
        title = $Title
        repoPath = ''
        description = "controlled sample; ground_truth=$GroundTruth"
        nextStep = $NextStep
        priority = $Priority
        intervalMin = $IntervalMin
        threadId = $null
        fake = $false
        lastRun = $null
        currentStage = ''
        achievement = ''
        ground_truth = $GroundTruth
    }
}

function New-ControlledSampleTasks {
    param(
        [Parameter(Mandatory)][string]$OutputPath,
        [int]$PositiveCount = 6,
        [int]$NegativeCount = 3
    )
    $items = @()
    $i = 0
    while ($i -lt $PositiveCount) {
        $items += New-ControlledSampleProject -Id ("ctl-pos-" + $i) -Title ("受控正样本-$i") -GroundTruth 'positive' -Priority (($i % 3) | ForEach-Object { @('P0','P1','P2')[$_] })
        $i++
    }
    $j = 0
    while ($j -lt $NegativeCount) {
        $items += New-ControlledSampleProject -Id ("ctl-neg-" + $j) -Title ("受控负样本-$j") -GroundTruth 'negative' -Priority (($j % 3) | ForEach-Object { @('P0','P1','P2')[$_] })
        $j++
    }
    $json = $items | ConvertTo-Json -Depth 6 -AsArray
    [System.IO.File]::WriteAllText($OutputPath, $json, [System.Text.UTF8Encoding]::new($false))
    return ,$items
}