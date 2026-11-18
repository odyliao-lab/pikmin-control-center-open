[CmdletBinding()]
param([Parameter(Mandatory)][string]$SdkPath,[Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$NdkPath,[Parameter(Mandatory)][string]$CmakePath,[Parameter(Mandatory)][string]$NinjaPath)
$ErrorActionPreference='Stop';$repo=Split-Path $PSScriptRoot
. "$PSScriptRoot/target-common.ps1"
$config=Get-MumuSourceSet $repo;$pins=Get-MumuInputPins $repo $config
$env:JAVA_HOME=$JavaHome;$bt="$SdkPath/build-tools/35.0.0";$java="$JavaHome/bin";$android="$SdkPath/platforms/android-35/android.jar"
foreach($p in @($android,"$java/javac.exe",$CmakePath,$NinjaPath,"$NdkPath/build/cmake/android.toolchain.cmake")){if(!(Test-Path -LiteralPath $p -PathType Leaf)){throw "Required tool unavailable: $p"}}
if((Get-Content "$NdkPath/source.properties" -Raw)-notmatch'Pkg.Revision\s*=\s*27\.3\.13750724'){throw 'Pinned NDK r27d required'}
$stage="$repo/.tools/build/mumu/$([guid]::NewGuid().ToString('N'))";$utf8=[Text.UTF8Encoding]::new($false)
foreach($d in @('automation','src/dev/ody/pikmincontrol','res','assets','classes','dex','compiled-res','helpers','module/zygisk')){New-Item -ItemType Directory "$stage/$d" -Force|Out-Null}
function Checked([string]$what){if($LASTEXITCODE-ne0){throw "$what failed; stage retained: $stage"}}
foreach($e in $config.automation){Copy-Item -LiteralPath "$repo/$($e.path)" -Destination "$stage/automation/$($e.name)"}
foreach($e in $config.java){Copy-Item -LiteralPath "$repo/$($e.path)" -Destination "$stage/src/dev/ody/pikmincontrol/$($e.name)"}
foreach($e in $config.resources){$p="$stage/res/$($e.name)";[IO.Directory]::CreateDirectory((Split-Path $p))|Out-Null;Copy-Item -LiteralPath "$repo/$($e.path)" -Destination $p}
Copy-Item "$repo/targets/mumu/android/assets/*" "$stage/assets"
Copy-Item "$repo/targets/mumu/android/AndroidManifest.xml" "$stage/AndroidManifest.xml"
$manifest=[xml](Get-Content "$stage/AndroidManifest.xml" -Raw)
if($manifest.manifest.package-cne$config.package-or[int]$manifest.manifest.versionCode-ne$config.appCode-or$manifest.manifest.versionName-cne$config.appVersion){throw 'Manifest/target mismatch'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$core="$stage/pcc-mumu-core-$($config.coreVersion).zip"
foreach($abi in @('arm64-v8a','x86_64')){
    & $CmakePath -S "$repo/targets/mumu/native" -B "$stage/native-$abi" -G Ninja "-DCMAKE_MAKE_PROGRAM=$NinjaPath" "-DCMAKE_TOOLCHAIN_FILE=$NdkPath/build/cmake/android.toolchain.cmake" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release "-DPCC_REPO=$repo" "-DPCC_AUTOMATION=$stage/automation";Checked "Configure $abi"
    & $CmakePath --build "$stage/native-$abi" --parallel 2;Checked "Native $abi"
}
Copy-Item "$repo/targets/mumu/native/module/*" "$stage/module"
# module.prop is consumed by Android sed equality guards; never ship CRLF.
[IO.File]::WriteAllText("$stage/module/module.prop",[IO.File]::ReadAllText("$stage/module/module.prop").Replace("`r`n","`n"),$utf8)
Copy-Item "$stage/native-arm64-v8a/libpcc_canary.so" "$stage/module/payload-arm64.so"
Copy-Item "$stage/native-x86_64/libpcc_canary.so" "$stage/module/zygisk/x86_64.so"
$zip=[IO.Compression.ZipFile]::Open($core,[IO.Compression.ZipArchiveMode]::Create)
try{foreach($f in Get-ChildItem "$stage/module" -Recurse -File){$n=$f.FullName.Substring((Resolve-Path "$stage/module").Path.Length+1).Replace('\','/');[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$f.FullName,$n)|Out-Null}}finally{$zip.Dispose()}
$coreSha=(Get-FileHash $core).Hash.ToLowerInvariant();Copy-Item $core "$stage/assets/pikmin-core.zip"
[IO.File]::WriteAllText("$stage/assets/core.properties","schema=2`nversion=$($config.coreVersion)`ncode=$($config.coreCode)`napp=$($config.appCode)`nsize=$((Get-Item $core).Length)`nsha256=$coreSha`n",$utf8)
# Rebuild every native/DEX helper; no copied executable, old receipt or Git object.
$bin="$NdkPath/toolchains/llvm/prebuilt/windows-x86_64/bin";$bridge="$repo/targets/mumu/native/bridge"
& "$bin/x86_64-linux-android30-clang++.cmd" -std=c++20 -O2 -shared -fPIC -static-libstdc++ -fno-exceptions -fno-rtti "$bridge/root_observer_jni.cpp" -o "$stage/assets/root-observer.so";Checked 'Root observer'
& "$bin/x86_64-linux-android30-clang++.cmd" -std=c++20 -static -O2 -fno-exceptions -fno-rtti "$bridge/root_gps_fence_main.cpp" -o "$stage/assets/gps-fence";Checked 'GPS fence'
& "$java/javac.exe" -source 8 -target 8 -cp $android -d "$stage/helpers" "$bridge/CoreAssets.java" "$bridge/RootApkPublisher.java" "$bridge/ObserverControl.java";Checked 'Observer Java'
& "$java/jar.exe" cf "$stage/observer.jar" -C "$stage/helpers" .;Checked 'Observer jar'
New-Item -ItemType Directory "$stage/observer-dex"|Out-Null
& "$bt/d8.bat" --min-api 30 --lib $android --output "$stage/observer-dex" "$stage/observer.jar";Checked 'Observer DEX'
Copy-Item "$stage/observer-dex/classes.dex" "$stage/assets/root-observer.dex"
$so=Get-Item "$stage/assets/root-observer.so";$dex=Get-Item "$stage/assets/root-observer.dex";$fence=Get-Item "$stage/assets/gps-fence"
[IO.File]::WriteAllText("$stage/assets/root-observer.tsv","ro1`t$($config.appCode)`t$($so.Length)`t$((Get-FileHash $so.FullName).Hash.ToLowerInvariant())`t$($dex.Length)`t$((Get-FileHash $dex.FullName).Hash.ToLowerInvariant())`tend`n",$utf8)
[IO.File]::WriteAllText("$stage/assets/gps-fence.tsv","gf1`t$($config.appCode)`t$($fence.Length)`t$((Get-FileHash $fence.FullName).Hash.ToLowerInvariant())`tend`n",$utf8)
$sources=@(Get-ChildItem "$stage/src" -Recurse -Filter '*.java'|ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'})
[IO.File]::WriteAllLines("$stage/sources.args",$sources,$utf8)
& "$java/javac.exe" '-J-Xmx384m' -source 8 -target 8 -encoding UTF-8 -cp $android -d "$stage/classes" "@$stage/sources.args";Checked 'MuMu Java'
& "$java/jar.exe" cf "$stage/classes.jar" -C "$stage/classes" .;Checked 'MuMu jar'
& "$bt/aapt2.exe" compile --dir "$stage/res" -o "$stage/compiled-res";Checked 'Resources'
& "$bt/aapt2.exe" link -I $android --auto-add-overlay --manifest "$stage/AndroidManifest.xml" -A "$stage/assets" -o "$stage/resources.apk" -R @(Get-ChildItem "$stage/compiled-res" -Filter '*.flat'|ForEach-Object FullName);Checked 'Resource link'
& "$bt/d8.bat" --min-api 28 --lib $android --output "$stage/dex" "$stage/classes.jar";Checked 'MuMu DEX'
$zip=[IO.Compression.ZipFile]::Open("$stage/resources.apk",[IO.Compression.ZipArchiveMode]::Update)
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$stage/dex/classes.dex",'classes.dex')|Out-Null}finally{$zip.Dispose()}
$apk="$stage/pikmin-control-center-mumu.apk";& "$bt/zipalign.exe" -f 4 "$stage/resources.apk" $apk;Checked 'APK alignment'
Assert-MumuPins $repo $pins
@{schema=1;target='mumu';code=$config.appCode;version=$config.appVersion;package=$config.package;game=$config.game;coreVersion=$config.coreVersion;coreCode=$config.coreCode;core=$core;coreSha=$coreSha;apk=$apk;sha256=(Get-FileHash $apk).Hash.ToLowerInvariant();stage=$stage;signed=$false;sources=$pins;scope='Source-controlled dual-target build only; no installation, loaded-core or gameplay validation'}|ConvertTo-Json -Depth 5|Set-Content "$stage/build.json"
"MuMu build receipt: $stage/build.json"
