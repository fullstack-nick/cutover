[CmdletBinding()]
param([Parameter(Mandatory)][string]$Checkpoint,[switch]$DestroyCutover,[switch]$NewWorld)
$ErrorActionPreference='Stop'
if(-not $DestroyCutover -or -not $NewWorld){throw 'Reset deliberately removes the Cutover dataset. Both -DestroyCutover and -NewWorld are required; use demo.ps1 Stop to preserve data.'}
& node (Join-Path $PSScriptRoot 'reset.mjs') "--checkpoint=$Checkpoint" --destroy-cutover --new-world
if($LASTEXITCODE -ne 0){throw 'The explicit reset did not finish. Inspect its private journal before retrying with the same checkpoint.'}
