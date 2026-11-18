param([Parameter(Mandatory)][string]$OutputDirectory,[string]$SdkPath=$env:ANDROID_HOME,[string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(!$SdkPath){throw 'SdkPath or ANDROID_HOME is required'}
if(!$JavaHome){throw 'JavaHome is required for the embedded HC payload'}
$env:JAVA_HOME=$JavaHome
New-Item -ItemType Directory -Force "$OutputDirectory/classes","$OutputDirectory/dex" | Out-Null
$files=@(Get-ChildItem "$root/magisk/hc-java" -Recurse -Filter *.java | ForEach-Object FullName)
$files+=@("$root/experimental/hc-compat/src/dev/ody/pikmincontrol/hccompat/CompatPolicy.java","$root/android-app/src/dev/ody/pikmincontrol/HcCompatPolicy.java")
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath "$SdkPath/platforms/android-35/android.jar" -d "$OutputDirectory/classes" $files
if($LASTEXITCODE -ne 0){throw 'Embedded HC Java compile failed'}
& "$JavaHome/bin/jar.exe" cf "$OutputDirectory/classes.jar" -C "$OutputDirectory/classes" .
if($LASTEXITCODE -ne 0){throw 'Embedded HC class jar failed'}
& "$SdkPath/build-tools/35.0.0/d8.bat" --lib "$SdkPath/platforms/android-35/android.jar" --min-api 28 --output "$OutputDirectory/dex" "$OutputDirectory/classes.jar"
if($LASTEXITCODE -ne 0){throw 'Embedded HC DEX failed'}
$bytes=[IO.File]::ReadAllBytes("$OutputDirectory/dex/classes.dex")
$lines=[Collections.Generic.List[string]]::new();$lines.Add('static const unsigned char pcc_hc_dex[] = {')
for($i=0;$i-lt$bytes.Length;$i+=24){$last=[Math]::Min($i+23,$bytes.Length-1);$lines.Add((($bytes[$i..$last]|ForEach-Object {"0x$($_.ToString('x2'))"}) -join ',')+',')}
$lines.Add('};');[IO.File]::WriteAllLines("$OutputDirectory/hc_dex.inc",$lines,[Text.UTF8Encoding]::new($false))
Write-Output 'PASS embedded HC DEX built from tracked source; no Xposed dependency or separate runtime APK'
