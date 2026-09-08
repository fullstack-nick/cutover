[CmdletBinding()]
param([ValidateSet('Start','Stop')][string]$Action='Start',[ValidateSet('console','grafana','prometheus','tempo','core-api','adapter-api')][string]$Target='console',
      [ValidateSet('demo','restoration')][string]$Profile='demo')
$ErrorActionPreference='Stop'
& node (Join-Path $PSScriptRoot 'manage-forward.mjs') "--action=$Action" "--target=$Target" "--profile=$Profile"
if($LASTEXITCODE -ne 0){throw 'The recorded Cutover forward could not complete its lifecycle action; inspect the message and its private process log.'}
