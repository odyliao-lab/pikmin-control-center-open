param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if (!$JavaHome) { throw 'JavaHome or JAVA_HOME is required' }
$component=Split-Path $PSScriptRoot -Parent
$stage=Join-Path ([IO.Path]::GetTempPath()) ('pcc-hc-policy-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
try {
  & "$JavaHome/bin/javac.exe" -encoding UTF-8 -d $stage "$component/src/dev/ody/pikmincontrol/hccompat/CompatPolicy.java" "$PSScriptRoot/CompatPolicyTest.java"
  if ($LASTEXITCODE -ne 0) { throw 'HC policy compile failed' }
  & "$JavaHome/bin/java.exe" -cp $stage CompatPolicyTest
  if ($LASTEXITCODE -ne 0) { throw 'HC policy checks failed' }
} finally {
  # Only the freshly created, unique policy compile directory is removed.
  if ((Split-Path $stage -Parent) -ne ([IO.Path]::GetTempPath()).TrimEnd('\','/')) { throw 'Unexpected policy temp directory' }
  Remove-Item -LiteralPath $stage -Recurse -Force
}
