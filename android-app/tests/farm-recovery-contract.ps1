$ErrorActionPreference = 'Stop'
$src = Join-Path (Split-Path $PSScriptRoot -Parent) 'src/dev/ody/pikmincontrol'
$farm = Get-Content -Raw (Join-Path $src 'FlowerFarmService.java')
$ui = Get-Content -Raw (Join-Path $src 'MainActivity.java')
$locks = Get-Content -Raw (Join-Path $src 'GpsWorkLock.java')
$bridge = Get-Content -Raw (Join-Path $src 'PlantingControlGateway.java')
$recovery = $farm.Substring($farm.IndexOf('routeSession.open'), $farm.IndexOf('// A flower-field run') - $farm.IndexOf('routeSession.open'))
$checks = [ordered]@{
    'Consent per launch, default false' = $farm.Contains('intent.getBooleanExtra("recover", false)')
    'User confirmation wired to recovery flag' = $ui.Contains('startFlowerFarm(input.points, dwell, recover)') -and $ui.Contains('confirmFlowerFarmStart(true)')
    'No planting start or GPS movement during recovery' = !$recovery.Contains('setEnabled(true)') -and !$recovery.Contains('flyTo(')
    'Native sustained stop acknowledgement' = $recovery.Contains('new RouteState.StopGate()') -and $recovery.Contains('routeSession.requireStatus(true)')
    'Same PID and launch token checked' = $recovery.Contains('sessionPid,System.currentTimeMillis(),routeSession.token')
    'Fresh game and planting state required' = $farm.Contains('FarmStartup.reason(fix, planting')
    'Cancellation and deadline retained' = $recovery.Contains('while(!stopping && SystemClock.elapsedRealtime()<stopDeadline)')
    'Failed recovery cannot teleport' = $farm.IndexOf('if(!recoveryConfirmed)throw') -lt $farm.IndexOf('GpsModuleGateway.flyTo') -and $recovery.Contains('throw new IllegalStateException')
    'Other feature and global stop blocks retained' = $locks.Contains('recovery ? otherStop : WorkStatus.blocked(context)')
    'No stopping return nursery nectar during recovery' = !$recovery.Contains('ReturnRewardModuleGateway') -and !$recovery.Contains('PlanterGateway') -and !$recovery.Contains('NectarModuleGateway')
    'Bounded recovery writes' = $bridge.Contains('return GpsModuleGateway.farmRead(command)')
    'New run identity retained' = $farm.Contains('java.util.UUID.randomUUID().toString()')
}
foreach ($c in $checks.GetEnumerator()) { if (!$c.Value) { throw $c.Key } }
Write-Output ('PASS: ' + $checks.Count + ' farm recovery source contracts (not device acceptance)')
