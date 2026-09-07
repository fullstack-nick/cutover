[CmdletBinding()]
param([switch]$SkipCompile)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if(-not $SkipCompile){& .\mvnw.cmd -B -ntp -DskipTests package;if($LASTEXITCODE -ne 0){throw 'Java packaging failed.'}}
    $lock=Get-Content -LiteralPath infra/versions.lock.json -Raw | ConvertFrom-Json
    $revision=(git rev-parse HEAD).Trim();$short=$revision.Substring(0,12)
    $environment=[ordered]@{
        CUTOVER_POSTGRES_IMAGE=$lock.images.postgres.reference
        CUTOVER_RABBITMQ_IMAGE=$lock.images.rabbitmq.reference
        CUTOVER_PROXY_IMAGE=$lock.images.proxy.reference
    }
    $manifest=[ordered]@{builtAt=(Get-Date).ToUniversalTime().ToString('o');revision=$revision;dirty=[bool](git status --porcelain);images=@()}
    foreach($service in @('equipment-simulator','equipment-adapter','legacy-core')){
        $jar=Join-Path $root "apps/$service/target/$service-0.1.0-SNAPSHOT-exec.jar"
        $hash=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        $dockerfileHash=(Get-FileHash -LiteralPath infra/images/application.Dockerfile -Algorithm SHA256).Hash
        $hasher=[Security.Cryptography.SHA256]::Create()
        try{$imageIdentity=-join ($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes("$hash|$dockerfileHash|$($lock.images.javaRuntime.reference)")) | ForEach-Object {$_.ToString('x2')})}finally{$hasher.Dispose()}
        $tag="cutover/$($service):$short-$($imageIdentity.Substring(0,12))"
        docker build --pull=false --build-arg "JAVA_IMAGE=$($lock.images.javaRuntime.reference)" --build-arg "SERVICE=$service" --build-arg "REVISION=$revision" --build-arg "JAR_SHA256=$hash" --file infra/images/application.Dockerfile --tag $tag .
        if($LASTEXITCODE -ne 0){throw "Image build failed for $service"}
        $id=(docker image inspect $tag --format '{{.Id}}').Trim()
        $environment["CUTOVER_$($service.ToUpperInvariant().Replace('-','_'))_IMAGE"]=$tag
        $manifest.images+=@{service=$service;reference=$tag;imageId=$id;jarSha256=$hash}
    }
    $keycloakHash=(Get-FileHash -LiteralPath infra/images/keycloak.Dockerfile -Algorithm SHA256).Hash.ToLowerInvariant().Substring(0,12)
    $keycloakTag="cutover/keycloak:$($lock.images.keycloak.version)-$short-$keycloakHash"
    docker build --pull=false --build-arg "KEYCLOAK_IMAGE=$($lock.images.keycloak.reference)" --file infra/images/keycloak.Dockerfile --tag $keycloakTag .
    if($LASTEXITCODE -ne 0){throw 'Local Keycloak image build failed.'}
    $environment['CUTOVER_KEYCLOAK_IMAGE']=$keycloakTag
    $manifest.images+=@{service='keycloak';reference=$keycloakTag;imageId=(docker image inspect $keycloakTag --format '{{.Id}}').Trim()}
    New-Item -ItemType Directory -Force -Path .local/images | Out-Null
    $environment.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content -LiteralPath .local/images/images.env -Encoding utf8
    $manifest | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath .local/images/manifest.json -Encoding utf8
    Write-Host 'Local images built and recorded. No registry upload or deployment was performed.'
} finally { Pop-Location }
