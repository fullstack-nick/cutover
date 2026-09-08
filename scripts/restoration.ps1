[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{2,63}$')][string]$Run,
      [Parameter(Mandatory)][ValidateSet('Release','Reconcile','Open','Start','Stop','ResumeDemo','Remove')][string]$Action)
$ErrorActionPreference='Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try { & node scripts/restoration.mjs "--run=$Run" "--action=$Action";if($LASTEXITCODE -ne 0){throw 'Cutover restoration lifecycle action failed; inspect its saved journal.'} }
finally { Pop-Location }
