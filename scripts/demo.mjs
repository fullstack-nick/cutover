import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { root, call, jsonFile, writeJson, privateDirectory, maintenanceLock, target, until, simulatorRead, request, maintenanceToken } from './lib/local-platform.mjs';
import { refreshEquipmentEndpoint } from './lib/equipment-endpoint.mjs';

const action=process.argv[2],options=process.argv.slice(3);
assert.ok(options.length<=1&&options.every(value=>value==='--preserve-offline-denial'),'The only optional lifecycle flag is --preserve-offline-denial.');
const preserveOffline=options.includes('--preserve-offline-denial');
assert.ok(['Start','Stop','Status'].includes(action),'Use Start, Stop or Status.');
const names=['cutover-control-plane','cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1','cutover-dev-equipment-volume-probe-1'];
const path=resolve(root,'.local/operations/demo-lifecycle.json');privateDirectory(resolve(root,'.local/operations'));
let journal=existsSync(path)?jsonFile(path):null;
const unlock=action==='Status'?()=>{}:maintenanceLock(`demo:${action}`);
function inspect(name){
  const id=call('docker',['ps','-a','--filter',`name=^/${name}$`,'--format','{{.ID}}']);
  if(!id)return null;
  const box=JSON.parse(call('docker',['inspect',id]))[0];assert.equal(box.Name,`/${name}`);
  if(name==='cutover-control-plane')assert.equal(box.Config.Labels['io.x-k8s.kind.cluster'],'cutover');
  else if(names.includes(name)){assert.equal(box.Config.Labels['dev.cutover.project'],'cutover');assert.equal(box.Config.Labels['com.docker.compose.project'],'cutover-dev');}
  return box;
}
function identity(box){return {name:box.Name.slice(1),id:box.Id,image:box.Image,volumes:box.Mounts.filter(m=>m.Type==='volume').map(m=>({name:m.Name,destination:m.Destination})).sort((a,b)=>a.destination.localeCompare(b.destination))};}
function boxes(){return names.map(name=>{const box=inspect(name);assert.ok(box,`Prepared container ${name} is missing. Complete the documented bootstrap before Start.`);return box;});}
function save(){writeJson(path,journal);}
async function command(tool,args){
  await new Promise((done,failed)=>{const child=spawn(tool,args,{cwd:root,windowsHide:true,stdio:'inherit'});child.once('error',failed);child.once('exit',code=>code===0?done():failed(new Error(`The scoped ${tool} lifecycle command failed (${code}).`)));});
}
async function forwards(state){for(const name of (state==='Start'?['console','grafana','prometheus','tempo']:['console','grafana','prometheus','tempo','core-api','adapter-api']))await command(process.execPath,['scripts/manage-forward.mjs',`--action=${state}`,'--profile=demo',`--target=${name}`]);}
function otherProfile(){
  const restored=inspect('cutover-restored-control-plane');assert.ok(!restored?.State.Running,'Use the restoration lifecycle while the restored application copy is running.');
  for(const name of ['cutover-dev-legacy-core-1','cutover-dev-equipment-adapter-1'])assert.ok(!inspect(name)?.State.Running,'Stop the older development application profile before operating the demo.');
}
async function healthyContainer(name){await until(()=>{const box=inspect(name);return box?.State.Running && (box.State.Health?.Status==='healthy' || (name===names[3]&&!box.State.Health));},`${name} is ready`,180000);}
async function healthyPlatform(){
  const started=Date.parse(inspect(names[0]).State.StartedAt),platform=target('demo');
  const required=['application-db','rabbitmq','keycloak','legacy-core','equipment-adapter','execution-service','shadow-scheduler','returns-service','proxy','collector','prometheus','tempo','grafana'];
  await until(()=>{
    try{
      const pods=JSON.parse(platform.kube(['--request-timeout=5s','get','pods','-A','-l','app.kubernetes.io/part-of=cutover','-o','json'])).items;
      return required.every(name=>{const selected=pods.filter(p=>p.metadata.labels?.['app.kubernetes.io/name']===name);return selected.length===1 && selected[0].status.conditions?.some(c=>c.type==='Ready'&&c.status==='True') && selected[0].status.containerStatuses?.every(c=>c.ready&&Date.parse(c.state.running?.startedAt??'')>=started);});
    }catch{return false;}
  },'every expected application/identity/observation process is ready from this node start',300000);
  platform.verify();
}
async function reachableEquipment(){
  const platform=target('demo'),endpoint=refreshEquipmentEndpoint(platform),physical=await simulatorRead('/sim/v1/equipment');
  const proxy=await platform.forward('proxy');
  try{
    const bearer=await maintenanceToken(proxy.origin,jsonFile(resolve(root,'.local/secrets/credentials.json')));
    await until(async()=>{
      try{const observed=await request(proxy.origin,'/api/v1/sites/site-a/equipment',{bearer});return !observed.stale&&!observed.worldMismatch&&observed.worldId===physical.worldId&&Date.parse(observed.observedAt)>=Date.parse(endpoint.at);}
      catch{return false;}
    },'the adapter observes fresh equipment through the discovered endpoint and restricted policy',60000);
  }finally{proxy.close();}
  return endpoint;
}
try {
  if(action==='Status'){
    console.log(JSON.stringify({state:journal?.state??'UNRECORDED',containers:names.map(name=>{const box=inspect(name);return {name,present:Boolean(box),running:box?.State.Running??false,health:box?.State.Health?.Status??null};})},null,2));
  }else{
    otherProfile();
    const current=boxes();
    const denialPath=resolve(root,'.local/offline/egress.json');
    const denial=existsSync(denialPath)?jsonFile(denialPath):null;
    if(preserveOffline){
      assert.equal(denial?.state,'ENABLED','The offline lifecycle flag requires an active recorded denial.');
      assert.equal(denial.protocol,1);assert.match(denial.chain,/^CUTOVER_OFF_[a-f0-9]{8}$/);
      const expectedNames=names.slice(0,3).sort();
      assert.deepEqual(denial.containers.map(box=>box.name).sort(),expectedNames);
      for(const recorded of denial.containers)assert.equal(current.find(box=>box.Name.slice(1)===recorded.name)?.Id,recorded.id,'Offline container identity changed.');
      assert.deepEqual(denial.networks.map(network=>network.name).sort(),['cutover-equipment','cutover-equipment-access','cutover-kind']);
      const networks=JSON.parse(call('docker',['network','inspect',...denial.networks.map(network=>network.name)]));
      for(const network of networks){
        assert.equal(network.Id,denial.networks.find(recorded=>recorded.name===network.Name).id,'Offline bridge identity changed.');
        assert.equal(network.Labels['dev.cutover.project'],'cutover');assert.equal(network.Driver,'bridge');assert.equal(network.EnableIPv6,false);
        for(const member of Object.values(network.Containers??{}))assert.ok(expectedNames.includes(member.Name),'An unrelated container joined an offline bridge.');
      }
    }else if(denial)assert.equal(denial.state,'DISABLED','Complete or disable the recorded offline walkthrough before normal lifecycle operations.');
    if(action==='Stop'){
      if(!current.some(box=>box.State.Running)){console.log('Cutover is already stopped; its containers and volumes remain preserved.');}
      else{
        assert.ok(!journal || !['STARTING'].includes(journal.state),'Finish the interrupted Start before beginning a new Stop.');
        if(journal?.state!=='STOPPING'){
          journal={protocol:1,state:'STOPPING',startedAt:new Date().toISOString(),containers:current.map(identity),stopped:[]};
          try{journal.physicalBefore=await simulatorRead('/sim/v1/equipment');}catch{journal.physicalObservationUnavailable=true;}
          save();
        }
        for(const box of current)assert.deepEqual(identity(box),journal.containers.find(item=>item.name===box.Name.slice(1)),'A recorded container or volume changed during Stop.');
        await forwards('Stop');
        // Stop interrupts work; durable owner databases, outboxes and the physical journal resume it on Start.
        for(const name of [names[0],names[1],names[3],names[2]]){
          const box=inspect(name);if(box.State.Running)await command('docker',['stop','--time','30',box.Id]);
          if(!journal.stopped.includes(name)){journal.stopped.push(name);save();}
        }
        journal.state='STOPPED';journal.stoppedAt=new Date().toISOString();save();
        console.log('Stopped only the four Cutover demo containers. Their data volumes and physical world are preserved.');
      }
    }else{
      if(journal && ['STOPPED','STOPPING','STARTING'].includes(journal.state))for(const box of current)assert.deepEqual(identity(box),journal.containers.find(item=>item.name===box.Name.slice(1)),'A preserved container/image/volume changed; inspect the lifecycle journal before Start.');
      journal={...(journal??{protocol:1}),containers:current.map(identity),state:'STARTING',startingAt:new Date().toISOString()};save();
      for(const name of [names[2],names[3]])if(!inspect(name).State.Running)await command('docker',['start',inspect(name).Id]);
      await healthyContainer(names[2]);await healthyContainer(names[3]);
      if(!inspect(names[1]).State.Running)await command('docker',['start',inspect(names[1]).Id]);await healthyContainer(names[1]);
      if(!inspect(names[0]).State.Running)await command('docker',['start',inspect(names[0]).Id]);await healthyPlatform();
      journal.equipmentEndpoint=await reachableEquipment();save();
      journal.physicalAfter=await simulatorRead('/sim/v1/equipment');
      if(journal.physicalBefore){for(const key of ['worldId','journalGeneration'])assert.equal(journal.physicalAfter[key],journal.physicalBefore[key]);assert.ok(journal.physicalAfter.journalHighWater>=journal.physicalBefore.journalHighWater);}
      await forwards('Start');journal.state='RUNNING';journal.runningAt=new Date().toISOString();save();
      console.log('The preserved Cutover demo is ready on localhost:8780; Grafana is on localhost:8783.');
    }
  }
}catch(error){if(journal){journal.lastFailure={action,at:new Date().toISOString(),message:error.message};save();}throw error;}
finally{unlock();}
