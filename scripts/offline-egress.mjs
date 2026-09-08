import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { root, call, jsonFile, writeJson, privateDirectory, maintenanceLock, sha } from './lib/local-platform.mjs';

const action=process.argv[2];assert.ok(['Enable','Disable','Status','Probe'].includes(action),'Use Enable, Disable, Status or Probe.');
const directory=resolve(root,'.local/offline'),path=resolve(directory,'egress.json');
privateDirectory(directory);
const containers=['cutover-control-plane','cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1'];
const networkNames=['cutover-kind','cutover-equipment','cutover-equipment-access'];
const image=jsonFile(resolve(root,'infra/versions.lock.json')).images.kindNode.reference;
const inspect=()=>JSON.parse(call('docker',['inspect',...containers]));
const unlock=action==='Status'?()=>{}:maintenanceLock(`offline-egress:${action}`);
let record=existsSync(path)?jsonFile(path):null;
function host(script,required=true) {
  // Only NET_ADMIN is granted. No host filesystem/PID mounts or privileged container.
  const result=spawnSync('docker',['run','--rm','--pull=never','--network','host','--cap-drop=ALL','--cap-add=NET_ADMIN','--memory=64m','--cpus=.25','--pids-limit=32','--label','dev.cutover.project=cutover','--label','dev.cutover.purpose=offline-egress','--entrypoint','bash',image,'-c',script],{cwd:root,encoding:'utf8',windowsHide:true,timeout:30000,maxBuffer:1024*1024});
  if(required && (result.error || result.status!==0))throw new Error(`The scoped Docker firewall helper failed (${result.status??result.error?.code}); no offline-ready claim is available.`);
  return result;
}
function owned(box) {
  const name=box.Name.replace(/^\//,'');assert.ok(containers.includes(name));
  if(name==='cutover-control-plane')assert.equal(box.Config.Labels['io.x-k8s.kind.cluster'],'cutover');
  else assert.equal(box.Config.Labels['dev.cutover.project'],'cutover');
}
function snapshot() {
  const boxes=inspect();boxes.forEach(owned);
  assert.ok(boxes.every(box=>box.State.Running),'Start the prepared demo before installing the scoped denial.');
  const networks=JSON.parse(call('docker',['network','inspect',...networkNames]));
  const cidrs=new Set();
  for(const network of networks){
    assert.equal(network.Driver,'bridge');assert.equal(network.EnableIPv6,false,'This profile certifies the configured IPv4-only Docker networks.');assert.equal(network.Labels['dev.cutover.project'],'cutover');
    for(const item of Object.values(network.Containers??{}))assert.ok(containers.includes(item.Name),'An unrelated container joined a Cutover network; inspect it before isolating this bridge.');
    network.bridge=network.Options['com.docker.network.bridge.name']??`br-${network.Id.slice(0,12)}`;
    assert.match(network.bridge,/^br-[a-f0-9]{12}$/);
    for(const config of network.IPAM.Config){assert.match(config.Subnet,/^(?:10|172|192)\.[0-9.]+\/(?:1[6-9]|2\d)$/);cidrs.add(config.Subnet);}
  }
  const yaml=readFileSync(resolve(root,'infra/kind/cluster.yaml'),'utf8');
  for(const key of ['podSubnet','serviceSubnet']){const value=yaml.match(new RegExp(`${key}:\\s*["']?([0-9./]+)`))?.[1];assert.match(value??'',/^10\.[0-9.]+\/16$/);cidrs.add(value);}
  return {containers:boxes.map(box=>({name:box.Name.slice(1),id:box.Id})),networks:networks.map(network=>({name:network.Name,id:network.Id,bridge:network.bridge})),allowedCidrs:[...cidrs].sort()};
}
function validateRecord() {
  assert.equal(record.protocol,1);assert.match(record.chain,/^CUTOVER_OFF_[a-f0-9]{8}$/);assert.equal(record.comment,`cutover-offline:${record.chain.slice(-8)}`);
  assert.ok(['iptables','iptables-nft','iptables-legacy'].includes(record.binary));
  assert.equal(record.networks.length,3);record.networks.forEach(n=>{assert.ok(networkNames.includes(n.name));assert.match(n.bridge,/^br-[a-f0-9]{12}$/);});
}
function counters() {return host(`${record.binary} -w 5 -nvxL ${record.chain}`).stdout.trim();}
try {
  if(action==='Enable'){
    assert.ok(!record || record.state==='DISABLED','A scoped denial journal already exists; inspect Status or Disable it first.');
    const selection=snapshot(),nonce=randomBytes(4).toString('hex');
    let binary;
    for(const candidate of ['iptables','iptables-nft','iptables-legacy'])if(host(`${candidate} -w 5 -S DOCKER-USER`,false).status===0){binary=candidate;break;}
    assert.ok(binary,'The Docker firewall chain is unavailable through the bounded helper.');
    const before=host(`${binary} -w 5 -S DOCKER-USER`).stdout;
    record={protocol:1,state:'INSTALLING',chain:`CUTOVER_OFF_${nonce}`,comment:`cutover-offline:${nonce}`,binary,image,...selection,createdAt:new Date().toISOString(),priorDockerUserSha256:sha(before)};
    writeJson(path,record);
    const rules=[`set -e`,`${binary} -w 5 -N ${record.chain}`];
    for(const cidr of record.allowedCidrs)rules.push(`${binary} -w 5 -A ${record.chain} -d ${cidr} -m comment --comment ${record.comment} -j RETURN`);
    rules.push(`${binary} -w 5 -A ${record.chain} -m comment --comment ${record.comment} -j REJECT --reject-with icmp-admin-prohibited`);
    for(const network of record.networks)rules.push(`ip link show ${network.bridge} >/dev/null`,`${binary} -w 5 -I DOCKER-USER 1 -i ${network.bridge} -m comment --comment ${record.comment} -j ${record.chain}`);
    host(rules.join('\n'));record.state='ENABLED';record.enabledAt=new Date().toISOString();record.counters=counters();writeJson(path,record);
    console.log('External IPv4 destinations are denied only for the three verified Cutover bridges. Run Probe and the prepared offline walkthrough.');
  }else if(action==='Disable'){
    if(!record || record.state==='DISABLED'){console.log('No recorded Cutover egress denial is active.');}
    else{
      validateRecord();const existing=host(`${record.binary} -w 5 -S ${record.chain}`,false);
      if(existing.status===0){
        const lines=existing.stdout.trim().split(/\r?\n/);assert.ok(lines.every(line=>line===`-N ${record.chain}` || (line.startsWith(`-A ${record.chain} `) && line.includes(`--comment ${record.comment}`))),'The reserved chain was changed; inspect it before removing rules.');
        record.finalCounters=counters();writeJson(path,record);
        const rules=['set -e'];for(const network of record.networks){const rule=`DOCKER-USER -i ${network.bridge} -m comment --comment ${record.comment} -j ${record.chain}`;rules.push(`if ${record.binary} -w 5 -C ${rule} 2>/dev/null; then ${record.binary} -w 5 -D ${rule}; fi`);}
        rules.push(`${record.binary} -w 5 -F ${record.chain}`,`${record.binary} -w 5 -X ${record.chain}`);host(rules.join('\n'));
      }
      record.state='DISABLED';record.disabledAt=new Date().toISOString();record.finalDockerUserSha256=sha(host(`${record.binary} -w 5 -S DOCKER-USER`).stdout);writeJson(path,record);
      console.log('Removed only the recorded Cutover bridge jumps and its reserved denial chain.');
    }
  }else if(action==='Probe'){
    validateRecord();assert.equal(record.state,'ENABLED');assert.deepEqual(snapshot().networks,record.networks);
    record.probes??=[];
    for(const name of ['cutover-control-plane','cutover-dev-equipment-simulator-1']){
      const started=Date.now();const result=spawnSync('docker',['exec',name,'bash','-c','timeout 3 bash -c "exec 3<>/dev/tcp/1.1.1.1/443"'],{encoding:'utf8',windowsHide:true,timeout:8000});
      assert.ok(!result.error && result.status!==0,'An external TCP connection unexpectedly succeeded.');
      record.probes.push({at:new Date().toISOString(),container:name,destination:'1.1.1.1:443',exitCode:result.status,elapsedMillis:Date.now()-started});
    }
    // Docker Desktop can inject a host proxy; testing only direct TCP would miss this route.
    const proxy=spawnSync('docker',['exec','cutover-control-plane','bash','-c','test -n "${HTTPS_PROXY:-}" || exit 42; command -v curl >/dev/null || exit 43; curl --silent --show-error --output /dev/null --connect-timeout 3 --max-time 5 --proxy "$HTTPS_PROXY" https://example.com'],{encoding:'utf8',windowsHide:true,timeout:8000});
    assert.ok(!proxy.error && ![0,42,43].includes(proxy.status),'The prepared profile must also prove that its injected Docker proxy cannot reach an external HTTP destination.');
    record.probes.push({at:new Date().toISOString(),container:'cutover-control-plane',destination:'https://example.com via injected HTTPS_PROXY',exitCode:proxy.status});
    record.counters=counters();assert.match(record.counters,/\n\s*[1-9][0-9]*\s+[0-9]+\s+REJECT\s/,'Denied traffic must increment the actual firewall counter.');writeJson(path,record);console.log('Both Cutover external TCP probes were refused and the scoped reject counter increased.');
  }else{
    if(record && record.state!=='DISABLED'){validateRecord();console.log(JSON.stringify({state:record.state,chain:record.chain,networks:record.networks,counters:counters()},null,2));}
    else console.log('No recorded Cutover egress denial is active.');
  }
}catch(error){if(record){record.lastFailure={at:new Date().toISOString(),action,message:error.message};writeJson(path,record);}throw error;}
finally{unlock();}
