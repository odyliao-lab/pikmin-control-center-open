$ErrorActionPreference = 'Stop'
$app = Split-Path $PSScriptRoot -Parent
$main = Get-Content -Raw -LiteralPath (Join-Path $app 'src/dev/ody/pikmincontrol/MainActivity.java')
function Require([bool]$ok, [string]$label) {
    if (!$ok) { throw "FAIL: $label" }
    Write-Output "PASS: $label"
}
Require ($main.Contains('if (expeditionTab == 1) { flowerFarmPage(); return; }')) 'separate batch / farm subpages'
Require ($main.Contains('addCard(nearbyCard);')) 'nearby card remains attached'
Require ($main.Contains('batchType("花苗", 1)') -and $main.Contains('batchType("水果", 2)') -and $main.Contains('batchType("禮物盒", 4)')) 'batch kinds restored from saved UI mask'
Require ($main.Contains('acceptsScan(requestedGeneration, scanGeneration, requestedKey)')) 'old scan responses cannot replace changed conditions'
Require (!$main.Contains('number++ >= 100')) 'no silent 100-row cutoff'
Require (!$main.Contains('flowerFarmPoints.setText(input.normalized)')) 'confirmation does not overwrite raw draft'
Require ($main.Contains('if (flowerFarmPoints.length() > 0)')) 'undo does not overwrite new draft'
Require ($main.Contains('new FlowerFarmDraft(input)')) 'tested parser used by production confirmation'
$pause = [regex]::Match($main, '(?s)void onPause\(\).*?super.onPause\(\);').Value
Require ($pause.Contains('persistFlowerDraft();')) 'draft flushed on leaving activity'
$navigation = [regex]::Match($main, '(?s)void showPage\(int target\).*?page.removeAllViews\(\);').Value
Require ($navigation.Contains('persistFlowerDraft();')) 'draft flushed before view replacement'
Require ($main.Contains('final boolean readHistory = expandedSections.getBoolean')) 'collapsed diagnostics not eagerly reloaded'
Write-Output 'Static UI wiring checks only; device visual and lifecycle validation still required.'
