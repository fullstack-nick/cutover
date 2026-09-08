import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { root, api, token, query, provisionObservers, saveEvidence }=await import('../tools/scenario-driver/client.mjs');
const path=resolve(root,'.local/operations/legacy-boundary-session.json');
const controlPath='/internal/v1/sites/site-a/test-controls';
const reason='Register the complete settled legacy inventory before replacing automatic task creation.';
let session=existsSync(path)?JSON.parse(readFileSync(path,'utf8')):null;
const action=process.argv[2]??'register';
if(!['register','release','cancel'].includes(action))throw new Error('Use register, release or cancel for the named Cutover boundary.');
const write=()=>{mkdirSync(resolve(root,'.local/operations'),{recursive:true});writeFileSync(path,JSON.stringify(session,null,2)+'\n');};
for(const target of ['core-api','adapter-api']) {
  const forward=spawnSync('pwsh',['-NoProfile','-File',resolve(root,'scripts/forward.ps1'),'-Target',target],{encoding:'utf8',windowsHide:true,timeout:45000});
  if(forward.status!==0)throw new Error('Start the scoped internal API forwards before registration.');
}
provisionObservers();
const bearer=await token();
async function status(target){const result=await api(controlPath,{target,bearer});assert.equal(result.status,200);return result.body;}
async function change(target,body,step){
  session.steps??={};
  const saved=session.steps[step]??={request:{...body,expectedVersion:session.versions[target],reason},done:false};write();
  if(saved.done){assert.equal((await status(target)).version,session.versions[target],'A completed gate step must still match its saved version.');return;}
  const result=await api(controlPath,{target,bearer,method:'POST',key:session.registrationId+'-'+step,body:saved.request});
  assert.equal(result.status,200);
  const actual=await status(target);assert.equal(actual.version,result.body.version,'A different operator changed the gate after this recorded action.');
  for(const [field,value] of Object.entries(body))assert.equal(actual[field],value);
  session.versions[target]=result.body.version;saved.done=true;write();
}
if(action!=='register'){
  if(!session || (action==='release' && session.state!=='VERIFIED'))throw new Error('A verified saved registration is required for release.');
  if(action==='release'){
    assert.equal(query('core',"SELECT count(*) FROM pg_trigger WHERE tgname='reservation_creates_legacy_task' AND NOT tgisinternal;"),'0','The explicit task-boundary migration must run before release.');
    assert.equal(query('core',"SELECT count(*) FROM pg_trigger WHERE tgname='reservation_creates_movement_intent' AND NOT tgisinternal;"),'1');
  }
  if(action==='cancel')assert.equal(query('core',"SELECT count(*) FROM pg_trigger WHERE tgname='reservation_creates_legacy_task' AND NOT tgisinternal;"),'1','After the creation-boundary migration, resume release instead of calling it a cancellation.');
  for(const target of ['adapter','core']){
    await change(target,target==='core'?{intakePaused:false,dispatchPaused:false}:{dispatchPaused:false},action+'-'+target);
  }
  session.state=action==='release'?'RELEASED':'CANCELLED';session.finishedAt=new Date().toISOString();write();
  console.log('Cutover boundary '+session.state.toLowerCase()+'. Both original process gates are open.');process.exit(0);
}
if(session?.state==='VERIFIED'){console.log('The saved inventory is verified. Apply the task-boundary migration before release.');process.exit(0);}
if(session && ['RELEASED','CANCELLED'].includes(session.state))throw new Error('This one-time boundary session is already closed; preserve its evidence.');
if(!session){
  const core=await status('core'),adapter=await status('adapter');
  for(const control of [core,adapter])for(const flag of ['workersPaused','criticalStorage','intakePaused','dispatchPaused'])assert.equal(control[flag],false,'Start from healthy unpaused process gates.');
  assert.equal(query('core',"SELECT count(*) FROM legacy_tasks WHERE state NOT IN ('COMPLETED','CANCELLED');"),'0','Settle the original baseline before the one-time creation boundary.');
  session={registrationId:randomUUID(),state:'PAUSING',startedAt:new Date().toISOString(),versions:{core:core.version,adapter:adapter.version},paused:{core:false,adapter:false}};write();
}
for(const target of ['core','adapter']){
  const current=await status(target);
  if(!session.paused[target]){
    await change(target,target==='core'?{intakePaused:true,dispatchPaused:true}:{dispatchPaused:true},'pause-'+target);
    session.paused[target]=true;write();
  }else {assert.equal(current.version,session.versions[target],'The saved control version must still match.');assert.equal(current.dispatchPaused,true);}
}
session.state='REGISTERING';write();
const kc=['--kubeconfig',resolve(root,'.local/kubeconfig'),'--context','kind-cutover','-n','cutover-apps'];
const inspected=spawnSync('kubectl',[...kc,'get','pods','-l','app.kubernetes.io/name=legacy-core','-o','json'],{encoding:'utf8',windowsHide:true});
assert.equal(inspected.status,0);
const candidates=JSON.parse(inspected.stdout).items.filter(pod=>pod.metadata.labels['app.kubernetes.io/part-of']==='cutover'&&!pod.metadata.deletionTimestamp&&pod.status.containerStatuses?.every(item=>item.ready));
assert.equal(candidates.length,1);
const result=spawnSync('kubectl',[...kc,'exec','-i',candidates[0].metadata.name,'--','env','JAVA_TOOL_OPTIONS=','CUTOVER_BOUNDARY_REGISTRATION=true','java','-Xmx256m','-Dorg.jooq.no-logo=true','-Dorg.jooq.no-tips=true','-Dloader.main=dev.cutover.core.LegacyBoundaryMain','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher'],{
  input:JSON.stringify({registrationId:session.registrationId,adapterControlVersion:session.versions.adapter,reason}),encoding:'utf8',windowsHide:true,timeout:120000,maxBuffer:262144,
});
const response=result.stdout.split(/\r?\n/).filter(line=>line.startsWith('{')).map(line=>{try{return JSON.parse(line);}catch{return null;}}).find(item=>['VERIFIED','REGISTRATION_FAILED'].includes(item?.state));
if(result.status!==0||response?.state!=='VERIFIED'){
  session.lastFailure=response?.code??'REGISTRATION_PROCESS_FAILED';write();
  throw new Error('Registration did not verify ('+session.lastFailure+'). Gates remain paused; repair and rerun the same saved session, or explicitly cancel.');
}
session.state='VERIFIED';session.response=response;session.verifiedAt=new Date().toISOString();write();
const checkpoints=JSON.parse(query('core',"SELECT row_to_json(c) FROM legacy_boundary_checkpoints c WHERE registration_id='"+session.registrationId+"';"));
const directory=saveEvidence('legacy-registration-'+session.registrationId,{response,checkpoint:checkpoints,pod:candidates[0].metadata.name});
console.log('Verified '+response.taskCount+' original task IDs and allocations. '+directory);
console.log('Intake and dispatch remain paused for the explicit task-boundary migration. Then run register-legacy-boundary.mjs release.');
