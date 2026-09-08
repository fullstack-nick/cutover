import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE='demo';
const {root,api,token,query,provisionObservers,roleCannotCreate,saveEvidence}=await import('./client.mjs');
const runId='assignment-boundary-'+Date.now(),cases=[];
let paused=false,controlVersion,bearer,orderId;
const controlPath='/internal/v1/sites/site-a/test-controls';
const evidence={cases};
async function control(body,key){
  const result=await api(controlPath,{target:'adapter',bearer,method:'POST',key:runId+'-'+key,body:{...body,expectedVersion:controlVersion,reason:'Verify intent retention before assignment at the initial task boundary.'}});
  assert.equal(result.status,200,JSON.stringify(result));controlVersion=result.body.version;return result.body;
}
async function reopen(){
  const actual=await api(controlPath,{target:'adapter',bearer:await token()});assert.equal(actual.status,200);assert.equal(actual.body.version,controlVersion,'Do not overwrite a different recorded process-control change.');
  bearer=await token();await control({workersPaused:false},'resume');paused=false;
}
try{
  provisionObservers();
  for(const target of ['core-api','adapter-api'])assert.equal(spawnSync('pwsh',['-NoProfile','-File',resolve(root,'scripts/forward.ps1'),'-Target',target],{encoding:'utf8',windowsHide:true,timeout:45000}).status,0);
  bearer=await token();
  assert.equal(query('core',"SELECT count(*) FROM pg_trigger WHERE tgname='reservation_creates_legacy_task' AND NOT tgisinternal;"),'0');
  assert.equal(query('core',"SELECT count(*) FROM pg_trigger WHERE tgname='reservation_creates_movement_intent' AND NOT tgisinternal;"),'1');
  const boundary=JSON.parse(query('core','SELECT row_to_json(b) FROM legacy_task_boundary b;'));
  const original=JSON.parse(query('core',"SELECT COALESCE(jsonb_agg(jsonb_build_object('taskId',r.task_id,'movementId',r.movement_id,'allocationId',t.allocation_id,'epoch',t.epoch,'state',t.state) ORDER BY r.task_id),'[]'::jsonb) FROM legacy_task_registration r JOIN legacy_tasks t ON t.site_id=r.site_id AND t.task_id=r.task_id AND t.movement_id=r.movement_id;"));
  assert.equal(original.length,boundary.original_task_count);assert.ok(original.every(task=>['COMPLETED','CANCELLED'].includes(task.state)&&task.allocationId));
  if(boundary.registration_ids.length)assert.equal(query('adapter',"SELECT count(*) FROM legacy_registration_receipts WHERE registration_id IN ("+boundary.registration_ids.map(id=>{assert.match(id,/^[a-f0-9-]{36}$/);return "'"+id+"'";}).join(',')+");"),String(original.length));
  evidence.registration={boundary,original};cases.push({name:'guarded DDL retains every registered original task and allocation',status:'passed',count:original.length});

  const gates=await api(controlPath,{target:'adapter',bearer});assert.equal(gates.status,200);controlVersion=gates.body.version;
  for(const flag of ['workersPaused','dispatchPaused','criticalStorage'])assert.equal(gates.body[flag],false);
  await control({workersPaused:true},'pause');paused=true;
  const request=await api('/api/v1/sites/site-a/orders',{bearer,method:'POST',key:runId,body:{sourceSystem:'scenario-driver',externalOrderRef:runId,storeId:'store-04',priority:6,lines:[{sku:'SKU-061',quantity:1},{sku:'SKU-062',quantity:1}]}});
  assert.equal(request.status,202,JSON.stringify(request));orderId=request.body.id;assert.match(orderId,/^[a-f0-9-]{36}$/);evidence.orderId=orderId;
  assert.equal(query('core',"SELECT count(*) FROM movement_intents WHERE order_id='"+orderId+"';"),'2');
  assert.equal(query('core',"SELECT count(*) FROM legacy_tasks WHERE order_id='"+orderId+"';"),'0','An intent-only reservation cannot manufacture a legacy task.');
  const intentIds=JSON.parse(query('core',"SELECT jsonb_agg(movement_id ORDER BY movement_id) FROM movement_intents WHERE order_id='"+orderId+"';"));
  const ids=intentIds.map(id=>{assert.match(id,/^[a-f0-9-]{36}$/);return "'"+id+"'";}).join(',');
  assert.equal(query('adapter','SELECT count(*) FROM movement_allocations WHERE movement_id IN ('+ids+');'),'0');
  evidence.pausedIntake={intents:2,legacyTasks:0,adapterAllocations:0};
  await reopen();
  const deadline=Date.now()+60000;let result;
  do{result=await api('/api/v1/sites/site-a/orders/'+orderId,{bearer});if(result.body.state==='COMPLETED')break;await delay(400);}while(Date.now()<deadline);
  assert.equal(result.body.state,'COMPLETED',JSON.stringify(result));
  assert.equal(query('core',"SELECT count(*) FROM legacy_tasks WHERE order_id='"+orderId+"' AND state='COMPLETED' AND priority=600 AND allocation_id IS NOT NULL;"),'2');
  assert.equal(query('core',"SELECT count(*) FROM inbox WHERE envelope->>'aggregateId' IN ("+ids+") AND envelope->>'eventType'='MovementAssigned.v1' AND state='APPLIED';"),'2');
  for(const id of intentIds){assert.equal(query('core',"SELECT count(*) FROM inventory_ledger WHERE movement_id='"+id+"';"),'1');assert.equal(query('simulator',"SELECT count(*) FROM execution_ledger WHERE movement_id='"+id+"';"),'1');}
  evidence.movements=intentIds;cases.push({name:'paused adapter retains intents without tasks, then broker assignment creates legacy tasks with one physical/inventory effect',status:'passed',orderId});
  assert.equal(query('execution','SELECT count(*) FROM execution_tasks WHERE movement_id IN ('+ids+');'),'0');
  assert.equal(query('execution',"SELECT count(*) FROM inbox WHERE envelope->>'aggregateId' IN ("+ids+") AND envelope->>'eventType'='MovementAssigned.v1' AND state='APPLIED';"),'2');
  const tasks=await api('/api/v1/sites/site-a/execution-tasks',{bearer});assert.equal(tasks.status,200);assert.ok(Array.isArray(tasks.body));
  assert.equal((await api('/api/v1/sites/site-b/execution-tasks',{bearer})).status,404);assert.ok(roleCannotCreate('execution'));
  cases.push({name:'independent execution consumer observes other-owner assignments without tasks and enforces site/DDL boundaries',status:'passed'});
  console.log('Passed '+cases.length+' assignment boundary process checks. '+saveEvidence(runId,evidence));
}catch(error){
  if(paused)try{await reopen();}catch(recovery){evidence.gateRecoveryFailure=recovery.message;}
  saveEvidence(runId,{...evidence,failure:error.message});throw error;
}
