param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (!$SdkPath -or !$JavaHome) { throw 'Set ANDROID_HOME and JAVA_HOME or pass both paths' }
$appRoot = Split-Path $PSScriptRoot -Parent
$stage = Join-Path ([IO.Path]::GetTempPath()) ('pikmin-unit-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
$androidJar = Join-Path $SdkPath 'platforms/android-35/android.jar'
. (Join-Path $appRoot '../scripts/health-connect-libraries.ps1')
$hc=Get-HealthConnectLibraries (Split-Path $appRoot -Parent) (Join-Path $stage 'hc-dependencies')
$classPath=(@($androidJar)+$hc.Jars -join ';')
$sources = @(Get-ChildItem (Join-Path $appRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$tests = @(Get-ChildItem $PSScriptRoot -Recurse -Filter '*Test.java' | Where-Object { $_.Name -notlike '*DeviceTest.java' })
$sources += @($tests | ForEach-Object FullName)
# Windows' process command line is bounded; the canonical Chinese-path
# checkout plus all tests can exceed it. javac's official argument file keeps
# every source (never truncate the test list to make the command fit).
$sourceArgs=Join-Path $stage 'sources.args'
[IO.File]::WriteAllText($sourceArgs,(($sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }) -join "`n"),[Text.UTF8Encoding]::new($false))
& "$JavaHome/bin/javac.exe" '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -source 8 -target 8 -classpath $classPath -d $stage "@$sourceArgs"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
foreach ($test in $tests) {
    & "$JavaHome/bin/java.exe" -cp "$stage;$classPath" "-Dpcc.fixtures=$(Join-Path $PSScriptRoot 'fixtures')" ('dev.ody.pikmincontrol.' + $test.BaseName)
    if ($LASTEXITCODE -ne 0) { throw ('Failed: ' + $test.Name) }
}
# Reuse these exact production classes and android.jar; no second build/dependency install.
& python (Join-Path $PSScriptRoot 'run-nectar-rollback-shell-tests.py') --java "$JavaHome/bin/java.exe" --classpath "$stage;$classPath" --root (Split-Path $appRoot -Parent) --work-dir $stage
if ($LASTEXITCODE -ne 0) { throw 'Nectar rollback shell/source regression failed' }
& python (Join-Path $PSScriptRoot 'run-nectar-first-install-tests.py') --java "$JavaHome/bin/java.exe" --classpath "$stage;$classPath"
if ($LASTEXITCODE -ne 0) { throw 'Nectar first-install stop shell regression failed' }
& python (Join-Path $PSScriptRoot 'run-display-groups-shell-tests.py') --java "$JavaHome/bin/java.exe" --classpath "$stage;$classPath"
if ($LASTEXITCODE -ne 0) { throw 'Display group migration/fan-out shell regression failed' }
Write-Output ('PASS: ' + $tests.Count + ' JVM test entrypoints')
