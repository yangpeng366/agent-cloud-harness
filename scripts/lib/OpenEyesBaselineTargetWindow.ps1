# OpenEyesBaselineTargetWindow.ps1
#
# Draws the isolated WinForms window that the FEAT-05 acceptance legs assert on.
# Launched by scripts/Start-OpenEyesBaselineTarget.ps1 inside a Windows PowerShell
# (STA) child process so the window stays alive independently of the caller.
#
# Stable UIA surface (kept intentionally small so element counts are predictable):
#   window name       : -Title (default ACH-OpenEyes-Baseline-Target)
#   StatusLabel       (Text)
#   HintLabel         (Text)
#   PromptInput       (Edit)
#   RunButton         (Button)
#   CancelButton      (Button)
#   StrictModeCheck   (CheckBox)
#   ModeCombo         (ComboBox)
#   ResultList        (List)

param(
    [string]$Title = 'ACH-OpenEyes-Baseline-Target',
    [int]$LifetimeSeconds = 0
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$form = New-Object System.Windows.Forms.Form
$form.Text = $Title
$form.Width = 560
$form.Height = 460
$form.StartPosition = 'Manual'
$form.Left = 60
$form.Top = 60

$status = New-Object System.Windows.Forms.Label
$status.Name = 'StatusLabel'
$status.Text = 'ACH FEAT-05 baseline target'
$status.Left = 16
$status.Top = 16
$status.Width = 500
$form.Controls.Add($status)

$hint = New-Object System.Windows.Forms.Label
$hint.Name = 'HintLabel'
$hint.Text = 'Deterministic window for structured UI assertion / screenshot diff'
$hint.Left = 16
$hint.Top = 42
$hint.Width = 500
$form.Controls.Add($hint)

$prompt = New-Object System.Windows.Forms.TextBox
$prompt.Name = 'PromptInput'
$prompt.Text = 'baseline prompt'
$prompt.Left = 16
$prompt.Top = 72
$prompt.Width = 500
$form.Controls.Add($prompt)

$run = New-Object System.Windows.Forms.Button
$run.Name = 'RunButton'
$run.Text = 'Run'
$run.Left = 16
$run.Top = 108
$run.Width = 110
$form.Controls.Add($run)

$cancel = New-Object System.Windows.Forms.Button
$cancel.Name = 'CancelButton'
$cancel.Text = 'Cancel'
$cancel.Left = 136
$cancel.Top = 108
$cancel.Width = 110
$form.Controls.Add($cancel)

$strict = New-Object System.Windows.Forms.CheckBox
$strict.Name = 'StrictModeCheck'
$strict.Text = 'Strict mode'
$strict.Left = 264
$strict.Top = 112
$strict.Width = 200
$form.Controls.Add($strict)

$mode = New-Object System.Windows.Forms.ComboBox
$mode.Name = 'ModeCombo'
$mode.Left = 16
$mode.Top = 148
$mode.Width = 230
[void]$mode.Items.Add('openeyes_structured')
[void]$mode.Items.Add('screenshot_diff')
$mode.SelectedIndex = 0
$form.Controls.Add($mode)

$results = New-Object System.Windows.Forms.ListBox
$results.Name = 'ResultList'
$results.Left = 16
$results.Top = 184
$results.Width = 500
$results.Height = 200
[void]$results.Items.Add('ready')
$form.Controls.Add($results)

if ($LifetimeSeconds -gt 0) {
    $timer = New-Object System.Windows.Forms.Timer
    $timer.Interval = [Math]::Max(1, $LifetimeSeconds) * 1000
    $timer.Add_Tick({ $form.Close() })
    $timer.Start()
}

[void]$form.ShowDialog()
