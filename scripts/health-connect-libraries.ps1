# Materialize the already-reviewed runtime graph; no Gradle resolution during product builds.
function Get-HealthConnectLibraries([string]$Repo,[string]$Stage) {
    $lock=Get-Content -LiteralPath (Join-Path $Repo 'scripts/health-connect-dependencies.lock.json') -Raw | ConvertFrom-Json
    if($lock.schema -ne 1 -or $lock.artifacts.Count -ne 36){throw 'Unexpected HC dependency graph'}
    $cache=Join-Path $Repo '.tools/health-connect'
    New-Item -ItemType Directory -Force $cache,$Stage | Out-Null
    $jars=@(); $resources=@(); $namespaces=@(); $seen=@{}
    foreach($a in $lock.artifacts) {
        if($a.group -notmatch '^[a-zA-Z0-9.]+$' -or $a.name -notmatch '^[a-zA-Z0-9_.-]+$' -or $a.version -notmatch '^[a-zA-Z0-9_.-]+$' -or $a.type -notin @('jar','aar') -or $a.sha256 -notmatch '^[a-f0-9]{64}$'){throw 'Malformed HC lock entry'}
        $base=if($a.group.StartsWith('androidx.')){'https://dl.google.com/dl/android/maven2/'}else{'https://repo.maven.apache.org/maven2/'}
        $name="$($a.name)-$($a.version).$($a.type)"
        $url=$base+$a.group.Replace('.','/')+"/$($a.name)/$($a.version)/$name"
        if($a.url -cne $url -or $seen.ContainsKey($a.group+':'+$a.name)){throw 'HC lock URL or duplicate rejected'}
        $seen[$a.group+':'+$a.name]=$true
        $raw=Join-Path $cache ($a.sha256+'.'+$a.type)
        if(!(Test-Path -LiteralPath $raw -PathType Leaf)) {
            $gradleCache=Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$($a.group)/$($a.name)/$($a.version)"
            $local=@(Get-ChildItem -LiteralPath $gradleCache -Recurse -File -Filter $name -ErrorAction SilentlyContinue | Where-Object { (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() -ceq $a.sha256 })
            if($local.Count){ Copy-Item -LiteralPath $local[0].FullName -Destination $raw }
            else { Invoke-WebRequest -TimeoutSec 45 -Uri $url -OutFile $raw }
        }
        if((Get-FileHash -LiteralPath $raw).Hash.ToLowerInvariant() -cne $a.sha256){throw "HC dependency hash mismatch: $($a.name)"}
        if($a.type -eq 'jar'){ $jars+=$raw; continue }
        $dir=Join-Path $Stage $a.sha256
        # All extraction stays inside this caller-owned fresh stage. Check archive paths even after hash verification.
        $zip=[IO.Compression.ZipFile]::OpenRead($raw)
        try { foreach($entry in $zip.Entries){ if($entry.FullName -match '(^[/\\]|(^|[/\\])\.\.([/\\]|$)|:)' ){throw 'Unsafe HC archive entry'} } } finally { $zip.Dispose() }
        [IO.Compression.ZipFile]::ExtractToDirectory($raw,$dir)
        if(Test-Path "$dir/classes.jar"){ $jars+="$dir/classes.jar" }
        $jars+=@(Get-ChildItem "$dir/libs" -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object FullName)
        if(Test-Path "$dir/res"){ $resources+="$dir/res" }
        if(Test-Path "$dir/AndroidManifest.xml"){
            [xml]$manifest=Get-Content "$dir/AndroidManifest.xml" -Raw
            if($manifest.manifest.package -match '^[a-zA-Z0-9_.]+$'){ $namespaces+=[string]$manifest.manifest.package }
        }
    }
    if($jars.Count -lt 30){throw 'HC runtime graph incomplete'}
    return @{Jars=$jars; Resources=$resources; Namespaces=@($namespaces | Sort-Object -Unique)}
}

function Get-HealthConnectNotices($Jars) {
    $texts=@()
    foreach($jar in $Jars){
        $zip=[IO.Compression.ZipFile]::OpenRead($jar)
        try{
            foreach($entry in $zip.Entries){
                if($entry.FullName -notmatch '(?i)(^|/)(license[^/]*|notice[^/]*|copyright[^/]*)$'){continue}
                if($entry.Length -gt 131072){throw 'Unexpected oversized library notice'}
                $reader=[IO.StreamReader]::new($entry.Open())
                try{$texts+=($entry.FullName+"`n"+$reader.ReadToEnd())}finally{$reader.Dispose()}
            }
        }finally{$zip.Dispose()}
    }
    if(!(($texts -join "`n") -match 'Apache License')){throw 'Missing complete Apache license in pinned runtime libraries'}
    return ($texts -join "`n`n-----`n`n")
}

function Add-HealthConnectServiceResources($Jars,[string]$Stage) {
    $providers=@{}
    foreach($jar in $Jars){
        $zip=[IO.Compression.ZipFile]::OpenRead($jar)
        try{foreach($entry in $zip.Entries){
            if($entry.FullName -notmatch '^META-INF/services/[A-Za-z0-9_.$]+$'){continue}
            if($entry.Length -gt 32768){throw 'Oversized service-provider resource'}
            $reader=[IO.StreamReader]::new($entry.Open())
            try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
            foreach($line in ($text -split "`n")){
                $provider=($line -split '#',2)[0].Trim()
                if(!$provider){continue}
                if($provider -notmatch '^[A-Za-z0-9_.$]+$'){throw 'Malformed service provider'}
                $providers[$entry.FullName]=@($providers[$entry.FullName])+@($provider)
            }
        }}finally{$zip.Dispose()}
    }
    foreach($path in $providers.Keys){
        $dest=Join-Path $Stage $path
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        [IO.File]::WriteAllText($dest,((@($providers[$path] | Sort-Object -Unique) -join "`n")+"`n"),[Text.UTF8Encoding]::new($false))
    }
    return @($providers.Keys)
}
