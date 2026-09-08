import assert from 'node:assert/strict';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, target, maintenanceLock, privateDirectory, writeJson, simulatorRead, until, call }=await import('../../scripts/lib/local-platform.mjs');
const id=`broker-capacity-${Date.now()}`,directory=resolve(root,'.local/evidence',id),platform=target('demo');
privateDirectory(directory);
const release=maintenanceLock('broker-capacity',id),prefix='/api/v1/sites/site-a';
const evidence={startedAt:new Date().toISOString(),seed:20260908,cases:[],requests:[],admission:[],replays:[],softActiveLimit:800,hardActiveLimit:1000};
let brokerHeld=false,session,forward,failure;
const snapshot=()=>JSON.parse(query('returns','SELECT row_to_json(a) FROM admission a WHERE singleton;'));
const counters=()=>JSON.parse(query('returns',"SELECT jsonb_agg(jsonb_build_object('classification',classification,'received',received,'sorted',sorted) ORDER BY classification) FROM crate_counters WHERE site_id='site-a';"));
const selected=`SELECT receipt_id FROM receipts WHERE site_id='site-a' AND source_system='scenario-driver' AND external_ref LIKE '${id}-%'`;
function broker(replicas) {
  const resource=platform.owned(platform.get('statefulset','rabbitmq','cutover-platform'));
  assert.ok([0,1].includes(resource.spec.replicas));
  platform.kube(['-n','cutover-platform','scale','statefulset/rabbitmq',`--replicas=${replicas}`]);
}
async function resumeBroker() {
  broker(1);
  await until(()=>platform.get('statefulset','rabbitmq','cutover-platform').status.readyReplicas===1,'the preserved Cutover broker is ready',180000);
  brokerHeld=false;
}
function body(index) {
  const classifications=['REUSABLE','NEEDS_CLEANING','DAMAGED'];
  return {sourceSystem:'scenario-driver',externalReceiptRef:`${id}-${index}`,counts:Object.fromEntries(classifications.map((type,n)=>[type,n===index%3?1:0]))};
}
async function submit(index,bearer) {
  const startedAt=new Date().toISOString(),request=body(index);
  const result=await api(`${prefix}/return-receipts`,{method:'POST',key:`${id}-${index}`,body:request,bearer});
  const record={index,startedAt,endedAt:new Date().toISOString(),key:`${id}-${index}`,body:request,status:result.status,receiptId:result.body?.id,code:result.body?.code,retryAfter:result.retryAfter};
  evidence.requests.push(record);return record;
}
async function replayPaused() {
  const rows=JSON.parse(query('returns',`SELECT coalesce(jsonb_agg(jsonb_build_object('eventId',event_id,'version',version,'hash',encode(sha256(envelope::text::bytea),'hex'))),'[]') FROM (SELECT * FROM outbox WHERE published_at IS NULL AND paused AND (lease_until IS NULL OR lease_until<now()) AND envelope->>'correlationId' IN (SELECT receipt_id::text FROM (${selected}) s) ORDER BY event_id LIMIT 100) o;`));
  if(rows.length && !session)session=await humanSession('supervisor-a');
  if(rows.length && !forward)forward=await platform.forward('returns-service');
  for(const row of rows) {
    const response=await fetch(`${forward.origin}/internal/v1/sites/site-a/messaging/outbox/${row.eventId}/replay`,{method:'POST',signal:AbortSignal.timeout(12000),headers:{Authorization:`Bearer ${session.bearer()}`,'Content-Type':'application/json','Idempotency-Key':`${id}-replay-${row.eventId}-${row.version}`},body:JSON.stringify({expectedVersion:row.version,reason:'The original Cutover broker process has recovered; retry the retained original event after its transport retries were exhausted.'})});
    assert.equal(response.status,200,'Audited replay of an original paused delivery must succeed.');
    evidence.replays.push({...row,response:await response.json()});
  }
}
async function drain(expected) {
  await until(async()=>{
    await replayPaused();
    const complete=Number(query('returns',`SELECT count(*) FROM receipts WHERE receipt_id IN (${selected}) AND state='COMPLETED';`));
    const quota=snapshot();evidence.admission.push({at:new Date().toISOString(),phase:'recovery',...quota,completed:complete});
    return complete===expected && quota.active_requests===0 && quota.unpublished_events===0;
  },'every original receipt completes and the durable return outbox drains',900000);
}
try {
  platform.verify();provisionObservers();
  const engine=JSON.parse(call('docker',['info','--format','{{json .}}']));
  evidence.allocation={docker:{cpus:engine.NCPU,memoryBytes:engine.MemTotal,serverVersion:engine.ServerVersion},database:platform.get('statefulset','application-db','cutover-platform').spec.template.spec.containers.map(({name,resources})=>({name,resources})),broker:platform.get('statefulset','rabbitmq','cutover-platform').spec.template.spec.containers.map(({name,resources})=>({name,resources}))};
  evidence.worldBefore=await simulatorRead('/sim/v1/equipment');evidence.countersBefore=counters();
  assert.ok(evidence.worldBefore.lanes.every(lane=>!lane.blocked));
  assert.equal(Number(query('adapter',"SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');")),0);
  assert.equal(Number(query('adapter',"SELECT count(*) FROM migration_sessions WHERE phase NOT IN ('COMPLETED','REVERSED','SUPERSEDED','ABORTED');")),0);
  assert.equal(Number(query('core','SELECT active_requests FROM admission WHERE singleton;')),0);
  for(const owner of ['core','adapter','execution','returns']){
    assert.equal(Number(query(owner,"SELECT count(*) FROM service_control WHERE workers_paused OR intake_paused OR dispatch_paused OR critical_storage OR relay_paused OR consumer_paused;")),0);
    assert.equal(Number(query(owner,'SELECT unpublished_events FROM admission WHERE singleton;')),0);
  }
  evidence.admissionBefore=snapshot();assert.equal(evidence.admissionBefore.active_requests,0);
  evidence.brokerBefore={uid:platform.get('pod','rabbitmq-0','cutover-platform').metadata.uid,replicas:platform.get('statefulset','rabbitmq','cutover-platform').spec.replicas};assert.equal(evidence.brokerBefore.replicas,1);
  brokerHeld=true;broker(0);
  await until(()=>JSON.parse(platform.kube(['-n','cutover-platform','get','pods','-l','app.kubernetes.io/name=rabbitmq','-o','json'])).items.length===0,'the actual Cutover broker process stops',120000);
  evidence.brokerStoppedAt=new Date().toISOString();
  for(let first=0;first<800;first+=16) {
    const bearer=await token();
    const batch=await Promise.all(Array.from({length:Math.min(16,800-first)},(_,offset)=>submit(first+offset,bearer)));
    assert.ok(batch.every(item=>item.status===202),'All work below the real active-request threshold must be accepted.');
    const quota=snapshot();evidence.admission.push({at:new Date().toISOString(),phase:'outage',...quota});
    assert.equal(quota.active_requests,first+batch.length);assert.ok(quota.active_requests<1000 && quota.unpublished_events<10000);
  }
  const bearer=await token();
  for(const index of [800,801]){const refused=await submit(index,bearer);assert.equal(refused.status,503);assert.equal(refused.code,'ADMISSION_CAPACITY');assert.match(refused.retryAfter??'',/^[1-9][0-9]*$/);}
  evidence.admissionAtRefusal=snapshot();assert.equal(evidence.admissionAtRefusal.active_requests,800);assert.equal(evidence.admissionAtRefusal.unpublished_events,1600);
  assert.equal(Number(query('returns',`SELECT count(*) FROM (${selected}) s;`)),800);
  assert.equal(Number(query('returns',`SELECT count(*) FROM sorting_ledger WHERE receipt_id IN (${selected});`)),0);
  await resumeBroker();evidence.brokerResumedAt=new Date().toISOString();
  evidence.brokerAfter={uid:platform.get('pod','rabbitmq-0','cutover-platform').metadata.uid};assert.notEqual(evidence.brokerAfter.uid,evidence.brokerBefore.uid);
  await drain(800);
  for(const index of [800,801])assert.equal((await submit(index,await token())).status,202,'The exact previously refused key/body becomes admissible after recovery.');
  await drain(802);
  const movements=JSON.parse(query('returns',`SELECT jsonb_agg(jsonb_build_object('movementId',movement_id,'receiptId',receipt_id,'classification',classification,'state',state) ORDER BY movement_id) FROM return_movements WHERE receipt_id IN (${selected});`));
  assert.equal(movements.length,802);assert.ok(movements.every(item=>item.state==='COMPLETED'));assert.equal(new Set(movements.map(item=>item.movementId)).size,802);
  for(const [owner,table] of [['returns','sorting_ledger'],['simulator','execution_ledger']]){
    const effects=[];
    for(let n=0;n<movements.length;n+=250){const ids=movements.slice(n,n+250).map(item=>`'${item.movementId}'::uuid`).join(',');effects.push(...JSON.parse(query(owner,`SELECT coalesce(jsonb_agg(jsonb_build_object('movementId',movement_id,'quantity',quantity,'commandId',command_id)),'[]') FROM ${table} WHERE movement_id IN (${ids});`)));}
    assert.equal(effects.length,802);assert.equal(new Set(effects.map(item=>item.movementId)).size,802);assert.ok(effects.every(item=>item.quantity===1));writeJson(resolve(directory,`${owner}-effects.json`),effects);
  }
  evidence.countersAfter=counters();
  for(const before of evidence.countersBefore){const after=evidence.countersAfter.find(item=>item.classification===before.classification),expected=movements.filter(item=>item.classification===before.classification).length;assert.equal(after.received-before.received,expected);assert.equal(after.sorted-before.sorted,expected);}
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');for(const key of ['worldId','journalGeneration'])assert.equal(evidence.worldAfter[key],evidence.worldBefore[key]);assert.equal(evidence.worldAfter.journalHighWater-evidence.worldBefore.journalHighWater,802);
  evidence.cases.push({id:'A15',status:'passed',name:'Actual broker outage reaches the 800-receipt admission threshold, returns 503/Retry-After before the hard limit, and recovers all 802 original requests with single physical/sorting effects',attempted:804,accepted:802,rejected:2,movements:802,scope:'Returns active-request admission quota; the source outbox retained 1,600 unpublished events during the outage.'});
}catch(error){failure=error;evidence.failure=error.message;evidence.cases.push({id:'A15',status:'failed',name:'Actual broker outage and admission-capacity recovery',reason:error.message});}
finally {
  const cleanup=[];
  if(brokerHeld)try{await resumeBroker();}catch(error){cleanup.push(error.message);}
  try{forward?.close();if(session)await session.close();}catch(error){cleanup.push(error.message);}
  if(cleanup.length){evidence.cleanupFailures=cleanup;failure??=new Error('Broker-capacity cleanup requires attention.');}
  evidence.endedAt=new Date().toISOString();
  try{saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A15'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,seed:evidence.seed,allocation:evidence.allocation,reproduce:'node tools/scenario-driver/broker-capacity-smoke.mjs',results:'results.json'});}finally{release();}
}
if(failure)throw failure;
console.log(`${id}: passed A15; 800 retained receipts at admission refusal, 802 single physical/sorting effects after recovery. ${directory}`);
