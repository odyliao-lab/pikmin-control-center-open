$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot)))
function Assert-ReadOnlyHistory([string]$text){
    $lines=@($text -split "`n"|Where-Object {$_ -match 'GAME_RETURN_HISTORY'}|ForEach-Object {$_.Trim()})
    $expected=@('GAME_RETURN_HISTORY="$GAME_FILES/return_reward_history.tsv"','if [ -r "$GAME_RETURN_HISTORY" ]; then','cp "$GAME_RETURN_HISTORY" "$PUBLIC_RETURN_HISTORY" 2>/dev/null')
    if(($lines -join "`n") -cne ($expected -join "`n") -or $text.Contains('RETURN_CUTOFF') -or $text.Contains('RETURN_LOG')){
        throw 'Service must only read/mirror private return history; native owns append and retention'
    }
}
foreach($path in @('native/automation/zygisk-module/module/service.sh','targets/mumu/native/module/service.sh')){
    $text=Get-Content (Join-Path $repo $path) -Raw
    Assert-ReadOnlyHistory $text
    foreach($write in @('cat "$GAME_RETURN_HISTORY.tmp" >"$GAME_RETURN_HISTORY"','cp "$PUBLIC_RETURN_HISTORY" "$GAME_RETURN_HISTORY"','for RETURN_LOG in "$GAME_RETURN_HISTORY"; do :; done')){
        $rejected=$false;try{Assert-ReadOnlyHistory ($text+"`n"+$write)}catch{$rejected=$true}
        if(!$rejected){throw 'History writer regression was accepted'}
    }
}
'PASS history single-writer contract: two services and six writeback regressions; not runtime retention acceptance'
