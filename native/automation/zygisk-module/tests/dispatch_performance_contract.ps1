$ErrorActionPreference='Stop'
$source=Get-Content (Join-Path $PSScriptRoot '../cpp/nectar_rpc.cpp') -Raw
foreach($name in @('append_dispatch_history','append_dispatch_selection_diagnostics','append_return_history','append_return_trace')) {
    $body=[regex]::Match($source,"(?s)void $name\([^;{]+\)\s*\{.*?\n\}").Value
    if(!$body.Contains('writer->append') -or $body -match 'std::(ifstream|ofstream|fopen|rename)') {
        throw "$name must enqueue owned text, not perform synchronous history IO"
    }
}
$scheduler=[regex]::Match($source,'(?s)void maybe_return_tasks\(\) \{.*?\n\}').Value
if($scheduler.IndexOf('dry_run_return_tasks();') -lt 0 -or
   $scheduler.IndexOf('dry_run_return_tasks();') -gt $scheduler.IndexOf('last_return_scheduler_ms < 1000') -or
   !$scheduler.Contains('current-last_projection_tick>=250')) {throw 'Dispatch inherited slow mutation scheduler'}
if(!$source.Contains('nearby_work.capacity(steady_ms())') -or !$source.Contains('++candidates;') -or
   !$source.Contains('nearby_work.prune(live_task_ids)')) {throw 'Missing bounded complete projection contract'}
Write-Output 'PASS dispatch async-history and independent bounded-scheduler wiring; NOT game performance acceptance'
