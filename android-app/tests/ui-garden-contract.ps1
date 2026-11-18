$ErrorActionPreference = 'Stop'
$app = Split-Path $PSScriptRoot -Parent
$main = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $app 'src/dev/ody/pikmincontrol/MainActivity.java')
$count = 0
function Require([bool]$ok, [string]$label) {
    if (!$ok) { throw "FAIL: $label" }
    $script:count++
    Write-Output "PASS: $label"
}
Require ($main.Contains('overviewPlanterDetail.setText(UiSummary.planterDetail(p))')) 'overview nursery detail comes from parsed inventory'
Require ($main.Contains('flowerFarmEditor.setVisibility(ui.showEditor') -and $main.Contains('flowerFarmPoints.setEnabled(ui.canEdit)')) 'running editor is hidden and disabled'
Require ($main.Contains('flowerFarmEditActions.setVisibility(ui.showEditor') -and $main.Contains('flowerFarmDwellBox.setVisibility(ui.showEditor')) 'running edit controls hidden together'
Require ($main.Contains('setButtonEnabled(flowerFarmRecover, ui.canRecover)')) 'recovery CTA is not an unconditional bypass'
Require ($main.Contains('FarmUi latest = farmPresentation()') -and $main.Contains('recover ? latest.canRecover : latest.canStart')) 'confirmation rechecks current work gates'
Require ($main.Contains('if (!farmPresentation().canEdit) return;')) 'clear and restore enforce edit gate'
Require ($main.Contains('dialog.setOnDismissListener') -and ([regex]::Matches($main, 'dismissFlowerFarmConfirmation\(\);')).Count -eq 2) 'confirmation dismisses on navigation and destruction'
Require ($main.Contains('if (flowerFarmConfirmation != null && flowerFarmConfirmation.isShowing()) return;')) 'repeated click cannot stack confirmations'
Require ($main.Contains('使用啟動時的路線與停留設定')) 'live metadata does not pretend saved draft is active route'
Require ($main.Contains('SetupDeployment.pending(this)') -and $main.Contains('UpdateInstaller.pending(this)')) 'deployment and install gates retained'
Require ($main.Contains('compactDisclosure("工作紀錄與路線診斷", records)')) 'history and route diagnostics remain accessible under one disclosure'
Write-Output "PASS: $count garden UI source contracts (not device validation)"
