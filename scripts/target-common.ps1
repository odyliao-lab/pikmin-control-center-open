$ErrorActionPreference='Stop'
function Get-MumuSourceSet([string]$Repo){
    $s=Get-Content -LiteralPath "$Repo/targets/mumu/source-set.json" -Raw|ConvertFrom-Json
    if($s.schema-ne1-or$s.target-cne'mumu'-or$s.package-cne'dev.ody.pikmincontrol.mumu'-or$s.game-cne'157.0'-or$s.appCode-ne595-or$s.coreCode-ne900178-or$s.coreVersion-cne'0.20.3'){throw 'Unsupported MuMu target/pair'}
    foreach($group in @('java','automation','resources')){
        $seen=@{}
        foreach($entry in $s.$group){
            if(!$entry.name-or$seen.ContainsKey($entry.name)){throw "Duplicate source in $group"};$seen[$entry.name]=$true
            if($entry.path-match'(^/|^[A-Za-z]:|\\|(^|/)\.\.(/|$)|(^|/)\.tools(/|$))'){throw 'Target source must be repository-relative, not a snapshot'}
            $p=[IO.Path]::GetFullPath((Join-Path $Repo $entry.path));$root=[IO.Path]::GetFullPath($Repo).TrimEnd('/','\')+[IO.Path]::DirectorySeparatorChar
            if(!$p.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)-or!(Test-Path -LiteralPath $p -PathType Leaf)){throw "Target source unavailable: $($entry.path)"}
            $node=$p;while($node.Length-ge$root.Length){if((Get-Item -LiteralPath $node -Force).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Linked source rejected'};$node=Split-Path $node}
            if($group-ceq'java'-and$entry.name-notmatch'^[A-Za-z][A-Za-z0-9]*\.java$'){throw 'Invalid Java source name'}
            if($group-ceq'java'-and$entry.kind-notin@('shared','override','mumu-only')){throw 'Unknown source layer'}
            if($group-ceq'java'){
                $expected=if($entry.kind-ceq'shared'){"android-app/src/dev/ody/pikmincontrol/$($entry.name)"}else{"targets/mumu/android/$($entry.kind)/$($entry.name)"}
                if($entry.path-cne$expected){throw 'Source layer/path mismatch'}
            }
        }
    }
    return $s
}
function Get-MumuInputPins([string]$Repo,$SourceSet){
    $paths=@($SourceSet.java.path)+@($SourceSet.automation.path)+@($SourceSet.resources.path)
    foreach($folder in @('targets/mumu/native','targets/mumu/android/assets','native/automation/zygisk-module/cpp/support','native/gps-copy/cpp')){
        $paths+=@(Get-ChildItem "$Repo/$folder" -Recurse -File|ForEach-Object {$_.FullName.Substring($Repo.Length+1).Replace('\','/')})
    }
    $paths+=@('targets/mumu/source-set.json','targets/mumu/android/AndroidManifest.xml','magisk/cpp/companion_probe.h','scripts/build-mumu.ps1','scripts/target-common.ps1')
    return @($paths|Sort-Object -Unique|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath "$Repo/$_").Hash.ToLowerInvariant()}})
}
function Assert-MumuPins([string]$Repo,$Pins){foreach($p in $Pins){$actual=(Get-FileHash -LiteralPath "$Repo/$($p.path)").Hash.ToLowerInvariant();if($actual-cne$p.sha256){throw "Source changed during build: $($p.path); expected=$($p.sha256); actual=$actual"}}}
