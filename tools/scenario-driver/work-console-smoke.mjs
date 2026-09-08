import assert from 'node:assert/strict';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, simulator, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, target, privateDirectory, maintenanceLock, request, until, simulatorRead, writeJson }=await import('../../scripts/lib/local-platform.mjs');
const id=`work-console-${Date.now()}`,directory=resolve(root,'.local/evidence',id),prefix='/api/v1/sites/site-a',control='/internal/v1/sites/site-a/test-controls';
privateDirectory(directory);const release=maintenanceLock(id),platform=target('demo');
const evidence={startedAt:new Date().toISOString(),cases:[],browserErrors:[],audits:[]};
let forward,operator,supervisor,second,movement,orderId,faultId,paused=false,failure;
async function gate(value){
  const bearer=await token(),before=await request(forward.origin,control,{bearer});
  if(before.dispatchPaused===value){paused=value;return;}
  await request(forward.origin,control,{bearer,method:'POST',key:`${id}-gate-${before.version}`,body:{expectedVersion:before.version,dispatchPaused:value,reason:value?'Hold new dispatch while inspecting one explicitly selected browser recovery fixture.':'Resume the original browser recovery movement after its history evidence is available.'}});paused=value;
}
async function command(){return (await api(`${prefix}/commands/${movement}`,{bearer:await token()})).body;}
const effectCount=()=>Number(query('simulator',`SELECT count(*) FROM execution_ledger WHERE movement_id='${movement}';`));
async function navigate(session,name){await session.page.getByRole('button',{name,exact:true}).click();}
async function openRecovery(session){await navigate(session,'Recovery');await session.page.getByRole('button',{name:movement.slice(0,8),exact:true}).click();await session.page.getByRole('dialog').getByRole('heading',{name:'Task and command timeline',exact:true}).waitFor();}
try{
  platform.verify();provisionObservers();
  for(const owner of ['core','returns'])assert.equal(query(owner,'SELECT active_requests FROM admission;'),'0');
  assert.equal(query('adapter',"SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');"),'0');
  evidence.physicalBefore=await simulatorRead('/sim/v1/equipment');forward=await platform.forward('equipment-adapter');
  assert.equal((await request(forward.origin,control,{bearer:await token()})).dispatchPaused,false);
  await gate(true);
  const sku=query('core',"SELECT sku FROM stock WHERE site_id='site-a' AND on_hand-reserved>0 ORDER BY sku LIMIT 1;");assert.match(sku,/^SKU-\d{3}$/);
  const created=await api(`${prefix}/orders`,{method:'POST',key:id,bearer:await token(),body:{sourceSystem:'scenario-driver',externalOrderRef:id,storeId:'store-05',priority:5,lines:[{sku,quantity:1}]}});assert.equal(created.status,202);orderId=created.body.id;
  const order=(await api(`${prefix}/orders/${orderId}`,{bearer:await token()})).body;assert.equal(order.movements.length,1);movement=order.movements[0].movementId;
  const fault=await simulator('/sim/v1/test-controls/faults',{kind:'HISTORY_GAP',commandId:movement,count:1,delayMillis:0});assert.equal(fault.status,200);faultId=fault.body.faultId;
  await gate(false);await until(async()=> (await command()).state==='QUARANTINED','the selected history gap holds the original command',90000);await gate(true);
  assert.equal(effectCount(),0);evidence.quarantined=await command();
  operator=await humanSession('operator-a');operator.page.on('pageerror',()=>evidence.browserErrors.push('Operator page error'));
  await openRecovery(operator);const opDialog=operator.page.getByRole('dialog');
  assert.equal(await opDialog.getByRole('button',{name:'Review investigation',exact:true}).count(),0);
  assert.equal((await api(`${prefix}/commands/${movement}/reconciliation`,{method:'POST',key:`${id}-operator-denied`,bearer:operator.bearer(),body:{expectedVersion:evidence.quarantined.version,reason:'Direct operator request must be denied by the service.'}})).status,403);
  for(const path of ['/commands','/tasks','/execution-tasks','/return-tasks','/audit/adapter','/audit/core','/audit/execution','/audit/returns',`/commands/${movement}/timeline`])assert.equal((await api(prefix.replace('site-a','site-b')+path,{bearer:operator.bearer()})).status,404);
  await operator.page.screenshot({path:resolve(directory,'operator-held-command.png'),fullPage:true});await operator.page.keyboard.press('Escape');await opDialog.waitFor({state:'hidden'});
  supervisor=await humanSession('supervisor-a');second=await humanSession('supervisor-a2');supervisor.page.on('pageerror',()=>evidence.browserErrors.push('Supervisor page error'));
  await openRecovery(supervisor);const page=supervisor.page,dialog=page.getByRole('dialog');
  const reason=`${id}: inspect the original same-world command after the selected history fault.`;
  await dialog.getByRole('textbox',{name:'Reason for investigation',exact:true}).fill(reason);await dialog.getByRole('button',{name:'Review investigation',exact:true}).click();
  const before=await command();
  const competing=await api(`${prefix}/commands/${movement}/reconciliation`,{method:'POST',key:`${id}-concurrent-supervisor`,bearer:second.bearer(),body:{expectedVersion:before.version,reason:`${id}: second supervisor records the first status investigation while dispatch is held.`}});assert.equal(competing.status,200);evidence.competing=competing.body;
  const conflict=page.waitForResponse(response=>response.url().endsWith(`/commands/${movement}/reconciliation`)&&response.request().method()==='POST');
  await dialog.getByRole('button',{name:'Confirm status investigation',exact:true}).click();assert.equal((await conflict).status(),409);
  await dialog.getByRole('alert').filter({hasText:'The command changed'}).waitFor();assert.equal(effectCount(),0);
  await page.screenshot({path:resolve(directory,'version-conflict.png'),fullPage:true});
  // The version from a fresh detail poll, not the stale reviewed request, is used for the next explicit action.
  const latest=await command();await until(async()=>await dialog.getByText(String(latest.version),{exact:true}).count()>0,'the current command version is visible',15000);
  await dialog.getByRole('textbox',{name:'Reason for investigation',exact:true}).fill(reason+' Latest evidence reviewed.');
  await dialog.getByRole('button',{name:'Review investigation',exact:true}).click();
  const accepted=page.waitForResponse(response=>response.url().endsWith(`/commands/${movement}/reconciliation`)&&response.request().method()==='POST');
  await dialog.getByRole('button',{name:'Confirm status investigation',exact:true}).click();const acceptedResponse=await accepted;assert.equal(acceptedResponse.status(),200);evidence.recorded=await acceptedResponse.json();
  await dialog.getByText('Investigation recorded. Follow the retained command evidence for its outcome.',{exact:true}).waitFor();assert.equal(effectCount(),0);
  await gate(false);await until(async()=> (await command()).state==='COMPLETED','the original command completes after the selected fault is exhausted',90000);
  await dialog.getByRole('heading',{name:'Verified completion',exact:true}).waitFor();await page.screenshot({path:resolve(directory,'verified-command-timeline.png'),fullPage:true});
  await page.keyboard.press('Escape');await dialog.waitFor({state:'hidden'});
  await navigate(supervisor,'Tasks');await page.getByRole('checkbox',{name:'Show active tasks',exact:true}).uncheck();await page.getByRole('button',{name:movement.slice(0,8),exact:true}).click();await page.getByRole('dialog').getByRole('heading',{name:'Verified completion',exact:true}).waitFor();
  await page.screenshot({path:resolve(directory,'completed-task-detail.png'),fullPage:true});await page.keyboard.press('Escape');
  await navigate(supervisor,'Audit');await page.getByRole('searchbox',{name:'Resource ID',exact:true}).fill(movement);await page.getByRole('button',{name:'Filter audit',exact:true}).click();
  await page.getByRole('cell',{name:reason+' Latest evidence reviewed.',exact:true}).waitFor();assert.equal(await page.locator('.audit-panel tbody tr').count(),2);
  await page.screenshot({path:resolve(directory,'supervisor-audit-history.png'),fullPage:true});
  for(const owner of ['adapter','core','execution','returns']){const result=await api(`${prefix}/audit/${owner}?limit=25`,{bearer:operator.bearer()});assert.equal(result.status,200);assert.equal(result.body.owner,owner);assert.ok(result.body.items.every(item=>!Object.hasOwn(item,'detail')));evidence.audits.push({owner,count:result.body.items.length});}
  await navigate(supervisor,'Migrations');await page.getByRole('heading',{name:'Shadow decisions',exact:true}).waitFor();
  const comparisons=await api(`${prefix}/shadow-comparisons`,{bearer:supervisor.bearer()});assert.equal(comparisons.status,200);assert.ok(comparisons.body.items.length>0,'The real legacy scheduling work must have produced a retained comparison.');const round=comparisons.body.items[0].roundId;
  await page.getByRole('searchbox',{name:'Stored round ID',exact:true}).fill(round);await page.getByRole('button',{name:'Inspect round',exact:true}).click();await page.getByRole('heading',{name:'Both schedulers produced the same decision',exact:true}).waitFor();
  await page.screenshot({path:resolve(directory,'stored-shadow-comparison.png'),fullPage:true});
  await navigate(supervisor,'Tasks');await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:resolve(directory,'mobile-task-queues.png'),fullPage:true});
  assert.deepEqual(evidence.browserErrors,[]);assert.equal(effectCount(),1);assert.equal(query('core',`SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement}' AND quantity=1;`),'1');
  evidence.finalCommand=await command();evidence.timeline=(await api(`${prefix}/commands/${movement}/timeline`,{bearer:operator.bearer()})).body;assert.equal(evidence.timeline.historyComplete,true);
  evidence.physicalAfter=await simulatorRead('/sim/v1/equipment');for(const key of ['worldId','journalGeneration'])assert.equal(evidence.physicalAfter[key],evidence.physicalBefore[key]);
  evidence.cases.push({status:'passed',supports:['A39','A40','A51'],name:'Real operator/supervisor work queues, command history, stale-version conflict, reviewed investigation, exact single effect, per-owner audit reads, stored scheduler comparison and narrow-screen navigation',scope:'Complements the separately recorded orders, returns and migration walkthroughs; no synthetic browser response replaces the physical or database assertions.'});
}catch(error){failure=error;evidence.failure=error.message;}
finally{
  try{
    if(faultId)assert.equal((await simulator(`/sim/v1/test-controls/faults/${faultId}`,undefined,'scenario','DELETE')).status,200);
    if(paused)await gate(false);
    if(failure&&movement){const retained=await command();if(!['COMPLETED','REJECTED_BEFORE_EXECUTION'].includes(retained.state)){
      supervisor??=await humanSession('supervisor-a');
      const repaired=await api(`${prefix}/commands/${movement}/reconciliation`,{method:'POST',key:`${id}-cleanup-${retained.version}`,bearer:supervisor.bearer(),body:{expectedVersion:retained.version,reason:'The selected browser fixture fault is cleared. Investigate only the retained original command after an unsuccessful walkthrough.'}});
      evidence.cleanupInvestigation={status:repaired.status,body:repaired.body};
    }}
  }catch(error){evidence.cleanupFailure=error.message;failure??=error;}
  for(const session of [operator,supervisor,second])if(session)await session.close();forward?.close();release();
  evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A39','A40','A51'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:'node tools/scenario-driver/work-console-smoke.mjs'});
}
if(failure)throw failure;
console.log(`${id}: operational console walkthrough passed. ${directory}`);
