[CmdletBinding()]
param([ValidateSet('Enable','Disable','Status','Probe')][string]$Action='Status')
$ErrorActionPreference='Stop'
& node (Join-Path $PSScriptRoot 'offline-egress.mjs') $Action
if($LASTEXITCODE -ne 0){throw 'The scoped Cutover egress operation did not complete. Inspect .local/offline/egress.json before retrying.'}
