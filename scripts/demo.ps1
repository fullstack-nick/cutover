[CmdletBinding()]
param([ValidateSet('Start','Stop','Status')][string]$Action='Status')
$ErrorActionPreference='Stop'
& node (Join-Path $PSScriptRoot 'demo.mjs') $Action
if($LASTEXITCODE -ne 0){throw 'The Cutover lifecycle operation did not finish. Inspect .local/operations/demo-lifecycle.json before retrying.'}
