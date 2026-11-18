[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$NdkPath,
    [Parameter(Mandatory)][string]$CmakePath,
    [Parameter(Mandatory)][string]$NinjaPath
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'build-common.ps1')
. (Join-Path $PSScriptRoot 'unified-common.ps1')
$fingerprint=Get-UnifiedSourceFingerprint $root
$stage=Join-Path ([IO.Path]::GetTempPath()) ('pcc-unified-'+[guid]::NewGuid().ToString('N'))
$sourceDateBefore=$env:SOURCE_DATE_EPOCH
$env:SOURCE_DATE_EPOCH='1677801600' # Pinned Dobby source date; no wall-clock build ID.
try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    & "$PSScriptRoot/build-hc-payload.ps1" -OutputDirectory "$stage/hc-generated"
    foreach($pair in @(@('magisk/cpp','magisk/cpp'),@('native/automation/zygisk-module/cpp','native/automation/zygisk-module/cpp'),@('native/gps-copy/cpp','native/gps-copy/cpp'))) {
        $dest=Join-Path $stage $pair[1]
        New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $root $pair[0]) -Destination $dest -Recurse
    }
    & $CmakePath -G Ninja "-DCMAKE_MAKE_PROGRAM=$NinjaPath" "-DCMAKE_TOOLCHAIN_FILE=$NdkPath/build/cmake/android.toolchain.cmake" "-DPCC_HC_DEX_DIR=$stage/hc-generated" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -S "$stage/magisk/cpp" -B "$stage/build"
    if($LASTEXITCODE -ne 0){throw 'Unified configure failed'}
    & $CmakePath --build "$stage/build"
    if($LASTEXITCODE -ne 0){throw 'Unified native build failed'}
    $out=Join-Path $root '.tools/unified-native'
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    Copy-Item -LiteralPath "$stage/build/libpikmin_control_core.so" -Destination "$out/arm64-v8a.so"
    if((Get-UnifiedSourceFingerprint $root) -cne $fingerprint){throw 'Native sources changed during build; rebuild before packaging'}
    $receipt=@{schema=1;source=$fingerprint;so=(Get-FileHash "$out/arm64-v8a.so" -Algorithm SHA256).Hash.ToLowerInvariant()}
    [IO.File]::WriteAllText("$out/build.json",($receipt|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Get-FileHash "$out/arm64-v8a.so"
    Write-Output 'Built experimental single loader only. No installable ZIP, APK replacement or device change.'
} finally { $env:SOURCE_DATE_EPOCH=$sourceDateBefore; Remove-BuildStage $stage 'pcc-unified-' }
