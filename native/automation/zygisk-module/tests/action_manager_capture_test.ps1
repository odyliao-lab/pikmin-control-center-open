$ErrorActionPreference = 'Stop'
$source = Get-Content -Raw (Join-Path $PSScriptRoot '../cpp/nectar_rpc.cpp')
$start = $source.IndexOf('void install_return_diagnostic_hook()')
$end = $source.IndexOf('// This is the public overload', $start)
if ($start -lt 0 -or $end -le $start) { throw 'Capture installation not found' }
$capture = $source.Substring($start, $end - $start)
if ($capture.Contains('class_get_method_from_name(klass, ".ctor", 0)')) {
    throw 'Unsafe 4-byte action-manager constructor hook restored'
}
foreach ($required in @('"BlockerService"', '"Zenject.IInitializable.Initialize"',
        '"pikminTaskActionManager"', 'field_get_offset(action_manager_capture_field) != 0x50',
        'std::memcmp(action_ctor_entry, capture_prologue, sizeof(capture_prologue))',
        'hooked_blocker_service_initialize')) {
    if (!$capture.Contains($required)) { throw "Missing capture contract: $required" }
}
if (!$source.Contains('field_get_value(self, action_manager_capture_field, &manager)')) {
    throw 'Manager must come from the injected field, not the BlockerService instance'
}
Write-Output 'PASS: source capture contract; not native runtime acceptance'
