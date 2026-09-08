import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, provisionObservers, saveEvidence, credentials }=await import('./client.mjs');
const { root, target, until, call, privateDirectory, writeJson, simulatorRead, maintenanceLock }=await import('../../scripts/lib/local-platform.mjs');
const runId=`causal-trace-${Date.now()}`, directory=resolve(root,'.local/evidence',runId), cases=[];
privateDirectory(directory);
const release=maintenanceLock(runId), prefix='/api/v1/sites/site-a';
const evidence={cases,startedAt:new Date().toISOString(),traceBoundary:'Application spans and authenticated equipment HTTP client calls; independent simulator execution is verified from its durable ledger.'};
async function submit(resource,body,bearer) {
  const traceId=randomBytes(16).toString('hex'), parent=randomBytes(8).toString('hex');
  const response=await fetch(`http://localhost:8780${prefix}/${resource}`,{method:'POST',signal:AbortSignal.timeout(8000),headers:{Authorization:`Bearer ${bearer}`,'Content-Type':'application/json','Idempotency-Key':body.externalOrderRef??body.externalReceiptRef,traceparent:`00-${traceId}-${parent}-01`},body:JSON.stringify(body)});
  assert.equal(response.status,202);
  return {traceId,parent,...await response.json()};
}
function spansOf(trace) {
  return (trace.batches??trace.resourceSpans??[]).flatMap(batch=>{
    const service=batch.resource?.attributes?.find(item=>item.key==='service.name')?.value?.stringValue;
    return (batch.scopeSpans??batch.instrumentationLibrarySpans??[]).flatMap(scope=>(scope.spans??[]).map(span=>({service,...span})));
  });
}
async function traceOf(id,required) {
  let actual,spans;
  await until(async()=>{
    const response=await fetch(`http://127.0.0.1:8782/api/traces/${id}`,{signal:AbortSignal.timeout(6000)});
    if(response.status===404)return false;
    assert.equal(response.status,200);actual=await response.json();spans=spansOf(actual);
    return required.every(name=>spans.some(span=>span.name===name));
  },`domain trace ${id} contains ${required.join(', ')}`,60000);
  const serialized=JSON.stringify(actual);
  for(const value of Object.values(credentials.passwords))if(typeof value==='string' && value.length>=12)assert.ok(!serialized.includes(value),'A credential reached telemetry.');
  writeJson(resolve(directory,`${id}.json`),actual);
  return {traceId:id,services:[...new Set(spans.map(span=>span.service))].sort(),spanCount:spans.length,domainSpans:spans.filter(span=>span.name.startsWith('cutover.')).map(({name,service,spanId,parentSpanId,attributes})=>({name,service,spanId,parentSpanId,attributes}))};
}
try {
  target('demo').verify();provisionObservers();
  call('pwsh',['-NoProfile','-NonInteractive','-File',resolve(root,'scripts/forward.ps1'),'-Profile','demo','-Target','tempo','-Action','Start']);
  const bearer=await token();evidence.worldBefore=await simulatorRead('/sim/v1/equipment');
  const order=await submit('orders',{sourceSystem:'scenario-driver',externalOrderRef:`${runId}-order`,storeId:'store-07',priority:5,lines:[{sku:'SKU-091',quantity:1},{sku:'SKU-092',quantity:1}]},bearer);
  const receipt=await submit('return-receipts',{sourceSystem:'scenario-driver',externalReceiptRef:`${runId}-receipt`,counts:{REUSABLE:1,NEEDS_CLEANING:1,DAMAGED:1}},bearer);
  evidence.intake={order,receipt};let outbound,returned;
  await until(async()=>{
    outbound=(await api(`${prefix}/orders/${order.id}`,{bearer})).body;
    returned=(await api(`${prefix}/return-receipts/${receipt.id}`,{bearer})).body;
    return outbound.state==='COMPLETED' && returned.state==='COMPLETED';
  },'both traced products complete',120000);
  const ids=[...outbound.movements,...returned.movements].map(item=>item.movementId);
  assert.equal(new Set(ids).size,5);ids.forEach(id=>assert.match(id,/^[a-f0-9-]{36}$/));const selected=ids.map(id=>`'${id}'`).join(',');
  evidence.adapterEvents=JSON.parse(query('adapter',`SELECT jsonb_agg(jsonb_build_object('eventId',event_id,'movementId',aggregate_id,'eventType',event_type,'traceparent',envelope->>'traceparent','causationId',envelope->>'causationId','correlationId',envelope->>'correlationId') ORDER BY aggregate_id,aggregate_version) FROM outbox WHERE aggregate_type='movement' AND aggregate_id IN (${selected});`));
  for(const event of evidence.adapterEvents){
    assert.match(event.causationId,/^[a-f0-9-]{36}$/);
    assert.ok([order.id,receipt.id].includes(event.correlationId));
    assert.ok(event.traceparent.includes(event.correlationId===order.id?order.traceId:receipt.traceId));
  }
  for(const [owner,table,count] of [['core','inventory_ledger',2],['returns','sorting_ledger',3],['simulator','execution_ledger',5]])assert.equal(Number(query(owner,`SELECT count(*) FROM ${table} WHERE movement_id IN (${selected});`)),count);
  cases.push({status:'passed',name:'Two products retain causal envelope IDs and exactly five physical effects',orderId:order.id,receiptId:receipt.id,movementIds:ids});
  evidence.orderTrace=await traceOf(order.traceId,['cutover.order.accept','cutover.event.record','cutover.event.apply','cutover.movement.allocate','cutover.task.dispatch','cutover.command.record','cutover.command.investigate','cutover.inventory.complete']);
  evidence.receiptTrace=await traceOf(receipt.traceId,['cutover.receipt.register','cutover.event.apply','cutover.return.coordinate','cutover.command.investigate','cutover.sorting.complete']);
  assert.ok(evidence.orderTrace.services.includes('legacy-core') && evidence.orderTrace.services.includes('equipment-adapter') && evidence.orderTrace.services.includes('execution-service'));
  assert.ok(evidence.receiptTrace.services.includes('returns-service') && evidence.receiptTrace.services.includes('equipment-adapter'));
  cases.push({status:'passed',name:'Original HTTP trace IDs join durable messaging, scheduling, equipment observation and single business completion',orderTraceId:order.traceId,receiptTraceId:receipt.traceId});
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');
  assert.equal(evidence.worldAfter.worldId,evidence.worldBefore.worldId);assert.equal(evidence.worldAfter.journalGeneration,evidence.worldBefore.journalGeneration);
  evidence.endedAt=new Date().toISOString();console.log(`Passed ${cases.length} causal trace checks. ${saveEvidence(runId,evidence)}`);
} catch(error){saveEvidence(runId,{...evidence,failure:error.message});throw error;}
finally{release();}
