import { resolve } from 'node:path';
import { cpus, freemem, totalmem, platform, release } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE='demo';
const { query }=await import('./client.mjs');
const { root, call, writeJson, target }=await import('../../scripts/lib/local-platform.mjs');
const id=process.argv[2];
if(!/^load-(?:diagnostic-)?\d+$/.test(id??''))throw new Error('Use an owned load run ID.');
const path=resolve(root,'.local/evidence',id,'resources.json');
const names=['cutover-control-plane','cutover-dev-equipment-simulator-1','cutover-dev-simulator-db-1','cutover-dev-equipment-volume-probe-1'];
const records=[],errors=[];
let stopped=false,previousHost,previousVm;
process.on('message',message=>{if(message==='stop')stopped=true;});
process.on('disconnect',()=>{stopped=true;});
function cpuSample(times,previous) {
  const total=times.reduce((sum,value)=>sum+value,0),idle=times[3];
  const result={ticks:times,usedPercent:previous && total>previous.total?100*(1-(idle-previous.idle)/(total-previous.total)):null};
  return [result,{total,idle}];
}
function hardware() {
  const hostTimes=cpus().reduce((sum,cpu)=>{const t=cpu.times;[t.user,t.nice,t.sys,t.idle,t.irq].forEach((value,index)=>sum[index]+=value);return sum;},[0,0,0,0,0]);
  const [hostCpu,nextHost]=cpuSample(hostTimes,previousHost);previousHost=nextHost;
  const raw=call('docker',['exec','cutover-control-plane','sh','-c','head -n 1 /proc/stat; cat /proc/meminfo']);
  const lines=raw.split(/\r?\n/),ticks=lines[0].trim().split(/\s+/).slice(1,9).map(Number);
  // Linux idle includes iowait here; steal is counted as occupied VM time.
  ticks[3]+=ticks[4];ticks[4]=0;
  const [vmCpu,nextVm]=cpuSample(ticks,previousVm);previousVm=nextVm;
  const mem=Object.fromEntries(lines.slice(1).map(line=>{const match=line.match(/^(\w+):\s+(\d+) kB$/);return match?[match[1],Number(match[2])*1024]:null;}).filter(Boolean));
  const containers=call('docker',['stats','--no-stream','--format','{{json .}}',...names]).split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line));
  return {host:{os:platform(),release:release(),logicalCpus:cpus().length,cpuModel:cpus()[0].model,totalBytes:totalmem(),availableBytes:freemem(),cpu:hostCpu},vm:{totalBytes:mem.MemTotal,availableBytes:mem.MemAvailable,cpu:vmCpu},containers};
}
function backlog(owner) {
  const taskTable={core:'legacy_tasks',execution:'execution_tasks',returns:'return_tasks'}[owner];
  return JSON.parse(query(owner,`SELECT jsonb_build_object(
    'unpublishedEvents',a.unpublished_events,'unpublishedBytes',a.unpublished_bytes,'activeRequests',a.active_requests,
    'outboxOldestSeconds',(SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM outbox WHERE published_at IS NULL),
    'pendingInbox',(SELECT count(*) FROM inbox WHERE state IN ('RECEIVED','PENDING')),
    'inboxOldestSeconds',(SELECT coalesce(extract(epoch FROM now()-min(received_at)),0) FROM inbox WHERE state IN ('RECEIVED','PENDING')),
    'quarantined',(SELECT count(*) FROM inbox WHERE state='QUARANTINED'),
    'taskStates',${taskTable?`(SELECT coalesce(jsonb_object_agg(state,n),'{}') FROM (SELECT state,count(*) n FROM ${taskTable} WHERE state NOT IN ('COMPLETED','CANCELLED') GROUP BY state) t)`:`'{}'::jsonb`},
    'unknownCommands',${owner==='adapter'?"(SELECT count(*) FROM command_journal WHERE state IN ('OUTCOME_UNKNOWN','QUARANTINED'))":'0'}
    ) FROM admission a;`));
}
try {
  target('demo').verify();
  const inspected=JSON.parse(call('docker',['inspect',...names]));
  for(const item of inspected)if(item.Config.Labels['io.x-k8s.kind.cluster']!=='cutover' && item.Config.Labels['dev.cutover.project']!=='cutover')throw new Error('Sampler ownership check failed.');
  const end=Date.now()+16*60*1000;
  do {
    const start=Date.now(),sample={at:new Date().toISOString(),...hardware(),owners:{}};
    for(const owner of ['core','adapter','execution','returns','shadow'])sample.owners[owner]=backlog(owner);
    sample.collectionMillis=Date.now()-start;records.push(sample);writeJson(path,{intervalMillis:10000,records,errors});
    if(records.length===1)process.send?.({type:'ready'});
    if(!stopped)await delay(Math.max(0,10000-(Date.now()-start)));
  }while(!stopped && Date.now()<end);
}catch(error){errors.push({at:new Date().toISOString(),message:error.message});writeJson(path,{intervalMillis:10000,records,errors});process.exitCode=1;}
finally{if(process.connected)process.disconnect();}
