import assert from 'node:assert/strict';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { root, target, privateDirectory, writeJson, maintenanceLock, until, simulatorRead, call }=await import('../../scripts/lib/local-platform.mjs');
const id=`platform-resilience-${Date.now()}`,directory=resolve(root,'.local/evidence',id),platform=target('demo');
privateDirectory(directory);const release=maintenanceLock(id),prefix='/api/v1/sites/site-a',controlPath='/internal/v1/sites/site-a/test-controls';
const evidence={startedAt:new Date().toISOString(),seed:20260908,cases:[],requests:[],transitions:[]};
const resources=[
  ['deployment','equipment-adapter','cutover-apps'],['statefulset','application-db','cutover-platform'],
  ['deployment','collector','cutover-observability'],['statefulset','tempo','cutover-observability'],
  ['statefulset','prometheus','cutover-observability'],['statefulset','grafana','cutover-observability'],
];
const held=new Map();let dispatchHeld=false,failure,skus;
const safeId=value=>{assert.match(value,/^[a-f0-9-]{36}$/);return value;};
const pods=(namespace,name)=>JSON.parse(platform.kube(['-n',namespace,'get','pods','-l',`app.kubernetes.io/name=${name}`,'-o','json'])).items;
function forward(name){call('pwsh',['-NoProfile','-NonInteractive','-File',resolve(root,'scripts/forward.ps1'),'-Profile','demo','-Target',name,'-Action','Start']);}
function identity(){return Object.fromEntries(['legacy-core','equipment-adapter','execution-service','returns-service','shadow-scheduler'].map(name=>[name,pods('cutover-apps',name).map(p=>({uid:p.metadata.uid,restarts:(p.status.containerStatuses??[]).reduce((n,c)=>n+c.restartCount,0)}))]));}
function unrelated(){return call('docker',['ps','--format','{{.ID}} {{.Names}}']).split('\n').filter(line=>line&&!line.includes('cutover-')).sort();}
function scale(resource,replicas){
  const [kind,name,namespace]=resource,current=platform.owned(platform.get(kind,name,namespace));
  assert.ok([0,1].includes(current.spec.replicas));
  const saved=held.get(name);if(saved)assert.equal(current.metadata.uid,saved.uid,'The controller identity must not change during recovery.');
  if(replicas===0&&!saved){assert.equal(current.spec.replicas,1);held.set(name,{resource,uid:current.metadata.uid});}
  platform.kube(['-n',namespace,'scale',`${kind}/${name}`,`--replicas=${replicas}`]);
  evidence.transitions.push({at:new Date().toISOString(),kind,name,namespace,uid:current.metadata.uid,replicas});
  writeJson(resolve(directory,'operation.json'),{runId:id,held:[...held.values()],dispatchHeld,transitions:evidence.transitions});
}
async function stop(resource){scale(resource,0);await until(()=>pods(resource[2],resource[1]).length===0,`${resource[1]} stops with its volumes preserved`,90000);}
async function start(resource){
  scale(resource,1);
  await until(()=>pods(resource[2],resource[1]).some(p=>(p.status.containerStatuses??[]).length>0&&p.status.containerStatuses.every(c=>c.ready)),`${resource[1]} is ready on preserved storage`,180000);
  held.delete(resource[1]);
}
async function gate(paused){
  forward('adapter-api');const bearer=await token();const before=await api(controlPath,{target:'adapter',bearer});assert.equal(before.status,200);
  const response=await api(controlPath,{target:'adapter',bearer,method:'POST',key:`${id}-gate-${before.body.version}`,body:{expectedVersion:before.body.version,dispatchPaused:paused,reason:paused?'Hold new equipment dispatch while accepted application records undergo a scoped database process restart.':'The same application database and independent physical history have recovered; reopen the preserved dispatch gate.'}});
  assert.equal(response.status,200);dispatchHeld=paused;
}
async function offer(label){
  const bearer=await token();const requests=[
    {resource:'orders',body:{sourceSystem:'scenario-driver',externalOrderRef:`${id}-${label}-order`,storeId:'store-01',priority:5,lines:skus.map(sku=>({sku,quantity:1}))}},
    {resource:'return-receipts',body:{sourceSystem:'scenario-driver',externalReceiptRef:`${id}-${label}-receipt`,counts:{REUSABLE:1,NEEDS_CLEANING:1,DAMAGED:1}}},
  ];
  for(const request of requests){request.key=request.body.externalOrderRef??request.body.externalReceiptRef;const response=await api(`${prefix}/${request.resource}`,{method:'POST',bearer,key:request.key,body:request.body});assert.equal(response.status,202);request.id=safeId(response.body.id);evidence.requests.push(request);}
  return requests;
}
async function complete(requests){
  const bearer=await token();let views;
  await until(async()=>{views=await Promise.all(requests.map(r=>api(`${prefix}/${r.resource}/${r.id}`,{bearer})));return views.every(v=>v.status===200&&v.body.state==='COMPLETED');},'both products complete their retained original movements',120000);
  for(let n=0;n<requests.length;n++){
    const request=requests[n],movements=views[n].body.movements;assert.equal(movements.length,request.resource==='orders'?2:3);
    for(const movement of movements){const movementId=safeId(movement.movementId);for(const [owner,table] of [[request.resource==='orders'?'core':'returns',request.resource==='orders'?'inventory_ledger':'sorting_ledger'],['simulator','execution_ledger']])assert.equal(Number(query(owner,`SELECT count(*) FROM ${table} WHERE movement_id='${movementId}';`)),1);}
    request.movements=movements.map(m=>m.movementId);
  }
}
async function unavailable(origin){try{const response=await fetch(origin,{signal:AbortSignal.timeout(2500)});assert.equal(response.ok,false);return {status:response.status};}catch(error){if(error.name==='AssertionError')throw error;return {transportUnavailable:true};}}
try {
  platform.verify();provisionObservers();forward('console');forward('adapter-api');
  evidence.unrelatedBefore=unrelated();evidence.worldBefore=await simulatorRead('/sim/v1/equipment');
  assert.ok(evidence.worldBefore.lanes.filter(l=>l.siteId==='site-a').every(l=>!l.blocked));
  for(const owner of ['core','adapter','execution','returns','shadow'])assert.equal(query(owner,'SELECT workers_paused OR dispatch_paused OR critical_storage OR intake_paused FROM service_control;'),'f','Start with healthy owner controls.');
  assert.equal(query('adapter',"SELECT count(*) FROM migration_sessions WHERE phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING','REVERSING');"),'0');
  assert.equal(query('adapter',"SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');"),'0');
  for(const owner of ['core','returns'])assert.equal(query(owner,'SELECT active_requests FROM admission;'),'0','Settle accepted work before this isolated outage run.');
  skus=JSON.parse(query('core',"SELECT jsonb_agg(sku ORDER BY temperature_class) FROM (SELECT DISTINCT ON(p.temperature_class) s.sku,p.temperature_class FROM stock s JOIN products p USING(site_id,sku) WHERE s.site_id='site-a' AND s.on_hand-s.reserved>=12 ORDER BY p.temperature_class,s.sku) s;"));assert.equal(skus.length,2);
  evidence.routesBefore=JSON.parse(query('adapter','SELECT jsonb_agg(row_to_json(z) ORDER BY site_id,zone_id) FROM zone_routes z;'));

  await stop(resources[0]);const adapterDown=await offer('adapter-down');
  const currentWorld=await simulatorRead('/sim/v1/equipment');assert.equal(currentWorld.journalHighWater,evidence.worldBefore.journalHighWater);
  for(const request of adapterDown){const view=await api(`${prefix}/${request.resource}/${request.id}`,{bearer:await token()});assert.equal(view.status,200);assert.notEqual(view.body.state,'COMPLETED');}
  await start(resources[0]);await complete(adapterDown);
  evidence.cases.push({id:'A24',status:'passed',name:'Both owners durably accept work while the adapter process is absent; its return produces five original single effects without an equipment bypass.'});

  await gate(true);const databaseRestart=await offer('database-restart');
  const dbBefore=platform.owned(platform.get('pod','application-db-0','cutover-platform'));
  const claimsBefore=JSON.parse(platform.kube(['-n','cutover-platform','get','pvc','-o','json'])).items.map(p=>({name:p.metadata.name,uid:p.metadata.uid,volume:p.spec.volumeName}));
  const physicalHeld=await simulatorRead('/sim/v1/equipment'),cachedBearer=await token();
  await stop(resources[1]);
  let refused;try{refused=await api(`${prefix}/orders`,{method:'POST',bearer:cachedBearer,key:`${id}-database-unavailable`,body:{...databaseRestart[0].body,externalOrderRef:`${id}-database-unavailable`}});evidence.databaseOutageResponse={status:refused.status,code:refused.body?.code};assert.ok(refused.status>=500,`An unavailable database must refuse intake: status ${refused.status}, code ${refused.body?.code}.`);}catch(error){if(error.name==='AssertionError')throw error;refused={transportUnavailable:true};}
  assert.equal((await simulatorRead('/sim/v1/equipment')).journalHighWater,physicalHeld.journalHighWater);
  await start(resources[1]);
  assert.notEqual(platform.get('pod','application-db-0','cutover-platform').metadata.uid,dbBefore.metadata.uid);
  assert.deepEqual(JSON.parse(platform.kube(['-n','cutover-platform','get','pvc','-o','json'])).items.map(p=>({name:p.metadata.name,uid:p.metadata.uid,volume:p.spec.volumeName})),claimsBefore);
  assert.equal(query('core',`SELECT count(*) FROM orders WHERE external_ref='${id}-database-unavailable';`),'0');
  for(const request of databaseRestart){const replay=await api(`${prefix}/${request.resource}`,{method:'POST',bearer:await token(),key:request.key,body:request.body});assert.equal(replay.status,202);assert.equal(replay.body.id,request.id);}
  await gate(false);await complete(databaseRestart);
  evidence.cases.push({id:'A45',status:'passed',name:'Application PostgreSQL independently restarts on the same PVCs; recorded responses and five accepted effects survive. Unavailable intake creates no order.',databasePodBefore:dbBefore.metadata.uid,databasePodAfter:platform.get('pod','application-db-0','cutover-platform').metadata.uid,refusedStatus:refused.status??'transport unavailable',scope:'Database process replacement; separate application and broker evidence completes A45.'});

  const beforeObservations=identity();evidence.telemetryBounds=[];
  for(const name of ['legacy-core','equipment-adapter','execution-service','returns-service','shadow-scheduler']){
    const deployment=platform.owned(platform.get('deployment',name,'cutover-apps')),container=deployment.spec.template.spec.containers[0];
    const env=Object.fromEntries(container.env.filter(e=>e.name.startsWith('OTEL_')).map(e=>[e.name,e.value]));
    assert.equal(env.OTEL_BSP_MAX_QUEUE_SIZE,'512');assert.equal(env.OTEL_BSP_MAX_EXPORT_BATCH_SIZE,'128');assert.equal(env.OTEL_EXPORTER_OTLP_TIMEOUT,'2000');assert.ok(container.resources.limits.memory);
    evidence.telemetryBounds.push({name,queueSpans:512,batchSpans:128,exportTimeoutMillis:2000,memoryLimit:container.resources.limits.memory});
  }
  for(const resource of resources.slice(2))await stop(resource);
  evidence.missingObservations=await Promise.all(['http://localhost:8781/-/ready','http://localhost:8782/ready','http://localhost:8783/api/health'].map(unavailable));
  for(let n=0;n<5;n++)await complete(await offer(`monitoring-down-${n}`));
  assert.deepEqual(identity(),beforeObservations,'Observation loss must not restart the business applications.');
  for(const resource of resources.slice(2))await start(resource);
  for(const name of ['prometheus','tempo','grafana'])forward(name);
  await until(async()=>{const response=await fetch('http://localhost:8781/api/v1/targets',{signal:AbortSignal.timeout(5000)});if(!response.ok)return false;const data=await response.json();return data.data.activeTargets.length>=10&&data.data.activeTargets.every(t=>t.health==='up');},'all expected local scrape targets recover',90000);
  evidence.cases.push({id:'A25',status:'passed',name:'All four monitoring processes are absent while both products commit 25 single movements; business pods survive and scrapes recover.',bounds:'The deployed agents have finite 512-span queues, 128-span batches, two-second exporter timeouts and memory limits. This check does not claim direct queue-occupancy measurement.'});
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');assert.equal(evidence.worldAfter.worldId,evidence.worldBefore.worldId);assert.equal(evidence.worldAfter.journalGeneration,evidence.worldBefore.journalGeneration);assert.equal(evidence.worldAfter.journalHighWater-evidence.worldBefore.journalHighWater,35);
  assert.deepEqual(JSON.parse(query('adapter','SELECT jsonb_agg(row_to_json(z) ORDER BY site_id,zone_id) FROM zone_routes z;')),evidence.routesBefore);
  assert.deepEqual(unrelated(),evidence.unrelatedBefore);
}catch(error){failure=error;evidence.failure=error.message;}
finally {
  const cleanupErrors=[];
  for(const {resource} of [...held.values()].reverse())try{await start(resource);}catch(error){cleanupErrors.push(`${resource[1]}: ${error.message}`);}
  if(dispatchHeld)try{await gate(false);}catch(error){cleanupErrors.push(`dispatch: ${error.message}`);}
  try{forward('console');forward('adapter-api');for(const name of ['prometheus','tempo','grafana'])forward(name);}catch(error){cleanupErrors.push(`forwards: ${error.message}`);}
  if(cleanupErrors.length){evidence.cleanupErrors=cleanupErrors;failure??=new Error('A scoped recovery step requires inspection.');}
  evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A24','A25','A45'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:'node tools/scenario-driver/platform-resilience-smoke.mjs',resources});release();
}
if(failure)throw failure;
console.log(`${id}: passed ${evidence.cases.length} scoped resilience checks. ${directory}`);
