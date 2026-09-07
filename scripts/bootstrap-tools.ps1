[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $root '.local/tools'
New-Item -ItemType Directory -Force -Path $cache | Out-Null

function Get-VerifiedArtifact([string]$Url, [string]$Destination, [string]$Algorithm, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Destination)) {
        Write-Host "Downloading $([IO.Path]::GetFileName($Destination))"
        Invoke-WebRequest -Uri $Url -OutFile "$Destination.partial"
        $actual = (Get-FileHash -LiteralPath "$Destination.partial" -Algorithm $Algorithm).Hash
        if ($actual -ne $Expected) { throw "Checksum mismatch for $Url" }
        Move-Item -LiteralPath "$Destination.partial" -Destination $Destination
    }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ne $Expected) { throw "Cached checksum mismatch: $Destination" }
}

$mavenZip = Join-Path $cache 'apache-maven-3.9.16-bin.zip'
Get-VerifiedArtifact 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip' $mavenZip 'SHA512' 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3'
if (-not (Test-Path -LiteralPath (Join-Path $cache 'apache-maven-3.9.16/bin/mvn.cmd'))) { Expand-Archive -LiteralPath $mavenZip -DestinationPath $cache }
Get-VerifiedArtifact 'https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-windows-amd64' (Join-Path $cache 'kind.exe') 'SHA256' '4b22adaa135368c5a465d56bbd8e520cbea87272a06ca00b6078e7b81515c9fc'
Write-Host 'Verified Maven 3.9.16 and kind 0.33.0 in .local/tools. No global PATH or system settings changed.'
