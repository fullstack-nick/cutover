import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
const { publishFrom } = await import('./broker-probe.mjs');
const runId = `storage-repair-${Date.now()}`, cases = [], controlPath = '/internal/v1/sites/site-a/test-controls';
let supervisor, operator, administrator, adapterPaused = false, coreCritical = false;
async function until(check, description, timeout = 110000) {
  const deadline = Date.now() + timeout;
  do { if (await check()) return; await delay(500); } while (Date.now() < deadline);
  throw new Error(`Timed out: ${description}`);
}
async function control(target, field, value) {
  const bearer = await token(), current = await api(controlPath, { target, bearer }); assert.equal(current.status, 200);
  const changed = await api(controlPath, { target, bearer, method: 'POST', key: `${runId}-${target}-${current.body.version}`, body: { expectedVersion: current.body.version, [field]: value, reason: `Set ${field} to ${value} for the isolated storage recovery fixture.` } });
  assert.equal(changed.status, 200);
  if (target === 'core') coreCritical = value; else adapterPaused = value;
}
function event(aggregateId, version, type) {
  return { eventId: randomUUID(), eventType: type, schemaVersion: 1, occurredAt: new Date().toISOString(), siteId: 'site-a', source: 'legacy-core', aggregateType: 'order', aggregateId, aggregateVersion: version, correlationId: aggregateId, causationId: null, traceparent: null, payload: { probe: runId } };
}
try {
  provisionObservers();
  for (const target of ['core-api', 'adapter-api']) assert.equal(spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', target], { encoding: 'utf8', windowsHide: true, timeout: 45000 }).status, 0);
  for (const target of ['core', 'adapter']) {
    const current = await api(controlPath, { target, bearer: await token() }); assert.equal(current.status, 200);
    for (const flag of ['workersPaused', 'criticalStorage', 'dispatchPaused']) assert.equal(current.body[flag], false, `The initial ${target} ${flag} gate must be open.`);
  }
  supervisor = await humanSession('supervisor-a'); operator = await humanSession('operator-a'); administrator = await humanSession('platform-admin');
  const storage = await api('/internal/v1/platform/storage', { target: 'core', bearer: administrator.bearer() }); assert.equal(storage.status, 200);
  assert.ok(storage.body.databaseBytes > 0); assert.ok(storage.body.retainedOutboxBytes > 0); assert.equal(storage.body.replayHorizonDays, 7);
  assert.equal((await api('/internal/v1/platform/storage', { target: 'core', bearer: operator.bearer() })).status, 403);
  assert.equal((await api('/internal/v1/platform/storage', { target: 'core', bearer: supervisor.bearer() })).status, 403);
  assert.equal((await api(`/internal/v1/sites/site-a/allocations/${randomUUID()}`, { target: 'adapter', bearer: administrator.bearer() })).status, 403);
  assert.equal((await api(controlPath, { target: 'core', bearer: supervisor.bearer() })).status, 403);
  cases.push({ candidate: 'A26', name: 'owner storage diagnostics are readable with the platform role', status: 'passed', storage: storage.body, limitation: 'This checks logical diagnostics; physical volume exhaustion is a separate required experiment.' });

  await control('adapter', 'dispatchPaused', true);
  const body = { sourceSystem: 'scenario-driver', externalOrderRef: `${runId}-accepted`, storeId: 'store-07', priority: 5, lines: [{ sku: 'SKU-057', quantity: 2 }] };
  const accepted = await api('/api/v1/sites/site-a/orders', { method: 'POST', body, key: body.externalOrderRef, bearer: await token() }); assert.equal(accepted.status, 202);
  const order = (await api(accepted.body.statusUrl, { bearer: supervisor.bearer() })).body; assert.equal(order.movements.length, 1);
  const movementId = order.movements[0].movementId;
  await control('core', 'criticalStorage', true); await control('adapter', 'dispatchPaused', false);
  await until(() => query('core', `SELECT last_error FROM legacy_tasks WHERE movement_id='${movementId}';`) === 'DURABILITY_PAUSED', 'the core observes the critical-storage dispatch barrier');
  assert.equal(query('adapter', `SELECT count(*) FROM command_journal WHERE movement_id='${movementId}';`), '0');
  assert.equal(query('core', `SELECT state FROM reservations WHERE reservation_id='${movementId}';`), 'RESERVED');
  const blocked = await fetch('http://localhost:8780/api/v1/sites/site-a/orders', { method: 'POST', headers: { Authorization: `Bearer ${await token()}`, 'Content-Type': 'application/json', 'Idempotency-Key': `${runId}-rejected` }, body: JSON.stringify({ ...body, externalOrderRef: `${runId}-rejected` }), signal: AbortSignal.timeout(8000) });
  assert.equal(blocked.status, 503); assert.ok(Number(blocked.headers.get('Retry-After')) >= 1);
  await control('core', 'criticalStorage', false);
  await until(async () => (await api(accepted.body.statusUrl, { bearer: supervisor.bearer() })).body.state === 'COMPLETED', 'accepted work resumes after the critical gate clears');
  assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movementId}';`), '1');
  assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movementId}';`), '1');
  cases.push({ candidate: 'A26', name: 'controlled critical gate refuses intake and dispatch, retains accepted stock and resumes one effect', status: 'passed', orderId: order.id, movementId, fault: 'explicit critical-storage control; no disk was filled' });

  const aggregate = randomUUID(), predecessor = event(aggregate, 1, 'OrderAccepted.v1'), gap = event(aggregate, 2, 'OrderProgressed.v1');
  assert.equal((await publishFrom('legacy-core', gap)).result, 'CONFIRMED');
  await until(() => query('adapter', `SELECT state FROM inbox WHERE event_id='${gap.eventId}';`) === 'QUARANTINED', 'the missing predecessor exhausts the bounded local retry budget');
  assert.equal(query('adapter', `SELECT attempts,deliveries FROM inbox WHERE event_id='${gap.eventId}';`), '6|1');
  assert.equal((await publishFrom('legacy-core', predecessor)).result, 'CONFIRMED');
  await until(() => query('adapter', `SELECT state FROM inbox WHERE event_id='${predecessor.eventId}';`) === 'APPLIED', 'the real missing predecessor reaches its receiving owner');
  const version = Number(query('adapter', `SELECT version FROM inbox WHERE event_id='${gap.eventId}';`));
  const action = `/internal/v1/sites/site-a/messaging/inbox/${gap.eventId}/replay`, key = `${runId}-repair`;
  const request = { expectedVersion: version, reason: 'The retained source predecessor has now arrived and applied; resume this original event without changing its identity.' };
  assert.equal((await api(action, { target: 'adapter', method: 'POST', body: request, key, bearer: operator.bearer() })).status, 403);
  assert.equal((await api(action.replace('/site-a/', '/site-b/'), { target: 'adapter', method: 'POST', body: request, key, bearer: supervisor.bearer() })).status, 404);
  const repaired = await api(action, { target: 'adapter', method: 'POST', body: request, key, bearer: supervisor.bearer() }); assert.equal(repaired.status, 200);
  assert.deepEqual((await api(action, { target: 'adapter', method: 'POST', body: request, key, bearer: supervisor.bearer() })).body, repaired.body);
  await until(() => query('adapter', `SELECT state FROM inbox WHERE event_id='${gap.eventId}';`) === 'APPLIED', 'the original quarantined event applies after a real predecessor correction');
  assert.equal(query('adapter', `SELECT count(*) FROM audit WHERE action='recover-inbox' AND resource_id='${gap.eventId}';`), '1');
  cases.push({ candidate: 'A09/A17/A39/A40', name: 'sequence poison exhausts once, then audited replay applies the original event after predecessor repair', status: 'passed', eventId: gap.eventId, predecessorId: predecessor.eventId, attemptsBeforeRepair: 6, deliveriesBeforeRepair: 1 });

  const malformed = `{"probe":"${runId}"`, sha = createHash('sha256').update(malformed).digest('hex');
  const raw = event(randomUUID(), 1, 'MalformedProbe.v1');
  assert.equal((await publishFrom('legacy-core', raw, { rawBody: malformed })).result, 'CONFIRMED');
  await until(() => query('adapter', `SELECT count(*) FROM delivery_quarantine WHERE body_hash='${sha}' AND site_id IS NULL;`) === '1', 'unparseable original bytes are durably isolated');
  const diagnostics = await api('/internal/v1/platform/untrusted-deliveries', { target: 'adapter', bearer: administrator.bearer() }); assert.equal(diagnostics.status, 200);
  const retained = diagnostics.body.find(item => item.bodySha256 === sha); assert.ok(retained); assert.equal(retained.payloadBytes, Buffer.byteLength(malformed));
  for (const field of ['rawBody', 'payload', 'transportMessageId', 'source', 'siteId']) assert.equal(Object.hasOwn(retained, field), false);
  const siteView = await api('/internal/v1/sites/site-a/messaging/quarantine', { target: 'adapter', bearer: supervisor.bearer() }); assert.equal(siteView.status, 200); assert.ok(siteView.body.every(item => item.deliveryId !== retained.deliveryId));
  const effectsBefore = query('core', 'SELECT count(*) FROM inventory_ledger;');
  const physicalBefore = query('simulator', 'SELECT count(*) FROM execution_ledger;');
  const forged = await publishFrom('legacy-core', event(randomUUID(), 1, 'MovementCompleted.v1'), { exchange: 'cutover.equipment-adapter.v1' }); assert.equal(forged.result, 'PUBLISH_FAILED');
  assert.equal(query('core', 'SELECT count(*) FROM inventory_ledger;'), effectsBefore);
  assert.equal(query('simulator', 'SELECT count(*) FROM execution_ledger;'), physicalBefore);
  cases.push({ candidate: 'A17/A40/A42', name: 'untrusted raw diagnostics hide business claims and core broker credentials cannot forge adapter publications', status: 'passed', deliveryId: retained.deliveryId, bodySha256: sha });
  console.log(`Passed ${cases.length} storage and repair process checks. ${saveEvidence(runId, { cases })}`);
} catch (error) { saveEvidence(runId, { cases, failure: error.message }); throw error; }
finally {
  if (coreCritical) await control('core', 'criticalStorage', false);
  if (adapterPaused) await control('adapter', 'dispatchPaused', false);
  await supervisor?.close(); await operator?.close(); await administrator?.close();
}
