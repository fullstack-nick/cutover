import assert from 'node:assert/strict';
import {resolve} from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const {token,query,provisionObservers,saveEvidence}=await import('./client.mjs');
const {humanSession}=await import('./human-session.mjs');
const {root,target,privateDirectory,maintenanceLock,writeJson}=await import('../../scripts/lib/local-platform.mjs');
const id=`problem-responses-${Date.now()}`,directory=resolve(root,'.local/evidence',id),prefix='/api/v1/sites/site-a';
privateDirectory(directory);const release=maintenanceLock(id),evidence={startedAt:new Date().toISOString(),cases:[]};let session,forward,failure;
const counts=()=>query('core',"SELECT (SELECT count(*) FROM orders)||':'||(SELECT count(*) FROM reservations)||':'||(SELECT count(*) FROM idempotency);");
async function check(name,origin,path,{status,code,method='GET',bearer,body,key}){
  const response=await fetch(origin+path,{method,headers:{Accept:'application/problem+json',...(bearer?{Authorization:`Bearer ${bearer}`}:{ }),...(body?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},body,signal:AbortSignal.timeout(10000)});
  assert.equal(response.status,status,name);assert.ok(response.headers.get('content-type')?.startsWith('application/problem+json'),`${name}: the wire response must be problem JSON`);
  const problem=await response.json();assert.equal(problem.status,status);assert.equal(problem.code,code);assert.equal(typeof problem.type,'string');assert.equal(typeof problem.title,'string');
  const challenge=response.headers.get('www-authenticate');if(status===401)assert.ok(challenge?.startsWith('Bearer'));
  assert.ok(!JSON.stringify(problem).includes('synthetic-invalid-token'));evidence.cases.push({status:'passed',name,httpStatus:status,code,contentType:response.headers.get('content-type'),challenge});
}
try{
  const platform=target('demo');platform.verify();provisionObservers();const before=counts(),consoleOrigin='http://localhost:8780';
  await check('Missing bearer token',consoleOrigin,`${prefix}/orders`,{status:401,code:'AUTHENTICATION_REQUIRED'});
  await check('Invalid bearer token',consoleOrigin,`${prefix}/orders`,{status:401,code:'AUTHENTICATION_REQUIRED',bearer:'synthetic-invalid-token'});
  await check('Malformed bearer credential',consoleOrigin,`${prefix}/orders`,{status:401,code:'AUTHENTICATION_REQUIRED',bearer:'two credentials'});
  session=await humanSession('operator-a');forward=await platform.forward('legacy-core');
  await check('Operator denied at the platform administration boundary',forward.origin,'/internal/v1/platform/storage',{status:403,code:'ACCESS_DENIED',bearer:session.bearer()});
  const bearer=await token(),request={sourceSystem:'scenario-driver',externalOrderRef:id,storeId:'store-01',priority:5,lines:[{sku:'SKU-001',quantity:1}]};
  await check('Missing required request key',consoleOrigin,`${prefix}/orders`,{status:400,code:'MALFORMED_REQUEST',method:'POST',bearer,body:JSON.stringify(request)});
  await check('Malformed JSON request body',consoleOrigin,`${prefix}/orders`,{status:400,code:'MALFORMED_JSON',method:'POST',bearer,key:`${id}-json`,body:'{"lines":'});
  await check('Oversized request rejected by the local proxy',consoleOrigin,`${prefix}/orders`,{status:413,code:'PAYLOAD_TOO_LARGE',method:'POST',bearer,key:`${id}-size`,body:JSON.stringify({...request,externalOrderRef:'x'.repeat(70000)})});
  assert.equal(counts(),before);evidence.ownerRowsUnchanged=true;
}catch(error){failure=error;evidence.failure=error.message;}
finally{await session?.close();forward?.close();release();evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A10','A39','A41'],scope:'Raw response format and unchanged owner rows; separate authentication evidence establishes full signature/site/source/TLS behavior.',reproduce:'node tools/scenario-driver/problem-responses-smoke.mjs'});}
if(failure)throw failure;console.log(`${id}: passed seven raw problem-response checks.`);
