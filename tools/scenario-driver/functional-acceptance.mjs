import assert from 'node:assert/strict';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { root, target, privateDirectory, writeJson, maintenanceLock, until, simulatorRead, call }=await import('../../scripts/lib/local-platform.mjs');
const id=`functional-${Date.now()}`,directory=resolve(root,'.local/evidence',id),prefix='/api/v1/sites/site-a';
privateDirectory(directory);const release=maintenanceLock(id);
const evidence={startedAt:new Date().toISOString(),seed:20260908,cases:[],orders:[],invalid:[]};
let bearer,held=false,failure;
const validId=value=>{assert.match(value,/^[a-f0-9-]{36}$/);return value;};
const stock=()=>JSON.parse(query('core',"SELECT jsonb_agg(jsonb_build_object('sku',s.sku,'zone',p.temperature_class,'onHand',s.on_hand,'reserved',s.reserved) ORDER BY s.sku) FROM stock s JOIN products p USING(site_id,sku) WHERE s.site_id='site-a';"));
const body=(name,lines)=>({sourceSystem:'scenario-driver',externalOrderRef:`${id}-${name}`,storeId:'store-01',priority:5,lines});
async function accept(request,key=request.externalOrderRef){const result=await api(`${prefix}/orders`,{method:'POST',bearer,key,body:request});assert.equal(result.status,202);validId(result.body.id);return result;}
async function finish(request,response){
  let order;await until(async()=>{const result=await api(`${prefix}/orders/${response.body.id}`,{bearer});assert.equal(result.status,200);order=result.body;return ['COMPLETED','COMPLETED_WITH_SHORTAGE','SHORTAGE'].includes(order.state);},'all reserved movements are physically confirmed',120000);
  for(const line of order.lines)assert.equal(line.reserved+line.shortage,line.requested);
  for(const movement of order.movements){
    const movementId=validId(movement.movementId),quantity=movement.movement.quantity;
    assert.equal(movement.state,'COMPLETED');
    assert.equal(query('core',`SELECT count(*) FROM inventory_ledger WHERE movement_id='${movementId}' AND quantity=${quantity};`),'1');
    assert.equal(query('simulator',`SELECT count(*) FROM execution_ledger WHERE movement_id='${movementId}' AND quantity=${quantity};`),'1');
    assert.equal(query('adapter',`SELECT count(*) FROM movement_allocations WHERE movement_id='${movementId}' AND zone_id='${movement.movement.zoneId}';`),'1');
  }
  evidence.orders.push({request,response:response.body,order});return order;
}
async function controls(values){
  const path='/internal/v1/sites/site-a/test-controls',before=await api(path,{target:'core',bearer});assert.equal(before.status,200);
  const after=await api(path,{target:'core',bearer,method:'POST',key:`${id}-controls-${before.body.version}`,body:{expectedVersion:before.body.version,...values,reason:'Verify original response retention and fresh-key admission at the real HTTP boundary.'}});assert.equal(after.status,200);
  held=Boolean(values.intakePaused||values.criticalStorage);
}
try {
  target('demo').verify();provisionObservers();
  call('pwsh',['-NoProfile','-NonInteractive','-File',resolve(root,'scripts/forward.ps1'),'-Profile','demo','-Target','core-api','-Action','Start']);
  bearer=await token();evidence.worldBefore=await simulatorRead('/sim/v1/equipment');
  assert.equal(query('core','SELECT active_requests FROM admission;'),'0');
  assert.equal(query('core','SELECT intake_paused OR critical_storage OR workers_paused FROM service_control;'),'f');
  const before=stock(),pair=['ambient','chilled'].map(zone=>before.find(s=>s.zone===zone&&s.onHand-s.reserved>=3));assert.ok(pair.every(Boolean));
  const completeRequest=body('complete',pair.map(s=>({sku:s.sku,quantity:1}))),completeResponse=await accept(completeRequest);
  const complete=await finish(completeRequest,completeResponse);assert.equal(complete.state,'COMPLETED');assert.equal(complete.movements.length,2);
  for(const movement of complete.movements){const sku=query('core',`SELECT sku FROM reservations WHERE reservation_id='${validId(movement.movementId)}';`);const expected=pair.find(s=>s.sku===sku);assert.ok(expected);assert.equal(movement.movement.zoneId,expected.zone);}
  evidence.cases.push({id:'A01',status:'passed',name:'Ambient/chilled HTTP order completes after two single effects in compatible zones.'});
  for(const key of [completeRequest.externalOrderRef,`${id}-new-key`])assert.deepEqual((await accept(completeRequest,key)).body,completeResponse.body);
  const changed={...completeRequest,priority:6};
  for(const key of [completeRequest.externalOrderRef,`${id}-changed-new-key`])assert.equal((await api(`${prefix}/orders`,{method:'POST',bearer,key,body:changed})).status,409);
  assert.equal(query('core',`SELECT count(*) FROM orders WHERE external_ref='${completeRequest.externalOrderRef}';`),'1');
  assert.equal(query('core',`SELECT count(*) FROM reservations WHERE order_id='${complete.id}';`),'2');
  evidence.cases.push({id:'A03',status:'passed',name:'Original key and fresh key return the identical recorded order with one reservation set.'});
  evidence.cases.push({id:'A04',status:'passed',scope:'Outbound intake; separate returns and supervised-recovery evidence covers their independent keys.',name:'Changed payload conflicts under both original-key and fresh-key business-reference lookup.'});
  for(const field of ['intakePaused','criticalStorage']){
    await controls({[field]:true});
    assert.deepEqual((await accept(completeRequest)).body,completeResponse.body);
    const refused=await api(`${prefix}/orders`,{method:'POST',bearer,key:`${id}-${field}-fresh`,body:completeRequest});assert.equal(refused.status,503);
    assert.equal((await api(`${prefix}/orders/${complete.id}`,{bearer})).status,200);
    await controls({intakePaused:false,criticalStorage:false});
  }
  evidence.cases.push({status:'passed',name:'Paused and critical-storage HTTP admission retain the original response and refuse fresh-key duplicate writes.'});
  const competition=stock(),scarce=['ambient','chilled'].map(zone=>competition.filter(s=>s.zone===zone&&s.onHand-s.reserved>=3).sort((a,b)=>(a.onHand-a.reserved)-(b.onHand-b.reserved)||a.sku.localeCompare(b.sku))[0]);assert.ok(scarce.every(Boolean));
  evidence.competitionStockBefore=scarce;
  const raced=Array.from({length:6},(_,n)=>body(`race-${n}`,(n%2?[...scarce].reverse():scarce).map(s=>({sku:s.sku,quantity:s.onHand-s.reserved-1}))));
  const responses=await Promise.all(raced.map(request=>accept(request)));
  const finished=[];for(let n=0;n<raced.length;n++)finished.push(await finish(raced[n],responses[n]));
  assert.ok(finished.some(o=>o.state==='COMPLETED_WITH_SHORTAGE'));assert.ok(finished.some(o=>o.state==='SHORTAGE'));
  for(const original of scarce){
    const lines=finished.flatMap(o=>o.lines).filter(l=>l.sku===original.sku),reserved=lines.reduce((n,l)=>n+l.reserved,0);
    assert.equal(reserved,original.onHand-original.reserved);
    const after=stock().find(s=>s.sku===original.sku);assert.equal(after.onHand,0);assert.equal(after.reserved,0);
  }
  assert.equal(query('core','SELECT count(*) FROM stock WHERE reserved<0 OR on_hand<reserved;'),'0');
  evidence.cases.push({id:'A02',status:'passed',name:'Concurrent real stock exhaustion produces exact partial/total shortage states and conserves every requested quantity.'});
  evidence.cases.push({id:'A05',status:'passed',name:'Six concurrent two-line HTTP requests with alternating input order consume only existing last units; zero overselling or leaked reservations.',scope:'Real concurrency and lock ordering. Deterministic transaction-retry behavior is additionally covered in PostgreSQL component checks.'});
  const counts=query('core','SELECT (SELECT count(*) FROM orders)||\':\'||(SELECT count(*) FROM reservations)||\':\'||(SELECT count(*) FROM idempotency);');
  const valid=body('invalid-base',[{sku:pair[0].sku,quantity:1}]);
  const invalid=[
    ['site',`${prefix.replace('site-a','site-b')}/orders`,valid,[404]],
    ['unknown-sku',`${prefix}/orders`,{...valid,lines:[{sku:'SKU-404',quantity:1}]},[422]],
    ['zero-quantity',`${prefix}/orders`,{...valid,lines:[{sku:pair[0].sku,quantity:0}]},[422]],
    ['negative-quantity',`${prefix}/orders`,{...valid,lines:[{sku:pair[0].sku,quantity:-1}]},[422]],
    ['fractional-quantity',`${prefix}/orders`,{...valid,lines:[{sku:pair[0].sku,quantity:1.5}]},[422]],
    ['duplicate-lines',`${prefix}/orders`,{...valid,lines:[{sku:pair[0].sku,quantity:1},{sku:pair[0].sku,quantity:2}]},[422]],
    ['oversize',`${prefix}/orders`,{...valid,externalOrderRef:'x'.repeat(70000)},[413]],
    ['unknown-classification',`${prefix}/return-receipts`,{sourceSystem:'scenario-driver',externalReceiptRef:`${id}-invalid-return`,counts:{UNSUPPORTED:1}},[422]],
  ];
  const returnsBefore=query('returns','SELECT count(*) FROM receipts;');
  for(const [name,path,request,statuses] of invalid){
    const result=await api(path,{bearer,method:'POST',key:`${id}-invalid-${name}`,body:request});assert.ok(statuses.includes(result.status),`${name}: ${result.status}`);assert.ok(result.body?.code||result.body?.title);
    if(name==='oversize'){
      const response=await fetch(`http://localhost:8780${path}`,{method:'POST',headers:{Authorization:`Bearer ${bearer}`,'Content-Type':'application/json','Idempotency-Key':`${id}-oversize-wire`},body:JSON.stringify(request),signal:AbortSignal.timeout(8000)});
      assert.equal(response.status,413);assert.ok(response.headers.get('content-type')?.startsWith('application/problem+json'));const problem=await response.json();assert.equal(problem.code,'PAYLOAD_TOO_LARGE');assert.equal(problem.status,413);
    }
    evidence.invalid.push({name,status:result.status,code:result.body?.code??result.body?.title});
  }
  assert.equal(query('core','SELECT (SELECT count(*) FROM orders)||\':\'||(SELECT count(*) FROM reservations)||\':\'||(SELECT count(*) FROM idempotency);'),counts);
  assert.equal(query('returns','SELECT count(*) FROM receipts;'),returnsBefore);
  evidence.cases.push({id:'A10',status:'passed',name:'Eight invalid HTTP inputs return bounded problem responses without order, reservation, idempotency or receipt writes.'});
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');assert.equal(evidence.worldAfter.worldId,evidence.worldBefore.worldId);assert.equal(evidence.worldAfter.journalGeneration,evidence.worldBefore.journalGeneration);
  assert.equal(evidence.worldAfter.journalHighWater-evidence.worldBefore.journalHighWater,evidence.orders.reduce((n,o)=>n+o.order.movements.length,0));
}catch(error){failure=error;evidence.failure=error.message;}
finally{
  try{if(held)await controls({intakePaused:false,criticalStorage:false});}catch(error){evidence.cleanupFailure=error.message;failure??=error;}
  evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A01','A02','A03','A04','A05','A10'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:'node tools/scenario-driver/functional-acceptance.mjs'});release();
}
if(failure)throw failure;
console.log(`${id}: passed ${evidence.cases.length} functional HTTP checks. ${directory}`);
