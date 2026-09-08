import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { openSync, closeSync, readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, simulator, provisionObservers, saveEvidence, credentials }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, target, call, privateDirectory, writeJson, simulatorRead, until }=await import('../../scripts/lib/local-platform.mjs');
const runId=`offline-${Date.now()}`,directory=resolve(root,'.local/evidence',runId),prefix='/api/v1/sites/site-a';
privateDirectory(directory);
const evidence={startedAt:new Date().toISOString(),cases:[],browserRequests:[],children:[],seed:20260908};
let enabled=false,gateHeld=false,fault,session;
async function run(name,script,args=[]) {
  const log=openSync(resolve(directory,`${name}.log`),'w',0o600),startedAt=new Date().toISOString();
  try { await new Promise((done,failed)=>{const child=spawn(process.execPath,[resolve(root,script),...args],{cwd:root,windowsHide:true,stdio:['ignore',log,log]});child.once('error',failed);child.once('exit',code=>code===0?done():failed(new Error(`${name} failed (${code}); inspect the retained local log.`)));}); }
  finally { closeSync(log);evidence.children.push({name,script,args,startedAt,endedAt:new Date().toISOString()}); }
}
async function gate(paused) {
  const bearer=await token(),path='/internal/v1/sites/site-a/test-controls';
  const state=await api(path,{target:'adapter',bearer});assert.equal(state.status,200);
  const response=await api(path,{target:'adapter',bearer,method:'POST',key:`${runId}-gate-${state.body.version}`,body:{expectedVersion:state.body.version,dispatchPaused:paused,reason:paused?'Select one original command for the prepared offline lost-response check.':'Resume dispatch after recording the exact offline fault selection.'}});
  assert.equal(response.status,200);gateHeld=paused;
}
const routes=()=>JSON.parse(query('adapter',"SELECT jsonb_agg(jsonb_build_object('site',site_id,'zone',zone_id,'owner',owner,'epoch',epoch,'state',state,'version',version) ORDER BY site_id,zone_id) FROM zone_routes;"));
async function waitOrder(id) {
  let order;await until(async()=>{const result=await api(`${prefix}/orders/${id}`,{bearer:await token()});assert.equal(result.status,200);order=result.body;return order.state==='COMPLETED';},'offline order completes with retained physical evidence',120000);return order;
}
async function waitReceipt(id) {
  let receipt;await until(async()=>{const result=await api(`${prefix}/return-receipts/${id}`,{bearer:await token()});assert.equal(result.status,200);receipt=result.body;return receipt.state==='COMPLETED';},'offline crate receipt completes',120000);return receipt;
}
try {
  target('demo').verify();provisionObservers();
  await run('cache-check','scripts/cache.mjs',['Check']);
  const cache=JSON.parse(readFileSync(resolve(root,'.local/offline/cache-index.json'),'utf8'));assert.ok(cache.rollback);evidence.rollback=cache.rollback;
  assert.equal(query('adapter',"SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');"),'0');
  assert.equal(query('adapter',"SELECT count(*) FROM service_control WHERE dispatch_paused OR workers_paused OR critical_storage OR restoration_required;"),'0');
  evidence.worldBefore=await simulatorRead('/sim/v1/equipment');evidence.routesBefore=routes();
  assert.ok(evidence.worldBefore.lanes.every(lane=>!lane.blocked));assert.ok(evidence.routesBefore.every(route=>route.state==='ACTIVE'));
  call('pwsh',['-NoProfile','-NonInteractive','-File',resolve(root,'scripts/forward.ps1'),'-Target','adapter-api','-Action','Start']);
  const denialPath=resolve(root,'.local/offline/egress.json');
  if(existsSync(denialPath))assert.equal(JSON.parse(readFileSync(denialPath,'utf8')).state,'DISABLED','Resolve any earlier denial journal before this walkthrough.');
  // Enable journals before changing rules, so finally can also clean up a partial installation.
  enabled=true;await run('egress-enable','scripts/offline-egress.mjs',['Enable']);
  await run('egress-probe-before','scripts/offline-egress.mjs',['Probe']);
  session=await humanSession('operator-a',{offline:true,onRequest:request=>evidence.browserRequests.push(request)});
  await session.page.evaluate(async()=>{try{await fetch('https://1.1.1.1/cutover-offline-probe');return false;}catch{return true;}}).then(blocked=>assert.equal(blocked,true));
  evidence.cases.push({status:'passed',name:'Local PKCE login under bridge denial and isolated browser external-request denial'});
  await gate(true);
  const bearer=await token(),reference=`${runId}-outbound`;
  const accepted=await api(`${prefix}/orders`,{bearer,method:'POST',key:reference,body:{sourceSystem:'scenario-driver',externalOrderRef:reference,storeId:'store-08',priority:5,lines:[{sku:'SKU-093',quantity:1},{sku:'SKU-094',quantity:1}]}});
  assert.equal(accepted.status,202);
  const initial=(await api(`${prefix}/orders/${accepted.body.id}`,{bearer})).body;assert.equal(initial.movements.length,2);
  const selected=initial.movements[0].movementId;
  const armed=await simulator('/sim/v1/test-controls/faults',{kind:'LOST_RESPONSE',commandId:selected,count:1,delayMillis:5000});assert.equal(armed.status,200);fault=armed.body.faultId;
  await gate(false);
  const received=await api(`${prefix}/return-receipts`,{bearer,method:'POST',key:`${runId}-returns`,body:{sourceSystem:'scenario-driver',externalReceiptRef:`${runId}-returns`,counts:{REUSABLE:1,NEEDS_CLEANING:1,DAMAGED:1}}});assert.equal(received.status,202);
  evidence.order=await waitOrder(accepted.body.id);evidence.receipt=await waitReceipt(received.body.id);
  assert.equal(query('adapter',`SELECT count(*) FROM outbox WHERE aggregate_id='${selected}' AND event_type='CommandOutcomeUnknown.v1';`),'1');
  const ids=[...evidence.order.movements.map(item=>[item.movementId,'core','inventory_ledger']),...evidence.receipt.movements.map(item=>[item.movementId,'returns','sorting_ledger'])];assert.equal(ids.length,5);
  for(const [id,owner,table] of ids){assert.match(id,/^[a-f0-9-]{36}$/);for(const [db,ledger] of [[owner,table],['simulator','execution_ledger']])assert.equal(query(db,`SELECT count(*) FROM ${ledger} WHERE movement_id='${id}' AND quantity=1;`),'1');}
  evidence.cases.push({status:'passed',name:'Both products complete five single physical/business effects; selected lost response recovers through its original command',movementIds:ids.map(item=>item[0]),lostResponseMovement:selected});
  await session.page.getByRole('button',{name:'Orders',exact:true}).click();await session.page.getByRole('button',{name:reference,exact:true}).click();
  await session.page.getByRole('heading',{name:'Reservation breakdown',exact:true}).waitFor();await session.page.screenshot({path:resolve(directory,'offline-order.png')});await session.page.keyboard.press('Escape');
  await session.page.getByRole('button',{name:'Returns',exact:true}).click();await session.page.getByRole('button',{name:`${runId}-returns`,exact:true}).click();
  await session.page.getByRole('heading',{name:'Classification totals',exact:true}).waitFor();await session.page.screenshot({path:resolve(directory,'offline-returns.png')});await session.page.keyboard.press('Escape');
  const name='cutover-dev-equipment-simulator-1',before=JSON.parse(call('docker',['inspect',name]))[0];assert.equal(before.Config.Labels['dev.cutover.project'],'cutover');
  call('docker',['restart','--time','5',name]);
  await until(()=>JSON.parse(call('docker',['inspect',name]))[0].State.Health?.Status==='healthy','cached offline simulator restart',120000);
  const after=JSON.parse(call('docker',['inspect',name]))[0];assert.notEqual(after.State.StartedAt,before.State.StartedAt);
  const physical=await simulatorRead('/sim/v1/equipment');assert.equal(physical.worldId,evidence.worldBefore.worldId);assert.equal(physical.journalGeneration,evidence.worldBefore.journalGeneration);
  await waitOrder(accepted.body.id);await waitReceipt(received.body.id);
  evidence.cases.push({status:'passed',name:'Actual simulator restart uses cached image and preserves world, journal and both completed products',before:before.State.StartedAt,after:after.State.StartedAt});
  await run('cached-rollback','tools/scenario-driver/adapter-image-rollback-smoke.mjs',[`--image=${cache.rollback.runtimeReference}`]);
  await run('telemetry','tools/scenario-driver/telemetry-smoke.mjs');
  await session.page.goto('http://127.0.0.1:8783/d/cutover-platform/cutover-local-operations');
  try {
    await session.page.getByRole('textbox',{name:'Email or username',exact:true}).fill('cutover-admin');
    await session.page.getByRole('textbox',{name:'Password',exact:true}).fill(credentials.passwords.grafana_admin);
    await session.page.getByRole('button',{name:'Log in',exact:true}).click();
  } catch { throw new Error('The prepared local dashboard login did not complete.'); }
  await session.page.getByText('Observed application targets',{exact:true}).waitFor({timeout:30000});await session.page.screenshot({path:resolve(directory,'offline-dashboard.png'),fullPage:true});
  evidence.cases.push({status:'passed',name:'Cached compatible rollback, ten scrape targets, local trace retrieval and actual dashboard rendering under denial'});
  assert.ok(evidence.browserRequests.some(item=>!item.allowed&&item.path==='/cutover-offline-probe'));
  assert.ok(evidence.browserRequests.every(item=>item.allowed||item.path==='/cutover-offline-probe'),'The application attempted an unexpected external browser request.');
  await run('egress-probe-after','scripts/offline-egress.mjs',['Probe']);
  evidence.egress=JSON.parse(readFileSync(resolve(root,'.local/offline/egress.json'),'utf8'));evidence.worldAfter=await simulatorRead('/sim/v1/equipment');assert.equal(evidence.worldAfter.worldId,evidence.worldBefore.worldId);assert.deepEqual(routes(),evidence.routesBefore);
  evidence.cases.push({id:'A48',status:'passed',name:'Prepared local runtime walkthrough completed with Cutover-only external egress denial'});
} catch(error) { evidence.failure=error.message;throw error; }
finally {
  try {
    const failures=[];
    for(const clean of [
      async()=>{if(fault){const cleared=await simulator(`/sim/v1/test-controls/faults/${fault}`,undefined,'scenario','DELETE');assert.equal(cleared.status,200);}},
      async()=>{if(gateHeld)await gate(false);},
      async()=>{await session?.close();},
      async()=>{if(enabled){await run('egress-disable','scripts/offline-egress.mjs',['Disable']);evidence.denialCleanup=JSON.parse(readFileSync(resolve(root,'.local/offline/egress.json'),'utf8'));assert.equal(evidence.denialCleanup.state,'DISABLED');assert.equal(evidence.denialCleanup.finalDockerUserSha256,evidence.denialCleanup.priorDockerUserSha256);}},
    ])try{await clean();}catch(failure){failures.push(failure.message);}
    if(failures.length)throw new Error(failures.join(' '));
  } catch(cleanup) { evidence.cleanupFailure=cleanup.message;throw cleanup; }
  finally { evidence.endedAt=new Date().toISOString();saveEvidence(runId,evidence);writeJson(resolve(directory,'browser-requests.json'),evidence.browserRequests);console.log(`${runId}: ${evidence.failure||evidence.cleanupFailure?'failed':'passed'}. ${directory}`); }
}
