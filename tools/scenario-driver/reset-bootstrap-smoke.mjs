import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { openSync, closeSync } from 'node:fs';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
assert.deepEqual(process.argv.slice(2).sort(),['--destroy-cutover','--new-world'],'This scenario deliberately replaces the demo dataset; both explicit flags are required.');
const { api, token, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, target, privateDirectory, simulatorRead, until, call, writeJson, jsonFile }=await import('../../scripts/lib/local-platform.mjs');
const { verifyCheckpoint }=await import('../../scripts/lib/checkpoint.mjs');
const id=`reset-bootstrap-${Date.now()}`,directory=resolve(root,'.local/evidence',id),prefix='/api/v1/sites/site-a';
privateDirectory(directory);
const evidence={startedAt:new Date().toISOString(),cases:[],steps:[]};
const names=['cutover-control-plane','cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1','cutover-dev-equipment-volume-probe-1'];
function inventory(){const ids=call('docker',['ps','-aq']).split(/\s+/).filter(Boolean);return ids.length?JSON.parse(call('docker',['inspect',...ids])).map(box=>({name:box.Name.slice(1),id:box.Id,running:box.State.Running})).sort((a,b)=>a.id.localeCompare(b.id)):[];}
async function step(name,tool,args,expected=0){
  const path=resolve(directory,`${evidence.steps.length}-${name}.log`),fd=openSync(path,'w',0o600),startedAt=new Date().toISOString();let code;
  try{code=await new Promise((done,failed)=>{const child=spawn(tool,args,{cwd:root,windowsHide:true,stdio:['ignore',fd,fd]});child.once('error',failed);child.once('exit',done);});}
  finally{closeSync(fd);evidence.steps.push({name,tool,args,startedAt,endedAt:new Date().toISOString(),exitCode:code});writeJson(resolve(directory,'progress.json'),evidence);}
  if(expected===0)assert.equal(code,0,`${name} failed; inspect its retained log.`);else assert.notEqual(code,0,`${name} must refuse before mutation.`);
}
let failure,session,ready=true;
try{
  target('demo').verify();provisionObservers();
  assert.equal(query('adapter',"SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');"),'0');
  for(const owner of ['core','returns'])assert.equal(query(owner,'SELECT active_requests FROM admission;'),'0');
  evidence.inventoryBefore=inventory();evidence.physicalBefore=await simulatorRead('/sim/v1/equipment');
  saveEvidence(id,evidence);evidence.runtimeBefore=jsonFile(resolve(directory,'results.json')).runtime;
  await step('refuse-missing-flags',process.execPath,['scripts/reset.mjs',`--checkpoint=${id}`],1);
  assert.deepEqual(inventory(),evidence.inventoryBefore);
  await step('checkpoint',process.execPath,['scripts/backup.mjs',`--name=${id}`]);
  const checkpoint=verifyCheckpoint(id);assert.equal(checkpoint.manifest.unsettledCommands.length,0);evidence.checkpointSha256=checkpoint.manifestSha256;
  await step('preserved-stop',process.execPath,['scripts/demo.mjs','Stop']);ready=false;
  const flags=[`--checkpoint=${id}`,'--destroy-cutover','--new-world'];
  await step('deliberate-reset',process.execPath,['scripts/reset.mjs',...flags]);
  assert.ok(inventory().every(box=>!names.includes(box.name)));
  evidence.removal=jsonFile(resolve(root,'.local/resets',id,'journal.json'));assert.equal(evidence.removal.state,'RESET');
  await step('repeated-reset',process.execPath,['scripts/reset.mjs',...flags]);
  await step('fresh-bootstrap','pwsh',['-NoProfile','-File','scripts/bootstrap.ps1','-SkipBuild']);ready=true;
  target('demo').verify();provisionObservers();evidence.physicalAfter=await simulatorRead('/sim/v1/equipment');
  for(const key of ['worldId','journalGeneration'])assert.notEqual(evidence.physicalAfter[key],evidence.physicalBefore[key]);
  assert.equal(evidence.physicalAfter.journalHighWater,0);assert.equal(evidence.physicalAfter.completeHistory,true);
  evidence.inventoryAfter=inventory();
  assert.deepEqual(evidence.inventoryAfter.filter(box=>!names.includes(box.name)),evidence.inventoryBefore.filter(box=>!names.includes(box.name)),'Every unrelated container identity/running state is preserved.');
  for(const name of names){const before=evidence.inventoryBefore.find(box=>box.name===name),after=evidence.inventoryAfter.find(box=>box.name===name);assert.ok(after?.running);assert.notEqual(after.id,before.id);}
  evidence.seed=JSON.parse(query('core',"SELECT jsonb_build_object('rows',count(*),'onHand',sum(on_hand),'reserved',sum(reserved)) FROM stock;"));
  assert.deepEqual(evidence.seed,{rows:200,onHand:19604,reserved:0});
  assert.equal(query('core','SELECT count(*) FROM orders;'),'0');assert.equal(query('returns','SELECT count(*) FROM receipts;'),'0');
  evidence.routes=JSON.parse(query('adapter',"SELECT jsonb_agg(to_jsonb(r) ORDER BY site_id,zone_id) FROM zone_routes r;"));
  assert.ok(evidence.routes.every(route=>route.epoch===0&&route.owner===(route.zone_id==='returns'?'returns-service':'legacy-core')));
  // An old completed reset must not touch the fresh containers, even with the same explicit flags.
  await step('refuse-old-reset-on-new-demo',process.execPath,['scripts/reset.mjs',...flags],1);assert.deepEqual(inventory(),evidence.inventoryAfter);
  session=await humanSession('operator-a');const bearer=session.bearer();
  assert.equal((await api(`${prefix}/orders`,{bearer})).status,200);assert.equal((await api(`${prefix}/return-receipts`,{bearer})).status,200);
  const order=await api(`${prefix}/orders`,{method:'POST',key:`${id}-order`,bearer:await token(),body:{sourceSystem:'scenario-driver',externalOrderRef:id,storeId:'store-01',priority:5,lines:[{sku:'SKU-001',quantity:1},{sku:'SKU-002',quantity:1}]}});assert.equal(order.status,202);
  const receipt=await api(`${prefix}/return-receipts`,{method:'POST',key:`${id}-receipt`,bearer:await token(),body:{sourceSystem:'scenario-driver',externalReceiptRef:id,counts:{REUSABLE:1,NEEDS_CLEANING:1,DAMAGED:1}}});assert.equal(receipt.status,202);
  await until(async()=>{evidence.order=(await api(`${prefix}/orders/${order.body.id}`,{bearer:await token()})).body;evidence.receipt=(await api(`${prefix}/return-receipts/${receipt.body.id}`,{bearer:await token()})).body;return evidence.order.state==='COMPLETED'&&evidence.receipt.state==='COMPLETED';},'both products complete in the fresh legacy-owned world',90000);
  assert.equal((await simulatorRead('/sim/v1/equipment')).journalHighWater,5);
  assert.equal(query('core','SELECT count(*) FROM inventory_ledger;'),'2');assert.equal(query('returns','SELECT count(*) FROM sorting_ledger;'),'3');
  evidence.cases.push({status:'passed',supports:['A52','A54'],name:'Explicit reset preserves unrelated resources and private archives, refuses an old reset against a new demo, and the exact cached-image bootstrap creates a fresh seed/world with real login and both products',scope:'Fresh local bootstrap with prepared images; source build and the recorded reviewer walkthrough are separate checks.'});
}catch(error){failure=error;evidence.failure=error.message;}
finally{if(session)await session.close();evidence.endedAt=new Date().toISOString();if(ready)saveEvidence(id,evidence);else writeJson(resolve(directory,'results.json'),evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A52','A54'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:'node tools/scenario-driver/reset-bootstrap-smoke.mjs --destroy-cutover --new-world'});}
if(failure)throw failure;
console.log(`${id}: explicit reset and fresh bootstrap passed. ${directory}`);
