[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{2,63}$')][string]$Name)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
try { & node scripts/backup.mjs "--name=$Name"; if($LASTEXITCODE -ne 0){throw 'Cutover checkpoint failed; inspect its private recovery journal.'} }
finally { Pop-Location }
