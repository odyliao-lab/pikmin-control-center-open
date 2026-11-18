param(
    [string[]]$Tests = @('auto_petal_inventory_policy_test', 'auto_petal_policy_test', 'expedition_display_policy_test', 'planter_display_policy_test', 'gift_display_policy_test', 'diagnostic_snapshot_file_test', 'decoration_display_policy_test', 'adult_display_policy_test', 'stock_display_policy_test', 'collection_insight_policy_test', 'gift_dispatch_policy_test', 'planter_pending_store_test', 'planter_continuous_test', 'planter_recovery_test', 'nectar_intent_policy_test', 'nectar_intent_file_test', 'bootstrap_retry_test', 'capability_state_test', 'capability_golden_test', 'batch_target_probe_test',
        'feeding_observation_test', 'feeding_policy_test', 'feeding_pending_test', 'feeding_completion_test', 'feeding_coordinator_test', 'feeding_rotation_test', 'nearby_safety_test', 'async_tsv_log_test', 'async_dispatch_projection_test'),
    [string]$NdkPath = $env:ANDROID_NDK_HOME,
    [string]$WslDistro = 'Ubuntu',
    [string]$GoldenDir = (Join-Path (Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) -Parent) 'android-app/tests/fixtures/core_capabilities'),
    [int]$TimeoutSeconds = 120,
    [switch]$UpdateGolden,
    [switch]$BuildOnly,
    [switch]$Ci
)
# Host entry point for the pure-C++ native tests (no device, no ADB).
#
# Each test is compiled with the NDK as a *static* x86_64 Android executable and
# executed on a Linux kernel: directly when this script runs on Linux (CI), or
# under WSL2 when it runs on Windows (developer machine, no host compiler). The
# aarch64 build is also compiled (build-only) to prove the same source compiles
# for the device toolchain used by run-native-tests.ps1.
#
# Failure semantics (R6):
#  * every execution is bounded by -TimeoutSeconds (inside the Linux side with
#    `timeout`, and on the PowerShell side as a second guard); a hang is FAIL.
#  * without a Linux executor the run is SKIP with exit 0 only in developer
#    mode; with -Ci (or CI=true in the environment) SKIP is a failure.
#  * golden lines "GOLDEN<TAB><name><TAB><line>" are compared with
#    $GoldenDir/<name>.tsv; any difference, a missing file, a file count that
#    differs from the produced count, or zero produced lines is FAIL.
#    -UpdateGolden rewrites them instead (never in CI mode).
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/bounded-process-output.ps1"
& "$PSScriptRoot/bounded-process-output-test.ps1"
$ci = $Ci -or ($env:CI -eq 'true')
if ($ci -and $UpdateGolden) { throw 'CI mode never rewrites golden fixtures' }
$onLinux = $PSVersionTable.Platform -eq 'Unix'
$hostTag = if ($onLinux) { 'linux-x86_64' } else { 'windows-x86_64' }
$ext = if ($onLinux) { '' } else { '.cmd' }
$bin = Join-Path $NdkPath "toolchains/llvm/prebuilt/$hostTag/bin"
$clangHost = Join-Path $bin "x86_64-linux-android28-clang++$ext"
$clangDev = Join-Path $bin "aarch64-linux-android28-clang++$ext"
if (-not (Test-Path $clangHost)) { throw "NDK clang not found: $clangHost" }
$stage = Join-Path ([IO.Path]::GetTempPath()) ('pikmin-host-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -LiteralPath $PSScriptRoot -Destination (Join-Path $stage 'tests') -Recurse
Copy-Item -LiteralPath (Join-Path (Split-Path $PSScriptRoot -Parent) 'cpp') -Destination (Join-Path $stage 'cpp') -Recurse
# Test the exact MuMu diagnostic writer without changing shared phone product sources.
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../../../../targets/mumu/native/automation/diagnostic_snapshot_file.h') -Destination (Join-Path $stage 'cpp/diagnostic_snapshot_file.h')

# Pick the Linux executor.
$executor = ''
if (-not $BuildOnly) {
    if ($onLinux) { $executor = 'linux' }
    elseif (Get-Command wsl.exe -ErrorAction SilentlyContinue) {
        & wsl.exe -d $WslDistro --cd /tmp -- true 2>$null
        if ($LASTEXITCODE -eq 0) { $executor = 'wsl' }
    }
}
if (-not $executor -and $ci) { Remove-Item -Recurse -Force $stage; throw 'CI mode requires a Linux executor (Linux host or WSL); SKIP is not allowed' }

# Runs one static binary with a bounded wall clock; returns @{ rc; lines }. rc 124 = timeout.
function Invoke-Bounded([string]$Binary) {
    $tmpOut = Join-Path $stage ('out-' + [guid]::NewGuid().ToString('N') + '.txt')
    if ($executor -eq 'linux') {
        & chmod 700 $Binary
        $proc = Start-Process -FilePath 'timeout' -ArgumentList @('-k', '5', "$TimeoutSeconds", $Binary) -RedirectStandardOutput $tmpOut -NoNewWindow -PassThru
    } else {
        $drive = $Binary.Substring(0, 1).ToLower()
        $wslPath = '/mnt/' + $drive + $Binary.Substring(2).Replace('\', '/')
        $tmp = '/tmp/pcc-host-' + [guid]::NewGuid().ToString('N')
        # The command goes through a script file (LF endings) so no quoting is re-interpreted by Windows process creation.
        $script = Join-Path $stage ('run-' + [guid]::NewGuid().ToString('N') + '.sh')
        [IO.File]::WriteAllText($script, "cp '$wslPath' '$tmp' && chmod 700 '$tmp' && TMPDIR=/tmp timeout -k 5 $TimeoutSeconds '$tmp'; rc=`$?; rm -f '$tmp'; exit `$rc`n", [Text.UTF8Encoding]::new($false))
        $wslScript = '/mnt/' + $script.Substring(0, 1).ToLower() + $script.Substring(2).Replace('\', '/')
        $proc = Start-Process -FilePath 'wsl.exe' -ArgumentList @('-d', $WslDistro, '--cd', '/tmp', '--', 'bash', $wslScript) -RedirectStandardOutput $tmpOut -NoNewWindow -PassThru
    }
    try { return Complete-BoundedProcessOutput $proc $tmpOut (($TimeoutSeconds + 30) * 1000) }
    finally { $proc.Dispose() }
}

$failed = 0
$goldenSeen = 0
foreach ($name in $Tests) {
    $src = Join-Path $stage "tests/$name.cpp"
    if (-not (Test-Path $src)) { throw "Test source missing: $src" }
    $hostBin = Join-Path $stage "$name.x86_64"
    $devBin = Join-Path $stage "$name.aarch64"
    & $clangHost -std=c++20 -O1 -Wall -Wextra -Werror -static -static-libstdc++ $src -o $hostBin
    if ($LASTEXITCODE -ne 0) { throw "Compile failed (x86_64 host): $name" }
    & $clangDev -std=c++20 -O2 -Wall -Wextra -Werror -static-libstdc++ $src -o $devBin
    if ($LASTEXITCODE -ne 0) { throw "Compile failed (aarch64 device): $name" }
    Write-Output "BUILD OK: $name (x86_64 static, aarch64)"
    if (-not $executor) { Write-Output "SKIP run: $name (no Linux executor: WSL '$WslDistro' unavailable or -BuildOnly); build-only evidence"; continue }
    $priorTmpDir=$env:TMPDIR
    try {$env:TMPDIR='/tmp';$run = Invoke-Bounded $hostBin}
    finally {$env:TMPDIR=$priorTmpDir}
    foreach ($line in $run.lines) {
        if ($line -like 'GOLDEN*') {
            $parts = $line.Split("`t", 3)
            if ($parts.Count -ne 3) { throw "Malformed golden line: $line" }
            $file = Join-Path $GoldenDir ($parts[1] + '.tsv')
            $expected = $parts[2] + "`n"
            if ($UpdateGolden) {
                New-Item -ItemType Directory -Path $GoldenDir -Force | Out-Null
                [IO.File]::WriteAllText($file, $expected, [Text.UTF8Encoding]::new($false))
                Write-Output "GOLDEN written: $($parts[1])"
            } elseif (-not (Test-Path $file)) {
                Write-Output "FAIL golden missing: $file (run with -UpdateGolden)"; $failed++
            } elseif (([IO.File]::ReadAllText($file)).Replace("`r`n", "`n") -cne $expected) {   # tolerate autocrlf checkouts
                Write-Output "FAIL golden differs: $($parts[1])"; $failed++
            }
            $goldenSeen++
        } else { Write-Output $line }
    }
    if ($run.rc -eq 124) { Write-Output "FAIL: $name timed out after $TimeoutSeconds s"; $failed++ }
    elseif ($run.rc -eq 125) { Write-Output "FAIL: $name output capture did not finish within 5 seconds after exit"; $failed++ }
    elseif ($run.rc -ne 0) { Write-Output "FAIL: $name exit $($run.rc)"; $failed++ }
    else { Write-Output "PASS run: $name ($executor, x86_64 static Android binary)" }
}
Remove-Item -Recurse -Force $stage
if ($executor -and ($Tests -contains 'capability_golden_test')) {
    $files = @(Get-ChildItem $GoldenDir -Filter '*.tsv' -ErrorAction SilentlyContinue).Count
    if ($goldenSeen -eq 0) { Write-Output 'FAIL golden: the golden test produced 0 lines'; $failed++ }
    elseif ($files -ne $goldenSeen -and -not $UpdateGolden) { Write-Output "FAIL golden count: $files files, $goldenSeen produced"; $failed++ }
    else { Write-Output "GOLDEN checked: $goldenSeen scenario(s) against $files fixture file(s) in $GoldenDir" }
}
if ($failed -gt 0) { throw "$failed host test(s) failed" }
Write-Output "PASS: host native tests ($(if ($executor) { $executor } else { 'build-only' })); no device, no game process, no module touched."
