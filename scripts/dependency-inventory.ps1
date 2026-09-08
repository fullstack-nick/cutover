[CmdletBinding()]
param([string]$OutputPath='docs/evidence/dependencies.json')
$ErrorActionPreference='Stop'
$repositoryRoot=(git rev-parse --show-toplevel).Trim()
if($LASTEXITCODE -ne 0){throw 'Run this command from the Cutover Git repository.'}
$repositoryRoot=[IO.Path]::GetFullPath($repositoryRoot)
$outputFile=[IO.Path]::GetFullPath($OutputPath,$repositoryRoot)
if(-not $outputFile.StartsWith($repositoryRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Inventory output must stay inside this repository.'}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$manifest=Get-Content -LiteralPath (Join-Path $repositoryRoot '.local/images/manifest.json') -Raw | ConvertFrom-Json
if($manifest.dirty){throw 'Build clean, verified application images before recording their dependency inventory.'}
git diff --quiet $manifest.revision -- apps/operations-console/package-lock.json tools/api-types/package-lock.json infra/versions.lock.json
if($LASTEXITCODE -ne 0){throw 'Rebuild the images after changing dependency lockfiles or pinned platform inputs.'}
$components=@{}
$applications=@()
$hash=[Security.Cryptography.SHA256]::Create()
try {
    foreach($built in $manifest.images | Where-Object { $_.jarSha256 }){
        $module="apps/$($built.service)"
        $jars=@([IO.Directory]::EnumerateFiles((Join-Path $repositoryRoot "$module/target"),'*-exec.jar'))
        if($jars.Count -ne 1){throw "Expected exactly one packaged executable JAR for $module."}
        $jar=$jars[0]
        $jarHash=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        if($jarHash -ne $built.jarSha256){throw "The packaged JAR for $module differs from the built image."}
        $archive=[IO.Compression.ZipFile]::OpenRead($jar)
        $dependencies=@()
        try {
            foreach($entry in $archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*.jar' }){
                $memory=[IO.MemoryStream]::new()
                $stream=$entry.Open()
                try{$stream.CopyTo($memory)}finally{$stream.Dispose()}
                try {
                    $memory.Position=0
                    $digest=[Convert]::ToHexString($hash.ComputeHash($memory)).ToLowerInvariant()
                    if(-not $components.ContainsKey($digest)){
                        $memory.Position=0
                        $nested=[IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read,$true)
                        try {
                            $coordinates=@()
                            foreach($metadata in $nested.Entries | Where-Object { $_.FullName -match '^META-INF/maven/.+/pom.properties$' }){
                                $reader=[IO.StreamReader]::new($metadata.Open())
                                try{$properties=$reader.ReadToEnd()}finally{$reader.Dispose()}
                                $values=@{}
                                foreach($match in [regex]::Matches($properties,'(?m)^(groupId|artifactId|version)=(.+)$')){$values[$match.Groups[1].Value]=$match.Groups[2].Value.Trim()}
                                if($values.Count -eq 3){$coordinates+=[ordered]@{groupId=$values.groupId;artifactId=$values.artifactId;version=$values.version}}
                            }
                            $licenseResources=@($nested.Entries | Where-Object { $_.FullName -match '(?i)(^|/)(LICENSE|LICENCE|NOTICE|COPYING)([./_-]|$)' } | ForEach-Object FullName | Sort-Object -Unique)
                            $components[$digest]=[ordered]@{file=$entry.Name;sha256=$digest;bytes=$entry.Length;embeddedMavenCoordinates=@($coordinates | Sort-Object groupId,artifactId,version);embeddedLicenseAndNoticeResources=$licenseResources}
                        } finally {$nested.Dispose()}
                    }
                    $dependencies+=$digest
                } finally {$memory.Dispose()}
            }
        } finally {$archive.Dispose()}
        if($dependencies.Count -lt 10){throw "The packaged runtime libraries are missing for $module."}
        $applications+=[ordered]@{module=$module;file=[IO.Path]::GetFileName($jar);sha256=$jarHash;dependencySha256=@($dependencies | Sort-Object -Unique)}
    }
} finally {$hash.Dispose()}
if($applications.Count -ne 5){throw 'Expected the five packaged Java applications.'}
$npm=@()
foreach($lockPath in @('apps/operations-console/package-lock.json','tools/api-types/package-lock.json')){
    $lock=Get-Content -LiteralPath (Join-Path $repositoryRoot $lockPath) -Raw | ConvertFrom-Json -AsHashtable
    $packages=@()
    foreach($entry in $lock.packages.GetEnumerator() | Where-Object { $_.Key }){
        $value=$entry.Value
        $packages+=[ordered]@{path=$entry.Key;version=$value.version;resolved=$value.resolved;integrity=$value.integrity;declaredLicense=$value.license;developmentOnly=[bool]$value.dev;optional=[bool]$value.optional}
    }
    $npm+=[ordered]@{lockfile=$lockPath;lockfileSha256=(Get-FileHash -LiteralPath (Join-Path $repositoryRoot $lockPath) -Algorithm SHA256).Hash.ToLowerInvariant();packages=@($packages | Sort-Object path)}
}
$versions=Get-Content -LiteralPath (Join-Path $repositoryRoot 'infra/versions.lock.json') -Raw | ConvertFrom-Json
$inventory=[ordered]@{
    format=1
    generatedAt=(Get-Date).ToUniversalTime().ToString('o')
    packagedBuildRevision=$manifest.revision
    scope='Every BOOT-INF/lib JAR in the five built Java executables; both complete npm lockfiles, including build, test and optional platform packages; and all pinned infrastructure/base images and tools. Shadow scheduling reuses the execution-service binary. Container operating-system package inventories remain part of the upstream images, not this application dependency list.'
    licenseMetadata='Npm license strings are the lockfile declarations. Java coordinates and notice paths come from the exact packaged library bytes; absent embedded metadata is left absent rather than guessed. Dependencies and base images retain their own upstream licenses.'
    javaApplications=$applications
    packagedJavaLibraries=@($components.Values | Sort-Object file,sha256)
    npmLockfiles=$npm
    pinnedPlatform=$versions
}
$json=$inventory | ConvertTo-Json -Depth 20
if($json -match '[A-Z]:\\|BEGIN (RSA |EC )?PRIVATE KEY|access_token|refresh_token|client_secret'){throw 'Private metadata is not allowed in a public dependency inventory.'}
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputFile)) | Out-Null
[IO.File]::WriteAllText($outputFile,$json+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
Write-Output "Recorded $($applications.Count) Java applications, $($components.Count) unique packaged libraries, $((($npm | ForEach-Object { $_.packages.Count }) | Measure-Object -Sum).Sum) npm lockfile entries, and $(@($versions.images.PSObject.Properties).Count) pinned images."
