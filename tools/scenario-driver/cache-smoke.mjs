import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import { openSync, closeSync, readFileSync } from 'node:fs';
process.env.CUTOVER_PROFILE='demo';
const { saveEvidence }=await import('./client.mjs');
const { root, target, privateDirectory, writeJson }=await import('../../scripts/lib/local-platform.mjs');
const predecessor=process.argv[2];assert.match(predecessor??'',/^[a-z][a-z0-9-]*-\d{13}$/,'Select a passed deployed predecessor evidence run.');
const id=`cache-${Date.now()}`,directory=resolve(root,'.local/evidence',id),fixture=resolve(root,'.local/verification',id);
privateDirectory(directory);privateDirectory(fixture);
const evidence={startedAt:new Date().toISOString(),cases:[],predecessor,fixture:'An isolated empty file cache; existing Docker daemon images and real user caches are preserved.'};
async function check(name,args,expectedCode=0) {
  const file=openSync(resolve(directory,`${name}.log`),'w',0o600);
  try { const code=await new Promise((done,failed)=>{const child=spawn(process.execPath,[resolve(root,'scripts/cache.mjs'),...args],{cwd:root,windowsHide:true,stdio:['ignore',file,file]});child.once('error',failed);child.once('exit',done);});assert.equal(code,expectedCode,`${name} must return the declared exit code`); }
  finally { closeSync(file); }
}
try {
  target('demo').verify();
  await check('record',['Record',`--rollback-evidence=${predecessor}`]);
  await check('prepared',['Check']);
  evidence.prepared=JSON.parse(readFileSync(resolve(root,'.local/offline/cache-check.json'),'utf8'));assert.equal(evidence.prepared.ready,true);
  await check('cold',['Check',`--empty-fixture=${fixture}`],1);
  evidence.cold=JSON.parse(readFileSync(resolve(root,'.local/offline/cold-cache-check.json'),'utf8'));assert.equal(evidence.cold.ready,false);
  for(const kind of ['tool','maven','npm','browser','image-archive','rollback-image-archive'])assert.ok(evidence.cold.missing.some(item=>item.startsWith(`${kind}:`)),`Missing ${kind} must be explicit.`);
  const index=JSON.parse(readFileSync(resolve(root,'.local/offline/cache-index.json'),'utf8'));assert.equal(evidence.cold.missing.length,index.files.length);
  evidence.inventory={recordedAt:index.recordedAt,files:index.files.length,images:index.images,browsers:index.browsers,rollback:index.rollback,inputs:index.inputs};
  evidence.cases.push({id:'A49',status:'passed',name:'Recorded cache matches; empty file-cache verification fails explicitly and identifies tools, package names, image digests and browser revisions',missingCount:evidence.cold.missing.length});
  evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A49'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:`node tools/scenario-driver/cache-smoke.mjs ${predecessor}`,fixture:evidence.fixture});
  console.log(`${id}: passed, ${evidence.cold.missing.length} cold-cache items reported. ${directory}`);
}catch(error){evidence.failure=error.message;saveEvidence(id,evidence);throw error;}
