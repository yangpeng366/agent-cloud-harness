# Test-JevPatrolL3Extract.ps1
$ErrorActionPreference = 'Stop'
$libPath = Join-Path $PSScriptRoot '..\scripts\lib\JevPatrolL3Extract.ps1'
if (-not (Test-Path -LiteralPath $libPath)) { throw "Lib not found: $libPath" }
. $libPath
$pass = 0
$fail = 0
function Assert-Eq { param($Expected, $Actual, [string]$Label); if ($Expected -eq $Actual) { Write-Host "[OK]  $Label"; $script:pass++ } else { Write-Host "[FAIL] $Label (expected='$Expected' actual='$Actual')"; $script:fail++ } }
function Assert-True { param([bool]$Condition, [string]$Label); if ($Condition) { Write-Host "[OK]  $Label"; $script:pass++ } else { Write-Host "[FAIL] $Label"; $script:fail++ } }
Assert-Eq 'agent-cloud-harness' (Resolve-JevPatrolL3Project -FileName 'patrol-last-agent-cloud-harness-20260915-214139' -Content '') 'project from filename'
Assert-Eq 'unknown' (Resolve-JevPatrolL3Project -FileName 'patrol-last-20260920-183035' -Content '') 'unknown when filename only has stamp'
Assert-Eq 'my-project' (Resolve-JevPatrolL3Project -FileName 'random-name' -Content '> 项目: my-project') 'project from content explicit field'
$projTitleContent = "# agent-cloud-harness 巡检留痕" + "`n" + "> foo: bar"
Assert-Eq 'agent-cloud-harness' (Resolve-JevPatrolL3Project -FileName 'random-name' -Content $projTitleContent) 'project from content title'
Assert-Eq 'unknown' (Resolve-JevPatrolL3Project -FileName 'random-name' -Content 'no project hint anywhere') 'unknown when no hint'
Assert-Eq '20260915-214139' (Resolve-JevPatrolL3Stamp -FileName 'patrol-last-agent-cloud-harness-20260915-214139') 'stamp from project+stamp filename'
Assert-Eq '20260920-183035' (Resolve-JevPatrolL3Stamp -FileName 'patrol-last-20260920-183035') 'stamp from stamp-only filename'
Assert-True ((Resolve-JevPatrolL3Stamp -FileName 'random-name') -match '^\d{8}-\d{6}$') 'stamp default is timestamp'
$empty = Build-JevPatrolL3Decisions -Content ''
Assert-Eq 0 $empty.Count 'empty content -> 0 decisions'
$progContent = "## 本轮推进" + "`n" + "`n" + "- master HEAD = 633110c, fix merged into main branch"
$prog = Build-JevPatrolL3Decisions -Content $progContent
Assert-Eq 1 $prog.Count 'single progress line'
Assert-Eq 'progress' $prog[0].category 'progress category'
Assert-Eq 'medium' $prog[0].priority 'progress medium priority'
$blkContent = "## 本轮推进" + "`n" + "`n" + "- state status=blocked persists since last round"
$blk = Build-JevPatrolL3Decisions -Content $blkContent
Assert-Eq 'blocker' $blk[0].category 'blocker English'
Assert-Eq 'high' $blk[0].priority 'blocker high priority'
$blk2Content = "## 本轮推进" + "`n" + "`n" + "- 历史 status=阻塞 需要持续修复"
$blk2 = Build-JevPatrolL3Decisions -Content $blk2Content
Assert-Eq 'blocker' $blk2[0].category 'blocker Chinese'
$nxtContent = "## 下一步" + "`n" + "`n" + "- 评估新增 opm-interview-processor 数字员工"
$nxt = Build-JevPatrolL3Decisions -Content $nxtContent
Assert-Eq 'next_step' $nxt[0].category 'next_step section'
Assert-Eq 'medium' $nxt[0].priority 'next_step medium priority'
$obsContent = "## 本轮推进" + "`n" + "`n" + "- isolate commit sha abc1234 noted for traceability"
$obs = Build-JevPatrolL3Decisions -Content $obsContent
Assert-Eq 'observation' $obs[0].category 'observation no keyword'
Assert-Eq 'low' $obs[0].priority 'observation low priority'
$metaContent = "## 本轮推进" + "`n" + "`n" + "> some meta comment to skip" + "`n" + "| table row to skip" + "`n" + "--- hr line to skip" + "`n" + "- actual content line worth keeping"
$meta = Build-JevPatrolL3Decisions -Content $metaContent
Assert-Eq 1 $meta.Count 'metadata prefixes skipped'
Assert-Eq 'observation' $meta[0].category 'only content line classified'
$multiContent = "## 本轮推进" + "`n" + "`n" + "- first line worth keeping" + "`n" + "- second line worth keeping" + "`n" + "- third line worth keeping" + "`n" + "- fourth line worth keeping"
$multi = Build-JevPatrolL3Decisions -Content $multiContent
Assert-Eq 4 $multi.Count 'four lines -> 4 decisions'
Assert-Eq 'kd-001' $multi[0].id 'first id'
Assert-Eq 'kd-004' $multi[3].id 'fourth id'
$cap = Build-JevPatrolL3Decisions -Content $multiContent -MaxDecisions 2
Assert-Eq 2 $cap.Count 'MaxDecisions cap'
$longLine = "## 本轮推进" + "`n" + "- " + ('x' * 200)
$trunc = Build-JevPatrolL3Decisions -Content $longLine -MaxSummaryLength 30
Assert-True ($trunc[0].summary.Length -le 30) 'summary truncated'
Assert-True ($trunc[0].summary.EndsWith('...')) 'truncated ends with ...'
$sectContent = "# top header" + "`n" + "`n" + "## 本轮推进" + "`n" + "`n" + "- line in 推进 section" + "`n" + "`n" + "## 下一步" + "`n" + "`n" + "- line in 下一步 section"
$sect = Build-JevPatrolL3Decisions -Content $sectContent
Assert-Eq 2 $sect.Count 'two sections classified'
Assert-Eq 'observation' $sect[0].category 'first section -> observation'
Assert-Eq '本轮推进' $sect[0].evidence 'evidence tracks section'
Assert-Eq 'next_step' $sect[1].category 'second section -> next_step'
Assert-Eq '下一步' $sect[1].evidence 'evidence tracks second section'
Write-Host ""
Write-Host "=== Test-JevPatrolL3Extract summary: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
