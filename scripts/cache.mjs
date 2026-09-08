import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, relative, dirname, basename, isAbsolute, sep } from 'node:path';
import { homedir } from 'node:os';
import { createHash } from 'node:crypto';
import { root, call, jsonFile, writeJson, privateDirectory, sha } from './lib/local-platform.mjs';

const action=process.argv[2];assert.ok(['Record','Check'].includes(action),'Use Record after online preparation, or Check before the prepared offline demonstration.');
const directory=resolve(root,'.local/offline'),indexPath=resolve(directory,'cache-index.json');
assert.ok(process.argv.slice(3).every(value=>value.startsWith('--empty-fixture=')||value.startsWith('--rollback-evidence=')),'Unrecognized cache option.');
const emptyArg=process.argv.find(value=>value.startsWith('--empty-fixture='))?.slice(16);
const rollbackArg=process.argv.find(value=>value.startsWith('--rollback-evidence='))?.slice(20);
if(rollbackArg){assert.equal(action,'Record');assert.match(rollbackArg,/^[a-z][a-z0-9-]*-\d{13}$/);}
let empty;
if(emptyArg){assert.equal(action,'Check');empty=resolve(root,emptyArg);const scope=resolve(root,'.local/verification');assert.ok(empty.startsWith(scope+sep));assert.ok(existsSync(empty) && readdirSync(empty).length===0,'The cold-cache fixture must be an existing empty directory inside .local/verification.');}
const lock=jsonFile(resolve(root,'infra/versions.lock.json'));
function walk(path,select=()=>true) {
  const files=[];if(!existsSync(path))return files;
  for(const entry of readdirSync(path,{withFileTypes:true})){
    assert.ok(!entry.isSymbolicLink(),'Prepared cache inventories do not traverse symbolic links.');
    const selected=resolve(path,entry.name);
    if(entry.isDirectory())files.push(...walk(selected,select));else if(entry.isFile() && select(selected))files.push(selected);
  }return files;
}
function inputs() {
  const files=[resolve(root,'pom.xml'),resolve(root,'.mvn/wrapper/maven-wrapper.properties'),resolve(root,'infra/versions.lock.json'),resolve(root,'apps/operations-console/package-lock.json'),resolve(root,'.local/images/manifest.json'),resolve(root,'.local/images/node-images.json')];
  for(const parent of ['apps','platform','tools']){
    function poms(path){for(const item of readdirSync(path,{withFileTypes:true})){if(['node_modules','target','.git'].includes(item.name))continue;const next=resolve(path,item.name);if(item.isDirectory())poms(next);else if(item.name==='pom.xml')files.push(next);}}
    poms(resolve(root,parent));
  }
  return Object.fromEntries(files.sort().map(path=>[relative(root,path).replaceAll('\\','/'),sha(readFileSync(path))]));
}
const missing=[];
let index;
if(action==='Record'){
  assert.ok(!existsSync(resolve(root,'.local/operations/maintenance.lock')),'Do not scan large caches during an active scenario or maintenance operation.');
  privateDirectory(directory);
  index={protocol:1,recordedAt:new Date().toISOString(),platform:process.platform,architecture:process.arch,inputs:inputs(),files:[],images:[],limits:'A cache inventory is not the prepared offline runtime/build acceptance result. Maven inventories the available local repository, which can contain extra dependencies.'};
  const add=(kind,label,path,algorithm='sha256',expected)=>{
    if(!existsSync(path)){missing.push(`${kind}: ${label}`);return;}
    const bytes=readFileSync(path),hash=createHash(algorithm).update(bytes).digest('hex');
    if(expected && hash!==expected){missing.push(`${kind}: checksum differs for ${label}`);return;}
    index.files.push({kind,label,path,bytes:bytes.length,algorithm,hash});
  };
  add('tool','kind '+lock.kind.version,resolve(root,'.local/tools/kind.exe'),'sha256',lock.kind.windowsAmd64Sha256);
  add('tool','Maven '+lock.maven.version,resolve(root,`.local/tools/apache-maven-${lock.maven.version}-bin.zip`),'sha256',lock.maven.sha256);
  add('agent','OpenTelemetry '+lock.javaAgent.version,resolve(root,'.local/assets/opentelemetry-javaagent.jar'),'sha256',lock.javaAgent.sha256);
  add('network','Calico '+lock.calicoManifest.version,resolve(root,`infra/vendor/calico/v${lock.calicoManifest.version}/calico.yaml`),'sha256',lock.calicoManifest.sha256);
  const maven=resolve(homedir(),'.m2/repository'),wrapper=resolve(process.env.MAVEN_USER_HOME??resolve(homedir(),'.m2'),'wrapper/dists');
  const jars=walk(maven,path=>/\.(?:jar|pom)$/.test(path) && !relative(maven,path).replaceAll('\\','/').startsWith('dev/cutover/'));
  if(!jars.length)missing.push('package: warmed Maven dependency/plugin repository');
  for(const path of jars)add('maven',relative(maven,path).replaceAll('\\','/'),path);
  const wrapperFiles=walk(wrapper);if(!wrapperFiles.length)missing.push('tool: Maven Wrapper cached distribution');
  for(const path of wrapperFiles)add('wrapper',relative(wrapper,path).replaceAll('\\','/'),path);
  const consolePath=resolve(root,'apps/operations-console'),packageLock=jsonFile(resolve(consolePath,'package-lock.json'));
  const npmCache=call('pwsh',['-NoProfile','-NonInteractive','-Command','npm config get cache']);assert.ok(isAbsolute(npmCache));
  for(const [path,item] of Object.entries(packageLock.packages)){
    if(!path || (item.optional && !existsSync(resolve(consolePath,path,'package.json'))))continue;
    if(!/^sha512-[A-Za-z0-9+/=]+$/.test(item.integrity??'')){missing.push(`npm: missing locked SHA-512 integrity for ${path}`);continue;}
    const hash=Buffer.from(item.integrity.slice(7),'base64').toString('hex');
    // npm 11's content-addressed store; npm ci --offline is the separate executable completeness proof.
    add('npm',`${path}@${item.version}`,resolve(npmCache,'_cacache/content-v2/sha512',hash.slice(0,2),hash.slice(2,4),hash.slice(4)),'sha512',hash);
  }
  if(!existsSync(resolve(consolePath,'node_modules/playwright/index.mjs')))missing.push('browser: installed pinned Playwright package');
  else{
    const {chromium}=await import('../apps/operations-console/node_modules/playwright/index.mjs');
    let browserFolder=dirname(chromium.executablePath());while(!/^chromium-\d+$/.test(basename(browserFolder)) && dirname(browserFolder)!==browserFolder)browserFolder=dirname(browserFolder);
    assert.match(basename(browserFolder),/^chromium-\d+$/);const browserRoot=dirname(browserFolder);
    const versions=jsonFile(resolve(consolePath,'node_modules/playwright-core/browsers.json')).browsers;
    index.browsers=[];
    for(const name of ['chromium','chromium-headless-shell','ffmpeg']){
      const selected=versions.find(item=>item.name===name);assert.ok(selected);const folder=resolve(browserRoot,`${name.replaceAll('-','_')}-${selected.revision}`);
      index.browsers.push({name,revision:selected.revision,version:selected.browserVersion??null});
      // Chromium can write diagnostics beside its executable. Those mutable logs are not installed runtime assets.
      const files=walk(folder,path=>!['debug.log','chrome_debug.log'].includes(basename(path).toLowerCase()));if(!files.length)missing.push(`browser: ${name} revision ${selected.revision}`);
      for(const path of files)add('browser',`${name}/${relative(folder,path).replaceAll('\\','/')}`,path);
    }
  }
  const nodeImages=jsonFile(resolve(root,'.local/images/node-images.json'));
  for(const [name,item] of Object.entries(nodeImages))add('image-archive',`${name} ${item.runtimeReference}`,item.archive,'sha256',item.archiveSha256);
  const images=[...Object.entries(lock.images).map(([name,item])=>({name,reference:item.reference})),...jsonFile(resolve(root,'.local/images/manifest.json')).images.map(item=>({name:item.service,reference:item.reference}))];
  if(rollbackArg){
    const evidencePath=resolve(root,'.local/evidence',rollbackArg,'results.json'),evidence=jsonFile(evidencePath),prior=evidence.nodeImages?.['equipment-adapter'];
    assert.ok(!evidence.failure && evidence.cases?.length && evidence.cases.every(item=>item.status==='passed'),'Select a completed, passed deployed evidence run for the predecessor image.');
    assert.match(prior?.runtimeReference??'',/^docker\.io\/cutover\/equipment-adapter@sha256:[a-f0-9]{64}$/);
    assert.match(prior.source,/^cutover\/equipment-adapter:[a-f0-9-]+$/);assert.match(prior.sourceImageId,/^sha256:[a-f0-9]{64}$/);
    assert.ok(resolve(prior.archive).startsWith(resolve(root,'.local/images/archives')+sep));
    add('rollback-image-archive',`equipment-adapter ${prior.runtimeReference}`,prior.archive,'sha256',prior.archiveSha256);
    assert.equal(call('docker',['image','inspect',prior.source,'--format','{{.Id}}']),prior.sourceImageId,'The retained predecessor tag must still identify the tested image.');
    images.push({name:'equipment-adapter-predecessor',reference:prior.source});
    index.rollback={sourceRun:rollbackArg,evidenceSha256:sha(readFileSync(evidencePath)),revision:evidence.revision,...prior};
  }else missing.push('rollback: choose a passed deployed predecessor with --rollback-evidence=<run-id>');
  for(const item of images){try{const imageId=call('docker',['image','inspect',item.reference,'--format','{{.Id}}']);index.images.push({...item,imageId});}catch{missing.push(`image: ${item.name} ${item.reference}`);}}
  if(!missing.length){writeJson(indexPath,index);console.log(`Recorded ${index.files.length} local cached files and ${index.images.length} Docker images. Run Check and the independent offline acceptance checks.`);}
}else{
  if(!existsSync(indexPath))missing.push('preparation: no cache index; acquire pinned tools, build/verify locally, install the pinned browser, then run cache.mjs Record');
  else{
    index=jsonFile(indexPath);assert.equal(index.protocol,1);assert.equal(index.platform,process.platform);assert.equal(index.architecture,process.arch);
    if(JSON.stringify(index.inputs)!==JSON.stringify(inputs()))missing.push('versions: dependency/build inputs changed after cache recording');
    for(const item of index.files){
      assert.ok(['sha256','sha512'].includes(item.algorithm));assert.match(item.hash,/^[a-f0-9]{64}(?:[a-f0-9]{64})?$/);
      const path=empty?resolve(empty,item.kind,sha(item.path)):item.path;
      if(!existsSync(path))missing.push(`${item.kind}: ${item.label}`);
      else if(statSync(path).size!==item.bytes || createHash(item.algorithm).update(readFileSync(path)).digest('hex')!==item.hash)missing.push(`${item.kind}: checksum differs for ${item.label}`);
    }
    if(!empty)for(const item of index.images){try{if(call('docker',['image','inspect',item.reference,'--format','{{.Id}}'])!==item.imageId)missing.push(`image: changed ${item.name}`);}catch{missing.push(`image: absent ${item.name} ${item.reference}`);}}
    const report={checkedAt:new Date().toISOString(),fixture:empty?'isolated empty file cache; existing Docker daemon cache is preserved':null,ready:missing.length===0,missing,groups:Object.fromEntries([...new Set(index.files.map(item=>item.kind))].map(kind=>[kind,index.files.filter(item=>item.kind===kind).length]))};
    writeJson(resolve(directory,empty?'cold-cache-check.json':'cache-check.json'),report);
    if(!missing.length)console.log('Recorded tools, Java packages, npm tarballs, browser files and image caches match. Runtime/offline-build evidence is a separate gate.');
  }
}
if(missing.length){console.error(`Offline preparation is incomplete (${missing.length} missing or invalid items).\n${missing.slice(0,30).join('\n')}${missing.length>30?'\nFull details: .local/offline/cold-cache-check.json or cache-check.json':''}`);process.exitCode=1;}
