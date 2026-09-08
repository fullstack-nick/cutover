import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { publishFrom }=await import('./broker-probe.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, target, privateDirectory, maintenanceLock, request, until, simulatorRead, writeJson }=await import('../../scripts/lib/local-platform.mjs');
const id=`queue-overflow-${Date.now()}`,directory=resolve(root,'.local/evidence',id),platform=target('demo'),prefix='/api/v1/sites/site-a';
privateDirectory(directory);const release=maintenanceLock(id);
const evidence={startedAt:new Date().toISOString(),cases:[],receipts:[],queues:[],probes:[],replays:[]};
const forwards=new Map(),held=new Set();let supervisor,operator,failure,baselineEvent,shadowEvent;
const json=(owner,sql)=>JSON.parse(query(owner,sql));
async function forward(service){if(!forwards.has(service))forwards.set(service,await platform.forward(service));return forwards.get(service).origin;}
async function consumer(service,paused){
  const origin=await forward(service),path='/internal/v1/sites/site-a/test-controls',bearer=await token();
  const before=await request(origin,path,{bearer});
  if(before.consumerPaused!==paused)await request(origin,path,{bearer,method:'POST',key:`${id}-${service}-${before.version}`,body:{expectedVersion:before.version,consumerPaused:paused,reason:paused?'Hold this one observation or adapter consumer to verify its real bounded queue.':'Resume the original retained queue after the bounded capacity experiment.'}});
  if(paused)held.add(service);else held.delete(service);
}
function queues(phase){
  platform.owned(platform.get('pod','rabbitmq-0','cutover-platform'));
  const rows=JSON.parse(platform.kube(['-n','cutover-platform','exec','rabbitmq-0','--','rabbitmqctl','--silent','list_queues','-p','cutover','name','messages_ready','messages_unacknowledged','arguments','--formatter=json']));
  evidence.queues.push({at:new Date().toISOString(),phase,rows});return rows;
}
function queue(name,phase){const row=queues(phase).find(row=>row.name===name);assert.ok(row);return row;}
async function fill(service,event,name,limit,exchange){
  const before=queue(name,'before-fill'),args=Object.fromEntries(before.arguments.map(([key,type,value])=>[key,value]));
  assert.equal(args['x-max-length'],limit);assert.equal(args['x-overflow'],'reject-publish');assert.equal(args['x-queue-type'],'quorum');
  for(let attempt=0;attempt<4;attempt++){
    const result=await publishFrom(service,event,{exchange,repeat:attempt===0?limit+1:64});evidence.probes.push({queue:name,eventId:event.eventId,...result});
    if(result.result==='NACKED'){
      const full=queue(name,'nack-observed');assert.ok(full.messages_ready>=limit);assert.ok(full.messages_ready<=limit+256,'Record bounded in-flight quorum overshoot instead of pretending max-length is an exact counter.');
      evidence.probes.at(-1).observedReady=full.messages_ready;return full;
    }
    assert.equal(result.result,'CONFIRMED');
  }
  throw Error('The bounded quorum queue did not reject the bounded overflow burst.');
}
async function receipt(label){
  const reference=`${id}-${label}`,result=await api(`${prefix}/return-receipts`,{method:'POST',key:reference,bearer:await token(),body:{sourceSystem:'scenario-driver',externalReceiptRef:reference,counts:{REUSABLE:1,NEEDS_CLEANING:0,DAMAGED:0}}});
  assert.equal(result.status,202);const retained={label,id:result.body.id};evidence.receipts.push(retained);return retained;
}
async function completed(receipt){
  await until(async()=>{await replayReturns();const result=await api(`${prefix}/return-receipts/${receipt.id}`,{bearer:await token()});return result.status===200&&result.body.state==='COMPLETED';},`the original ${receipt.label} receipt completes`,900000);
  const movement=query('returns',`SELECT movement_id FROM return_movements WHERE receipt_id='${receipt.id}';`);assert.match(movement,/^[a-f0-9-]{36}$/);receipt.movementId=movement;
  for(const [owner,table]of[['returns','sorting_ledger'],['simulator','execution_ledger']])assert.equal(query(owner,`SELECT count(*) FROM ${table} WHERE movement_id='${movement}' AND quantity=1;`),'1');
}
async function replayReturns(){
  if(!evidence.receipts.length)return;
  const ids=evidence.receipts.map(receipt=>`'${receipt.id}'`).join(',');
  const rows=json('returns',`SELECT coalesce(jsonb_agg(jsonb_build_object('eventId',event_id,'version',version,'hash',encode(sha256(envelope::text::bytea),'hex'))),'[]') FROM outbox WHERE published_at IS NULL AND paused AND (lease_until IS NULL OR lease_until<now()) AND envelope->>'correlationId' IN (${ids});`);
  for(const row of rows){
    supervisor??=await humanSession('supervisor-a');const origin=await forward('returns-service');
    const result=await request(origin,`/internal/v1/sites/site-a/messaging/outbox/${row.eventId}/replay`,{bearer:supervisor.bearer(),method:'POST',key:`${id}-return-${row.eventId}-${row.version}`,body:{expectedVersion:row.version,reason:'The adapter consumer is available again. Retry this original event after its queue overflow exhausted transport retries.'}});
    assert.equal(query('returns',`SELECT encode(sha256(envelope::text::bytea),'hex') FROM outbox WHERE event_id='${row.eventId}';`),row.hash);evidence.replays.push({kind:'critical',...row,result});
  }
}
async function replayShadows(){
  const rows=json('core',`SELECT coalesce(jsonb_agg(jsonb_build_object('eventId',o.event_id,'version',o.recovery_version,'hash',encode(sha256(o.envelope::text::bytea),'hex'))),'[]') FROM shadow_observation_outbox o JOIN legacy_decision_rounds r ON r.round_id=o.event_id WHERE r.site_id='site-a' AND r.created_at>='${evidence.startedAt}'::timestamptz AND o.published_at IS NULL AND o.paused AND (o.lease_until IS NULL OR o.lease_until<now());`);
  for(const row of rows){
    supervisor??=await humanSession('supervisor-a');const origin=await forward('legacy-core'),body={expectedVersion:row.version,reason:'The independent shadow consumer is available again. Resume only this retained observation after quorum queue capacity recovered.'},key=`${id}-shadow-${row.eventId}-${row.version}`,path=`/internal/v1/sites/site-a/messaging/shadow-observations/${row.eventId}/replay`;
    const result=await request(origin,path,{bearer:supervisor.bearer(),method:'POST',key,body});
    assert.deepEqual(await request(origin,path,{bearer:supervisor.bearer(),method:'POST',key,body}),result);
    assert.equal(query('core',`SELECT encode(sha256(envelope::text::bytea),'hex') FROM shadow_observation_outbox WHERE event_id='${row.eventId}';`),row.hash);evidence.replays.push({kind:'observation',...row,result});
  }
}
async function capture(){
  const fixture=JSON.parse(readFileSync(resolve(root,'tools/shadow-checks/target/shadow-evidence/comparisons.jsonl'),'utf8').split(/\r?\n/)[0]);
  return request(await forward('legacy-core'),'/internal/v1/sites/site-a/test-controls/scheduling-rounds',{bearer:await token(),method:'POST',body:fixture.input});
}
try{
  platform.verify();provisionObservers();
  for(const owner of ['core','adapter','execution','returns','shadow']){
    assert.equal(query(owner,'SELECT count(*) FROM service_control WHERE workers_paused OR consumer_paused OR relay_paused OR dispatch_paused OR intake_paused OR critical_storage;'),'0');
    assert.equal(query(owner,'SELECT unpublished_events FROM admission;'),'0');
  }
  assert.equal(query('core','SELECT pending_count FROM shadow_observation_capacity;'),'0');
  assert.ok(queues('preflight').every(row=>row.messages_ready===0&&row.messages_unacknowledged===0));
  for(const owner of ['core','returns'])assert.equal(query(owner,'SELECT active_requests FROM admission;'),'0');
  evidence.worldBefore=await simulatorRead('/sim/v1/equipment');evidence.routesBefore=json('adapter',"SELECT jsonb_agg(row_to_json(r) ORDER BY zone_id) FROM zone_routes r WHERE site_id='site-a';");
  const baseline=await receipt('baseline');await completed(baseline);
  baselineEvent=json('returns',`SELECT envelope FROM outbox WHERE aggregate_id='${baseline.id}' AND event_type='ReturnReceiptRegistered.v1' AND published_at IS NOT NULL;`);
  const baselineDeliveries=Number(query('adapter',`SELECT deliveries FROM inbox WHERE event_id='${baselineEvent.eventId}';`));
  await consumer('equipment-adapter',true);const head=await receipt('head-before-overflow');
  await until(()=>query('returns',`SELECT count(*) FROM outbox WHERE envelope->>'correlationId'='${head.id}' AND published_at IS NULL;`)==='0','the real head receipt enters the held queue');
  assert.equal(query('returns',`SELECT count(*) FROM sorting_ledger WHERE receipt_id='${head.id}';`),'0');
  const full=await fill('returns-service',baselineEvent,'cutover.equipment-adapter.inbox',10000,'cutover.returns-service.v1');
  const blocked=await receipt('source-nacked');
  await until(()=>Number(query('returns',`SELECT count(*) FROM outbox WHERE envelope->>'correlationId'='${blocked.id}' AND published_at IS NULL AND last_error='PUBLISH_NACK';`))>0,'a real business outbox retains the rejected publish',90000);
  evidence.retainedCritical=json('returns',`SELECT jsonb_agg(jsonb_build_object('eventId',event_id,'body',envelope,'lastError',last_error,'attempts',attempts,'publishedAt',published_at)) FROM outbox WHERE envelope->>'correlationId'='${blocked.id}';`);
  await consumer('equipment-adapter',false);await completed(head);await completed(blocked);
  await until(()=>queue('cutover.equipment-adapter.inbox','critical-drain').messages_ready===0,'all original critical copies drain',900000);
  evidence.criticalDuplicateDeliveries=Number(query('adapter',`SELECT deliveries FROM inbox WHERE event_id='${baselineEvent.eventId}';`))-baselineDeliveries;
  assert.ok(evidence.criticalDuplicateDeliveries>=full.messages_ready-2);await completed(baseline);
  evidence.cases.push({id:'A16',status:'passed',part:'Critical quorum queue rejects publishers, preserves the pre-overflow head and source bytes, and drains duplicates with single physical/sorting effects.'});

  const observed=await capture();shadowEvent=json('core',`SELECT envelope FROM shadow_observation_outbox WHERE event_id='${observed.roundId}';`);
  await until(()=>query('shadow',`SELECT count(*) FROM shadow_comparisons WHERE round_id='${observed.roundId}';`)==='1','the real baseline shadow comparison is retained');
  const shadowDeliveries=Number(query('shadow',`SELECT deliveries FROM inbox WHERE event_id='${observed.roundId}';`));
  await consumer('shadow-scheduler',true);const shadowFull=await fill('legacy-core',shadowEvent,'cutover.shadow-scheduler.inbox',1000,'cutover.observation.v1');
  const rejectedRound=await capture();evidence.rejectedRound=rejectedRound;
  await until(()=>query('core',`SELECT count(*) FROM shadow_observation_outbox WHERE event_id='${rejectedRound.roundId}' AND published_at IS NULL AND last_error='PUBLISH_NACK';`)==='1','a real shadow relay records the queue rejection',90000);
  const sku=query('core',"SELECT sku FROM stock WHERE site_id='site-a' AND on_hand-reserved>0 ORDER BY sku LIMIT 1;");assert.match(sku,/^SKU-\d{3}$/);
  const order=await api(`${prefix}/orders`,{method:'POST',key:`${id}-isolated-order`,bearer:await token(),body:{sourceSystem:'scenario-driver',externalOrderRef:`${id}-isolated-order`,storeId:'store-04',priority:5,lines:[{sku,quantity:1}]}});assert.equal(order.status,202);evidence.criticalOrder=order.body;
  const isolatedReceipt=await receipt('shadow-isolated');await completed(isolatedReceipt);
  await until(async()=> (await api(`${prefix}/orders/${order.body.id}`,{bearer:await token()})).body.state==='COMPLETED','critical outbound completes while shadow stays full',90000);
  const movement=query('core',`SELECT movement_id FROM movement_intents WHERE order_id='${order.body.id}';`);assert.match(movement,/^[a-f0-9-]{36}$/);
  for(const [owner,table]of[['core','inventory_ledger'],['simulator','execution_ledger']])assert.equal(query(owner,`SELECT count(*) FROM ${table} WHERE movement_id='${movement}' AND quantity=1;`),'1');
  await until(()=>query('core',`SELECT paused FROM shadow_observation_outbox WHERE event_id='${rejectedRound.roundId}';`)==='t','the original shadow transport exhausts its bounded retry budget',180000);
  assert.ok(queue('cutover.shadow-scheduler.inbox','critical-work-completed').messages_ready>=1000);
  operator=await humanSession('operator-a');const origin=await forward('legacy-core');
  for(const site of ['site-a','site-b']){
    const response=await fetch(`${origin}/internal/v1/sites/${site}/messaging/shadow-observations/${rejectedRound.roundId}/replay`,{method:'POST',headers:{Authorization:`Bearer ${operator.bearer()}`,'Content-Type':'application/json','Idempotency-Key':`${id}-denied-${site}`},body:JSON.stringify({expectedVersion:0,reason:'An operator cannot reopen an observation transport record.'}),signal:AbortSignal.timeout(10000)});assert.equal(response.status,403);
  }
  await consumer('shadow-scheduler',false);await replayShadows();
  await until(async()=>{await replayShadows();return query('core','SELECT pending_count FROM shadow_observation_capacity;')==='0'&&query('shadow',`SELECT count(*) FROM shadow_comparisons WHERE round_id='${rejectedRound.roundId}' AND matches;`)==='1';},'audited replay settles original observations',180000);
  await until(()=>queue('cutover.shadow-scheduler.inbox','shadow-drain').messages_ready===0,'all shadow copies drain',180000);
  evidence.shadowDuplicateDeliveries=Number(query('shadow',`SELECT deliveries FROM inbox WHERE event_id='${observed.roundId}';`))-shadowDeliveries;assert.ok(evidence.shadowDuplicateDeliveries>=shadowFull.messages_ready);
  assert.equal(query('shadow',`SELECT count(*) FROM shadow_comparisons WHERE round_id='${observed.roundId}';`),'1');assert.equal(query('shadow','SELECT count(*) FROM execution_tasks;'),'0');
  assert.ok(evidence.replays.some(item=>item.kind==='observation'&&item.eventId===rejectedRound.roundId));
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');for(const key of ['worldId','journalGeneration'])assert.equal(evidence.worldAfter[key],evidence.worldBefore[key]);
  assert.equal(evidence.worldAfter.journalHighWater-evidence.worldBefore.journalHighWater,5);
  assert.deepEqual(json('adapter',"SELECT jsonb_agg(row_to_json(r) ORDER BY zone_id) FROM zone_routes r WHERE site_id='site-a';"),evidence.routesBefore);
  evidence.cases.push({id:'A16',status:'passed',part:'A full independent shadow queue does not block either product; exhausted original observations resume through versioned, idempotent supervisor audit without command authority.'});
}catch(error){failure=error;evidence.failure=error.message;}
finally{
  for(const service of [...held])try{await consumer(service,false);}catch(error){(evidence.cleanupFailures??=[]).push(error.message);failure??=error;}
  if(failure)try{await replayReturns();await replayShadows();}catch(error){(evidence.cleanupFailures??=[]).push(error.message);}
  for(const session of [supervisor,operator])if(session)await session.close();for(const item of forwards.values())item.close();release();
  evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A16'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:'node tools/scenario-driver/queue-overflow-smoke.mjs',note:'Quorum max-length can overshoot by in-flight messages. Actual queue arguments, depth and bounded publisher confirmation batches are retained.'});
}
if(failure)throw failure;console.log(`${id}: both bounded queue-overflow checks passed. ${directory}`);
