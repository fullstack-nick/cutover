import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE='demo';
const {root,api,token,simulator,query,provisionObservers,roleCannotCreate,saveEvidence}=await import('./client.mjs');
const {publishFrom}=await import('./broker-probe.mjs');
const {humanSession}=await import('./human-session.mjs');
const runId=`returns-${Date.now()}`,prefix='/api/v1/sites/site-a',cases=[],evidence={cases,receipts:[]};
const k=['--kubeconfig',resolve(root,'.local/kubeconfig'),'--context','kind-cutover','-n','cutover-apps'];
let operator,otherSite,returnsStopped=false,dispatchPaused=false;const lanes=new Map(),faults=[];
function kube(args){const result=spawnSync('kubectl',[...k,...args],{encoding:'utf8',windowsHide:true,timeout:15000});assert.equal(result.status,0,'The scoped returns lifecycle operation failed.');return result.stdout;}
function replicas(count){const deployment=JSON.parse(kube(['get','deployment','returns-service','-o','json']));assert.equal(deployment.metadata.labels['app.kubernetes.io/part-of'],'cutover');kube(['scale','deployment/returns-service',`--replicas=${count}`]);returnsStopped=count===0;}
async function until(check,description,timeout=120000){const end=Date.now()+timeout;do{if(await check())return;await delay(400);}while(Date.now()<end);throw new Error(`Timed out: ${description}`);}
async function receipt(label,counts={REUSABLE:4,NEEDS_CLEANING:2,DAMAGED:1}){const body={sourceSystem:'scenario-driver',externalReceiptRef:`${runId}-${label}`,counts};const reply=await api(prefix+'/return-receipts',{method:'POST',key:body.externalReceiptRef,bearer:await token(),body});assert.equal(reply.status,202,JSON.stringify(reply.body));const value={id:reply.body.id,body};evidence.receipts.push(value);return value;}
async function finish(receipt){const bearer=await token();let current;await until(async()=>{const result=await api(`${prefix}/return-receipts/${receipt.id}`,{bearer});assert.equal(result.status,200);current=result.body;return current.state==='COMPLETED';},'receipt has confirmed sorting evidence');
  for(const movement of current.movements){
    assert.equal(query('returns',`SELECT count(*) FROM sorting_ledger WHERE movement_id='${movement.movementId}' AND quantity=${movement.movement.quantity};`),'1');
    assert.equal(query('simulator',`SELECT count(*) FROM execution_ledger WHERE movement_id='${movement.movementId}' AND quantity=${movement.movement.quantity} AND destination='${movement.movement.destination}';`),'1');
    assert.equal(query('adapter',`SELECT count(*) FROM movement_allocations WHERE movement_id='${movement.movementId}' AND owner='returns-service' AND zone_id='returns';`),'1');
  }
  assert.deepEqual(Object.fromEntries(current.counts.map(count=>[count.classification,count.sorted])),receipt.body.counts);return current;
}
async function setLane(lane,blocked){if(!lanes.has(lane.laneId))lanes.set(lane.laneId,lane);assert.equal((await simulator('/sim/v1/test-controls/lanes',{siteId:'site-a',laneId:lane.laneId,blocked})).status,200);}
async function restoreLanes(){for(const lane of lanes.values())assert.equal((await simulator('/sim/v1/test-controls/lanes',{siteId:'site-a',laneId:lane.laneId,blocked:lane.blocked})).status,200);lanes.clear();}
async function gate(paused){const bearer=await token(),path='/internal/v1/sites/site-a/test-controls';const before=await api(path,{bearer,target:'adapter'});assert.equal(before.status,200);const after=await api(path,{method:'POST',bearer,target:'adapter',key:`${runId}-gate-${before.body.version}`,body:{expectedVersion:before.body.version,dispatchPaused:paused,reason:paused?'Select a deterministic returns command fault before dispatch.':'Restore dispatch after configuring the selected returns command fault.'}});assert.equal(after.status,200);dispatchPaused=paused;}
async function order(label){const body={sourceSystem:'scenario-driver',externalOrderRef:`${runId}-${label}`,storeId:'store-08',priority:5,lines:[{sku:'SKU-089',quantity:1},{sku:'SKU-090',quantity:1}]};const bearer=await token(),created=await api(prefix+'/orders',{method:'POST',bearer,key:body.externalOrderRef,body});assert.equal(created.status,202);return created.body.id;}
async function finishOrder(id){const bearer=await token();let result;await until(async()=>{result=(await api(`${prefix}/orders/${id}`,{bearer})).body;return result.state==='COMPLETED';},'independent outbound order completes');return result;}
try{
  provisionObservers();const bearer=await token();
  const controls=await api('/internal/v1/sites/site-a/test-controls',{bearer,target:'adapter'});assert.equal(controls.status,200);for(const flag of ['workersPaused','dispatchPaused','criticalStorage'])assert.equal(controls.body[flag],false);
  assert.equal(query('adapter',"SELECT count(*) FROM migration_sessions WHERE phase NOT IN ('COMPLETED','REVERSED','SUPERSEDED','CANCELLED');"),'0');
  const before=(await api(prefix+'/return-counters',{bearer})).body;assert.equal(before.classifications.length,3);const coreBefore=query('core','SELECT count(*) FROM inventory_ledger;');
  const mixed=await receipt('mixed',{REUSABLE:8,NEEDS_CLEANING:3,DAMAGED:2});
  const duplicates=await Promise.all(Array.from({length:5},(_,index)=>api(prefix+'/return-receipts',{method:'POST',bearer,key:`${runId}-duplicate-${index}`,body:mixed.body})));
  for(const duplicate of duplicates){assert.equal(duplicate.status,202);assert.equal(duplicate.body.id,mixed.id);}
  const changed={...mixed.body,counts:{...mixed.body.counts,REUSABLE:9}};
  assert.equal((await api(prefix+'/return-receipts',{method:'POST',bearer,key:mixed.body.externalReceiptRef,body:changed})).status,409);
  assert.equal((await api(prefix+'/return-receipts',{method:'POST',bearer,key:`${runId}-changed-reference`,body:changed})).status,409);
  const completed=await finish(mixed);const duplicateIds=[];
  for(const movement of completed.movements){
    for(const [owner,publisher,type]of [['returns','returns-service','MovementRequested.v1'],['adapter','equipment-adapter','MovementCompleted.v1']]){
      const original=JSON.parse(query(owner,`SELECT envelope FROM outbox WHERE aggregate_id='${movement.movementId}' AND event_type='${type}';`));
      const duplicate={...original,eventId:randomUUID()};duplicateIds.push({id:duplicate.eventId,receiver:owner==='returns'?'adapter':'returns'});
      const result=await publishFrom(publisher,duplicate);assert.equal(result.result,'CONFIRMED');
    }
  }
  await until(()=>duplicateIds.every(item=>query(item.receiver,`SELECT state FROM inbox WHERE event_id='${item.id}';`)==='APPLIED'),'new event IDs are durably deduplicated');
  await finish(mixed);const after=(await api(prefix+'/return-counters',{bearer})).body;
  for(const count of after.classifications){const prior=before.classifications.find(item=>item.classification===count.classification);assert.equal(count.received-prior.received,mixed.body.counts[count.classification]);assert.equal(count.sorted-prior.sorted,mixed.body.counts[count.classification]);}
  assert.equal(query('core','SELECT count(*) FROM inventory_ledger;'),coreBefore);
  assert.equal(query('returns',`SELECT count(*) FROM receipts WHERE receipt_id='${mixed.id}';`),'1');
  cases.push({candidates:['A04','A06','A07','A38'],name:'mixed receipt, concurrent business duplicates and different event IDs have single independent effects',status:'passed',receiptId:mixed.id,duplicateIds,before,after});
  operator=await humanSession('operator-a');otherSite=await humanSession('operator-b');
  assert.equal((await api(`${prefix}/return-receipts/${mixed.id}`,{bearer:otherSite.bearer()})).status,404);
  assert.equal((await api(`/api/v1/sites/site-b/return-receipts/${mixed.id}`,{bearer:otherSite.bearer()})).status,404);
  const isolated=await api('/api/v1/sites/site-b/return-receipts',{bearer:otherSite.bearer()});assert.equal(isolated.status,200);assert.equal(isolated.body.items.length,0);
  assert.equal((await api(prefix+'/return-receipts',{method:'POST',bearer:operator.bearer(),key:`${runId}-operator`,body:mixed.body})).status,403);
  for(const counts of [{REUSABLE:-1,NEEDS_CLEANING:0,DAMAGED:0},{REUSABLE:0,NEEDS_CLEANING:0,DAMAGED:0},{REUSABLE:10001,NEEDS_CLEANING:0,DAMAGED:0},{OTHER:1}])assert.equal((await api(prefix+'/return-receipts',{method:'POST',bearer,key:randomUUID(),body:{...mixed.body,counts}})).status,422);
  assert.ok(roleCannotCreate('returns'));
  cases.push({candidates:['A10','A37','A40'],name:'local human roles, site isolation, bounded input and runtime DDL denial',status:'passed'});
  const equipment=(await simulator('/sim/v1/equipment')).body;
  const ambient=equipment.lanes.find(lane=>lane.siteId==='site-a'&&lane.zoneId==='ambient');assert.ok(ambient && !ambient.blocked);await setLane(ambient,true);
  await until(async()=>(await api(prefix+'/equipment',{bearer})).body.lanes.find(lane=>lane.laneId===ambient.laneId)?.blocked,'adapter observes selected outbound lane fault');
  const flowing=await receipt('outbound-fault'),outbound=await order('alternate-lane');await finish(flowing);const fulfilled=await finishOrder(outbound);
  for(const movement of fulfilled.movements){const command=(await api(`${prefix}/commands/${movement.movementId}`,{bearer})).body;assert.notEqual(command.payload.laneId,ambient.laneId);}
  cases.push({candidate:'A23',name:'returns and compatible alternate outbound lanes continue during one outbound lane fault',status:'passed',receiptId:flowing.id,orderId:outbound,blockedLane:ambient.laneId});await restoreLanes();
  const returnLane=equipment.lanes.find(lane=>lane.siteId==='site-a'&&lane.zoneId==='returns');assert.ok(returnLane&&!returnLane.blocked);await setLane(returnLane,true);
  await until(async()=>(await api(prefix+'/equipment',{bearer})).body.lanes.find(lane=>lane.laneId===returnLane.laneId)?.blocked,'adapter observes blocked returns lane');
  const held=await receipt('restart',{REUSABLE:3,NEEDS_CLEANING:0,DAMAGED:0});let task;
  await until(async()=>{const view=(await api(`${prefix}/return-receipts/${held.id}`,{bearer})).body;task=view.movements[0];return task?.taskState==='BLOCKED';},'sorting task is durable and blocked');
  const beforePod=JSON.parse(kube(['get','pods','-l','app.kubernetes.io/name=returns-service','-o','json'])).items[0];
  replicas(0);await until(()=>JSON.parse(kube(['get','pods','-l','app.kubernetes.io/name=returns-service','-o','json'])).items.length===0,'returns process stops');
  const independentOrder=await order('returns-stopped');await finishOrder(independentOrder);
  replicas(1);await until(()=>JSON.parse(kube(['get','pods','-l','app.kubernetes.io/name=returns-service','-o','json'])).items.some(pod=>pod.metadata.uid!==beforePod.metadata.uid&&pod.status.containerStatuses?.every(container=>container.ready)),'returns restarts on its existing owner database',180000);
  const resumed=(await api(`${prefix}/return-receipts/${held.id}`,{bearer:await token()})).body;assert.equal(resumed.movements[0].taskId,task.taskId);assert.equal(resumed.movements[0].movementId,task.movementId);
  await restoreLanes();await finish(held);
  cases.push({candidates:['A38','A45'],name:'outbound keeps working during returns process outage; returns retains its original task and receipt',status:'passed',receiptId:held.id,taskId:task.taskId,orderId:independentOrder,previousPodUid:beforePod.metadata.uid});
  await gate(true);const lost=await receipt('lost-response',{REUSABLE:2,NEEDS_CLEANING:0,DAMAGED:0});const lostView=(await api(`${prefix}/return-receipts/${lost.id}`,{bearer:await token()})).body;
  const movementId=lostView.movements[0].movementId;const fault=await simulator('/sim/v1/test-controls/faults',{kind:'LOST_RESPONSE',commandId:movementId,count:1,delayMillis:5000});assert.equal(fault.status,200);faults.push(fault.body.faultId);await gate(false);await finish(lost);
  assert.ok(Number(query('adapter',`SELECT count(*) FROM outbox WHERE aggregate_id='${movementId}' AND event_type='CommandOutcomeUnknown.v1';`))>=1);
  cases.push({candidate:'A18',name:'returns lost response reconciles one physical sorting effect',status:'passed',receiptId:lost.id,movementId});
  evidence.runtimeSchema=query('returns',"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;");
  console.log(`Passed ${cases.length} returns process checks. ${saveEvidence(runId,evidence)}`);
}catch(error){saveEvidence(runId,{...evidence,failure:error.message});throw error;}
finally{
  if(returnsStopped)replicas(1);
  if(dispatchPaused)await gate(false);
  await restoreLanes();for(const id of faults)assert.equal((await simulator(`/sim/v1/test-controls/faults/${id}`,undefined,'scenario','DELETE')).status,200);
  await operator?.close();await otherSite?.close();
}
