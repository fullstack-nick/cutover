import assert from 'node:assert/strict';

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const percentile=values=>values.slice().sort((a,b)=>a-b)[Math.ceil(values.length*.99)-1];
const attribute=(span,key)=>span.attributes?.find(item=>item.key===key)?.value?.stringValue;

/** Verification only: the HTTP journal span ends after its database transaction returns. */
export async function durableDispatchTiming(movements,contexts,readTrace) {
  assert.ok(movements.length>0&&movements.length<=5000,'Use a bounded, nonempty workload.');
  const ids=new Set(movements.map(m=>m.movementId));assert.equal(ids.size,movements.length);
  assert.equal(contexts.length,movements.length);
  const assigned=new Map();
  for(const context of contexts){
    assert.ok(ids.has(context.movementId)&&!assigned.has(context.movementId),'Exactly one original assignment context is required per movement.');
    assert.match(context.traceparent,/^00-[a-f0-9]{32}-[a-f0-9]{16}-[a-f0-9]{2}$/);
    assigned.set(context.movementId,context.traceparent.split('-')[1]);
  }
  const traces=new Map();
  for(const traceId of new Set(assigned.values())){
    const body=await readTrace(traceId);
    const spans=(body.batches??body.resourceSpans??[]).flatMap(batch=>{
      if(batch.resource?.attributes?.find(item=>item.key==='service.name')?.value?.stringValue!=='equipment-adapter')return [];
      return (batch.scopeSpans??[]).flatMap(scope=>scope.spans??[]);
    });
    traces.set(traceId,spans.filter(span=>span.name==='cutover.command.record'
      &&span.status?.code!=='STATUS_CODE_ERROR'&&span.status?.code!==2));
  }
  const rows=movements.map(movement=>{
    assert.match(movement.movementId,uuid);assert.ok(['warmup','measurement'].includes(movement.phase));
    const eligible=Date.parse(movement.eligibleAt),recorded=Date.parse(movement.recordedAt);
    assert.ok(Number.isFinite(eligible)&&Number.isFinite(recorded)&&recorded>=eligible);
    const traceId=assigned.get(movement.movementId);
    const spans=traces.get(traceId).filter(span=>attribute(span,'cutover.resource_id')===movement.movementId
      &&/^\d+$/.test(span.endTimeUnixNano??'')&&BigInt(span.endTimeUnixNano)>=BigInt(recorded)*1000000n)
      .sort((a,b)=>BigInt(a.endTimeUnixNano)<BigInt(b.endTimeUnixNano)?-1:1);
    assert.ok(spans.length,`No successful post-record transaction span for ${movement.movementId}; missing evidence cannot qualify latency.`);
    const span=spans[0],endMillis=Number((BigInt(span.endTimeUnixNano)+999999n)/1000000n);
    return {movementId:movement.movementId,phase:movement.phase,traceId,spanId:span.spanId,spanEndUnixNano:span.endTimeUnixNano,
      transactionReturnUpperMillis:endMillis-eligible,journalTimestampToReturnUpperMillis:endMillis-recorded};
  });
  const measured=rows.filter(row=>row.phase==='measurement');assert.ok(measured.length>0);
  return {basis:'Successful adapter HTTP command-journal domain span ending after transaction return; end rounded up and original eligibility rounded down to milliseconds.',
    movements:rows.length,measuredMovements:measured.length,traces:traces.size,
    withinTwoSecondsPercent:100*measured.filter(row=>row.transactionReturnUpperMillis<=2000).length/measured.length,
    p99Millis:percentile(measured.map(row=>row.transactionReturnUpperMillis)),
    journalTimestampToReturnP99Millis:percentile(measured.map(row=>row.journalTimestampToReturnUpperMillis)),rows};
}
