[CmdletBinding()]
param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $sourceRoot '../scripts/build-common.ps1')
if (!$SdkPath -or !$JavaHome) { throw 'Set ANDROID_HOME and JAVA_HOME or pass both paths' }
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("pikmin-control-center-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $stageRoot | Out-Null
try {
foreach ($inputPath in @('src','res','assets','AndroidManifest.xml')) {
    Copy-Item -LiteralPath (Join-Path $sourceRoot $inputPath) -Destination $stageRoot -Recurse -Force
}
$root = $stageRoot
$assetRoot = Join-Path $root 'assets'
$sdk = $SdkPath
$javaHome = $JavaHome
$buildTools = Join-Path $sdk 'build-tools\35.0.0'
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
$out = Join-Path $root 'build'
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
$compiledRes = Join-Path $out 'compiled-res'

if (Test-Path $out) {
    $resolvedBuild = [IO.Path]::GetFullPath($out)
    if (!$resolvedBuild.StartsWith([IO.Path]::GetFullPath($stageRoot) + [IO.Path]::DirectorySeparatorChar)) { throw 'Build cleanup target is outside staging' }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $classes, $dex, $compiledRes | Out-Null
. (Join-Path $sourceRoot '../scripts/health-connect-libraries.ps1')
$hc=Get-HealthConnectLibraries (Split-Path $sourceRoot -Parent) (Join-Path $out 'hc-dependencies')
[IO.File]::AppendAllText((Join-Path $assetRoot 'health-connect-notices.txt'),("`n`n"+(Get-HealthConnectNotices $hc.Jars)),[Text.UTF8Encoding]::new($false))
$generatedR=Join-Path $out 'generated-r'
New-Item -ItemType Directory -Force $generatedR | Out-Null

$hcIndex=0
foreach($res in $hc.Resources){
    $libRes=Join-Path $compiledRes ('hc-'+($hcIndex++))
    New-Item -ItemType Directory -Force $libRes | Out-Null
    & (Join-Path $buildTools 'aapt2.exe') compile --dir $res -o $libRes
    if($LASTEXITCODE -ne 0){throw 'HC library resource compilation failed'}
}

$sources = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object FullName
if (!$sources) { throw 'No Java sources found' }
$env:JAVA_HOME = $javaHome

& (Join-Path $buildTools 'aapt2.exe') compile --dir (Join-Path $root 'res') -o $compiledRes
if ($LASTEXITCODE -ne 0) { throw 'aapt2 resource compilation failed' }
$flat = @(Get-ChildItem $compiledRes -Directory | ForEach-Object { Get-ChildItem $_.FullName -Filter '*.flat' } | ForEach-Object FullName)
$flat += @(Get-ChildItem $compiledRes -File -Filter *.flat | ForEach-Object FullName)
$unsignedApk = Join-Path $out 'pikmin-control-center-unsigned.apk'
& (Join-Path $buildTools 'aapt2.exe') link -I $androidJar --auto-add-overlay --manifest (Join-Path $root 'AndroidManifest.xml') -A $assetRoot -o $unsignedApk -R $flat --java $generatedR --extra-packages ($hc.Namespaces -join ':')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }
$sources+=@(Get-ChildItem $generatedR -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourceArgs=Join-Path $out 'sources.args'
[IO.File]::WriteAllText($sourceArgs,(($sources | ForEach-Object { '"'+$_.Replace('\','/')+'"' }) -join "`n"),[Text.UTF8Encoding]::new($false))
& (Join-Path $javaHome 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath (@($androidJar)+$hc.Jars -join ';') -d $classes "@$sourceArgs"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$classesJar = Join-Path $out 'classes.jar'
& (Join-Path $javaHome 'bin\jar.exe') cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'classes jar failed' }
& (Join-Path $buildTools 'd8.bat') --lib $androidJar --min-api 28 --output $dex $classesJar $hc.Jars
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Push-Location $dex
try { & (Join-Path $javaHome 'bin\jar.exe') uf $unsignedApk @(Get-ChildItem $dex -Filter 'classes*.dex' | ForEach-Object Name) }
finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'adding classes.dex failed' }
$runtimeRes=Join-Path $out 'runtime-res'
$serviceResources=@(Add-HealthConnectServiceResources $hc.Jars $runtimeRes)
if($serviceResources.Count){
    Push-Location $runtimeRes
    try{ & (Join-Path $javaHome 'bin/jar.exe') uf $unsignedApk $serviceResources }
    finally{Pop-Location}
    if($LASTEXITCODE -ne 0){throw 'HC runtime service resources failed'}
}

$aligned = Join-Path $out 'pikmin-control-center-aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') -f 4 $unsignedApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
$unsignedOutput = Join-Path $sourceRoot 'pikmin-control-center-unsigned.apk'
Copy-Item -LiteralPath $aligned -Destination $unsignedOutput -Force
Write-Output 'PASS: unsigned phone APK built without prebuilt core or rollback assets'
} finally { Remove-BuildStage $stageRoot 'pikmin-control-center-' }
