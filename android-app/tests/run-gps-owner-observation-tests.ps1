param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$fixtureRoot = Join-Path $PSScriptRoot 'fixtures/gps-owner-observation'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $javac) -or !(Test-Path -LiteralPath $java)) { throw 'Existing JDK required; this script installs nothing.' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose a new output directory; existing files are never overwritten.' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$fixtureSources = @(Get-ChildItem -LiteralPath $fixtureRoot -Recurse -Filter '*.java' | ForEach-Object FullName)
$targets = @(
    @{Name='phone'; Source='android-app/src/dev/ody/pikmincontrol/GpsWorkLock.java'},
    @{Name='mumu'; Source='targets/mumu/android/override/GpsWorkLock.java'}
)
foreach ($target in $targets) {
    $source = Join-Path $repoRoot $target.Source
    $before = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    $stage = Join-Path $OutputDirectory $target.Name
    New-Item -ItemType Directory -Path $stage | Out-Null
    $sources = @($source) + $fixtureSources
    $argFile = Join-Path $stage 'sources.args'
    [IO.File]::WriteAllText($argFile,(($sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }) -join [Environment]::NewLine),[Text.UTF8Encoding]::new($false))
    & $javac '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -source 8 -target 8 -d $stage "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw ('Fixture compilation failed: ' + $target.Name) }
    & $java -cp $stage 'dev.ody.pikmincontrol.GpsOwnerObservationHarness'
    if ($LASTEXITCODE -ne 0) { throw ('Fixture failed: ' + $target.Name) }
    $after = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if ($before -ne $after) { throw ('Production source changed during test: ' + $target.Name) }
    Write-Output ('PASS ' + $target.Name + ' production_source_sha256=' + $after)
}
Write-Output 'PASS both selected production GpsWorkLock sources; HOST only, no root/device/API.'
