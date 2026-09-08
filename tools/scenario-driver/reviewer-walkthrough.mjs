import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFileSync, readdirSync, openSync, closeSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, target, privateDirectory, writeJson, simulatorRead, call }=await import('../../scripts/lib/local-platform.mjs');
const id=`reviewer-${Date.now()}`,directory=resolve(root,'.local/evidence',id);
privateDirectory(directory);
const evidence={startedAt:new Date().toISOString(),seed:20260908,cases:[],steps:[],browserErrors:[]};
const steps=[
  ['platform-smoke.mjs','platform-','Both temperature zones'],
  ['returns-smoke.mjs','returns-','Independent crate returns and lane fault'],
  ['shadow-smoke.mjs','shadow-','One thousand stored scheduling comparisons'],
  ['migration-smoke.mjs','zone-migration-','Ambient ownership change while chilled work flows'],
  ['work-console-smoke.mjs','work-console-','Unknown-command recovery, conflict and audit'],
  ['causal-trace-smoke.mjs','causal-trace-','Both product traces and local dashboards'],
];
const unrelated=()=>call('docker',['ps','--format','{{.ID}} {{.Names}}']).split('\n').filter(line=>line&&!line.includes('cutover-')).sort();
let session,failure;
async function run(script,prefix,label){
  const before=new Set(readdirSync(resolve(root,'.local/evidence'))),started=Date.now(),command=`node tools/scenario-driver/${script}`;
  console.log(`${id}: ${label}.`);
  const log=openSync(resolve(directory,script.replace('.mjs','.log')),'w',0o600);
  let exitCode;
  try{exitCode=await new Promise((done,failed)=>{const child=spawn(process.execPath,[resolve(root,'tools/scenario-driver',script)],{cwd:root,windowsHide:true,stdio:['ignore',log,log]});child.once('error',failed);child.once('exit',done);});}
  finally{closeSync(log);}
  const created=readdirSync(resolve(root,'.local/evidence')).filter(name=>!before.has(name)&&new RegExp('^'+prefix+'\\d{13}$').test(name));
  const step={label,command,startedAt:new Date(started).toISOString(),endedAt:new Date().toISOString(),seconds:(Date.now()-started)/1000,exitCode,runIds:created};
  evidence.steps.push(step);writeJson(resolve(directory,'progress.json'),evidence);
  assert.equal(exitCode,0,`${script} failed; inspect the retained step log before continuing.`);assert.equal(created.length,1);
  const result=JSON.parse(readFileSync(resolve(root,'.local/evidence',created[0],'results.json'),'utf8'));
  assert.ok(!result.failure&&!result.cleanupFailure&&!result.cleanupErrors?.length);
  assert.ok(result.cases?.length>0&&result.cases.every(test=>test.status==='passed'));
  evidence.cases.push({status:'passed',name:label,runId:created[0],seconds:step.seconds});
}
try{
  target('demo').verify();provisionObservers();
  assert.ok(!existsSync(resolve(root,'.local/operations/maintenance.lock')),'Finish other maintenance before the timed reviewer path.');
  assert.equal(query('core','SELECT active_requests FROM admission;'),'0');assert.equal(query('returns','SELECT active_requests FROM admission;'),'0');
  const routes=JSON.parse(query('adapter',"SELECT jsonb_agg(jsonb_build_object('zone',zone_id,'owner',owner,'state',state,'epoch',epoch) ORDER BY zone_id) FROM zone_routes WHERE site_id='site-a';"));
  assert.ok(routes.filter(route=>route.zone!=='returns').every(route=>route.owner==='legacy-core'&&route.state==='ACTIVE'),'Use the documented fresh bootstrap or supervised reversal; never edit route epochs in SQL.');
  evidence.routesBefore=routes;evidence.worldBefore=await simulatorRead('/sim/v1/equipment');evidence.unrelatedBefore=unrelated();
  evidence.walkthroughStartedAt=new Date().toISOString();const started=Date.now();
  for(const step of steps)await run(...step);
  evidence.commandSeconds=(Date.now()-started)/1000;
  assert.ok(evidence.commandSeconds<=900,'The documented command sequence exceeded fifteen minutes; record the actual duration and investigate before qualifying the reviewer path.');
  session=await humanSession('operator-a');const page=session.page;
  page.on('pageerror',()=>evidence.browserErrors.push('An uncaught browser error occurred.'));
  await page.setViewportSize({width:1440,height:1000});
  for(const view of ['Overview','Orders','Returns','Tasks','Migrations']){
    await page.getByRole('button',{name:view,exact:true}).click();
    await page.getByRole('heading',{name:view,exact:true}).waitFor();
    if(['Overview','Orders','Returns'].includes(view))await page.locator('tbody tr').first().waitFor();
    if(view==='Migrations')await page.locator('.route-owner').first().waitFor();
    if(view==='Tasks')await page.getByRole('heading',{name:'Task queues',exact:true}).waitFor();
    await page.screenshot({path:resolve(directory,`${view.toLowerCase()}.png`)});
  }
  assert.deepEqual(evidence.browserErrors,[]);
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');assert.equal(evidence.worldAfter.worldId,evidence.worldBefore.worldId);assert.equal(evidence.worldAfter.journalGeneration,evidence.worldBefore.journalGeneration);
  assert.deepEqual(unrelated(),evidence.unrelatedBefore);
  evidence.cases.push({id:'A54',status:'passed',name:'The six exact reviewer commands pass sequentially within fifteen minutes, followed by actual current-console screenshots.',seconds:evidence.commandSeconds,scope:'Prepared local runtime; online acquisition/build and fresh bootstrap have separate evidence. The command time excludes optional human reading time.'});
}catch(error){failure=error;evidence.failure=error.message;}
finally{
  await session?.close();evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);
  writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A54'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:'node tools/scenario-driver/reviewer-walkthrough.mjs',guide:'docs/onboarding/quickstart.md',screenshots:['overview.png','orders.png','returns.png','tasks.png','migrations.png']});
}
if(failure)throw failure;
console.log(`${id}: reviewer commands passed in ${evidence.commandSeconds.toFixed(1)} seconds. ${directory}`);
