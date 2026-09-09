import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { resolve } from 'node:path';
import { readFileSync, writeFileSync, openSync, closeSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import {tracingProfile} from './tracing-profile.mjs';
import {durableDispatchTiming} from './durable-dispatch-timing.mjs';
import {traceReadback} from './trace-readback.mjs';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { root, target, until, simulatorRead, privateDirectory, writeJson, maintenanceLock, call }=await import('../../scripts/lib/local-platform.mjs');
const argumentsList=process.argv.slice(2);
assert.ok(argumentsList.length<=1 && argumentsList.every(value=>/^--diagnostic=(30|60|120)$/.test(value)), 'Usage: healthy-load.mjs [--diagnostic=30|60|120]');
const diagnostic=argumentsList.length!==0,warmupSeconds=diagnostic?10:60,measurementSeconds=diagnostic?Number(argumentsList[0].split('=')[1]):600;
const totalSeconds=warmupSeconds+measurementSeconds,orderCount=totalSeconds*2,receiptCount=totalSeconds,movementCount=totalSeconds*5;
const id=`load-${diagnostic?'diagnostic-':''}${Date.now()}`,directory=resolve(root,'.local/evidence',id),seed=20260908;
privateDirectory(directory);
const unlock=maintenanceLock(id),prefix='/api/v1/sites/site-a',requests=[],cases=[];
const evidence={startedAt:new Date().toISOString(),seed,cases,requests,qualification:diagnostic?'Bounded diagnostic only; does not establish A50.':'Full A50 candidate',workload:{warmupSeconds,measurementSeconds,ordersPerSecond:2,reservedLinesPerOrder:2,receiptsPerSecond:1,receiptMovements:1,cratesPerReceipt:2,classificationMix:'One nonzero classification per receipt, rotating REUSABLE / NEEDS_CLEANING / DAMAGED',concurrencyLimit:32,externalAssets:'none'}};
let sampler,stoppedSampler,samplerLog,bearer,refreshAt=0;
const allOffers=[];
const percentile=(values,q)=>{const sorted=[...values].sort((a,b)=>a-b);return sorted.length?sorted[Math.ceil(sorted.length*q)-1]:null;};
const sqlIds=ids=>ids.map(value=>{assert.match(value,/^[a-f0-9-]{36}$/);return `'${value}'`;}).join(',');
function walSnapshot() {
  return JSON.parse(target('demo').kube(['-n','cutover-platform','exec','application-db-0','-c','application-db','--','psql','-X','-U','postgres','-d','postgres','-Atc',
    "SELECT jsonb_build_object('at',clock_timestamp(),'timingEnabled',current_setting('track_wal_io_timing'),'fsyncEnabled',current_setting('fsync'),'synchronousCommit',current_setting('synchronous_commit'),'walSyncMethod',current_setting('wal_sync_method'),'commitDelayMicros',current_setting('commit_delay'),'commitSiblings',current_setting('commit_siblings'),'io',(SELECT row_to_json(s) FROM pg_stat_io s WHERE object='wal' AND context='normal' AND backend_type='client backend'),'wal',(SELECT row_to_json(s) FROM pg_stat_wal s));"]));
}
function batchQuery(owner,ids,makeSql) {
  const result=[];
  for(let n=0;n<ids.length;n+=300)result.push(...JSON.parse(query(owner,makeSql(sqlIds(ids.slice(n,n+300)))))??[]);
  return result;
}
async function stopSampler() {
  try {
    // A suspended host may disconnect the child between this check and send.
    // Consume the send error and use the child's exit result as the authority.
    if(sampler?.connected)sampler.send('stop',()=>{});
    if(stoppedSampler)await stoppedSampler;
  } finally {
    sampler=undefined;
    if(samplerLog!==undefined){closeSync(samplerLog);samplerLog=undefined;}
  }
}
async function offer(kind,index,started,plannedMillis,phase,body) {
  const key=`${id}-${kind}-${index}`,at=Date.now();
  const record={kind,index,phase,key,scheduledAt:new Date(started+plannedMillis).toISOString(),startedAt:new Date(at).toISOString(),latenessMillis:at-started-plannedMillis};
  try{
    const result=await api(`${prefix}/${kind==='order'?'orders':'return-receipts'}`,{bearer,method:'POST',key,body});
    Object.assign(record,{status:result.status,id:result.body?.id,code:result.body?.code,latencyMillis:Date.now()-at});
  }catch(error){Object.assign(record,{status:0,code:'TRANSPORT_FAILURE',latencyMillis:Date.now()-at});}
  requests.push(record);
}
try {
  target('demo').verify();provisionObservers();
  const engine=JSON.parse(call('docker',['info','--format','{{json .}}']));
  evidence.allocation={docker:{cpus:engine.NCPU,memoryBytes:engine.MemTotal,serverVersion:engine.ServerVersion,kernelVersion:engine.KernelVersion},pods:[]};
  for(const namespace of ['cutover-apps','cutover-platform','cutover-observability'])for(const pod of JSON.parse(target('demo').kube(['-n',namespace,'get','pods','-l','app.kubernetes.io/part-of=cutover','-o','json'])).items)evidence.allocation.pods.push({namespace,name:pod.metadata.name,containers:pod.spec.containers.map(({name,resources})=>({name,...resources}))});
  assert.equal(query('adapter',"SELECT count(*) FROM migration_sessions WHERE phase NOT IN ('COMPLETED','REVERSED','SUPERSEDED','ABORTED');"),'0','Healthy load requires settled migration sessions.');
  const routes=JSON.parse(query('adapter',"SELECT jsonb_agg(jsonb_build_object('site',site_id,'zone',zone_id,'owner',owner,'epoch',epoch,'state',state)) FROM zone_routes WHERE site_id='site-a';"));
  assert.ok(routes.every(route=>route.state==='ACTIVE'));assert.ok(routes.filter(route=>route.zone!=='returns').every(route=>route.owner==='execution-service'));
  evidence.routesBefore=routes;evidence.worldBefore=await simulatorRead('/sim/v1/equipment');
  assert.ok(evidence.worldBefore.lanes.every(lane=>!lane.blocked));
  evidence.faultsBefore=await simulatorRead('/sim/v1/test-controls/faults');assert.ok(Array.isArray(evidence.faultsBefore));assert.ok(evidence.faultsBefore.every(fault=>fault.remaining===0));
  assert.equal(query('core',"SELECT count(*) FROM orders WHERE state NOT IN ('COMPLETED','COMPLETED_WITH_SHORTAGE','SHORTAGE','CANCELLED');"),'0','Resolve earlier accepted outbound work before the healthy measurement.');
  assert.equal(query('returns',"SELECT count(*) FROM receipts WHERE state<>'COMPLETED';"),'0','Resolve earlier accepted returns before the healthy measurement.');
  assert.equal(query('adapter',"SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');"),'0','Do not mix the healthy denominator with preexisting unresolved commands.');
  for(const owner of ['core','adapter','execution','returns','shadow'])assert.equal(query(owner,"SELECT count(*) FROM service_control WHERE intake_paused OR dispatch_paused OR workers_paused OR relay_paused OR consumer_paused OR critical_storage;"),'0');
  const stock=JSON.parse(query('core',"SELECT jsonb_agg(jsonb_build_object('sku',s.sku,'zone',p.temperature_class,'available',s.on_hand-s.reserved) ORDER BY s.sku) FROM stock s JOIN products p USING(site_id,sku) WHERE s.site_id='site-a';"));
  const pools=Object.fromEntries(['ambient','chilled'].map(zone=>[zone,stock.filter(item=>item.zone===zone && item.available>0)]));
  for(const zone of ['ambient','chilled'])assert.ok(pools[zone].reduce((sum,item)=>sum+item.available,0)>=orderCount,'This run needs enough existing compatible stock; it never replenishes or resets it.');
  evidence.workload.stockSelection='Seeded round-robin across all compatible existing positive stock; skip exhausted items without any stock writes.';
  const remaining=new Map(stock.map(item=>[item.sku,item.available])),positions=Object.fromEntries(['ambient','chilled'].map(zone=>[zone,seed%pools[zone].length]));
  const nextSku=zone=>{for(let n=0;n<pools[zone].length;n++){const item=pools[zone][positions[zone]++%pools[zone].length];if(remaining.get(item.sku)>0){remaining.set(item.sku,remaining.get(item.sku)-1);return item.sku;}}throw new Error('Existing compatible stock was exhausted during workload preparation.');};
  const orderBodies=Array.from({length:orderCount},(_,index)=>({sourceSystem:'scenario-driver',externalOrderRef:`${id}-order-${index}`,storeId:`store-${String(index%10+1).padStart(2,'0')}`,priority:5,lines:['ambient','chilled'].map(zone=>({sku:nextSku(zone),quantity:1}))}));
  const needed={};for(const body of orderBodies)for(const line of body.lines)needed[line.sku]=(needed[line.sku]??0)+1;
  for(const [sku,count] of Object.entries(needed))assert.ok(stock.find(item=>item.sku===sku).available>=count);
  evidence.stockBefore=stock;evidence.stockRequired=needed;
  samplerLog=openSync(resolve(directory,'sampler.log'),'w',0o600);
  sampler=fork(resolve(root,'tools/scenario-driver/load-sampler.mjs'),[id],{cwd:root,windowsHide:true,stdio:['ignore',samplerLog,samplerLog,'ipc']});
  stoppedSampler=new Promise((done,failed)=>{sampler.once('exit',code=>code===0?done():failed(new Error('Resource sampler failed; inspect this run’s sampler.log.')));sampler.once('error',failed);});
  stoppedSampler.catch(()=>{});
  await new Promise((done,failed)=>{const timer=setTimeout(()=>failed(new Error('Sampler startup timed out.')),30000);sampler.once('message',message=>{clearTimeout(timer);assert.equal(message.type,'ready');done();});sampler.once('exit',()=>{clearTimeout(timer);failed(new Error('Sampler exited before readiness.'));});});
  bearer=await token();refreshAt=Date.now()+60000;
  evidence.wal={before:walSnapshot()};
  evidence.tracing={before:tracingProfile()};
  const active=new Set(),started=Date.now();evidence.offeringStartedAt=new Date(started).toISOString();evidence.measurementStartedAt=new Date(started+warmupSeconds*1000).toISOString();
  const launch=promise=>{active.add(promise);allOffers.push(promise);promise.finally(()=>active.delete(promise));};
  for(let tick=0;tick<orderCount;tick++) {
    await delay(Math.max(0,started+tick*500-Date.now()));
    const latenessMillis=Date.now()-started-tick*500;
    if(latenessMillis>=2000)evidence.timingFailure={tick,at:new Date().toISOString(),scheduledAt:new Date(started+tick*500).toISOString(),latenessMillis};
    assert.ok(latenessMillis<2000,'The load driver cannot sustain the declared open-loop arrival schedule.');
    assert.ok(active.size<32,'The bounded HTTP offer concurrency is exhausted.');
    if(Date.now()>=refreshAt){bearer=await token();refreshAt=Date.now()+60000;}
    const phase=tick<warmupSeconds*2?'warmup':'measurement';launch(offer('order',tick,started,tick*500,phase,orderBodies[tick]));
    if(tick%2===0){const receipt=tick/2,counts={REUSABLE:0,NEEDS_CLEANING:0,DAMAGED:0};counts[Object.keys(counts)[receipt%3]]=2;
      launch(offer('receipt',receipt,started,tick*500,phase,{sourceSystem:'scenario-driver',externalReceiptRef:`${id}-receipt-${receipt}`,counts}));}
    if(tick%120===0){writeJson(resolve(directory,'progress.json'),{at:new Date().toISOString(),offered:tick+1,completedHttp:requests.length,seconds:Math.floor((Date.now()-started)/1000)});console.log(`${id}: ${phase}, ${Math.floor((Date.now()-started)/1000)} s, ${requests.length} HTTP results`);}
  }
  await delay(Math.max(0,started+totalSeconds*1000-Date.now()));await Promise.all(active);
  evidence.offeringEndedAt=new Date().toISOString();writeJson(resolve(directory,'requests.json'),requests);
  evidence.wal.afterOffering=walSnapshot();
  evidence.tracing.afterOffering=tracingProfile();assert.deepEqual(evidence.tracing.afterOffering,evidence.tracing.before,'Owner pods and declared tracing settings must remain unchanged throughout offering.');
  const walFirst=evidence.wal.before,walLast=evidence.wal.afterOffering;
  assert.ok([walFirst,walLast].every(s=>s.timingEnabled==='on'&&s.fsyncEnabled==='on'&&s.synchronousCommit==='on'));
  for(const setting of ['walSyncMethod','commitDelayMicros','commitSiblings'])assert.equal(walFirst[setting],walLast[setting],`Database ${setting} must remain unchanged throughout the measured offering.`);
  assert.equal(walFirst.io.stats_reset,walLast.io.stats_reset);assert.equal(walFirst.wal.stats_reset,walLast.wal.stats_reset);
  const walSeconds=(Date.parse(walLast.at)-Date.parse(walFirst.at))/1000;
  evidence.wal.delta={seconds:walSeconds,writes:walLast.io.writes-walFirst.io.writes,writeMillis:walLast.io.write_time-walFirst.io.write_time,writesPerSecond:(walLast.io.writes-walFirst.io.writes)/walSeconds,fsyncs:walLast.io.fsyncs-walFirst.io.fsyncs,fsyncMillis:walLast.io.fsync_time-walFirst.io.fsync_time,walBytes:Number(walLast.wal.wal_bytes)-Number(walFirst.wal.wal_bytes),fsyncsPerSecond:(walLast.io.fsyncs-walFirst.io.fsyncs)/walSeconds};
  assert.equal(requests.length,orderCount+receiptCount);assert.ok(requests.every(item=>item.status===202),'Every offered healthy request must be durably accepted.');
  await until(()=>Number(query('core',`SELECT count(*) FROM orders WHERE source_system='scenario-driver' AND external_ref LIKE '${id}-order-%' AND state='COMPLETED';`))===orderCount && Number(query('returns',`SELECT count(*) FROM receipts WHERE source_system='scenario-driver' AND external_ref LIKE '${id}-receipt-%' AND state='COMPLETED';`))===receiptCount,'all warmup and measured work completes',180000);
  await until(()=>['core','adapter','execution','returns'].every(owner=>query(owner,"SELECT unpublished_events=0 AND NOT EXISTS(SELECT FROM inbox WHERE state IN ('RECEIVED','PENDING')) FROM admission;")==='t'),'critical delivery backlogs drain after offered load',90000);
  await stopSampler();
  const orderMap=new Map(requests.filter(item=>item.kind==='order').map(item=>[item.id,item])),receiptMap=new Map(requests.filter(item=>item.kind==='receipt').map(item=>[item.id,item]));
  const outbound=batchQuery('core',[...orderMap.keys()],ids=>`SELECT coalesce(jsonb_agg(jsonb_build_object('movementId',movement_id,'requestId',order_id,'state',state)),'[]') FROM movement_intents WHERE order_id IN (${ids});`);
  const returned=batchQuery('returns',[...receiptMap.keys()],ids=>`SELECT coalesce(jsonb_agg(jsonb_build_object('movementId',movement_id,'requestId',receipt_id,'state',state)),'[]') FROM return_movements WHERE receipt_id IN (${ids});`);
  assert.equal(outbound.length,orderCount*2);assert.equal(returned.length,receiptCount);
  const movementMap=new Map([...outbound,...returned].map(item=>[item.movementId,{...item,phase:(orderMap.get(item.requestId)??receiptMap.get(item.requestId)).phase}]));
  const movements=[...movementMap.keys()];assert.equal(movements.length,movementCount);
  const adapter=batchQuery('adapter',movements,ids=>`SELECT coalesce(jsonb_agg(jsonb_build_object('movementId',a.movement_id,'zone',a.zone_id,'owner',a.owner,'epoch',a.epoch,'assignedAt',a.assigned_at,'eligibleAt',a.movement->>'eligibleAt','recordedAt',c.created_at,'completedAt',c.completed_at,'state',c.state,'attempts',c.attempts,'eligibleToDispatchMillis',extract(epoch FROM c.created_at-(a.movement->>'eligibleAt')::timestamptz)*1000,'assignmentToDispatchMillis',extract(epoch FROM c.created_at-GREATEST(a.assigned_at,(a.movement->>'eligibleAt')::timestamptz))*1000)),'[]') FROM movement_allocations a JOIN command_journal c USING(site_id,movement_id) WHERE a.movement_id IN (${ids});`);
  assert.equal(adapter.length,movementCount);assert.ok(adapter.every(item=>item.state==='COMPLETED'));
  const execution=batchQuery('execution',outbound.map(item=>item.movementId),ids=>`SELECT coalesce(jsonb_agg(jsonb_build_object('movementId',movement_id,'taskId',task_id,'eligibleAt',eligible_at,'createdAt',created_at,'state',state)),'[]') FROM execution_tasks WHERE movement_id IN (${ids});`);
  const returnTasks=batchQuery('returns',returned.map(item=>item.movementId),ids=>`SELECT coalesce(jsonb_agg(jsonb_build_object('movementId',movement_id,'taskId',task_id,'createdAt',created_at,'state',state)),'[]') FROM return_tasks WHERE movement_id IN (${ids});`);
  assert.equal(execution.length,orderCount*2);assert.equal(returnTasks.length,receiptCount);assert.ok([...execution,...returnTasks].every(item=>item.state==='COMPLETED'));
  const taskMap=new Map([...execution,...returnTasks].map(item=>[item.movementId,item]));
  for(const row of adapter){Object.assign(row,{phase:movementMap.get(row.movementId).phase,task:taskMap.get(row.movementId)});row.taskEligibilityToDispatchMillis=Date.parse(row.recordedAt)-Math.max(Date.parse(row.eligibleAt),Date.parse(row.task.createdAt));assert.ok(row.taskEligibilityToDispatchMillis>=0);}
  const outboundIds=new Set(outbound.map(item=>item.movementId));
  for(const [owner,table,selected] of [['core','inventory_ledger',outbound.map(item=>item.movementId)],['returns','sorting_ledger',returned.map(item=>item.movementId)],['simulator','execution_ledger',movements]]){
    const effects=batchQuery(owner,selected,ids=>`SELECT coalesce(jsonb_agg(jsonb_build_object('movementId',movement_id,'quantity',quantity,'completedAt',${owner==='core'?'consumed_at':'completed_at'})),'[]') FROM ${table} WHERE movement_id IN (${ids});`);
    assert.equal(effects.length,selected.length);assert.equal(new Set(effects.map(item=>item.movementId)).size,selected.length);
    assert.ok(effects.every(item=>selected.includes(item.movementId)&&item.quantity===(outboundIds.has(item.movementId)?1:2)), 'Physical and business quantities must match every original requested movement.');
    writeJson(resolve(directory,`${owner}-effects.json`),effects);
  }
  assert.equal(query('core',"SELECT count(*) FROM stock WHERE reserved<0 OR on_hand<reserved;"),'0');
  assert.equal(query('core',`SELECT count(*) FROM reservations r JOIN orders o USING(order_id) WHERE o.external_ref LIKE '${id}-order-%' AND r.state<>'CONSUMED';`),'0');
  evidence.stockAfter=JSON.parse(query('core',"SELECT jsonb_agg(jsonb_build_object('sku',s.sku,'available',s.on_hand-s.reserved) ORDER BY s.sku) FROM stock s WHERE s.site_id='site-a';"));
  for(const before of stock)assert.equal(evidence.stockAfter.find(item=>item.sku===before.sku).available,before.available-(needed[before.sku]??0),'Every stock decrement must equal its requested and consumed quantity.');
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');assert.equal(evidence.worldAfter.worldId,evidence.worldBefore.worldId);assert.equal(evidence.worldAfter.journalGeneration,evidence.worldBefore.journalGeneration);
  const measured=adapter.filter(item=>item.phase==='measurement'),measuredHttp=requests.filter(item=>item.phase==='measurement');assert.equal(measured.length,measurementSeconds*5);
  evidence.latency={eligibleMovements:measured.length,excludedBlocked:0,excludedDraining:0,excludedUnknown:0,taskEligibilityToDispatchP99Millis:percentile(measured.map(item=>item.taskEligibilityToDispatchMillis),.99),intentEligibilityToDispatchP99Millis:percentile(measured.map(item=>item.eligibleToDispatchMillis),.99),taskWithinTwoSecondsPercent:100*measured.filter(item=>item.taskEligibilityToDispatchMillis<=2000).length/measured.length,withinTwoSecondsPercent:100*measured.filter(item=>item.eligibleToDispatchMillis<=2000).length/measured.length,acceptanceP99Millis:percentile(measuredHttp.map(item=>item.latencyMillis),.99),driverLatenessP99Millis:percentile(measuredHttp.map(item=>item.latenessMillis),.99),movementCompletionP99Millis:percentile(measured.map(item=>Date.parse(item.completedAt)-Date.parse(item.eligibleAt)),.99)};
  evidence.throughput={measurementSeconds,attemptedOrders:measurementSeconds*2,acceptedOrders:measuredHttp.filter(item=>item.kind==='order'&&item.status===202).length,attemptedReceipts:measurementSeconds,acceptedReceipts:measuredHttp.filter(item=>item.kind==='receipt'&&item.status===202).length,completedMeasuredMovements:measured.length,eventualCompletedMovementsPerOfferedSecond:measured.length/measurementSeconds,completedWithinWindow:measured.filter(item=>Date.parse(item.completedAt)<=started+totalSeconds*1000).length};
  evidence.resources=JSON.parse(readFileSync(resolve(directory,'resources.json'),'utf8'));assert.equal(evidence.resources.errors.length,0);assert.ok(evidence.resources.records.length>=Math.floor(measurementSeconds/10));
  writeJson(resolve(directory,'movements.json'),adapter);
  const contexts=batchQuery('adapter',movements,ids=>`SELECT coalesce(jsonb_agg(jsonb_build_object('movementId',aggregate_id,'traceparent',envelope->>'traceparent')),'[]') FROM outbox WHERE event_type='MovementAssigned.v1' AND aggregate_id IN (${ids});`);
  const readback=traceReadback();let timing;
  try{timing=await durableDispatchTiming(adapter,contexts,readback.read);}
  finally{writeJson(resolve(directory,'trace-readback.json'),{scope:'Post-offering retrieval only: at most 60 seconds per trace and five minutes overall, with ten-second HTTP limits. Original span times remain the measurement endpoints.',observations:readback.observations});}
  writeJson(resolve(directory,'durable-dispatch-timing.json'),timing);
  evidence.durableDispatchTiming={...timing,rows:undefined};
  evidence.latency.intentEligibilityToJournalTimestampP99Millis=evidence.latency.intentEligibilityToDispatchP99Millis;
  evidence.latency.journalTimestampWithinTwoSecondsPercent=evidence.latency.withinTwoSecondsPercent;
  evidence.latency.intentEligibilityToDispatchP99Millis=timing.p99Millis;
  evidence.latency.withinTwoSecondsPercent=timing.withinTwoSecondsPercent;
  evidence.latencyDenominator=`All ${measured.length} measured movements, from original immutable movement eligibleAt to a successful adapter command-journal span ending after transaction return. End times round up and eligibility rounds down to milliseconds. Assignment/publication delay and durable commit are included. Journal-row timestamps and the later task-created stage remain separate diagnostic metrics. Missing timing proof cannot qualify a pass; no healthy work is excluded for transient waiting.`;
  cases.push({...(diagnostic?{}:{id:'A50'}),status:evidence.latency.withinTwoSecondsPercent>=99?'passed':'failed',name:diagnostic?'Bounded mixed-product diagnostic; not an A50 qualification':'Ten-minute mixed-product healthy workload after sixty-second warmup',latency:evidence.latency,throughput:evidence.throughput});
  evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);
  const saved=JSON.parse(readFileSync(resolve(directory,'results.json'),'utf8'));
  writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:diagnostic?[]:['A50'],revision:saved.revision,dirty:saved.dirty,profile:saved.profile,images:saved.images,nodeImages:saved.nodeImages,runtime:saved.runtime,allocation:evidence.allocation,seed,worldId:evidence.worldBefore.worldId,journalGeneration:evidence.worldBefore.journalGeneration,startedAt:evidence.startedAt,endedAt:evidence.endedAt,workload:evidence.workload,reproduce:`node tools/scenario-driver/healthy-load.mjs${diagnostic?' --diagnostic='+measurementSeconds:''}`,artifacts:['requests.json','movements.json','durable-dispatch-timing.json','trace-readback.json','resources.json','core-effects.json','returns-effects.json','simulator-effects.json','results.json','report.md']});
  writeFileSync(resolve(directory,'report.md'),`# ${id}\n\n${diagnostic?'Diagnostic (not A50)':'A50'}: ${cases[0].status}. ${evidence.latency.withinTwoSecondsPercent.toFixed(3)}% within 2 s; original eligibility dispatch p99 ${evidence.latency.intentEligibilityToDispatchP99Millis} ms. All ${movementCount} warmup/measured movements have single physical and business effects with matched quantities. See manifest/results and the declared denominator.\n`);
  console.log(`${diagnostic?'Diagnostic':'A50'} ${cases[0].status}: ${evidence.latency.withinTwoSecondsPercent.toFixed(3)}% / p99 ${evidence.latency.intentEligibilityToDispatchP99Millis} ms. WAL ${evidence.wal.delta.fsyncsPerSecond.toFixed(3)} fsync/s. ${directory}`);
  assert.ok(diagnostic||evidence.latency.withinTwoSecondsPercent>=99,'The declared dispatch target did not pass. Preserve this run and correct the measured cause.');
}catch(error){evidence.failure=error.message;saveEvidence(id,evidence);throw error;}
finally{
  try{
    await Promise.allSettled(allOffers);writeJson(resolve(directory,'requests.json'),requests);
    try{await stopSampler();}catch(error){evidence.cleanupFailure=error.message;saveEvidence(id,evidence);if(!evidence.failure)throw error;}
  }finally{unlock();}
}
