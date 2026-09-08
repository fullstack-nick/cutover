import assert from 'node:assert/strict';
import {readFileSync,mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
process.env.CUTOVER_PROFILE='demo';
const{root,api,token,simulator,query,saveEvidence}=await import('./client.mjs');
const{humanSession}=await import('./human-session.mjs');
const source=process.argv.find(value=>value.startsWith('--run='))?.slice(6);
assert.match(source??'',/^returns-\d+$/,'Supply the passing returns process run ID.');
const prior=JSON.parse(readFileSync(resolve(root,'.local/evidence',source,'results.json'),'utf8'));assert.ok(!prior.failure&&prior.cases.every(item=>item.status==='passed'));
const runId=`returns-ui-${Date.now()}`,directory=resolve(root,'.local/evidence',runId),cases=[];mkdirSync(directory,{recursive:true});
let operator,supervisor,dispatchPaused=false,faultId,receipt;const prefix='/api/v1/sites/site-a';
async function until(check,description,timeout=90000){const end=Date.now()+timeout;do{if(await check())return;await delay(400);}while(Date.now()<end);throw new Error(`Timed out: ${description}`);}
async function gate(paused){const bearer=await token(),path='/internal/v1/sites/site-a/test-controls';const before=await api(path,{bearer,target:'adapter'});assert.equal(before.status,200);const recorded=await api(path,{method:'POST',bearer,target:'adapter',key:`${runId}-gate-${before.body.version}`,body:{expectedVersion:before.body.version,dispatchPaused:paused,reason:paused?'Select a returns history fault for the local recovery walkthrough.':'Restore dispatch after selecting the returns recovery walkthrough fault.'}});assert.equal(recorded.status,200);dispatchPaused=paused;}
async function openReturns(session,reference){await session.page.getByRole('button',{name:'Returns',exact:true}).click();await session.page.getByRole('heading',{name:'Receipt register',exact:true}).waitFor();if(reference){await session.page.getByRole('button',{name:reference,exact:true}).click();await session.page.getByRole('dialog').waitFor();}}
try{
  operator=await humanSession('operator-a');const page=operator.page;await page.setViewportSize({width:1440,height:1050});
  const errors=[],external=[];page.on('pageerror',()=>errors.push('page-error'));page.on('request',request=>{const url=new URL(request.url());if(!['localhost','127.0.0.1'].includes(url.hostname)&&['http:','https:'].includes(url.protocol))external.push(url.origin);});
  const mixed=prior.receipts.find(item=>item.body.externalReceiptRef.endsWith('-mixed'));assert.ok(mixed);
  await openReturns(operator);await page.getByRole('button',{name:mixed.body.externalReceiptRef,exact:true}).waitFor();await page.screenshot({path:resolve(directory,'returns-register.png'),fullPage:true});
  await page.getByRole('button',{name:mixed.body.externalReceiptRef,exact:true}).click();const detail=page.getByRole('dialog');await detail.getByRole('heading',{name:'Classification totals',exact:true}).waitFor();
  assert.equal(await detail.getByRole('row').count(),4);await detail.getByRole('button',{name:'Inspect movement evidence →',exact:true}).first().click();await detail.getByRole('heading',{name:'Confirmed sorting movement',exact:true}).waitFor();await detail.getByRole('heading',{name:'Confirmed sorting movement',exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:resolve(directory,'returns-completion-evidence.png'),fullPage:true});
  await page.keyboard.press('Escape');assert.equal(await detail.isVisible(),false);assert.equal(await page.evaluate(()=>document.activeElement?.textContent),mixed.body.externalReceiptRef);
  await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:resolve(directory,'returns-mobile.png'),fullPage:true});
  cases.push({candidate:'A51',name:'real operator PKCE, counts, receipt detail, physical proof, keyboard close and narrow viewport',status:'passed',receiptId:mixed.id});
  await gate(true);const body={sourceSystem:'scenario-driver',externalReceiptRef:`${runId}-investigation`,counts:{REUSABLE:1,NEEDS_CLEANING:0,DAMAGED:0}};
  const created=await api(prefix+'/return-receipts',{method:'POST',bearer:await token(),key:body.externalReceiptRef,body});assert.equal(created.status,202);receipt={id:created.body.id,body};
  const current=(await api(`${prefix}/return-receipts/${receipt.id}`,{bearer:await token()})).body;const movement=current.movements[0].movementId;
  const fault=await simulator('/sim/v1/test-controls/faults',{kind:'HISTORY_GAP',commandId:movement,count:1,delayMillis:0});assert.equal(fault.status,200);faultId=fault.body.faultId;await gate(false);
  await until(async()=>{const reply=await api(`${prefix}/commands/${movement}`,{bearer:await token()});return reply.status===200&&reply.body.state==='QUARANTINED';},'selected sorting command is durably quarantined');
  await page.setViewportSize({width:1440,height:1050});await page.getByRole('button',{name:'Refresh',exact:true}).click();await page.getByRole('button',{name:body.externalReceiptRef,exact:true}).waitFor({timeout:20000});await page.getByRole('button',{name:body.externalReceiptRef,exact:true}).click();await detail.getByRole('button',{name:'Inspect movement evidence →',exact:true}).click();await detail.getByRole('heading',{name:'Physical outcome needs investigation',exact:true}).waitFor();
  assert.equal(await detail.getByRole('button',{name:'Request reconciliation',exact:true}).count(),0);await detail.getByRole('heading',{name:'Physical outcome needs investigation',exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:resolve(directory,'returns-operator-uncertainty.png'),fullPage:true});assert.equal(errors.length,0);assert.deepEqual(external,[]);
  await operator.close();operator=undefined;supervisor=await humanSession('supervisor-a');await supervisor.page.setViewportSize({width:1440,height:1050});await openReturns(supervisor,body.externalReceiptRef);
  const recovery=supervisor.page.getByRole('dialog');await recovery.getByRole('button',{name:'Inspect movement evidence →',exact:true}).click();await recovery.getByRole('heading',{name:'Physical outcome needs investigation',exact:true}).waitFor();
  await recovery.getByRole('textbox',{name:'Reason for investigation'}).fill('The selected history fault is exhausted. Investigate the retained simulator history for this original sorting command.');await supervisor.page.screenshot({path:resolve(directory,'returns-supervisor-investigation.png'),fullPage:true});
  await recovery.getByRole('button',{name:'Request reconciliation',exact:true}).click();await until(async()=>(await api(`${prefix}/return-receipts/${receipt.id}`,{bearer:supervisor.bearer()})).body.state==='COMPLETED','UI-requested reconciliation completes original sorting movement');
  await recovery.getByRole('heading',{name:'Confirmed sorting movement',exact:true}).waitFor({timeout:20000});await supervisor.page.screenshot({path:resolve(directory,'returns-after-reconciliation.png'),fullPage:true});
  assert.equal(query('returns',`SELECT count(*) FROM sorting_ledger WHERE movement_id='${movement}';`),'1');assert.equal(query('simulator',`SELECT count(*) FROM execution_ledger WHERE movement_id='${movement}';`),'1');
  cases.push({candidates:['A39','A51'],name:'operator has no physical recovery action; supervisor records reasoned reconciliation through the UI',status:'passed',receiptId:receipt.id,movementId:movement});
  console.log(`Passed ${cases.length} returns browser checks. ${saveEvidence(runId,{cases,source,receipt})}`);
}catch(error){saveEvidence(runId,{cases,source,receipt,failure:error.message});throw error;}
finally{if(dispatchPaused)await gate(false);if(faultId)await simulator(`/sim/v1/test-controls/faults/${faultId}`,undefined,'scenario','DELETE');await operator?.close();await supervisor?.close();}
