function Remove-BuildStage([string]$Path, [string]$Prefix) {
    $base = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    $resolved = [IO.Path]::GetFullPath($Path)
    if ([IO.Path]::GetDirectoryName($resolved) -ne $base -or
            [IO.Path]::GetFileName($resolved) -notmatch ('^' + [regex]::Escape($Prefix) + '[a-f0-9]{32}$')) {
        throw 'Refusing cleanup outside the exact generated build stage'
    }
    if (Test-Path -LiteralPath $resolved) {
        if ((Get-Item -LiteralPath $resolved -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing linked staging directory' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
