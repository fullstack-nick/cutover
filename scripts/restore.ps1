[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{2,63}$')][string]$Name,
      [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{2,63}$')][string]$Run)
$ErrorActionPreference='Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try { & node scripts/restore.mjs "--name=$Name" "--run=$Run";if($LASTEXITCODE -ne 0){throw 'Cutover restoration failed; inspect the private run journal.'} }
finally { Pop-Location }
