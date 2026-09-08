import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api,token,query,provisionObservers,saveEvidence,credentials }=await import('./client.mjs');
const { publishFrom }=await import('./broker-probe.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root,target,privateDirectory,maintenanceLock,until,request,writeJson,simulatorRead }=await import('../../scripts/lib/local-platform.mjs');
const id=`mandatory-return-${Date.now()}`,directory=resolve(root,'.local/evidence',id),platform=target('demo'),prefix='/api/v1/sites/site-a';
privateDirectory(directory);const release=maintenanceLock(id);
const evidence={startedAt:new Date().toISOString(),cases:[],replays:[]};
const exchange='cutover.returns-service.v1',queue='cutover.equipment-adapter.inbox',bindingPath=`/api/bindings/cutover/e/${exchange}/q/${queue}`;
let brokerForward,brokerOrigin,returnsForward,supervisor,removed=false,binding,failure,receipt;
async function broker(path,method='GET',body){
  assert.ok(path.startsWith(bindingPath)||path===`/api/exchanges/cutover/${exchange}/bindings/source`||path===`/api/queues/cutover/${queue}`);
  const response=await fetch(brokerOrigin+path,{method,signal:AbortSignal.timeout(10000),headers:{Authorization:`Basic ${Buffer.from(`cutover_admin:${credentials.passwords.rabbit_admin}`).toString('base64')}`,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined});
  assert.ok(response.ok,`The scoped broker binding operation returned ${response.status}.`);const text=await response.text();return text?JSON.parse(text):null;
}
async function restoreBinding(){
  if(!removed)return;
  await broker(bindingPath,'POST',{routing_key:binding.routing_key,arguments:binding.arguments});
  const actual=await broker(bindingPath);assert.equal(actual.length,1);assert.equal(actual[0].properties_key,binding.properties_key);assert.deepEqual(actual[0].arguments,binding.arguments);removed=false;evidence.bindingRestoredAt=new Date().toISOString();
}
async function replay(){
  if(!receipt)return;
  const rows=JSON.parse(query('returns',`SELECT coalesce(jsonb_agg(jsonb_build_object('eventId',event_id,'version',version,'hash',encode(sha256(envelope::text::bytea),'hex'))),'[]') FROM outbox WHERE envelope->>'correlationId'='${receipt.id}' AND published_at IS NULL AND paused AND (lease_until IS NULL OR lease_until<now());`));
  for(const row of rows){
    supervisor??=await humanSession('supervisor-a');returnsForward??=await platform.forward('returns-service');
    const result=await request(returnsForward.origin,`/internal/v1/sites/site-a/messaging/outbox/${row.eventId}/replay`,{method:'POST',bearer:supervisor.bearer(),key:`${id}-${row.eventId}-${row.version}`,body:{expectedVersion:row.version,reason:'The original owned exchange-to-queue binding has been restored. Retry the retained mandatory-return event with its unchanged identity.'}});
    assert.equal(query('returns',`SELECT encode(sha256(envelope::text::bytea),'hex') FROM outbox WHERE event_id='${row.eventId}';`),row.hash);evidence.replays.push({...row,result});
  }
}
try{
  platform.verify();provisionObservers();
  for(const owner of ['core','returns'])assert.equal(query(owner,'SELECT active_requests FROM admission;'),'0');
  for(const owner of ['core','returns','adapter','execution'])assert.equal(query(owner,'SELECT unpublished_events FROM admission;'),'0');
  const pod=platform.owned(platform.get('pod','rabbitmq-0','cutover-platform'));evidence.brokerUid=pod.metadata.uid;
  brokerForward=spawn('kubectl',[...platform.args,'-n','cutover-platform','port-forward','pod/rabbitmq-0',':15672','--address','127.0.0.1'],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});
  let output='',port,forwardFailure;brokerForward.stdout.on('data',chunk=>{output=(output+chunk).slice(-4096);port??=Number(output.match(/Forwarding from 127\.0\.0\.1:(\d+)/)?.[1])||undefined;});brokerForward.stderr.resume();brokerForward.on('error',error=>forwardFailure=error);brokerForward.on('exit',()=>forwardFailure??=Error('The owned broker management forward exited.'));
  await until(()=>{if(forwardFailure)throw forwardFailure;return port;},'the owned broker management forward',20000);brokerOrigin=`http://127.0.0.1:${port}`;
  const sourceBindings=await broker(`/api/exchanges/cutover/${exchange}/bindings/source`);assert.equal(sourceBindings.length,1);assert.equal(sourceBindings[0].destination,queue);assert.equal(sourceBindings[0].destination_type,'queue');
  [binding]=await broker(bindingPath);assert.ok(binding);assert.equal(binding.routing_key,'#');assert.deepEqual(binding.arguments,{});evidence.bindingBefore=binding;
  const queueBefore=await broker(`/api/queues/cutover/${queue}`);assert.equal(queueBefore.type,'quorum');evidence.queueBefore={name:queueBefore.name,type:queueBefore.type,arguments:queueBefore.arguments};evidence.worldBefore=await simulatorRead('/sim/v1/equipment');
  // Record the exact restore operation before removing one owned binding. No queue or message is deleted.
  writeJson(resolve(directory,'binding-recovery.json'),{state:'REMOVING',brokerUid:pod.metadata.uid,path:bindingPath,binding});removed=true;
  await broker(`${bindingPath}/${encodeURIComponent(binding.properties_key)}`,'DELETE');assert.deepEqual(await broker(bindingPath),[]);
  const accepted=await api(`${prefix}/return-receipts`,{method:'POST',key:id,bearer:await token(),body:{sourceSystem:'scenario-driver',externalReceiptRef:id,counts:{REUSABLE:1,NEEDS_CLEANING:0,DAMAGED:0}}});assert.equal(accepted.status,202);receipt=accepted.body;evidence.receipt=receipt;
  await until(()=>Number(query('returns',`SELECT count(*) FROM outbox WHERE envelope->>'correlationId'='${receipt.id}' AND published_at IS NULL AND last_error='MANDATORY_RETURN';`))>0,'the real relay retains the returned event',90000);
  evidence.retained=JSON.parse(query('returns',`SELECT jsonb_agg(jsonb_build_object('eventId',event_id,'envelope',envelope,'publishedAt',published_at,'attempts',attempts,'lastError',last_error)) FROM outbox WHERE envelope->>'correlationId'='${receipt.id}';`));
  assert.ok(evidence.retained.every(item=>item.publishedAt===null));
  const retained=evidence.retained.find(item=>item.lastError==='MANDATORY_RETURN');
  evidence.probe=await publishFrom('returns-service',retained.envelope);assert.equal(evidence.probe.result,'RETURNED');assert.equal(evidence.probe.exitCode,2);
  const movement=query('returns',`SELECT movement_id FROM return_movements WHERE receipt_id='${receipt.id}';`);assert.match(movement,/^[a-f0-9-]{36}$/);evidence.movementId=movement;
  for(const [owner,table]of[['returns','sorting_ledger'],['simulator','execution_ledger']])assert.equal(query(owner,`SELECT count(*) FROM ${table} WHERE movement_id='${movement}';`),'0');
  await restoreBinding();await replay();
  await until(async()=>{await replay();return (await api(`${prefix}/return-receipts/${receipt.id}`,{bearer:await token()})).body.state==='COMPLETED';},'the original receipt completes after restoring the binding',120000);
  for(const event of evidence.retained){const row=JSON.parse(query('returns',`SELECT jsonb_build_object('envelope',envelope,'published',published_at IS NOT NULL) FROM outbox WHERE event_id='${event.eventId}';`));assert.deepEqual(row.envelope,event.envelope);assert.equal(row.published,true);}
  for(const [owner,table]of[['returns','sorting_ledger'],['simulator','execution_ledger']])assert.equal(query(owner,`SELECT count(*) FROM ${table} WHERE movement_id='${movement}' AND quantity=1;`),'1');
  const queueAfter=await broker(`/api/queues/cutover/${queue}`);assert.deepEqual({name:queueAfter.name,type:queueAfter.type,arguments:queueAfter.arguments},evidence.queueBefore);assert.equal(platform.get('pod','rabbitmq-0','cutover-platform').metadata.uid,evidence.brokerUid);
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');for(const key of ['worldId','journalGeneration'])assert.equal(evidence.worldAfter[key],evidence.worldBefore[key]);assert.equal(evidence.worldAfter.journalHighWater-evidence.worldBefore.journalHighWater,1);
  evidence.cases.push({id:'A14',status:'passed',name:'Actual positive-confirm mandatory return retains the business outbox; restoring one exact original binding delivers the same IDs and produces one physical/sorting effect.'});
}catch(error){failure=error;evidence.failure=error.message;}
finally{
  try{await restoreBinding();if(failure)await replay();}catch(error){evidence.cleanupFailure=error.message;failure??=error;}
  writeJson(resolve(directory,'binding-recovery.json'),{state:removed?'RESTORE_REQUIRED':'RESTORED',brokerUid:evidence.brokerUid,path:bindingPath,binding});
  if(supervisor)await supervisor.close();returnsForward?.close();brokerForward?.kill();release();evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);
}
if(failure)throw failure;console.log(`${id}: live mandatory-return recovery passed. ${directory}`);
