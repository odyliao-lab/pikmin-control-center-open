$ErrorActionPreference='Stop'
$src=Join-Path (Split-Path $PSScriptRoot -Parent) 'src/dev/ody/pikmincontrol'
$ui=Get-Content "$src/MainActivity.java" -Raw
$settings=Get-Content "$src/PlanterSettings.java" -Raw
foreach($obsolete in @('confirmPlanterRecovery(', 'PlanterManualRecoveryGateway.run(', 'PlanterRetirementGateway.run(', 'PlanterReportRepair.run(')){
 if($ui.Contains($obsolete)){throw "Obsolete manual recovery entry: $obsolete"}
}
if($settings.Contains('PlanterGateway.recover(')-or$settings.Contains('if(reportRepairPaused)')-or$settings.Contains('if(manualRecoveryBusy)')){throw 'Legacy recovery gates continuous mode'}
if($settings.IndexOf('PlanterGateway.setMode(wanted)')-gt$settings.IndexOf('PlanterGateway.read()')){throw 'Report failure prevents mode propagation'}
foreach($guard in @('CoreUpdater.requireFiles(c,CoreUpdater.pair(c))','CoreInstallWizard.preparing(c)','WorkStatus.stoppingAll')){
 if(!$settings.Contains($guard)){throw "Missing explicit stop/install guard: $guard"}
}
if(!$ui.Contains('if(afterStopped==null)PlanterSettings.choose(this,"off")')){throw 'Explicit stop must persist OFF'}
Write-Output 'PASS continuous planter UI admission; real reconciliation exercised by JVM fixture'
