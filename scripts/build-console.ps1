[CmdletBinding()]
param([switch]$SkipInstall)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if(-not $SkipInstall){
        npm ci --prefix tools/api-types --no-fund --no-audit
        if($LASTEXITCODE -ne 0){throw 'API generator dependency installation failed.'}
        npm ci --prefix apps/operations-console --no-fund --no-audit
        if($LASTEXITCODE -ne 0){throw 'Console dependency installation failed.'}
    }
    npm run build --prefix apps/operations-console
    if($LASTEXITCODE -ne 0){throw 'Console type generation, type check or build failed.'}
    $lock=Get-Content -LiteralPath infra/versions.lock.json -Raw | ConvertFrom-Json
    $revision=(git rev-parse HEAD).Trim()
    $identity=@($lock.images.proxy.reference,(Get-FileHash -LiteralPath infra/images/console.Dockerfile -Algorithm SHA256).Hash)
    $files=Get-ChildItem -LiteralPath apps/operations-console/dist -File -Recurse | Sort-Object FullName
    foreach($file in $files){$identity+="$($file.Name):$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash)"}
    $hasher=[Security.Cryptography.SHA256]::Create()
    try{$hash=-join ($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(($identity -join '|'))) | ForEach-Object {$_.ToString('x2')})}finally{$hasher.Dispose()}
    $tag="cutover/operations-console:$($revision.Substring(0,12))-$($hash.Substring(0,12))"
    docker build --pull=false --build-arg "PROXY_IMAGE=$($lock.images.proxy.reference)" --build-arg "REVISION=$revision" --build-arg "CONTENT_SHA256=$hash" --file infra/images/console.Dockerfile --tag $tag .
    if($LASTEXITCODE -ne 0){throw 'Console image build failed.'}
    $manifest=Get-Content -LiteralPath .local/images/manifest.json -Raw | ConvertFrom-Json
    $manifest.images=@($manifest.images | Where-Object { $_.service -ne 'operations-console' })+@(@{service='operations-console';reference=$tag;imageId=(docker image inspect $tag --format '{{.Id}}').Trim();contentSha256=$hash;builtAt=(Get-Date).ToUniversalTime().ToString('o');revision=$revision;dirty=[bool](git status --porcelain)})
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath .local/images/manifest.json -Encoding utf8
    $environment=@(Get-Content -LiteralPath .local/images/images.env | Where-Object { -not $_.StartsWith('CUTOVER_OPERATIONS_CONSOLE_IMAGE=') })+"CUTOVER_OPERATIONS_CONSOLE_IMAGE=$tag"
    $environment | Set-Content -LiteralPath .local/images/images.env -Encoding utf8
    Write-Host 'Built and recorded the local console image.'
}finally{Pop-Location}
