[CmdletBinding()]
param([ValidateSet('dev','demo')][string]$Profile='dev',[switch]$Json,[switch]$OfflineReady)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$required=@('java','javac','node','npm','docker','git','kubectl')
$tools=@{};$missing=@()
foreach($name in $required){$command=Get-Command $name -ErrorAction SilentlyContinue;if($command){$tools[$name]='found'}else{$tools[$name]='missing';$missing+=$name}}
$kind=Join-Path $root '.local/tools/kind.exe'
if(Test-Path -LiteralPath $kind){$tools['kind']=(& $kind version)}else{$tools['kind']='missing';if($Profile -eq 'demo'){$missing+='kind'}}
$dockerReady=$false
if($tools['docker'] -eq 'found'){$server=docker info --format '{{.OSType}}' 2>$null;$dockerReady=($LASTEXITCODE -eq 0 -and $server -eq 'linux')}
$os=Get-CimInstance Win32_OperatingSystem
$drive=Get-PSDrive -Name ([IO.Path]::GetPathRoot($root).Substring(0,1))
$vmAvailable=$null
$cutoverMemoryGiB=0.0
$runningCutover=@()
if($dockerReady){
    $memory=wsl -d docker-desktop -- cat /proc/meminfo 2>$null
    $line=$memory | Select-String '^MemAvailable:\s+(\d+)'
    if($line){$vmAvailable=[math]::Round([long]$line.Matches[0].Groups[1].Value/1MB,2)}
    foreach($name in @('cutover-control-plane','cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1','cutover-dev-equipment-volume-probe-1')){
        $present=& docker ps --filter "name=^/$name`$" --format '{{.Names}}'
        if($LASTEXITCODE -ne 0){throw 'Could not inspect Docker capacity.'}
        if($present -ne $name){continue}
        $container=(& docker inspect $name | ConvertFrom-Json)[0]
        if($name -eq 'cutover-control-plane'){$owned=$container.Config.Labels.'io.x-k8s.kind.cluster' -eq 'cutover'}else{$owned=$container.Config.Labels.'dev.cutover.project' -eq 'cutover'}
        if(-not $owned){throw 'A reserved Cutover container name has unexpected ownership.'}
        $usage=& docker stats --no-stream --format '{{.MemUsage}}' $name
        if($LASTEXITCODE -ne 0 -or $usage -notmatch '^([0-9.]+)([KMG]iB|B)\s*/'){throw 'Could not measure the existing Cutover working set.'}
        $units=@{B=1;KiB=1KB;MiB=1MB;GiB=1GB}
        $cutoverMemoryGiB+=[double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture)*$units[$Matches[2]]/1GB
        $runningCutover+=$name
    }
}
$offlineCache=$null
if($OfflineReady){
    if($tools['node'] -ne 'found'){throw 'Node.js is required to inspect the prepared offline cache.'}
    $cacheOutput=& node (Join-Path $PSScriptRoot 'cache.mjs') Check 2>&1
    $offlineCache=[ordered]@{ready=($LASTEXITCODE -eq 0);details=@($cacheOutput | ForEach-Object {"$_"});reportPath='.local/offline/cache-check.json'}
}
$listener=Get-NetTCPConnection -State Listen -LocalPort 8780 -ErrorAction SilentlyContinue
$report=[ordered]@{
    checkedAt=(Get-Date).ToUniversalTime().ToString('o');profile=$Profile;tools=$tools;missing=$missing;dockerLinuxReady=$dockerReady
    hostAvailableGiB=[math]::Round($os.FreePhysicalMemory/1MB,2);dockerVmAvailableGiB=$vmAvailable;diskAvailableGiB=[math]::Round($drive.Free/1GB,2)
    runningCutover=$runningCutover;runningCutoverWorkingSetGiB=[math]::Round($cutoverMemoryGiB,2);offlineCache=$offlineCache
    port8780InUse=[bool]$listener;projectKubeconfigPresent=(Test-Path -LiteralPath (Join-Path $root '.local/kubeconfig'))
}
if($Json){$report | ConvertTo-Json -Depth 5}else{$report | Format-List}
if($missing.Count -gt 0 -or -not $dockerReady){throw 'Required tools or the Docker Linux engine are unavailable.'}
if($drive.Free -lt 20GB){throw 'At least 20 GiB disk headroom is required for image acquisition and test data.'}
if($Profile -eq 'demo' -and $null -ne $vmAvailable -and ($vmAvailable+$cutoverMemoryGiB) -lt 10){throw 'Available Docker VM memory plus the measured existing Cutover working set is below the 10 GiB planning budget. Do not stop unrelated workloads without authorization.'}
if($OfflineReady -and -not $offlineCache.ready){throw 'The prepared offline cache is incomplete; inspect the reported missing assets. Runtime walkthrough verification remains separate.'}
