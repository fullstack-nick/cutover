import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { openSync, closeSync } from 'node:fs';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
assert.ok(process.argv.slice(2).every(argument=>argument==='--offline-browser'),'Only the optional isolated-browser denial flag is supported.');
const offlineBrowser=process.argv.includes('--offline-browser');
const { api, token, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, target, privateDirectory, simulatorRead, until, call, writeJson }=await import('../../scripts/lib/local-platform.mjs');
const id=`demo-lifecycle-${Date.now()}`,directory=resolve(root,'.local/evidence',id),prefix='/api/v1/sites/site-a';
privateDirectory(directory);const evidence={startedAt:new Date().toISOString(),cases:[],steps:[],offlineBrowser,browserRequests:[]};
const names=['cutover-control-plane','cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1','cutover-dev-equipment-volume-probe-1'];
const containers=()=>JSON.parse(call('docker',['inspect',...names])).map(box=>({name:box.Name,id:box.Id,image:box.Image,running:box.State.Running,mounts:box.Mounts.filter(m=>m.Type==='volume').map(m=>({name:m.Name,destination:m.Destination})).sort((a,b)=>a.destination.localeCompare(b.destination))}));
const counts=()=>({core:JSON.parse(query('core',"SELECT jsonb_build_object('orders',(SELECT count(*) FROM orders),'reservations',(SELECT count(*) FROM reservations),'effects',(SELECT count(*) FROM inventory_ledger),'stock',(SELECT sum(on_hand) FROM stock),'reserved',(SELECT sum(reserved) FROM stock));")),returns:JSON.parse(query('returns',"SELECT jsonb_build_object('receipts',(SELECT count(*) FROM receipts),'effects',(SELECT count(*) FROM sorting_ledger),'received',(SELECT sum(received) FROM crate_counters),'sorted',(SELECT sum(sorted) FROM crate_counters));"))});
async function step(action){
  const file=openSync(resolve(directory,`${action.toLowerCase()}-${evidence.steps.length}.log`),'w',0o600),startedAt=new Date().toISOString();
  try{await new Promise((done,failed)=>{const child=spawn(process.execPath,['scripts/demo.mjs',action],{cwd:root,windowsHide:true,stdio:['ignore',file,file]});child.once('error',failed);child.once('exit',code=>code===0?done():failed(new Error(`Demo ${action} failed (${code}); inspect the retained lifecycle log.`)));});}
  finally{closeSync(file);evidence.steps.push({action,startedAt,endedAt:new Date().toISOString()});}
}
let stopped=false,session,failure;
try{
  target('demo').verify();provisionObservers();
  assert.equal(query('adapter',"SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');"),'0');
  for(const owner of ['core','returns'])assert.equal(query(owner,'SELECT active_requests FROM admission;'),'0');
  const body={sourceSystem:'scenario-driver',externalReceiptRef:id,counts:{REUSABLE:1,NEEDS_CLEANING:0,DAMAGED:0}};
  const accepted=await api(`${prefix}/return-receipts`,{method:'POST',key:id,body,bearer:await token()});assert.equal(accepted.status,202);
  let receipt;await until(async()=>{receipt=(await api(`${prefix}/return-receipts/${accepted.body.id}`,{bearer:await token()})).body;return receipt.state==='COMPLETED';},'the retained lifecycle receipt completes',90000);
  evidence.receipt=receipt;evidence.body=body;evidence.physicalBefore=await simulatorRead('/sim/v1/equipment');evidence.countsBefore=counts();evidence.containersBefore=containers();
  stopped=true;await step('Stop');assert.ok(containers().every(box=>!box.running));
  await step('Status');await step('Stop');assert.ok(containers().every(box=>!box.running),'Repeated Stop must remain idempotent.');
  await step('Start');stopped=false;await step('Start');
  evidence.containersAfter=containers();assert.ok(evidence.containersAfter.every(box=>box.running));
  const identities=boxes=>boxes.map(({running,...box})=>box);assert.deepEqual(identities(evidence.containersAfter),identities(evidence.containersBefore));
  evidence.physicalAfter=await simulatorRead('/sim/v1/equipment');for(const key of ['worldId','journalGeneration','journalHighWater'])assert.equal(evidence.physicalAfter[key],evidence.physicalBefore[key]);
  evidence.countsAfter=counts();assert.deepEqual(evidence.countsAfter,evidence.countsBefore);
  const repeated=await api(`${prefix}/return-receipts`,{method:'POST',key:id,body,bearer:await token()});assert.equal(repeated.status,202);assert.equal(repeated.body.id,receipt.id);assert.deepEqual(counts(),evidence.countsBefore);
  session=await humanSession('operator-a',{offline:offlineBrowser,onRequest:request=>evidence.browserRequests.push(request)});const bearer=session.bearer();
  if(offlineBrowser)assert.ok(evidence.browserRequests.length>0&&evidence.browserRequests.every(request=>request.allowed),'The restarted local login must request only permitted loopback assets.');
  assert.equal((await api(`${prefix}/orders?limit=2`,{bearer})).status,200);assert.equal((await api(`${prefix}/return-receipts/${receipt.id}`,{bearer})).body.state,'COMPLETED');
  evidence.cases.push({status:'passed',supports:['A45','A52'],name:'Normal Stop/Start and repeated commands preserve all four container/volume identities, world/generation/high water, business totals, original receipt idempotency and real local login',scope:'Preservation lifecycle only; explicit reset and a fresh bootstrap are separate required checks.'});
}catch(error){failure=error;evidence.failure=error.message;}
finally{
  try{if(stopped)await step('Start');if(session)await session.close();}catch(error){evidence.cleanupFailure=error.message;failure??=error;}
  evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A45','A52'],scope:'Normal preserved lifecycle; not full reset acceptance',startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:'node tools/scenario-driver/demo-lifecycle-smoke.mjs'});
}
if(failure)throw failure;
console.log(`${id}: preserved lifecycle passed. ${directory}`);
