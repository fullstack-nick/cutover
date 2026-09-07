[CmdletBinding()]
param([ValidateSet('Start','Stop','Status')][string]$Action='Start',[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
function Invoke-Compose([string[]]$Arguments){
    & docker compose --project-name cutover-dev --project-directory $root --file (Join-Path $root 'infra/compose/dev.yml') --env-file (Join-Path $root '.local/images/images.env') --env-file (Join-Path $root '.local/secrets/dev.env') @Arguments
    if($LASTEXITCODE -ne 0){throw "Cutover Compose operation failed: $($Arguments -join ' ')"}
}
Push-Location $root
try {
    if($Action -eq 'Start'){
        & (Join-Path $PSScriptRoot 'doctor.ps1') -Profile dev
        & node scripts/configure-local.mjs
        if($LASTEXITCODE -ne 0){throw 'Local configuration failed.'}
        if(-not $SkipBuild){& (Join-Path $PSScriptRoot 'build-images.ps1')}
        Invoke-Compose @('config','--quiet')
        Invoke-Compose @('up','-d','--wait','--wait-timeout','120','application-db','simulator-db','rabbitmq')
        foreach($migration in @('migrate-core','migrate-adapter','migrate-simulator')){Invoke-Compose @('run','--rm','--no-deps',$migration)}
        Invoke-Compose @('up','-d','--wait','--wait-timeout','180','identity-migrate')
        Invoke-Compose @('stop','identity-migrate')
        Invoke-Compose @('up','-d','--wait','--wait-timeout','180','keycloak','equipment-simulator','equipment-adapter','legacy-core')
        Invoke-Compose @('up','-d','--wait','--wait-timeout','60','proxy')
        Write-Host 'Cutover development baseline is available at http://localhost:8780. Local credentials are in .local/secrets/credentials.json.'
    }elseif($Action -eq 'Stop'){
        Invoke-Compose @('stop')
        Write-Host 'Cutover stopped; all data volumes are preserved.'
    }else{Invoke-Compose @('ps','--all')}
}finally{Pop-Location}
