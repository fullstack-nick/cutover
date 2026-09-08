[CmdletBinding()]
param([ValidateSet('Record','Check')][string]$Action='Check',[string]$RollbackEvidence,[string]$EmptyFixture)
$ErrorActionPreference='Stop'
$cacheArguments=@($Action)
if($RollbackEvidence){$cacheArguments+="--rollback-evidence=$RollbackEvidence"}
if($EmptyFixture){$cacheArguments+="--empty-fixture=$EmptyFixture"}
& node (Join-Path $PSScriptRoot 'cache.mjs') @cacheArguments
if($LASTEXITCODE -ne 0){throw 'The prepared Cutover cache is incomplete. Follow the listed missing items before offline verification.'}
