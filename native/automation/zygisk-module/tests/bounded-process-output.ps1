# Process exit does not imply that redirected OutputDataReceived handlers finished.
# WaitForExitAsync also drains their EOF, with an explicit second deadline.
function Complete-BoundedProcessOutput {
    param([Diagnostics.Process]$Process,[string]$OutputPath,
        [int]$TimeoutMilliseconds,[int]$DrainMilliseconds=5000)
    if(-not $Process.WaitForExit($TimeoutMilliseconds)) {
        try { $Process.Kill() } catch {}
        return @{rc=124;lines=@()}
    }
    if(-not $Process.WaitForExitAsync().Wait($DrainMilliseconds)) {
        return @{rc=125;lines=@()} # Incomplete capture must never pass golden validation.
    }
    $lines=if(Test-Path -LiteralPath $OutputPath){Get-Content -LiteralPath $OutputPath}else{@()}
    return @{rc=$Process.ExitCode;lines=@($lines)}
}
