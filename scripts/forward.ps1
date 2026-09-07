[CmdletBinding()]
param([ValidateSet('Start','Stop')][string]$Action='Start',[ValidateSet('console','grafana','prometheus','tempo')][string]$Target='console')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$config=Join-Path $root '.local/kubeconfig'
$directory=Join-Path $root '.local/processes'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$record=Join-Path $directory "$Target-forward.json"
$settings=@{console=@('cutover-apps','proxy',8780,8080);grafana=@('cutover-observability','grafana',8783,3000);prometheus=@('cutover-observability','prometheus',8781,9090);tempo=@('cutover-observability','tempo',8782,3200)}[$Target]
$healthPath=@{console='/';grafana='/api/health';prometheus='/-/ready';tempo='/ready'}[$Target]
function Test-Forward {
    try {
        $response=Invoke-WebRequest -Uri "http://127.0.0.1:$($settings[2])$healthPath" -TimeoutSec 3 -UseBasicParsing
        return $response.StatusCode -eq 200
    } catch { return $false }
}
if(Test-Path -LiteralPath $record){
    $saved=Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
    $existing=Get-CimInstance Win32_Process -Filter "ProcessId=$($saved.pid)" -ErrorAction SilentlyContinue
    if($existing){
        if($existing.Name -ne 'kubectl.exe' -or -not $existing.CommandLine.Contains($config) -or -not $existing.CommandLine.Contains('port-forward') -or -not $existing.CommandLine.Contains("service/$($settings[1])") -or -not $existing.CommandLine.Contains("$($settings[2]):$($settings[3])")){throw 'Recorded process identity changed; refusing to stop or reuse it.'}
        if($Action -eq 'Stop'){Stop-Process -Id $saved.pid;Remove-Item -LiteralPath $record;Write-Host "Stopped Cutover $Target forward.";return}
        if(Test-Forward){Write-Host "Cutover $Target forward is healthy on loopback port $($settings[2]).";return}
        Stop-Process -Id $saved.pid -ErrorAction SilentlyContinue
        Write-Host "Reconnecting Cutover $Target forward after its pod changed or became unavailable."
    }
    Remove-Item -LiteralPath $record
}
if($Action -eq 'Stop'){return}
if(Get-NetTCPConnection -State Listen -LocalPort $settings[2] -ErrorAction SilentlyContinue){throw "Local port $($settings[2]) is occupied; no unrelated process was stopped."}
$arguments=@('--kubeconfig',"`"$config`"",'--context','kind-cutover','-n',$settings[0],'port-forward',"service/$($settings[1])","$($settings[2]):$($settings[3])",'--address','127.0.0.1')
$process=Start-Process -FilePath (Get-Command kubectl).Source -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory "$Target-forward.stdout.log") -RedirectStandardError (Join-Path $directory "$Target-forward.stderr.log")
@{pid=$process.Id;target=$Target;port=$settings[2];createdAt=(Get-Date).ToUniversalTime().ToString('o');kubeconfig=$config} | ConvertTo-Json | Set-Content -LiteralPath $record
$deadline=(Get-Date).AddSeconds(30)
do {
    if(Test-Forward){Write-Host "Started healthy Cutover $Target forward on 127.0.0.1:$($settings[2]).";return}
    if($process.HasExited){throw "Cutover $Target forward exited. See its private log under .local/processes."}
    Start-Sleep -Milliseconds 500
} while((Get-Date) -lt $deadline)
throw "Cutover $Target forward did not become healthy. See its private log under .local/processes."
