import assert from 'node:assert/strict';
import test from 'node:test';
import {durableDispatchTiming} from './durable-dispatch-timing.mjs';

const id='00000000-0000-4000-8000-000000000001',peer='00000000-0000-4000-8000-000000000002';
const traceId='a'.repeat(32),traceparent=`00-${traceId}-${'b'.repeat(16)}-01`;
const movement=(movementId=id)=>({movementId,phase:'measurement',eligibleAt:'2026-01-01T00:00:00.000Z',recordedAt:'2026-01-01T00:00:01.500Z'});
const span=(movementId,endMillis,code)=>({name:'cutover.command.record',spanId:movementId.slice(-16),endTimeUnixNano:String(BigInt(Date.parse('2026-01-01T00:00:00Z'))*1000000n+BigInt(endMillis)*1000000n),status:{code},attributes:[{key:'cutover.resource_id',value:{stringValue:movementId}}]});
const trace=spans=>({batches:[{resource:{attributes:[{key:'service.name',value:{stringValue:'equipment-adapter'}}]},scopeSpans:[{spans}]}]});
const contexts=ids=>ids.map(movementId=>({movementId,traceparent}));

test('a pre-commit row timestamp cannot turn a late transaction return into a pass',async()=>{
  const result=await durableDispatchTiming([movement()],contexts([id]),async()=>trace([span(id,2250)]));
  assert.equal(result.withinTwoSecondsPercent,0);assert.equal(result.p99Millis,2250);assert.equal(result.journalTimestampToReturnP99Millis,750);
});
test('each movement needs its own successful span, even when a product trace is shared',async()=>{
  let reads=0;
  const result=await durableDispatchTiming([movement(),movement(peer)],contexts([id,peer]),async()=>{reads++;return trace([span(id,1800),span(peer,1600,'STATUS_CODE_ERROR'),span(peer,2300)]);});
  assert.equal(reads,1);assert.equal(result.withinTwoSecondsPercent,50);assert.equal(result.p99Millis,2300);
  await assert.rejects(durableDispatchTiming([movement(),movement(peer)],contexts([id,peer]),async()=>trace([span(id,1800),span(peer,1600,2)])),/missing evidence cannot qualify/);
});
test('missing or duplicated assignment coverage cannot qualify a workload',async()=>{
  await assert.rejects(durableDispatchTiming([movement(),movement(peer)],contexts([id,id]),async()=>trace([])),/Exactly one original assignment/);
  await assert.rejects(durableDispatchTiming([movement()],contexts([id]),async()=>trace([span(id,1400)])),/missing evidence cannot qualify/);
});
test('submillisecond transaction return is rounded conservatively at the deadline',async()=>{
  const exact=span(id,2000);exact.endTimeUnixNano=String(BigInt(exact.endTimeUnixNano)+1n);
  const result=await durableDispatchTiming([movement()],contexts([id]),async()=>trace([exact]));
  assert.equal(result.p99Millis,2001);assert.equal(result.withinTwoSecondsPercent,0);
});
