import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, simulator, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
const runId = 'migration-reversal-' + Date.now(), prefix = '/api/v1/sites/site-a';
const evidence = { cases: [], faults: [], orders: [] }; let first, second, sessionId, lanes = [];
async function until(check, description, timeout = 120000) {
  const end = Date.now() + timeout; let nextLog = Date.now() + 30000;
  do { if (await check()) return; if (Date.now() >= nextLog) { console.log('Waiting: ' + description); nextLog += 30000; } await delay(200); } while (Date.now() < end);
  throw Error('Timed out: ' + description);
}
async function unblock() { for (const lane of lanes) assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: lane.blocked })).status, 200); lanes = []; }
async function order(label) {
  const ref = runId + '-' + label;
  const accepted = await api(prefix + '/orders', { method: 'POST', bearer: await token(), key: ref, body: { sourceSystem: 'scenario-driver', externalOrderRef: ref, storeId: 'store-08', priority: 5, lines: [{ sku: 'SKU-084', quantity: 1 }] } });
  assert.equal(accepted.status, 202); assert.match(accepted.body.id, /^[a-f0-9-]{36}$/);
  const actual = (await api(prefix + '/orders/' + accepted.body.id, { bearer: first.bearer() })).body; assert.equal(actual.movements.length, 1);
  const result = { label, id: actual.id, movementId: actual.movements[0].movementId }; assert.match(result.movementId, /^[a-f0-9-]{36}$/); evidence.orders.push(result); return result;
}
async function completed(item) {
  await until(async () => (await api(prefix + '/orders/' + item.id, { bearer: first.bearer() })).body.state === 'COMPLETED', item.label + ' completes from verified physical evidence');
  assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${item.movementId}';`), '1');
  assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${item.movementId}';`), '1');
}
async function migration() { const response = await api(prefix + '/migrations/' + sessionId, { bearer: first.bearer() }); assert.equal(response.status, 200); return response.body; }
async function fault(kind, movement, delayMillis = 0, count = 1) {
  const created = await simulator('/sim/v1/test-controls/faults', { kind, commandId: movement, count, delayMillis }); assert.equal(created.status, 200); evidence.faults.push({ kind, id: created.body.faultId }); return created.body.faultId;
}
try {
  provisionObservers();
  assert.equal(spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', 'adapter-api'], { encoding: 'utf8', windowsHide: true, timeout: 45000 }).status, 0);
  assert.equal(query('adapter', 'SELECT count(*) FROM migration_process_faults WHERE remaining=1;'), '0', 'Clear prior process-test faults before this separate business reversal.');
  const gates = (await api('/internal/v1/sites/site-a/test-controls', { target: 'adapter', bearer: await token() })).body;
  for (const name of ['workersPaused', 'dispatchPaused', 'criticalStorage']) assert.equal(gates[name], false);
  first = await humanSession('supervisor-a'); second = await humanSession('supervisor-a2');
  const subject = session => JSON.parse(Buffer.from(session.bearer().split('.')[1], 'base64url').toString()).sub;
  assert.notEqual(subject(first), subject(second)); evidence.supervisors = [subject(first), subject(second)];
  const route = (await api(prefix + '/zones', { bearer: first.bearer() })).body.find(item => item.zoneId === 'chilled');
  assert.equal(route.state, 'ACTIVE'); assert.equal(route.owner, 'execution-service'); evidence.beforeRoute = route;
  const prior = (await api(prefix + '/migrations', { bearer: first.bearer() })).body.items.find(item => item.zoneId === 'chilled' && item.phase === 'OBSERVING');
  if (prior) evidence.priorObservation = prior;
  lanes = (await simulator('/sim/v1/equipment')).body.lanes.filter(item => item.siteId === 'site-a' && item.zoneId === 'chilled'); assert.equal(lanes.length, 2); assert.ok(lanes.every(item => !item.blocked));
  for (const lane of lanes) assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: true })).status, 200);
  await until(async () => (await api(prefix + '/equipment', { bearer: first.bearer() })).body.lanes.filter(item => item.zoneId === 'chilled').every(item => item.blocked), 'adapter sees the selected lane hold');
  const allocated = await order('uncertain-old-owner');
  await until(() => query('adapter', `SELECT count(*) FROM movement_allocations WHERE movement_id='${allocated.movementId}' AND state='ASSIGNED' AND owner='execution-service';`) === '1', 'the original owner receives the allocation');
  await fault('HOLD_EXECUTING', allocated.movementId, 60000); await fault('LOST_RESPONSE', allocated.movementId, 8000); await unblock();
  let unknown;
  await until(async () => { const response = await api(prefix + '/commands/' + allocated.movementId, { bearer: first.bearer() }); unknown = response.body; return response.status === 200 && unknown.state === 'OUTCOME_UNKNOWN'; }, 'lost response leaves an actual unknown command');
  evidence.unknown = unknown;
  const gap = await fault('HISTORY_GAP', allocated.movementId, 0, 100);
  const request = { expectedVersion: route.version, targetOwner: 'legacy-core', reason: runId + ': two supervisors request a safe reversal while the original command outcome is uncertain.' };
  const replies = await Promise.all([first, second].map((session, index) => api(prefix + '/zones/chilled/migrations', { bearer: session.bearer(), method: 'POST', key: runId + '-concurrent-' + index, body: request })));
  assert.deepEqual(replies.map(reply => reply.status).sort(), [202, 409]);
  sessionId = replies.find(reply => reply.status === 202).body.sessionId; assert.match(sessionId, /^[a-f0-9-]{36}$/); evidence.sessionId = sessionId;
  evidence.cases.push({ candidate: 'A32', status: 'passed', name: 'two distinct real supervisor identities concurrently request the same route version; one session commits', statuses: replies.map(reply => reply.status), sessionId, actors: evidence.supervisors });
  await until(async () => (await migration()).blockers.items.some(item => item.movementId === allocated.movementId && ['OUTCOME_UNKNOWN', 'QUARANTINED'].includes(item.commandState)), 'the durable drain identifies the uncertain physical command');
  const pending = await order('pending-reversal');
  await until(() => query('adapter', `SELECT count(*) FROM movement_allocations WHERE movement_id='${pending.movementId}' AND state='PENDING' AND owner IS NULL AND epoch IS NULL;`) === '1', 'new reverse-session work remains unassigned');
  const heldAt = Date.now(); await delay(12000);
  const held = await migration(); assert.equal(held.phase, 'DRAINING'); assert.equal(held.targetEpoch, null); assert.ok(held.blockers.items.some(item => item.movementId === allocated.movementId)); evidence.held = held;
  const current = (await api(prefix + '/zones', { bearer: first.bearer() })).body.find(item => item.zoneId === 'chilled'); assert.equal(current.owner, route.owner); assert.equal(current.epoch, route.epoch);
  assert.equal(query('core', `SELECT count(*) FROM legacy_tasks WHERE movement_id='${pending.movementId}';`), '0');
  assert.equal(query('execution', `SELECT count(*) FROM execution_tasks WHERE movement_id='${pending.movementId}';`), '0');
  evidence.cases.push({ candidate: 'A33', status: 'passed', name: 'unknown/later quarantined command blocks reversal beyond the transport timeout; waiting grants no ownership authority', movementId: allocated.movementId, observedHoldMillis: Date.now() - heldAt, phase: held.phase, owner: current.owner, epoch: current.epoch });
  assert.equal((await simulator('/sim/v1/test-controls/faults/' + gap, undefined, 'scenario', 'DELETE')).status, 200);
  const investigation = (await api(prefix + '/commands/' + allocated.movementId, { bearer: first.bearer() })).body;
  assert.ok(['QUARANTINED', 'OUTCOME_UNKNOWN'].includes(investigation.state));
  const recovery = await api(prefix + '/commands/' + allocated.movementId + '/reconciliation', { bearer: first.bearer(), method: 'POST', key: runId + '-investigate', body: { expectedVersion: investigation.version, reason: 'The selected history fault is cleared. Read the original command in its unchanged physical world and retain the accepted identity.' } }); assert.equal(recovery.status, 200);
  await completed(allocated);
  await until(async () => (await migration()).phase === 'OBSERVING', 'old-owner completion and reconciled proof permit reverse epoch switch');
  await completed(pending);
  for (let index = 0; index < 9; index++) await completed(await order('reverse-sample-' + index));
  await until(async () => { const session = await migration(); if (session.lastError === 'OBSERVATION_LATENCY_TARGET_MISSED') throw Error('Reverse observation missed two seconds: ' + session.observation.dispatchP99Millis + ' ms; retain the failed sample.'); return session.phase === 'COMPLETED'; }, 'the reverse-owner observation sample satisfies its bound');
  const final = await migration(); evidence.completed = final; assert.equal(final.targetEpoch, route.epoch + 1); assert.equal(final.inventoryCount, final.verifiedCount);
  assert.equal(query('execution', `SELECT count(*) FROM execution_tasks WHERE movement_id='${allocated.movementId}' AND state='COMPLETED' AND epoch=${route.epoch};`), '1');
  assert.equal(query('core', `SELECT count(*) FROM legacy_tasks WHERE movement_id='${pending.movementId}' AND state='COMPLETED' AND epoch=${final.targetEpoch};`), '1');
  assert.equal(query('execution', `SELECT count(*) FROM execution_tasks WHERE movement_id='${pending.movementId}';`), '0');
  assert.equal(query('simulator', `SELECT count(*) FROM simulator_commands WHERE command_id='${allocated.movementId}';`), '1');
  assert.equal(query('adapter', `SELECT attempts FROM command_journal WHERE command_id='${allocated.movementId}';`), '1');
  if (prior) { const parent = (await api(prefix + '/migrations/' + prior.sessionId, { bearer: first.bearer() })).body; assert.equal(parent.phase, 'REVERSED'); assert.equal(final.reversesSessionId, parent.sessionId); evidence.reversedParent = parent; }
  evidence.cases.push({ candidate: 'A35', status: 'passed', name: 'original allocated work completes under execution ownership, pending work moves once to the reversed legacy epoch, and retained uncertain history is reconciled without redispatch', sessionId, checkpointHash: final.checkpointHash, epoch: final.targetEpoch, p99Millis: final.observation.dispatchP99Millis });
  console.log('Passed ' + evidence.cases.length + ' concurrency, uncertain-outcome and business-reversal checks. ' + saveEvidence(runId, evidence));
} catch (failure) { if (sessionId && first) try { evidence.currentSession = await migration(); } catch {} saveEvidence(runId, { ...evidence, failure: failure.message }); throw failure; }
finally {
  await unblock();
  for (const item of evidence.faults) try { await simulator('/sim/v1/test-controls/faults/' + item.id, undefined, 'scenario', 'DELETE'); } catch { console.error('Inspect the selected simulator fault ' + item.id + '; cleanup failed.'); }
  await first?.close(); await second?.close();
}
