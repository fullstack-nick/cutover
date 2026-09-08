import assert from 'node:assert/strict';
import { createReadStream, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve, sep } from 'node:path';
import { spawn } from 'node:child_process';
import { root, target, call, maintenanceLock, until } from './lib/local-platform.mjs';

assert.equal(process.argv.length,2,'This command loads only the recorded, verified adapter predecessor.');
const release=maintenanceLock('load-cached-adapter-predecessor');
try {
  const platform=target('demo');platform.verify();
  const node=JSON.parse(call('docker',['inspect','cutover-control-plane']))[0];
  assert.equal(node.Config.Labels['io.x-k8s.kind.cluster'],'cutover');assert.equal(node.State.Running,true);
  const index=JSON.parse(readFileSync(resolve(root,'.local/offline/cache-index.json'),'utf8')),entry=index.rollback;
  assert.equal(index.protocol,1);assert.match(entry?.runtimeReference??'',/^docker\.io\/cutover\/equipment-adapter@sha256:[a-f0-9]{64}$/);
  const archive=resolve(entry.archive);assert.ok(archive.startsWith(resolve(root,'.local/images/archives')+sep));
  const hash=createHash('sha256');for await(const bytes of createReadStream(archive))hash.update(bytes);
  assert.equal(hash.digest('hex'),entry.archiveSha256,'The cached predecessor archive changed.');
  const manifest=JSON.parse(call('tar',['-xOf',archive,'index.json']));
  const candidates=manifest.manifests.filter(item=>item.platform?.os==='linux'&&item.platform?.architecture==='amd64');assert.equal(candidates.length,1);
  const descriptor=candidates[0];assert.equal(entry.runtimeReference,`docker.io/cutover/equipment-adapter@${descriptor.digest}`);
  const alias=descriptor.annotations?.['io.containerd.image.name'];assert.match(alias??'',/^docker\.io\/cutover\/cache:equipment-adapter-[a-f0-9]{20}$/);
  await new Promise((done,failed)=>{
    const child=spawn(resolve(root,'.local/tools/kind.exe'),['load','image-archive',archive,'--name','cutover'],{cwd:root,windowsHide:true,stdio:'inherit'});
    child.once('error',failed);child.once('exit',code=>code===0?done():failed(new Error('The cached adapter import failed.')));
  });
  call('docker',['exec','cutover-control-plane','ctr','--namespace','k8s.io','images','tag','--force',alias,entry.runtimeReference]);
  await until(()=>{
    try{return JSON.parse(call('docker',['exec','cutover-control-plane','crictl','inspecti',entry.runtimeReference])).status.repoDigests.includes(entry.runtimeReference);}
    catch{return false;}
  },'CRI recognizes the exact cached adapter predecessor',15000);
  console.log(`Verified and loaded cached adapter predecessor ${descriptor.digest}.`);
} finally { release(); }
