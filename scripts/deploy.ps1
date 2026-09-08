[CmdletBinding()]
param([switch]$TransferBaseline,[switch]$SkipImageLoad,[ValidateSet('latest','109')][string]$LegacyMigrationTarget='latest')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$config=Join-Path $root '.local/kubeconfig'
if(Test-Path -LiteralPath (Join-Path $root '.local/operations/maintenance.lock')){throw 'Cutover checkpoint or recovery maintenance is active. Inspect its private journal before deployment.'}
function Invoke-Kubectl([string[]]$Arguments){
    & kubectl --kubeconfig $config --context kind-cutover @Arguments
    if($LASTEXITCODE -ne 0){throw "Cutover deployment operation failed: $($Arguments -join ' ')"}
}
function Run-Jobs([string]$group,[string]$namespace,[string[]]$names){
    foreach($name in $names){
        $old=& kubectl --kubeconfig $config --context kind-cutover -n $namespace get job $name -o json --ignore-not-found
        if($LASTEXITCODE -ne 0){throw 'Could not inspect a prior migration Job.'}
        if($old){
            $job=$old | ConvertFrom-Json
            if($job.metadata.labels.'app.kubernetes.io/part-of' -ne 'cutover' -or $job.status.active -gt 0){throw 'A prior migration is active or has unexpected ownership.'}
            Invoke-Kubectl @('-n',$namespace,'delete','job',$name,'--wait=true')
        }
    }
    Invoke-Kubectl @('apply','-k',".local/kubernetes/demo/$group")
    foreach($name in $names){Invoke-Kubectl @('-n',$namespace,'wait','--for=condition=complete',"job/$name",'--timeout=240s')}
}
Push-Location $root
try {
    Write-Host "Deploying only context kind-cutover using $config"
    $node=& kubectl --kubeconfig $config --context kind-cutover get node cutover-control-plane -o json | ConvertFrom-Json
    if($LASTEXITCODE -ne 0 -or $node.metadata.labels.'dev.cutover.project' -ne 'cutover'){throw 'Cutover node ownership check failed.'}
    if(-not $SkipImageLoad){& node scripts/load-images.mjs;if($LASTEXITCODE -ne 0){throw 'Node image loading failed.'}}
    $priorLegacyTarget=$env:CUTOVER_LEGACY_MIGRATION_TARGET
    try {$env:CUTOVER_LEGACY_MIGRATION_TARGET=$LegacyMigrationTarget;& node scripts/render-demo.mjs}
    finally {$env:CUTOVER_LEGACY_MIGRATION_TARGET=$priorLegacyTarget}
    if($LASTEXITCODE -ne 0){throw 'Platform rendering failed.'}
    Invoke-Kubectl @('apply','-k','.local/kubernetes/demo/namespaces')
    # Diff output may contain generated Secret data, so retain it only in ignored local storage.
    $priorDiff=$env:KUBECTL_EXTERNAL_DIFF
    try {
        $env:KUBECTL_EXTERNAL_DIFF='git diff --no-index --'
        & kubectl --kubeconfig $config --context kind-cutover diff -k .local/kubernetes/demo/foundation *> .local/operations/deploy-foundation.diff
    }finally{$env:KUBECTL_EXTERNAL_DIFF=$priorDiff}
    if($LASTEXITCODE -gt 1){throw 'The rendered foundation diff could not be checked.'}
    Write-Host 'Rendered foundation diff is recorded privately; applying the reviewed local templates.'
    Invoke-Kubectl @('apply','-k','.local/kubernetes/demo/foundation')
    Invoke-Kubectl @('-n','cutover-platform','rollout','status','statefulset/application-db','--timeout=180s')
    Invoke-Kubectl @('-n','cutover-platform','rollout','status','statefulset/rabbitmq','--timeout=180s')
    if($TransferBaseline){& node scripts/transfer-baseline.mjs restore;if($LASTEXITCODE -ne 0){throw 'Frozen baseline transfer failed.'}}
    & node scripts/ensure-databases.mjs
    if($LASTEXITCODE -ne 0){throw 'Owner database provisioning failed.'}
    Run-Jobs 'migrations' 'cutover-apps' @('migrate-legacy-core','migrate-equipment-adapter','migrate-execution-service','migrate-shadow-scheduler','migrate-returns-service')
    # Stop the runtime identity process before its migration account makes schema changes.
    $identity=& kubectl --kubeconfig $config --context kind-cutover -n cutover-platform get deployment keycloak --ignore-not-found -o name
    if($identity){Invoke-Kubectl @('-n','cutover-platform','scale','deployment/keycloak','--replicas=0');Invoke-Kubectl @('-n','cutover-platform','wait','--for=delete','pod','-l','app.kubernetes.io/name=keycloak','--timeout=90s')}
    Run-Jobs 'identity-migration' 'cutover-platform' @('migrate-keycloak')
    Invoke-Kubectl @('apply','-k','.local/kubernetes/demo/runtime')
    foreach($entry in @(@('cutover-platform','deployment/keycloak'),@('cutover-apps','deployment/equipment-adapter'),@('cutover-apps','deployment/legacy-core'),@('cutover-apps','deployment/proxy'),@('cutover-observability','deployment/collector'),@('cutover-observability','statefulset/prometheus'),@('cutover-observability','statefulset/tempo'),@('cutover-observability','statefulset/grafana'))){Invoke-Kubectl @('-n',$entry[0],'rollout','status',$entry[1],'--timeout=240s')}
    & (Join-Path $PSScriptRoot 'forward.ps1') -Action Start -Target console
    & node scripts/ensure-local-users.mjs
    if($LASTEXITCODE -ne 0){throw 'Local identity fixture verification failed.'}
    foreach($name in @('execution-service','shadow-scheduler','returns-service')){Invoke-Kubectl @('-n','cutover-apps','rollout','status',"deployment/$name",'--timeout=240s')}
    Write-Host 'Cutover local platform rolled out. Verify its behavior before recording acceptance.'
}finally{Pop-Location}
