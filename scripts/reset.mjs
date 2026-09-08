// Deliberate new-dataset operation. Normal demo Stop never enters this path.
import assert from 'node:assert/strict';
import { existsSync, readFileSync, openSync, closeSync } from 'node:fs';
import { resolve } from 'node:path';
import { root, call, jsonFile, writeJson, privateDirectory, maintenanceLock, until, sha } from './lib/local-platform.mjs';
import { verifyCheckpoint } from './lib/checkpoint.mjs';

const arguments_=process.argv.slice(2),checkpoint=arguments_.find(value=>value.startsWith('--checkpoint='))?.slice(13);
assert.equal(arguments_.length,3,'Use --checkpoint=<name> --destroy-cutover --new-world after demo Stop.');
assert.ok(arguments_.includes('--destroy-cutover')&&arguments_.includes('--new-world'),'Both explicit destruction and new-world flags are required.');
const verified=verifyCheckpoint(checkpoint),directory=resolve(root,'.local/resets',checkpoint);
privateDirectory(directory);
const path=resolve(directory,'journal.json'),lifecyclePath=resolve(root,'.local/operations/demo-lifecycle.json');
const unlock=maintenanceLock(`reset:${checkpoint}`);
const names=['cutover-control-plane','cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1','cutover-dev-equipment-volume-probe-1'];
const equipmentVolumes=['cutover-equipment-data','cutover-equipment-volume-observation'];
let journal=existsSync(path)?jsonFile(path):null;
function inspect(name){const id=call('docker',['ps','-a','--filter',`name=^/${name}$`,'--format','{{.ID}}']);return id?JSON.parse(call('docker',['inspect',id]))[0]:null;}
function identity(box){return {name:box.Name.slice(1),id:box.Id,image:box.Image,volumes:box.Mounts.filter(m=>m.Type==='volume').map(m=>({name:m.Name,destination:m.Destination})).sort((a,b)=>a.destination.localeCompare(b.destination))};}
function save(){writeJson(path,journal);}
function validateContainer(box,expected){
  assert.deepEqual(identity(box),expected,'A recorded container/image/volume changed during reset.');
  if(expected.name===names[0])assert.equal(box.Config.Labels['io.x-k8s.kind.cluster'],'cutover');
  else{assert.equal(box.Config.Labels['dev.cutover.project'],'cutover');assert.equal(box.Config.Labels['com.docker.compose.project'],'cutover-dev');}
}
function volumePresent(name){return call('docker',['volume','ls','--filter',`name=^${name}$`,'--format','{{.Name}}'])===name;}
function validateVolumes(){
  for(const volume of journal.volumes){
    if(!volumePresent(volume.name)){assert.equal(journal.state,'REMOVING','A retained volume disappeared before removal.');continue;}
    const actual=JSON.parse(call('docker',['volume','inspect',volume.name]))[0];
    assert.equal(actual.Name,volume.name);assert.equal(actual.CreatedAt,volume.createdAt,'A named volume was replaced during reset.');
    if(equipmentVolumes.includes(volume.name))assert.equal(actual.Labels['dev.cutover.project'],'cutover');
    const ids=call('docker',['ps','-aq','--no-trunc','--filter',`volume=${volume.name}`]).split(/\s+/).filter(Boolean);
    assert.ok(ids.every(id=>journal.containers.some(box=>box.id===id)),`Volume ${volume.name} is also used outside the recorded demo containers.`);
  }
}
try{
  if(journal?.state==='RESET'){
    assert.ok(names.every(name=>!inspect(name)),'New containers exist; this completed reset cannot remove them.');
    assert.ok(journal.volumes.every(volume=>!volumePresent(volume.name)),'A volume exists after the completed reset; inspect the newer preparation operation.');
    writeJson(lifecyclePath,{protocol:1,state:'RESET',resetCheckpoint:checkpoint,resetAt:journal.finishedAt,discardedWorldId:journal.physicalBefore.worldId});
    console.log('This recorded reset is already complete. Bootstrap creates a new dataset.');
  }
  else{
    assert.ok(!inspect('cutover-restored-control-plane'),'Remove the separate restoration copy through its own lifecycle before deliberately resetting the physical world.');
    for(const name of ['cutover-dev-legacy-core-1','cutover-dev-equipment-adapter-1'])assert.ok(!inspect(name)?.State.Running,'The older development application profile must be stopped.');
    if(existsSync(resolve(root,'.local/offline/egress.json')))assert.equal(jsonFile(resolve(root,'.local/offline/egress.json')).state,'DISABLED');
    if(!journal){
      const lifecycle=jsonFile(lifecyclePath);assert.equal(lifecycle.state,'STOPPED','Run the normal demo Stop first.');
      assert.ok(lifecycle.physicalBefore,'Stop must record the physical world before a deliberate reset.');
      for(const key of ['worldId','journalGeneration','journalHighWater'])assert.equal(lifecycle.physicalBefore[key],verified.manifest.physicalEnd[key],'Create a current quiescent checkpoint before Stop and reset.');
      assert.equal(verified.manifest.unsettledCommands.length,0,'Resolve outstanding equipment work before resetting this dataset.');
      const boxes=names.map(name=>{const box=inspect(name);assert.ok(box,`The recorded ${name} is missing.`);assert.equal(box.State.Running,false);validateContainer(box,lifecycle.containers.find(item=>item.name===name));return box;});
      const volumeNames=[...new Set(boxes.flatMap(box=>identity(box).volumes.map(volume=>volume.name)))];
      assert.deepEqual(volumeNames.filter(name=>equipmentVolumes.includes(name)).sort(),[...equipmentVolumes].sort());
      assert.equal(volumeNames.length,3,'Only one kind data volume and the two equipment volumes may be removed.');
      const volumes=volumeNames.map(name=>{const volume=JSON.parse(call('docker',['volume','inspect',name]))[0];return {name,createdAt:volume.CreatedAt};});
      journal={protocol:1,state:'ARCHIVING',checkpoint,checkpointSha256:verified.manifestSha256,startedAt:new Date().toISOString(),revision:call('git',['rev-parse','HEAD']),physicalBefore:lifecycle.physicalBefore,containers:boxes.map(identity),volumes,removed:[]};
      writeJson(resolve(directory,'preserved-lifecycle.json'),lifecycle);save();
    }
    assert.equal(journal.checkpointSha256,verified.manifestSha256);
    assert.ok(['ARCHIVING','REMOVING'].includes(journal.state));validateVolumes();
    for(const expected of journal.containers){const box=inspect(expected.name);if(box){validateContainer(box,expected);assert.ok(!box.State.Running||(journal.state==='ARCHIVING'&&expected.name===names[2]),'Only the isolated simulator database may run during journal archival.');}else assert.equal(journal.state,'REMOVING');}
    if(journal.state==='ARCHIVING'){
      const database=inspect(names[2]),dump=resolve(directory,'simulator.dump');
      try{
        if(!database.State.Running)call('docker',['start',database.Id]);
        await until(()=>inspect(names[2])?.State.Health?.Status==='healthy','isolated simulator database is ready for archival',90000);
        // No simulator/application process runs here. The independent physical history is immutable during the dump.
        const fd=openSync(dump,'w',0o600);
        try{call('docker',['exec',database.Id,'sh','-c','export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_dump -h 127.0.0.1 -U postgres -d cutover_simulator -Fc --no-owner --no-acl --exclude-schema=cutover_ops'],{stdio:['ignore',fd,'pipe'],timeout:180000});}finally{closeSync(fd);}
        const input=openSync(dump,'r');let listing;
        try{listing=call('docker',['exec','-i',database.Id,'pg_restore','--list'],{stdio:[input,'pipe','pipe']});}finally{closeSync(input);}
        assert.ok(listing.includes('TABLE DATA'),'The physical history archive has no data entries.');
        journal.physicalArchive={file:'simulator.dump',sha256:sha(readFileSync(dump)),bytes:readFileSync(dump).length,archivedAt:new Date().toISOString(),purpose:'Historical evidence of the deliberately discarded world. Never apply it as part of application restoration.'};save();
      }finally{const box=inspect(names[2]);if(box?.State.Running)call('docker',['stop','--time','30',box.Id]);}
      journal.state='REMOVING';save();
    }
    assert.equal(sha(readFileSync(resolve(directory,journal.physicalArchive.file))),journal.physicalArchive.sha256);
    validateVolumes();
    const node=inspect(names[0]);
    if(node){
      validateContainer(node,journal.containers[0]);assert.equal(node.State.Running,false);
      const nodeIds=call('docker',['ps','-aq','--no-trunc','--filter','label=io.x-k8s.kind.cluster=cutover']).split(/\s+/).filter(Boolean);assert.deepEqual(nodeIds,[node.Id]);
      call(resolve(root,'.local/tools/kind.exe'),['delete','cluster','--name','cutover','--kubeconfig',resolve(root,'.local/kubeconfig')],{timeout:180000});
    }
    for(const expected of journal.containers.slice(1)){
      const box=inspect(expected.name);if(box){validateContainer(box,expected);assert.equal(box.State.Running,false);call('docker',['rm',box.Id]);}
      if(!journal.removed.includes(expected.name)){journal.removed.push(expected.name);save();}
    }
    validateVolumes();
    for(const volume of journal.volumes)if(volumePresent(volume.name))call('docker',['volume','rm',volume.name]);
    assert.ok(names.every(name=>!inspect(name)));assert.ok(journal.volumes.every(volume=>!volumePresent(volume.name)));
    journal.state='RESET';journal.finishedAt=new Date().toISOString();save();
    writeJson(lifecyclePath,{protocol:1,state:'RESET',resetCheckpoint:checkpoint,resetAt:journal.finishedAt,discardedWorldId:journal.physicalBefore.worldId});
    console.log(`Explicit reset completed for only the recorded Cutover resources. The six application dumps and archived physical journal remain private. Run bootstrap.ps1 to deliberately create a new world.`);
  }
}catch(error){if(journal){journal.failure={at:new Date().toISOString(),message:error.message};save();}throw error;}
finally{unlock();}
