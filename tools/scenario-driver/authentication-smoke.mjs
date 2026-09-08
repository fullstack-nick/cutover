import assert from 'node:assert/strict';
import { createPublicKey,verify,randomUUID,randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import https from 'node:https';
process.env.CUTOVER_PROFILE='demo';
const { api,token,query,provisionObservers,saveEvidence,credentials,simulator }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { publishFrom }=await import('./broker-probe.mjs');
const { root,target,maintenanceLock,privateDirectory,until,simulatorRead,writeJson }=await import('../../scripts/lib/local-platform.mjs');
const id=`authentication-${Date.now()}`,directory=resolve(root,'.local/evidence',id),platform=target('demo');
privateDirectory(directory);const release=maintenanceLock(id),evidence={startedAt:new Date().toISOString(),cases:[],http:[]};
let operator,failure;
const decode=jwt=>JSON.parse(Buffer.from(jwt.split('.')[1],'base64url'));
const encoded=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
const paths=['/orders','/tasks','/commands','/execution-tasks','/return-receipts','/return-tasks'];
async function rejected(label,bearer,status=401,site='site-a'){
  for(const path of paths){const result=await api(`/api/v1/sites/${site}${path}`,{bearer});assert.equal(result.status,status,`${label} at ${path}`);evidence.http.push({label,path,site,status:result.status});}
}
function tls(pfx,passphrase){
  return new Promise(resolveResult=>{
    const req=https.get('https://localhost:18784/sim/v1/equipment',{agent:false,ca:readFileSync(resolve(root,'.local/secrets/equipment/ca.pem')),pfx,passphrase,timeout:8000},response=>{response.resume();resolveResult({httpStatus:response.statusCode});});
    req.on('error',error=>resolveResult({tlsError:error.code??'TLS_FAILURE'}));req.on('timeout',()=>req.destroy(Error('TLS timeout')));
  });
}
try{
  platform.verify();provisionObservers();
  for(const owner of ['core','returns'])assert.equal(query(owner,'SELECT active_requests FROM admission;'),'0');
  assert.equal(query('adapter',"SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');"),'0');
  evidence.worldBefore=await simulatorRead('/sim/v1/equipment');
  operator=await humanSession('operator-a');const original=operator.bearer(),claims=decode(original),parts=original.split('.');
  for(const path of paths)assert.equal((await api(`/api/v1/sites/site-a${path}`,{bearer:original})).status,200);
  await rejected('Missing authentication',undefined);await rejected('Authorized identity outside its site',original,404,'site-b');
  for(const [label,changes]of[['Changed site membership',{sites:['site-b']}],['Changed issuer',{iss:claims.iss+'/untrusted'}],['Changed audience',{aud:['unrelated-service']}],['Changed expiry',{exp:1}]])await rejected(label,`${parts[0]}.${encoded({...claims,...changes})}.${parts[2]}`);
  const header=JSON.parse(Buffer.from(parts[0],'base64url'));
  await rejected('Unknown key ID',`${encoded({...header,kid:randomUUID()})}.${parts[1]}.${parts[2]}`);
  await rejected('Unsigned token',`${encoded({alg:'none',typ:'JWT'})}.${parts[1]}.`);
  const signature=Buffer.from(parts[2],'base64url');signature[0]^=1;await rejected('Invalid signature',`${parts[0]}.${parts[1]}.${signature.toString('base64url')}`);
  await until(()=>operator.idToken(),'the real PKCE response supplies its ID token',10000);const idToken=operator.idToken(),idClaims=decode(idToken),idParts=idToken.split('.'),idHeader=JSON.parse(Buffer.from(idParts[0],'base64url'));
  assert.equal(idClaims.iss,claims.iss);assert.equal(idHeader.alg,'RS256');assert.equal(idClaims.aud,'operations-console');
  const jwksResponse=await fetch('http://localhost:8780/identity/realms/cutover/protocol/openid-connect/certs',{signal:AbortSignal.timeout(10000)});assert.equal(jwksResponse.status,200);
  const jwk=(await jwksResponse.json()).keys.find(key=>key.kid===idHeader.kid);assert.ok(jwk);assert.equal(verify('RSA-SHA256',Buffer.from(`${idParts[0]}.${idParts[1]}`),createPublicKey({key:jwk,format:'jwk'}),Buffer.from(idParts[2],'base64url')),true);
  await rejected('Valid Keycloak signature with non-API audience',idToken);
  evidence.cases.push({id:'A40/A41',status:'passed',name:'Actual service APIs enforce site scope, reject tampered/unsigned/unknown-key tokens, and reject a cryptographically verified local ID token with the wrong API audience',scope:'The separate production-decoder component checks isolate trusted-signature issuer and time-bound validation; claim tampering alone does not prove those validators.'});

  const retained=JSON.parse(query('adapter',"SELECT envelope FROM outbox WHERE event_type='MovementCompleted.v1' AND published_at IS NOT NULL ORDER BY created_at DESC LIMIT 1;"));assert.ok(retained);
  const forged={...retained,eventId:randomUUID()},movement=retained.aggregateId;
  const before=query('core',`SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement}';`),journal=query('adapter',`SELECT state,version FROM command_journal WHERE movement_id='${movement}';`);
  evidence.brokerDenied=await publishFrom('legacy-core',forged,{exchange:'cutover.equipment-adapter.v1'});assert.equal(evidence.brokerDenied.result,'PUBLISH_FAILED');
  evidence.forgedSource=await publishFrom('legacy-core',forged);assert.equal(evidence.forgedSource.result,'CONFIRMED');
  await until(()=>query('adapter',`SELECT count(*) FROM delivery_quarantine WHERE transport_message_id='${forged.eventId}' AND reason='UNTRUSTED_EVENT_SOURCE';`)==='1','the wrong authenticated event source is quarantined before application',30000);
  assert.equal(query('adapter',`SELECT count(*) FROM inbox WHERE event_id='${forged.eventId}';`),'0');
  assert.equal(query('core',`SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement}';`),before);assert.equal(query('adapter',`SELECT state,version FROM command_journal WHERE movement_id='${movement}';`),journal);
  evidence.permissions=JSON.parse(platform.kube(['-n','cutover-platform','exec','rabbitmq-0','--','rabbitmqctl','--silent','list_user_permissions','cutover_core','--formatter=json']));
  const scope=evidence.permissions.find(item=>item.vhost==='cutover');assert.ok(scope);assert.equal(new RegExp(scope.write).test('cutover.equipment-adapter.v1'),false);assert.equal(new RegExp(scope.write).test('cutover.legacy-core.v1'),true);
  evidence.cases.push({id:'A42',status:'passed',name:'Ordinary core publisher cannot use the adapter exchange; spoofed adapter completion on its own exchange is quarantined with no inbox, journal or inventory change',forgedEventId:forged.eventId});

  evidence.noCertificate=await tls();assert.ok(evidence.noCertificate.tlsError);
  const certificate=resolve(directory,'untrusted-client.p12'),passphrase=randomBytes(24).toString('hex');
  const created=spawnSync('keytool',['-genkeypair','-alias','untrusted-client','-keyalg','RSA','-keysize','2048','-validity','1','-dname','CN=cutover-untrusted-verification','-ext','EKU=clientAuth','-storetype','PKCS12','-keystore',certificate,'-storepass:env','CUTOVER_AUTH_TEST_PASSWORD','-noprompt'],{windowsHide:true,encoding:'utf8',timeout:30000,env:{...process.env,CUTOVER_AUTH_TEST_PASSWORD:passphrase}});assert.equal(created.status,0,'Create only an isolated ephemeral verification certificate.');
  evidence.untrustedCertificate=await tls(readFileSync(certificate),passphrase);assert.ok(evidence.untrustedCertificate.tlsError);
  const trusted=await simulator('/sim/v1/equipment',undefined,'adapter');assert.equal(trusted.status,200);assert.equal(trusted.body.worldId,evidence.worldBefore.worldId);
  evidence.cases.push({id:'A43',status:'passed',name:'Actual simulator TLS rejects absent and independently self-signed client certificates, and accepts the retained adapter certificate'});

  // Preserve this exact originally valid token in memory; allow real time to expire it without changing identity settings or any system clock.
  const expiryMillis=(claims.exp+16)*1000;assert.ok(expiryMillis-Date.now()<360000,'The normal local token lifetime must bound this check.');
  console.log(`${id}: signature, audience, source and TLS checks passed; waiting for the original real token to expire.`);
  await until(()=>Date.now()>=expiryMillis,'the original token expires beyond the configured 15-second clock skew',360000);
  await rejected('Originally valid signature after actual expiry',original);
  for(const path of paths)assert.equal((await api(`/api/v1/sites/site-a${path}`,{bearer:operator.bearer()})).status,200,'The automatically renewed PKCE session remains valid.');
  evidence.cases.push({id:'A41',status:'passed',name:'The originally accepted real Keycloak access token is rejected after its actual expiry plus allowed skew; the refreshed session still succeeds',issuedAt:new Date(claims.iat*1000).toISOString(),expiredAt:new Date(claims.exp*1000).toISOString()});
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');for(const key of ['worldId','journalGeneration','journalHighWater'])assert.equal(evidence.worldAfter[key],evidence.worldBefore[key]);
}catch(error){failure=error;evidence.failure=error.message;}
finally{
  if(operator)await operator.close();release();evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);writeJson(resolve(directory,'manifest.json'),{runId:id,scenarioIds:['A40','A41','A42','A43'],startedAt:evidence.startedAt,endedAt:evidence.endedAt,reproduce:'node tools/scenario-driver/authentication-smoke.mjs',tokenStorage:'All bearer/ID tokens and temporary certificate passwords stay in memory and are omitted from evidence.'});
}
if(failure)throw failure;console.log(`${id}: authentication and source-boundary checks passed. ${directory}`);
