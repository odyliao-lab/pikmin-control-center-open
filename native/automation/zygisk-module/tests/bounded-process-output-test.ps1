$ErrorActionPreference='Stop'
. "$PSScriptRoot/bounded-process-output.ps1"
if(-not ('PccOutputDrain.Capture' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
namespace PccOutputDrain {
    public sealed class Capture : IDisposable {
        public Process Process { get; }
        private readonly StreamWriter writer;
        private readonly ManualResetEventSlim gate;
        public Capture(string shell,string command,string path,int delay) {
            gate=new ManualResetEventSlim(delay>=0);
            writer=new StreamWriter(path,false,new UTF8Encoding(false)) { AutoFlush=true };
            var info=new ProcessStartInfo(shell) {
                UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true
            };
            info.ArgumentList.Add("-NoProfile");
            info.ArgumentList.Add("-EncodedCommand");
            info.ArgumentList.Add(Convert.ToBase64String(Encoding.Unicode.GetBytes(command)));
            Process=new Process { StartInfo=info };
            Process.OutputDataReceived+=(sender,e)=> {
                if(e.Data==null) { writer.Dispose();return; }
                gate.Wait(5000);Thread.Sleep(Math.Abs(delay));
                writer.WriteLine(e.Data);
            };
            Process.Start();Process.BeginOutputReadLine();
        }
        public void Release() { gate.Set(); }
        public void Dispose() {
            gate.Set();
            if(!Process.HasExited) Process.Kill();
            if(!Process.WaitForExitAsync().Wait(5000)) throw new Exception("fixture output failed to drain");
            Process.Dispose();writer.Dispose();gate.Dispose();
        }
    }
}
'@
}
$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$stage=Join-Path $tempRoot ('pcc-output-drain-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
$shell=(Get-Process -Id $PID).Path
$captures=[Collections.Generic.List[IDisposable]]::new()
try {
    $out=Join-Path $stage 'golden.txt'
    $capture=[PccOutputDrain.Capture]::new($shell,'1..18 | ForEach-Object { [Console]::WriteLine("GOLDEN`trow$_`tvalue$_") }',$out,-60)
    $captures.Add($capture)
    if(-not $capture.Process.WaitForExit(20000)){throw 'Fixture did not exit'}
    # Delayed real async handlers reproduce the old early-file-read counterexample.
    $early=@(Get-Content -LiteralPath $out).Count
    if($early -ge 18){throw 'Fixture failed to expose exit-before-output completion'}
    $capture.Release()
    $run=Complete-BoundedProcessOutput $capture.Process $out 20000
    if($run.rc -ne 0 -or $run.lines.Count -ne 18){throw 'Lost redirected golden rows'}
    for($i=1;$i -le 18;$i++){if($run.lines[$i-1] -cne "GOLDEN`trow$i`tvalue$i"){throw 'Golden output changed'}}
    $out=Join-Path $stage 'rejected.txt'
    $capture=[PccOutputDrain.Capture]::new($shell,'1..18 | ForEach-Object { [Console]::WriteLine($_) }',$out,-60)
    $captures.Add($capture)
    if(-not $capture.Process.WaitForExit(20000)){throw 'Drain timeout fixture did not exit'}
    $run=Complete-BoundedProcessOutput $capture.Process $out 20000 1
    if($run.rc -ne 125 -or $run.lines.Count -ne 0){throw 'Partial capture accepted'}
    $capture.Release()
    $out=Join-Path $stage 'nonzero.txt'
    $capture=[PccOutputDrain.Capture]::new($shell,'[Console]::WriteLine("failure detail");exit 7',$out,30)
    $captures.Add($capture)
    $run=Complete-BoundedProcessOutput $capture.Process $out 20000
    if($run.rc -ne 7 -or $run.lines.Count -ne 1 -or $run.lines[0] -cne 'failure detail'){throw 'Exit status or output lost'}
    $out=Join-Path $stage 'timeout.txt'
    $capture=[PccOutputDrain.Capture]::new($shell,'Start-Sleep -Seconds 30',$out,0)
    $captures.Add($capture)
    $run=Complete-BoundedProcessOutput $capture.Process $out 100
    if($run.rc -ne 124 -or $run.lines.Count -ne 0){throw 'Execution timeout accepted'}
    Write-Output "PASS process output: early read=$early/18 reproduced; full drain, bounded drain rejection, nonzero and execution timeout"
} finally {
    foreach($capture in $captures){$capture.Dispose()}
    $resolved=[IO.Path]::GetFullPath($stage)
    if(-not $resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or
        -not [IO.Path]::GetFileName($resolved).StartsWith('pcc-output-drain-')){throw 'Unsafe fixture cleanup path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
