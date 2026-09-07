[CmdletBinding()]
param([ValidateSet('dev','demo')][string]$Profile='dev',[switch]$Json)
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
if($dockerReady){
    $memory=wsl -d docker-desktop -- cat /proc/meminfo 2>$null
    $line=$memory | Select-String '^MemAvailable:\s+(\d+)'
    if($line){$vmAvailable=[math]::Round([long]$line.Matches[0].Groups[1].Value/1MB,2)}
}
$listener=Get-NetTCPConnection -State Listen -LocalPort 8780 -ErrorAction SilentlyContinue
$report=[ordered]@{
    checkedAt=(Get-Date).ToUniversalTime().ToString('o');profile=$Profile;tools=$tools;missing=$missing;dockerLinuxReady=$dockerReady
    hostAvailableGiB=[math]::Round($os.FreePhysicalMemory/1MB,2);dockerVmAvailableGiB=$vmAvailable;diskAvailableGiB=[math]::Round($drive.Free/1GB,2)
    port8780InUse=[bool]$listener;projectKubeconfigPresent=(Test-Path -LiteralPath (Join-Path $root '.local/kubeconfig'))
}
if($Json){$report | ConvertTo-Json -Depth 5}else{$report | Format-List}
if($missing.Count -gt 0 -or -not $dockerReady){throw 'Required tools or the Docker Linux engine are unavailable.'}
if($drive.Free -lt 20GB){throw 'At least 20 GiB disk headroom is required for image acquisition and test data.'}
if($Profile -eq 'demo' -and $null -ne $vmAvailable -and $vmAvailable -lt 10){throw 'The full demo needs more free Docker VM capacity; do not stop unrelated workloads without authorization.'}
