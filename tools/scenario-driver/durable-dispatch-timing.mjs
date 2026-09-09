import assert from 'node:assert/strict';

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const percentile=values=>values.slice().sort((a,b)=>a-b)[Math.ceil(values.length*.99)-1];
const attribute=(span,key)=>span.attributes?.find(item=>item.key===key)?.value?.stringValue;
const commandSpans=body=>(body.batches??body.resourceSpans??[]).flatMap(batch=>{
  if(batch.resource?.attributes?.find(item=>item.key==='service.name')?.value?.stringValue!=='equipment-adapter')return [];
  return (batch.scopeSpans??[]).flatMap(scope=>scope.spans??[]);
}).filter(span=>span.name==='cutover.command.record'&&span.status?.code!=='STATUS_CODE_ERROR'&&span.status?.code!==2);
const matchingSpans=(movement,spans)=>{
  const recorded=Date.parse(movement.recordedAt);if(!Number.isFinite(recorded))return [];
  return spans.filter(span=>attribute(span,'cutover.resource_id')===movement.movementId
    &&/^\d+$/.test(span.endTimeUnixNano??'')&&BigInt(span.endTimeUnixNano)>=BigInt(recorded)*1000000n);
};

/** Trace storage may expose a trace before every movement's committed span is visible. */
export function hasCommandTimingProof(body,movements){
  const spans=commandSpans(body);return movements.length>0&&movements.every(movement=>matchingSpans(movement,spans).length>0);
}

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
    const expected=movements.filter(movement=>assigned.get(movement.movementId)===traceId);
    const body=await readTrace(traceId,expected);traces.set(traceId,commandSpans(body));
  }
  const rows=movements.map(movement=>{
    assert.match(movement.movementId,uuid);assert.ok(['warmup','measurement'].includes(movement.phase));
    const eligible=Date.parse(movement.eligibleAt),recorded=Date.parse(movement.recordedAt);
    assert.ok(Number.isFinite(eligible)&&Number.isFinite(recorded)&&recorded>=eligible);
    const traceId=assigned.get(movement.movementId);
    const spans=matchingSpans(movement,traces.get(traceId))
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
