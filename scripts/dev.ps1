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
    if($Action -ne 'Status' -and (Test-Path -LiteralPath (Join-Path $root '.local/operations/maintenance.lock'))){throw 'Cutover checkpoint or recovery maintenance is active; use its recorded lifecycle operation.'}
    $kindNodes=@()
    foreach($name in @('cutover-control-plane','cutover-restored-control-plane')){
        $id=& docker ps -a --filter "name=^/$name`$" --format '{{.ID}}'
        if($LASTEXITCODE -ne 0){throw 'Could not inspect Cutover profile ownership.'}
        if($id){$kindNodes+=(& docker inspect $id | ConvertFrom-Json)}
    }
    if($Action -eq 'Start'){
        if($kindNodes.Count -gt 0){throw 'A preserved Cutover kind environment exists. Use its demo/restoration lifecycle instead of starting an older development database copy.'}
        & (Join-Path $PSScriptRoot 'doctor.ps1') -Profile dev
        & node scripts/configure-local.mjs
        if($LASTEXITCODE -ne 0){throw 'Local configuration failed.'}
        if(-not $SkipBuild){& (Join-Path $PSScriptRoot 'build-images.ps1')}
        Invoke-Compose @('config','--quiet')
        Invoke-Compose @('up','-d','--wait','--wait-timeout','120','application-db','simulator-db','rabbitmq','application-volume-probe','equipment-volume-probe')
        foreach($database in @('application','simulator')){& node scripts/ensure-compose-volume.mjs $database;if($LASTEXITCODE -ne 0){throw 'Local database-volume observation setup failed.'}}
        foreach($migration in @('migrate-core','migrate-adapter','migrate-simulator')){Invoke-Compose @('run','--rm','--no-deps',$migration)}
        Invoke-Compose @('up','-d','--wait','--wait-timeout','180','identity-migrate')
        Invoke-Compose @('stop','identity-migrate')
        Invoke-Compose @('up','-d','--wait','--wait-timeout','180','keycloak','equipment-simulator','equipment-adapter','legacy-core')
        Invoke-Compose @('up','-d','--wait','--wait-timeout','60','proxy')
        Write-Host 'Cutover development baseline is available at http://localhost:8780. Local credentials are in .local/secrets/credentials.json.'
    }elseif($Action -eq 'Stop'){
        Invoke-Compose @('stop','proxy','legacy-core','equipment-adapter','keycloak','identity-migrate','rabbitmq','application-db','application-volume-probe')
        if(-not ($kindNodes | Where-Object {$_.State.Running})){Invoke-Compose @('stop','equipment-simulator','simulator-db','equipment-volume-probe')}
        Write-Host 'Development services stopped with their volumes preserved. A simulator used by a running Cutover kind environment remains available.'
    }else{Invoke-Compose @('ps','--all')}
}finally{Pop-Location}
