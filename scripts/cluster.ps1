[CmdletBinding()]
param([ValidateSet('Create','Status')][string]$Action='Create')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$kind=Join-Path $root '.local/tools/kind.exe'
$config=Join-Path $root '.local/kubeconfig'
$lock=Get-Content -LiteralPath (Join-Path $root 'infra/versions.lock.json') -Raw | ConvertFrom-Json
function Invoke-Kubectl([string[]]$Arguments){
    & kubectl --kubeconfig $config --context kind-cutover @Arguments
    if($LASTEXITCODE -ne 0){throw "Cutover kubectl failed: $($Arguments -join ' ')"}
}
Push-Location $root
$priorNetwork=$env:KIND_EXPERIMENTAL_DOCKER_NETWORK
try {
    if($Action -eq 'Status'){Invoke-Kubectl @('get','nodes','-o','wide');Invoke-Kubectl @('get','pods','-A');return}
    & (Join-Path $PSScriptRoot 'doctor.ps1') -Profile demo
    if(-not (Test-Path -LiteralPath $kind)){throw 'Run bootstrap-tools.ps1 first.'}
    $hash=(Get-FileHash -LiteralPath infra/vendor/calico/v3.32.2/calico.yaml -Algorithm SHA256).Hash.ToLowerInvariant()
    if($hash -ne $lock.calicoManifest.sha256){throw 'Calico manifest checksum differs from the implementation lock.'}
    $network=docker network inspect cutover-kind --format '{{index .Labels "dev.cutover.project"}}' 2>$null
    if($LASTEXITCODE -ne 0){
        docker network create --label dev.cutover.project=cutover cutover-kind | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Could not create the dedicated Cutover node network.'}
    }elseif($network -ne 'cutover'){throw 'The cutover-kind network exists without the expected ownership label.'}
    $env:KIND_EXPERIMENTAL_DOCKER_NETWORK='cutover-kind'
    $clusters=& $kind get clusters
    if($clusters -notcontains 'cutover'){
        & $kind create cluster --name cutover --image $lock.images.kindNode.reference --config infra/kind/cluster.yaml --kubeconfig $config --wait 0s
        if($LASTEXITCODE -ne 0){throw 'Cutover cluster creation failed.'}
    }else{
        $owner=docker inspect cutover-control-plane --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}'
        if($LASTEXITCODE -ne 0 -or $owner -ne 'cutover'){throw 'Existing node ownership could not be verified.'}
        & $kind export kubeconfig --name cutover --kubeconfig $config
        if($LASTEXITCODE -ne 0){throw 'Could not export the project kubeconfig.'}
    }
    & node scripts/load-images.mjs --calico
    if($LASTEXITCODE -ne 0){throw 'Could not load the pinned Calico images.'}
    & node scripts/render-calico.mjs
    if($LASTEXITCODE -ne 0){throw 'Could not render Calico with verified platform manifests.'}
    Invoke-Kubectl @('apply','--server-side','--field-manager=cutover-bootstrap','-k','.local/kubernetes/calico')
    Invoke-Kubectl @('rollout','status','daemonset/calico-node','-n','kube-system','--timeout=180s')
    Invoke-Kubectl @('wait','--for=condition=Ready','node/cutover-control-plane','--timeout=180s')
    Invoke-Kubectl @('rollout','status','deployment/calico-kube-controllers','-n','kube-system','--timeout=180s')
    Write-Host 'Dedicated Cutover Kubernetes node and Calico are ready. All commands used the project kubeconfig.'
}finally{
    $env:KIND_EXPERIMENTAL_DOCKER_NETWORK=$priorNetwork
    Pop-Location
}
