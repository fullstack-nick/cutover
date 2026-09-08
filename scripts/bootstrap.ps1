[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$recordPath=Join-Path $root '.local/operations/bootstrap.json'
function Save-Bootstrap {
    [IO.File]::WriteAllText("$recordPath.tmp",($script:record | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$recordPath.tmp" -Destination $recordPath -Force
}
function Invoke-Node([string[]]$Arguments){& node @Arguments;if($LASTEXITCODE -ne 0){throw "Cutover preparation failed in $($Arguments[0])."}}
function Invoke-Compose([string[]]$Arguments){
    & docker compose --project-name cutover-dev --project-directory $root --file (Join-Path $root 'infra/compose/dev.yml') --env-file (Join-Path $root '.local/images/images.env') --env-file (Join-Path $root '.local/secrets/dev.env') @Arguments
    if($LASTEXITCODE -ne 0){throw 'The scoped equipment bootstrap did not finish.'}
}
Push-Location $root
try {
    if(Test-Path -LiteralPath .local/operations/maintenance.lock){throw 'Complete the recorded Cutover maintenance operation before bootstrap.'}
    $node=& docker ps -a --filter 'name=^/cutover-control-plane$' --format '{{.ID}}'
    if($LASTEXITCODE -ne 0){throw 'The Docker Linux engine must be available.'}
    $script:record=if(Test-Path -LiteralPath $recordPath){Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json -AsHashtable}else{$null}
    $resuming=$record -and $record.state -eq 'PREPARING'
    if($node -and -not $resuming){& (Join-Path $PSScriptRoot 'demo.ps1') Start;return}
    $restored=& docker ps -a --filter 'name=^/cutover-restored-control-plane$' --format '{{.ID}}'
    if($LASTEXITCODE -ne 0 -or $restored){throw 'Finish the separate restoration lifecycle before bootstrapping the demo.'}
    foreach($name in @('cutover-dev-legacy-core-1','cutover-dev-equipment-adapter-1','cutover-dev-proxy-1')){
        $running=& docker ps --filter "name=^/$name`$" --format '{{.ID}}'
        if($LASTEXITCODE -ne 0 -or $running){throw 'Stop the older development application profile before demo bootstrap.'}
    }
    foreach($name in @('cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1','cutover-dev-equipment-volume-probe-1')){
        $present=& docker ps -a --filter "name=^/$name`$" --format '{{.ID}}'
        if($LASTEXITCODE -ne 0){throw 'Could not inspect equipment ownership.'}
        if($present){
            $box=(& docker inspect $present | ConvertFrom-Json)[0]
            if($LASTEXITCODE -ne 0 -or $box.Config.Labels.'dev.cutover.project' -ne 'cutover' -or $box.Config.Labels.'com.docker.compose.project' -ne 'cutover-dev'){throw 'A reserved equipment name has unexpected ownership.'}
            if(-not $resuming){throw 'A physical world already exists without the demo node. Use application restoration or the deliberate reset procedure; bootstrap cannot silently adopt it.'}
        }
    }
    foreach($name in @('cutover-equipment-data','cutover-equipment-volume-observation')){
        $present=& docker volume ls --filter "name=^$name`$" --format '{{.Name}}'
        if($LASTEXITCODE -ne 0){throw 'Could not inspect equipment volume ownership.'}
        if($present){
            $volume=(& docker volume inspect $name | ConvertFrom-Json)[0]
            if($LASTEXITCODE -ne 0 -or $volume.Labels.'dev.cutover.project' -ne 'cutover' -or -not $resuming){throw 'An existing physical volume requires its recorded recovery lifecycle.'}
        }
    }
    if(-not $resuming){
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $recordPath) | Out-Null
        $script:record=@{state='PREPARING';startedAt=(Get-Date).ToUniversalTime().ToString('o')};Save-Bootstrap
    }
    & (Join-Path $PSScriptRoot 'bootstrap-tools.ps1')
    & (Join-Path $PSScriptRoot 'doctor.ps1') -Profile demo
    Invoke-Node @('scripts/bootstrap-assets.mjs')
    Invoke-Node @('scripts/configure-local.mjs')
    if(-not $SkipBuild){& (Join-Path $PSScriptRoot 'build-images.ps1')}
    if(-not (Test-Path -LiteralPath .local/images/manifest.json)){throw 'Build the application images before using -SkipBuild.'}
    Invoke-Compose @('config','--quiet')
    Invoke-Compose @('up','-d','--no-deps','--wait','--wait-timeout','120','simulator-db','equipment-volume-probe')
    Invoke-Node @('scripts/ensure-compose-volume.mjs','simulator')
    Invoke-Compose @('run','--rm','--no-deps','migrate-simulator')
    Invoke-Compose @('up','-d','--no-deps','--wait','--wait-timeout','120','equipment-simulator')
    $physical=& node --input-type=module -e 'import {simulatorRead} from "./scripts/lib/local-platform.mjs"; const p=await simulatorRead("/sim/v1/equipment"); console.log(JSON.stringify({worldId:p.worldId,journalGeneration:p.journalGeneration}));'
    if($LASTEXITCODE -ne 0){throw 'The prepared simulator identity could not be recorded.'}
    $observed=$physical | ConvertFrom-Json -AsHashtable
    if($record.physical -and ($record.physical.worldId -ne $observed.worldId -or $record.physical.journalGeneration -ne $observed.journalGeneration)){throw 'The physical world changed during bootstrap. Inspect the preparation journal.'}
    $record.physical=$observed;Save-Bootstrap
    & (Join-Path $PSScriptRoot 'cluster.ps1') Create
    & (Join-Path $PSScriptRoot 'deploy.ps1')
    & (Join-Path $PSScriptRoot 'demo.ps1') Start
    $record.state='COMPLETED';$record.completedAt=(Get-Date).ToUniversalTime().ToString('o');Save-Bootstrap
    Write-Host 'Cutover is ready at http://localhost:8780. New datasets begin with legacy ownership; the documented shadow and migration walkthrough changes ownership through the API.'
}finally{Pop-Location}
