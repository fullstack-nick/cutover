import assert from 'node:assert/strict';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, simulator, saveEvidence }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, target, until, simulatorRead, privateDirectory, writeJson, maintenanceLock }=await import('../../scripts/lib/local-platform.mjs');
const eventId=process.argv[2];assert.match(eventId??'',/^[a-f0-9-]{36}$/,'Specify the original quarantined MovementRequested event ID.');
const runId=`retained-intent-${Date.now()}`,directory=resolve(root,'.local/evidence',runId),platform=target('demo');
privateDirectory(directory);const unlock=maintenanceLock(runId),evidence={eventId,cases:[],startedAt:new Date().toISOString()};
let supervisor,forward;
try{
  platform.verify();
  const item=JSON.parse(query('adapter',`SELECT jsonb_build_object('eventId',event_id,'version',version,'state',state,'lastError',last_error,'attempts',attempts,'envelope',envelope,'payloadHash',payload_hash) FROM inbox WHERE event_id='${eventId}' AND site_id='site-a';`));
  assert.equal(item.state,'QUARANTINED');assert.equal(item.envelope.eventType,'MovementRequested.v1');assert.equal(item.envelope.siteId,'site-a');
  assert.ok(['legacy-core','returns-service'].includes(item.envelope.source));
  const owner=item.envelope.source==='legacy-core'?'core':'returns',movement=item.envelope.aggregateId,requestId=item.envelope.correlationId;
  assert.match(movement,/^[a-f0-9-]{36}$/);assert.match(requestId,/^[a-f0-9-]{36}$/);
  const original=JSON.parse(query(owner,`SELECT envelope FROM outbox WHERE event_id='${eventId}';`));assert.deepEqual(original,item.envelope);
  assert.ok(['ambient','chilled','returns'].includes(original.payload.zoneId));
  assert.equal(query('adapter',`SELECT count(*) FROM movement_allocations WHERE movement_id='${movement}';`),'0','This procedure is restricted to original intents that never acquired an allocation.');
  const absent=await simulator(`/sim/v1/commands/${movement}`);assert.equal(absent.status,404);assert.equal(absent.body.completeHistory,true);
  evidence.worldBefore=await simulatorRead('/sim/v1/equipment');assert.equal(absent.body.worldId,evidence.worldBefore.worldId);assert.equal(absent.body.journalGeneration,evidence.worldBefore.journalGeneration);
  evidence.route=JSON.parse(query('adapter',`SELECT to_jsonb(r) FROM zone_routes r WHERE site_id='site-a' AND zone_id='${original.payload.zoneId}';`));assert.equal(evidence.route.state,'ACTIVE');
  assert.equal(query('adapter',"SELECT count(*) FROM service_control WHERE workers_paused OR dispatch_paused OR critical_storage OR restoration_required;"),'0');
  evidence.before=item;evidence.absence=absent.body;writeJson(resolve(directory,'before.json'),evidence);
  supervisor=await humanSession('supervisor-a');forward=await platform.forward('equipment-adapter');
  const response=await fetch(`${forward.origin}/internal/v1/sites/site-a/messaging/inbox/${eventId}/replay`,{method:'POST',signal:AbortSignal.timeout(8000),headers:{Authorization:`Bearer ${supervisor.bearer()}`,'Content-Type':'application/json','Idempotency-Key':runId},body:JSON.stringify({expectedVersion:item.version,reason:'Current route and workers are healthy; same-world absence and original retained intent verified before replay.'})});
  assert.equal(response.status,200);evidence.replay=await response.json();
  const bearer=await token(),resource=owner==='core'?'orders':'return-receipts';let work;
  await until(async()=>{work=(await api(`/api/v1/sites/site-a/${resource}/${requestId}`,{bearer})).body;return work.state==='COMPLETED';},'original quarantined business request completes after audited replay',120000);
  assert.equal(query('adapter',`SELECT state FROM inbox WHERE event_id='${eventId}';`),'APPLIED');
  assert.equal(query('adapter',`SELECT payload_hash FROM inbox WHERE event_id='${eventId}';`),item.payloadHash);
  for(const [database,table] of [[owner,owner==='core'?'inventory_ledger':'sorting_ledger'],['simulator','execution_ledger']])assert.equal(query(database,`SELECT count(*) FROM ${table} WHERE movement_id='${movement}';`),'1');
  evidence.audit=JSON.parse(query('adapter',`SELECT jsonb_agg(to_jsonb(a) ORDER BY occurred_at) FROM audit a WHERE resource_id='${eventId}';`));assert.ok(evidence.audit.length>0);
  evidence.completed=work;evidence.worldAfter=await simulatorRead('/sim/v1/equipment');assert.equal(evidence.worldAfter.worldId,evidence.worldBefore.worldId);
  evidence.cases.push({status:'passed',name:'Audited replay retains the original accepted intent and produces one physical and business effect',eventId,movementId:movement,requestId});
  evidence.endedAt=new Date().toISOString();console.log(`Retained intent recovered. ${saveEvidence(runId,evidence)}`);
}catch(error){saveEvidence(runId,{...evidence,failure:error.message});throw error;}
finally{forward?.close();await supervisor?.close();unlock();}
