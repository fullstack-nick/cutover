import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
const resume = process.argv.find(value => value.startsWith('--resume='))?.slice(9);
const reverseFailed = process.argv.find(value => value.startsWith('--reverse-failed-observation='))?.split('=')[1];
if (reverseFailed) { assert.match(reverseFailed, /^[a-f0-9-]{36}$/); assert.ok(!resume); }
if (resume) assert.match(resume, /^migration-crash-\d+$/);
const runId = resume ?? 'migration-crash-' + Date.now(), phases = ['DRAINING', 'RECONCILING', 'READY_TO_SWITCH', 'OBSERVING', 'COMPLETED'];
const prior = resume ? JSON.parse(readFileSync(resolve(root, '.local/evidence', resume, 'results.json'), 'utf8')) : undefined;
const outputRun = resume ? runId + '-resume-' + Date.now() : runId;
const evidence = prior ? { cases: [], faults: prior.faults, processExits: prior.processExits, samples: prior.samples, podUid: prior.podUid, restartsBefore: prior.restartsBefore, sessionId: prior.sessionId, reversesFailedObservation: prior.reversesFailedObservation, resumedFrom: resume, priorFailure: prior.failure } : { cases: [], faults: [], processExits: [], samples: [] };
const reversedObservation = reverseFailed ?? evidence.reversesFailedObservation?.sessionId;
const controls = '/internal/v1/sites/site-a/test-controls';
const prefix = '/api/v1/sites/site-a';
let supervisor, sessionId, podBefore;
function kube(args) {
  const result = spawnSync('kubectl', ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-apps', ...args], { encoding: 'utf8', windowsHide: true, timeout: 15000 });
  if (result.status !== 0) throw Error('Scoped Cutover process inspection failed.'); return result.stdout;
}
function pod() {
  const items = JSON.parse(kube(['get', 'pods', '-l', 'app.kubernetes.io/name=equipment-adapter', '-o', 'json'])).items;
  assert.equal(items.length, 1); assert.equal(items[0].metadata.labels['app.kubernetes.io/part-of'], 'cutover'); return items[0];
}
function inspect() {
  const current = pod(); assert.equal(current.metadata.uid, podBefore.metadata.uid, 'The same owned pod must restart its application process.');
  const state = current.status.containerStatuses[0], exit = state.lastState?.terminated;
  if (exit && exit.finishedAt !== evidence.baselineFinishedAt && Date.parse(exit.finishedAt) >= (evidence.earliestExit ?? 0) && !evidence.processExits.some(item => item.finishedAt === exit.finishedAt)) {
    assert.equal(exit.exitCode, 73); evidence.processExits.push({ restartCount: state.restartCount, exitCode: exit.exitCode, finishedAt: exit.finishedAt, reason: exit.reason });
    console.log('Observed adapter process exit 73; restart count ' + state.restartCount + '.');
  }
  return state;
}
async function currentSession() {
  let result;
  await until(async () => {
    try { const response = await api(prefix + '/migrations/' + sessionId, { bearer: supervisor.bearer() });
      if (response.status === 200) { result = response.body; return true; }
      assert.ok([502, 503, 504].includes(response.status), 'Session read must enforce its normal authorization and contract.');
    } catch (failure) { if (!(failure instanceof TypeError) && failure.name !== 'TimeoutError') throw failure; }
    return false;
  }, 'the restarted process serves its persisted session through the local proxy', 60000);
  return result;
}
function faultRows() {
  if (!evidence.faults.length) return [];
  return JSON.parse(query('adapter', "SELECT jsonb_agg(jsonb_build_object('faultId',fault_id,'phase',phase,'remaining',remaining,'firedAt',fired_at,'sessionId',fired_session_id) ORDER BY armed_at) FROM migration_process_faults WHERE fault_id IN (" + evidence.faults.map(item => "'" + item.faultId + "'").join(',') + ');'));
}
async function until(check, description, timeout = 600000) {
  const end = Date.now() + timeout; let nextLog = Date.now() + 30000;
  do { if (await check()) return; if (Date.now() >= nextLog) { console.log('Waiting: ' + description); nextLog += 30000; } await delay(800); } while (Date.now() < end);
  throw Error('Timed out: ' + description);
}
function forward() {
  const result = spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', 'adapter-api'], { encoding: 'utf8', windowsHide: true, timeout: 45000 });
  assert.equal(result.status, 0, 'Adapter forward must reconnect to the owned pod.');
}
try {
  provisionObservers(); forward(); supervisor = await humanSession('supervisor-a');
  const routes = (await api(prefix + '/zones', { bearer: supervisor.bearer() })).body;
  let route = routes.find(item => item.zoneId === 'chilled'); assert.equal(route.state, 'ACTIVE');
  assert.equal(query('adapter', "SELECT count(*) FROM movement_allocations WHERE zone_id='chilled' AND state IN ('PENDING','ASSIGNED');"), '0', 'This crash run starts with settled retained chilled work.');
  if (!resume && !reverseFailed) assert.equal(query('adapter', "SELECT count(*) FROM migration_sessions WHERE zone_id='chilled' AND phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING','REVERSING');"), '0');
  if (reverseFailed) {
    const parent = (await api(prefix + '/migrations/' + reverseFailed, { bearer: supervisor.bearer() })).body;
    assert.equal(parent.phase, 'OBSERVING'); assert.equal(parent.observation?.withinTwoSeconds, false, 'The explicit reversal must retain an actual failed sample.');
    assert.equal(parent.zoneId, 'chilled'); assert.equal(parent.targetOwner, route.owner); assert.equal(parent.targetEpoch, route.epoch);
    assert.equal(query('adapter', `SELECT migration_session_id FROM zone_routes WHERE site_id='site-a' AND zone_id='chilled';`), reverseFailed);
    evidence.reversesFailedObservation = parent;
  }
  const bearer = await token(), gates = (await api(controls, { target: 'adapter', bearer })).body;
  for (const field of ['workersPaused', 'dispatchPaused', 'criticalStorage']) assert.equal(gates[field], false);
  let request;
  if (resume) {
    sessionId = evidence.sessionId; assert.match(sessionId, /^[a-f0-9-]{36}$/);
    podBefore = pod(); assert.equal(podBefore.metadata.uid, evidence.podUid);
    const persisted = await currentSession(); assert.equal(persisted.phase, 'OBSERVING'); assert.notEqual(persisted.observation?.withinTwoSeconds, false, 'A failed immutable sample requires a separate reversal.');
    assert.equal(route.epoch, persisted.targetEpoch); assert.equal(route.owner, persisted.targetOwner);
    evidence.earliestExit = Math.floor(Date.parse(persisted.createdAt) / 1000) * 1000;
    evidence.processExits = evidence.processExits.filter(item => Date.parse(item.finishedAt) >= evidence.earliestExit);
    inspect(); route = { ...route, owner: persisted.sourceOwner, epoch: persisted.sourceEpoch };
    const currentFaults = (await api(controls + '/migration-faults', { target: 'adapter', bearer })).body;
    const selected = currentFaults.find(item => item.faultId === evidence.faults.find(fault => fault.phase === 'COMPLETED').faultId);
    if (!selected?.remaining && !selected?.firedAt) {
      const armed = await api(controls + '/migration-faults', { target: 'adapter', bearer, method: 'POST', key: outputRun + '-terminal', body: { expectedVersion: gates.version, phase: 'COMPLETED', sessionId, reason: 'Resume the original four-phase process check after its harness failed; scope the remaining terminal fault to the same session.' } });
      assert.equal(armed.status, 200); evidence.supersededTerminalFault = selected?.faultId;
      evidence.faults = evidence.faults.map(item => item.phase === 'COMPLETED' ? { phase: item.phase, faultId: armed.body.faultId } : item);
    }
    const rows = faultRows(); assert.equal(rows.filter(item => item.firedAt && item.sessionId === sessionId).length, 4);
    assert.equal(inspect().restartCount, evidence.restartsBefore + 4); assert.equal(evidence.processExits.length, 4);
  } else {
  let version = gates.version;
  for (const phase of phases) {
    const armed = await api(controls + '/migration-faults', { target: 'adapter', bearer, method: 'POST', key: runId + '-' + phase, body: { expectedVersion: version, phase, reason: runId + ': actual one-shot process restart after durable ' + phase + '.' } });
    assert.equal(armed.status, 200, JSON.stringify(armed)); version = armed.body.controlVersion; evidence.faults.push({ phase, faultId: armed.body.faultId });
  }
  podBefore = pod(); evidence.podUid = podBefore.metadata.uid; evidence.restartsBefore = podBefore.status.containerStatuses[0].restartCount;
  evidence.baselineFinishedAt = podBefore.status.containerStatuses[0].lastState?.terminated?.finishedAt;
  evidence.earliestExit = Math.floor(Date.now() / 1000) * 1000;
  const targetOwner = route.owner === 'legacy-core' ? 'execution-service' : 'legacy-core';
  request = { expectedVersion: route.version, targetOwner, reason: runId + ': preserve this session through every durable phase and actual process loss.' }; evidence.request = request;
  let accepted;
  try { accepted = await api(prefix + '/zones/chilled/migrations', { method: 'POST', bearer: supervisor.bearer(), key: runId, body: request }); evidence.initialStatus = accepted.status; }
  catch { evidence.initialStatus = 'transport interrupted by deliberate process halt'; }
  await until(() => {
    inspect(); const rows = faultRows();
    const fired = rows.filter(item => item.firedAt); evidence.faultResults = rows;
    if (fired.length) { sessionId = fired[0].sessionId; assert.match(sessionId, /^[a-f0-9-]{36}$/); evidence.sessionId = sessionId; }
    const status = inspect();
    return fired.length === 4 && status.restartCount >= evidence.restartsBefore + 4 && status.ready
      && Date.parse(status.state.running?.startedAt ?? '') > Math.max(...fired.map(item => Date.parse(item.firedAt)));
  }, 'four persisted pre-completion phases halt once and the same pod becomes ready');
  const current = await currentSession(); assert.equal(current.phase, 'OBSERVING');
  const repeated = await api(prefix + '/zones/chilled/migrations', { method: 'POST', bearer: supervisor.bearer(), key: runId, body: request }); assert.equal(repeated.status, 202); assert.equal(repeated.body.sessionId, sessionId);
  }
  assert.equal(query('adapter', `SELECT count(*) FROM migration_sessions WHERE session_id='${sessionId}';`), '1');
  for (let index = 0; index < 10; index++) {
    const reference = runId + '-sample-' + index;
    const order = await api(prefix + '/orders', { method: 'POST', bearer: await token(), key: reference, body: { sourceSystem: 'scenario-driver', externalOrderRef: reference, storeId: 'store-07', priority: 5, lines: [{ sku: 'SKU-082', quantity: 1 }] } });
    assert.equal(order.status, 202); assert.match(order.body.id, /^[a-f0-9-]{36}$/);
    await until(async () => { inspect(); return (await api(prefix + '/orders/' + order.body.id, { bearer: supervisor.bearer() })).body.state === 'COMPLETED'; }, 'sample ' + index + ' records its physical completion', 90000);
    const movementId = query('core', `SELECT movement_id FROM movement_intents WHERE order_id='${order.body.id}';`); assert.match(movementId, /^[a-f0-9-]{36}$/);
    assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movementId}';`), '1');
    assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movementId}';`), '1');
    if (!evidence.samples.some(item => item.movementId === movementId)) evidence.samples.push({ orderId: order.body.id, movementId });
  }
  await until(() => {
    const status = inspect(); evidence.faultResults = faultRows();
    const retained = JSON.parse(query('adapter', `SELECT jsonb_build_object('phase',phase,'error',last_error,'observation',observation) FROM migration_sessions WHERE session_id='${sessionId}';`));
    if (retained.error === 'OBSERVATION_LATENCY_TARGET_MISSED') {
      evidence.failedObservation = retained;
      throw Error('The measured observation p99 exceeded two seconds (' + retained.observation.dispatchP99Millis + ' ms). The session remains blocked; reverse ownership explicitly.');
    }
    return evidence.faultResults.every(item => item.firedAt && item.remaining === 0) && status.ready && status.restartCount >= evidence.restartsBefore + 5
      && Date.parse(status.state.running?.startedAt ?? '') > Math.max(...evidence.faultResults.map(item => Date.parse(item.firedAt)));
  }, 'the fifth terminal phase halts once and restarts');
  const final = await currentSession();
  assert.equal(final.phase, 'COMPLETED'); assert.equal(final.targetEpoch, route.epoch + 1); assert.equal(final.inventoryCount, final.verifiedCount); assert.ok(final.observation.withinTwoSeconds);
  const after = inspect(); evidence.restartsAfter = after.restartCount;
  assert.equal(after.restartCount - evidence.restartsBefore, 5); assert.equal(evidence.processExits.length, 5);
  assert.deepEqual(evidence.faultResults.map(item => item.phase), phases); assert.ok(evidence.faultResults.every(item => item.sessionId === sessionId));
  assert.equal(query('adapter', `SELECT count(*) FROM outbox WHERE event_type='ZoneOwnershipChanged.v1' AND envelope->'payload'->>'sessionId'='${sessionId}';`), '1');
  evidence.completed = final;
  if (reversedObservation) {
    assert.equal(final.reversesSessionId, reversedObservation);
    const parent = (await api(prefix + '/migrations/' + reversedObservation, { bearer: supervisor.bearer() })).body;
    assert.equal(parent.phase, 'REVERSED');
    assert.deepEqual(parent.observation, evidence.reversesFailedObservation.observation);
    assert.equal(query('adapter', "SELECT count(*) FROM migration_sessions WHERE site_id='site-a' AND zone_id='chilled' AND phase='REVERSING';"), '0');
    evidence.reversedParent = parent;
  }
  evidence.cases.push({ candidate: 'A31', status: 'passed', name: 'one retained session crosses five actual process exits after each durable migration phase with one epoch change and ten unique physical effects', phases, sessionId, podUid: evidence.podUid, checkpointHash: final.checkpointHash, dispatchP99Millis: final.observation.dispatchP99Millis });
  console.log('Verified all five durable phases through actual adapter process restarts. ' + saveEvidence(outputRun, evidence));
} catch (failure) { saveEvidence(outputRun, { ...evidence, failure: failure.message }); throw failure; }
finally {
  if (evidence.faults.length) {
    try {
      forward(); const bearer = await token(); const actual = (await api(controls + '/migration-faults', { target: 'adapter', bearer })).body;
      for (const owned of evidence.faults) {
        const fault = actual.find(item => item.faultId === owned.faultId);
        if (fault?.remaining) assert.equal((await api(controls + '/migration-faults/' + owned.faultId + '/clear', { target: 'adapter', bearer, method: 'POST', key: runId + '-clear-' + owned.phase, body: { expectedVersion: fault.version, reason: 'Clear this run\'s unfired process fault without changing the retained migration session.' } })).status, 200);
      }
    } catch { console.error('Inspect this run\'s retained migration-fault records before submitting more work; fault cleanup could not finish.'); }
  }
  await supervisor?.close();
}
