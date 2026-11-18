$ErrorActionPreference = 'Stop'
$source=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '../cpp/feeding_probe.inc')
$squad=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '../cpp/feeding_squad_probe.inc')
$source += "`n" + $squad
foreach($required in @('observe-only','pikmin-feeding-probe.txt','mode != "observe"','count > 512','color > 4','ScopedManagedRoot','0x7297C4C','0x7281428','0x5F753A0','0x5F753B0','0x5F753C0')) {
    if(!$source.Contains($required)){throw "Missing probe guard: $required"}
}
if($source -match 'SendFeed|SendPick|object_new|reinterpret_cast<.*\(.*proto') { throw 'Readonly feeding probe must not construct or send game requests' }
if($source -match '\\tready\\t') { throw 'Unvalidated catalogue must never announce ready' }
foreach($required in @('0x72993EC','0x7285604','0x64E0194','0x64E0174','0x64E0184','0x6D3E180','petal_count > 512','feeding_observation::confirmed_petal_stock(petal_stocks,family,color,ok)','invalid-petals','0x729AE80','0x7289DA0','0x67B8C74','0x67BA1B0','capacity_count > 1','int capacity = -1','invalid-capacity')) {
    if(!$source.Contains($required)){throw "Missing petal probe boundary: $required"}
}
$petalGuard=$source.IndexOf('if (!ok) { publish("invalid-petals"); return; }')
$petalJoin=$source.IndexOf('feeding_observation::confirmed_petal_stock(')
if($petalGuard-lt0-or$petalJoin-le$petalGuard){throw 'Missing petals may become zero only after complete validated enumeration'}
$config=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '../../../../targets/mumu/source-set.json')|ConvertFrom-Json
foreach($name in @('feeding_probe.inc','feeding_observation.h')){
    $selected=@($config.automation|Where-Object name -ceq $name)
    if($selected.Count-ne1-or$selected[0].path-cne "native/automation/zygisk-module/cpp/$name"){throw "Both targets must share confirmed-petal semantics: $name"}
}
Write-Output 'PASS: feeding probe opt-in, metadata pins, bounded rooted reads and observe-only contract (not device validation)'
foreach($required in @('0x5F753D0','0x64E01A4','feeding_catalogue::family','kind_text_method','petal_kind_text_method')) {
    if(!$source.Contains($required)){throw "Missing string flower kind support: $required"}
}
if($source.Contains('kind == 0) continue')) {throw 'Do not silently discard string-backed flower kinds'}
foreach($required in @('0x711A138','KindToObsoleteString','matched_family(kind, kind_text)','mapped.empty()')) {
    if(!$source.Contains($required)){throw "Missing game-owned flower identity mapping: $required"}
}
if(([regex]::Matches($source,'matched_family\(kind, kind_text\)')).Count -ne 2) {throw 'Nectar and petals must use the same game-owned mapping'}
foreach($required in @('0x7297664','0x7280450','0x72A5540','0x5A252A8','0x5A24ED8','0x5A24F08','0x5A25084','0x6D45964','0x591D164','IsPikminInTroop','troop-query-failed','count > 4096','std::isfinite','squad-unverified','invalid-squad')) {
    if(!$squad.Contains($required)){throw "Missing squad read boundary: $required"}
}
if($squad.Contains('if (status != 32) continue')) {throw 'Entourage is not the actual v152 squad; use game troop predicate'}
foreach($required in @('0x6D45658','ShouldRestrictActions','0x6CF3E18','0x6CF4184','restriction-unavailable','flower-identity-unavailable','collecting.squad.push_back')) {
    if(!$squad.Contains($required)){throw "Missing typed squad observation guard: $required"}
}
foreach($required in @('latest={};collecting={}','collecting.account==current_account()','item-id-invalid','collecting.nectar.push_back','item_ids.insert')) {
    if(!$source.Contains($required)){throw "Missing typed catalogue observation guard: $required"}
}
foreach($required in @('latestServerSideTimestamp','feeding_inventory_revision_offset','itemsById','feeding_inventory_items_offset','get_HasPredictors','0xA4BA0C4','get_ConfirmedValue','0xA4B9FE0','collecting.server_revision==final_revision')) {
    if(!$source.Contains($required)){throw "Missing confirmed inventory boundary: $required"}
}
if(([regex]::Matches($source,'confirmed_item\(item\)')).Count -ne 4) {throw 'Capacity, petal, nectar and squad reads must use confirmed inventory'}
