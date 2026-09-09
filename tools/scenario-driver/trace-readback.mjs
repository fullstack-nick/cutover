import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';
import {hasCommandTimingProof} from './durable-dispatch-timing.mjs';

/** Bounded evidence retrieval after offering ends; never substitutes observation time for span time. */
export function traceReadback({request=async(traceId,timeout)=>fetch(`http://127.0.0.1:8782/api/traces/${traceId}`,{signal:AbortSignal.timeout(timeout)}),now=Date.now,pause=delay}={}){
  const deadline=now()+300000,observations=[];
  return {observations,async read(traceId,movements){
    assert.match(traceId,/^[a-f0-9]{32}$/);assert.ok(movements.length>0&&movements.length<=5000);
    const traceDeadline=Math.min(deadline,now()+60000);
    for(;;){
      const remaining=traceDeadline-now();assert.ok(remaining>0,`Timing proof unavailable within the bounded readback window for ${traceId}.`);
      const response=await request(traceId,Math.min(10000,remaining));
      assert.ok([200,404].includes(response.status),`Trace readback failed with HTTP ${response.status}.`);
      const body=response.status===200?await response.json():null;
      const complete=body!==null&&hasCommandTimingProof(body,movements);
      observations.push({traceId,at:new Date(now()).toISOString(),status:response.status,complete});
      if(complete){assert.ok(now()<=traceDeadline,`Timing proof arrived after the bounded readback window for ${traceId}.`);return body;}
      await pause(Math.min(1000,Math.max(1,traceDeadline-now())));
    }
  }};
}
