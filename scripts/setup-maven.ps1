$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force .tools | Out-Null
$base = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
Invoke-WebRequest $base -OutFile .tools/maven.zip
$expected = (Invoke-WebRequest "$base.sha512").Content.Trim().Split(' ')[0]
$actual = (Get-FileHash .tools/maven.zip -Algorithm SHA512).Hash
if ($actual -ne $expected) { throw 'Maven archive checksum mismatch.' }
Expand-Archive -LiteralPath .tools/maven.zip -DestinationPath .tools -Force
Write-Host 'Maven is ready in .tools/apache-maven-3.9.11.'
