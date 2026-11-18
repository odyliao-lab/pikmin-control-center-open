$ErrorActionPreference = 'Stop'
$src = Join-Path (Split-Path $PSScriptRoot -Parent) 'src/dev/ody/pikmincontrol'
$export = Get-Content -LiteralPath (Join-Path $src 'DiagnosticsExporter.java') -Raw
$ui = Get-Content -LiteralPath (Join-Path $src 'MainActivity.java') -Raw
$envReport = Get-Content -LiteralPath (Join-Path $src 'EnvironmentReport.java') -Raw
$checks = @(
    @($export.Contains('return export(context, false)'), 'summary export defaults to no raw data'),
    @(($export.IndexOf('if (!includeRaw) return out;') -lt $export.IndexOf('collector.root(')), 'all root log sections are behind opt-in'),
    @($export.Contains('BoundedCommand.root(command, 2, 32768)'), 'root output and duration bounded'),
    @($export.Contains('elapsedRealtime() >= deadline'), 'collection has overall deadline'),
    @(!$export.Contains("grep -iE 'SIGSEGV"), 'no global cross-app crash log export'),
    @($ui.Contains('Intent.ACTION_CREATE_DOCUMENT'), 'user chooses save destination'),
    @($ui.Contains('DiagnosticsExporter.preview(f)'), 'preview before save'),
    @($envReport.Contains('Presentation only'), 'environment report is not execution authority')
)
foreach ($check in $checks) { if (!$check[0]) { throw "FAIL: $($check[1])" }; Write-Output "PASS: $($check[1])" }
