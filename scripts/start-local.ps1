param([switch]$Test, [switch]$SkipBuild,
  [string]$JdkPath = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.4.7-hotspot',
  [string]$PostgresBin = 'C:/Program Files/PostgreSQL/17/bin')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = $JdkPath
if (!(Test-Path -LiteralPath "$JdkPath/bin/java.exe")) { throw 'Pass -JdkPath with the location of Java 21.' }
if (!(Test-Path -LiteralPath "$PostgresBin/initdb.exe")) { throw 'Pass -PostgresBin with the PostgreSQL bin directory.' }
$runtime = Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Force $runtime | Out-Null
$passwordFile = Join-Path $runtime 'db-password'
if (!(Test-Path -LiteralPath $passwordFile)) {
  [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) | Set-Content -LiteralPath $passwordFile -NoNewline
}
$env:PGPASSWORD = Get-Content -LiteralPath $passwordFile -Raw
$data = Join-Path $runtime 'postgres'
if (!(Test-Path -LiteralPath (Join-Path $data 'PG_VERSION'))) {
  & "$PostgresBin/initdb.exe" -D $data -U diamond -A scram-sha-256 --pwfile=$passwordFile --encoding=UTF8 --locale=C
  if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initialization failed.' }
}
& "$PostgresBin/pg_ctl.exe" -D $data status *> $null
if ($LASTEXITCODE -ne 0) {
  $postgresLog = Join-Path $runtime 'postgres.log'
  $starter = Start-Process -FilePath "$PostgresBin/pg_ctl.exe" -ArgumentList @('-D', "`"$data`"", '-l', "`"$postgresLog`"", '-o', '"-p 55432 -h 127.0.0.1"', '-w', 'start') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'pg-start.log') -RedirectStandardError (Join-Path $runtime 'pg-start-error.log')
  if (!$starter.WaitForExit(30000)) { throw 'Database startup timed out. Check .runtime/postgres.log.' }
  if ($starter.ExitCode -ne 0) { throw 'Cannot start the project PostgreSQL instance on port 55432.' }
}
foreach ($databaseName in @('diamond_bank','diamond_bank_test')) {
  $exists = & "$PostgresBin/psql.exe" -h 127.0.0.1 -p 55432 -U diamond -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$databaseName'"
  if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to the project PostgreSQL instance.' }
  if ($exists -ne '1') {
    & "$PostgresBin/createdb.exe" -h 127.0.0.1 -p 55432 -U diamond $databaseName
    if ($LASTEXITCODE -ne 0) { throw 'Could not create project database.' }
  }
}
$env:DATABASE_USER = 'diamond'
$env:DATABASE_PASSWORD = $env:PGPASSWORD
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55432/diamond_bank'
$env:TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55432/diamond_bank_test'
$maven = Join-Path $projectRoot '.tools/apache-maven-3.9.11/bin/mvn.cmd'
if (!(Test-Path -LiteralPath $maven)) { throw 'Run scripts/setup-maven.ps1 first.' }
if ($Test) {
  & $maven '-Dmaven.repo.local=.tools/m2' -B test
  if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
} else {
  if (!$SkipBuild) {
    & $maven '-Dmaven.repo.local=.tools/m2' -B package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
  }
  & "$JdkPath/bin/java.exe" -jar target/diamond-bank-1.0.0.jar
}
