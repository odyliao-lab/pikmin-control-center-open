$ErrorActionPreference = 'Stop'
$src = Join-Path (Split-Path $PSScriptRoot -Parent) 'src/dev/ody/pikmincontrol'
$farm = Get-Content -LiteralPath (Join-Path $src 'FlowerFarmService.java') -Raw
$store = Get-Content -LiteralPath (Join-Path $src 'FarmReports.java') -Raw
$ui = Get-Content -LiteralPath (Join-Path $src 'MainActivity.java') -Raw
$estimate = Get-Content -LiteralPath (Join-Path $src 'FlowerFarmEstimate.java') -Raw
$checks = @(
    @(($farm.IndexOf('runReport.openWindow(') -lt $farm.IndexOf('DispatchProbeGateway.setFarmArmed(true)')), 'attribution window saved before arming'),
    @(($farm -match '(?s)finally\s*\{\s*DispatchProbeGateway.setFarmArmed\(false\);\s*runReport.closeWindow'), 'window closes after stopping farm dispatch'),
    @(($farm.IndexOf('settleWithPlantingCheck();') -lt $farm.IndexOf('collectReport();')), 'statistics collected after existing dwell and settle'),
    @(($farm.Contains('RESULT_SETTLE_MS = FlowerFarmEstimate.RESULT_SETTLE_MS') -and $estimate.Contains('RESULT_SETTLE_MS = 5_000L')), 'preview and runner share unchanged five-second result wait'),
    @($farm.Contains('currentPoint + "\n" + reportCounts'), 'overlay includes receipt counts'),
    @($farm.Contains('manager.notify(156,'), 'distinct completion notification'),
    @($store.Contains('new AtomicFile('), 'atomic report persistence'),
    @($store.Contains('for(int i=20;'), 'bounded generated report retention'),
    @($store.Contains('r.ended>0'), 'only finished report refreshed out of service'),
    @($store.Contains('BoundedCommand.root(cmd,2,524288)'), 'bounded read-only history snapshot'),
    @((!$store.Contains('setFarmArmed') -and !$store.Contains('flyTo(')), 'report refresh never sends gameplay controls'),
    @($ui.Contains('showFlowerReport(true)'), 'result report is reachable from UI')
)
foreach ($check in $checks) { if (!$check[0]) { throw "FAIL: $($check[1])" }; Write-Output "PASS: $($check[1])" }
