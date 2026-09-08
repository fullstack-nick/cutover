[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
function Invoke-Compose([string[]]$Arguments){
    & docker compose --project-name cutover-dev --project-directory $root --file (Join-Path $root 'infra/compose/dev.yml') --env-file (Join-Path $root '.local/images/images.env') --env-file (Join-Path $root '.local/secrets/dev.env') @Arguments
    if($LASTEXITCODE -ne 0){throw 'The scoped simulator update failed. Its database and physical history are retained; inspect the failed step before starting the process.'}
}
Push-Location $root
try {
    foreach($name in @('cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1')){
        $label=& docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $name
        if($LASTEXITCODE -ne 0 -or $label.Trim() -ne 'cutover-dev'){throw 'Simulator resource ownership could not be verified.'}
    }
    Invoke-Compose @('stop','equipment-simulator')
    Invoke-Compose @('run','--rm','--no-deps','migrate-simulator')
    Invoke-Compose @('up','-d','--no-deps','--wait','--wait-timeout','120','equipment-simulator')
    Write-Host 'Simulator migrations applied before runtime startup. Its existing database volume was preserved; verify the retained world and pending commands.'
}finally{Pop-Location}
