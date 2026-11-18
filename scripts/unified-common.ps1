function Get-UnifiedSourceFingerprint([string]$Root) {
    $files=@()
    foreach($part in @('magisk/cpp','magisk/hc-java','native/automation/zygisk-module/cpp','native/gps-copy/cpp')) {
        $files+=Get-ChildItem -LiteralPath (Join-Path $Root $part) -Recurse -File
    }
    foreach($part in @('scripts/build-unified-native.ps1','scripts/build-hc-payload.ps1','scripts/unified-common.ps1','experimental/hc-compat/src/dev/ody/pikmincontrol/hccompat/CompatPolicy.java','android-app/src/dev/ody/pikmincontrol/HcCompatPolicy.java')) {
        $files+=Get-Item -LiteralPath (Join-Path $Root $part)
    }
    [string[]]$lines=$files | ForEach-Object {
        $_.FullName.Substring($Root.Length).Replace('\','/')+'='+(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }
    [Array]::Sort($lines,[StringComparer]::Ordinal)
    $sha=[Security.Cryptography.SHA256]::Create()
    try{return [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join "`n")))).ToLowerInvariant()}
    finally{$sha.Dispose()}
}
