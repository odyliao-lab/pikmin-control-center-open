param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$out=Join-Path $root '.tools/auto-petal-tests'
New-Item -ItemType Directory -Force $out|Out-Null
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -d $out "$root/android-app/src/dev/ody/pikmincontrol/AutoPetalConfig.java" "$root/android-app/src/dev/ody/pikmincontrol/AutoPetalState.java" "$root/tests/auto-petals/dev/ody/pikmincontrol/AutoPetalTest.java"
if($LASTEXITCODE){throw 'Auto petal tests failed to compile'}
& "$JavaHome/bin/java.exe" -cp $out dev.ody.pikmincontrol.AutoPetalTest
if($LASTEXITCODE){throw 'Auto petal tests failed'}

& python "$root/scripts/test-petal-auto-sync.py"
if($LASTEXITCODE){throw "Petal auto sync source boundary failed"}
