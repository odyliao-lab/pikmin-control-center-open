$ErrorActionPreference='Stop'
$src=Join-Path (Split-Path $PSScriptRoot -Parent) 'cpp'
$engine=Get-Content "$src/planter_automation.inc" -Raw
$recovery=Get-Content "$src/planter_recovery.inc" -Raw
if($engine.Contains('restart_blocked')-or$engine.Contains('review-required')-or$recovery.Contains('recovery_request')){throw 'Manual recovery latch returned'}
if(!$engine.Contains('pending.watch.expiry(now)')-or!$engine.Contains('current_session(s)')){throw 'Missing lifetime/session admission'}
if($recovery.Contains('start(')-or$recovery.Contains('call(')){throw 'Archival must never replay a mutation'}
Write-Output 'PASS continuous planter source boundary; production behavior covered by planter_continuous_test'
