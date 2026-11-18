$ErrorActionPreference='Stop';$repo=Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot)))
foreach($path in @('native/automation/zygisk-module/cpp/nectar_rpc.cpp','targets/mumu/native/automation/nectar_rpc.cpp')) {
    $s=[IO.File]::ReadAllText((Join-Path $repo $path))
    $worker=$s.Substring($s.IndexOf('static pcc_projection::Worker &dispatch_projection_worker()'),$s.IndexOf('struct DispatchProjectionTiming')-$s.IndexOf('static pcc_projection::Worker &dispatch_projection_worker()'))
    foreach($bad in @('get_inventory','get_pikmin','start_expedition','il2cpp','void *','void*')){if($worker.Contains($bad)){throw "Managed operation/pointer in worker: $path / $bad"}}
    foreach($required in @('static pcc_projection::Worker worker','capability_registry.begin_scan()','capability_registry.commit_scan(s.token','std::fwrite','output.commit()')){if(!$worker.Contains($required)){throw "Missing worker boundary: $required"}}
    $capture=$s.Substring($s.IndexOf('void write_dispatch_candidates('),$s.IndexOf('void *hooked_complete_pikmin_task')-$s.IndexOf('void write_dispatch_candidates('))
    if($capture.Contains('full_projection')-or$capture.Contains('target_tmp')-or$capture.Contains('commit_scan(')-or$capture.Contains('wait_idle(')){throw 'Capture performs candidate file I/O, freshness commit or synchronous wait'}
    $expectedRows=2
    if($path.StartsWith('targets/')){
        # MuMu147 retains an unavailable row for a persistently excluded old
        # task. It uses the same owned worker, never a third output/commit path.
        $expectedRows=3
        $start=$capture.IndexOf('if(!task_mutation_allowed(id_text)) {')
        $end=$capture.IndexOf('        if (nearby) {',$start)
        if($start-lt0-or$end-le$start){throw 'Missing bounded MuMu exclusion row'}
        $denial=$capture.Substring($start,$end-$start)
        if(!$denial.Contains('prior-admission-unresolved')-or!$denial.Contains('++candidates;continue;')-or([regex]::Matches($denial,'projection\.append\(')).Count-ne1){throw 'Excluded task must remain a visible unavailable owned row'}
        foreach($bad in @('start_expedition','enqueue_dispatch_projection','commit_scan','fwrite','open(')){if($denial.Contains($bad)){throw 'Excluded row must not mutate or publish independently'}}
    }
    if(([regex]::Matches($capture,'projection\.append\(')).Count-ne$expectedRows-or([regex]::Matches($capture,'enqueue_dispatch_projection\(')).Count-ne2){throw 'Both owned full/target row paths required'}
    if(!$capture.Contains('count,false,true,projection_timing')-or!$capture.Contains('count,complete_task_projection,false,projection_timing')){throw 'Partial target may not publish complete scan capability'}
    if($path.StartsWith('targets/')-and!$worker.Contains('emu_atomic_projection::Writer')){throw 'Existing MuMu full projection writer must remain'}
}
'PASS phone/MuMu worker wiring: owned rows, no managed callback, no synchronous candidate I/O, exact full/target attribution; not runtime verification'
