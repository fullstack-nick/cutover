import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { mkdirSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, simulator, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
const { verifyStaleOwner } = await import('./migration-stale-owner-smoke.mjs');
const runId = 'zone-migration-' + Date.now(), evidence = { cases: [], orders: [] };
const prefix = '/api/v1/sites/site-a', controls = '/internal/v1/sites/site-a/test-controls';
let supervisor, operator, lanes = [], sessionId;
const directory = resolve(root, '.local/evidence', runId); mkdirSync(directory, { recursive: true });
function uuid(value) { assert.match(value, /^[a-f0-9-]{36}$/); return value; }
async function until(check, description, timeout = 120000) {
  const end = Date.now() + timeout; let nextLog = Date.now() + 30000;
  do { if (await check()) return; if (Date.now() >= nextLog) { console.log('Waiting: ' + description); nextLog += 30000; } await delay(400); } while (Date.now() < end);
  throw Error('Timed out: ' + description);
}
async function createOrder(label, sku = 'SKU-071') {
  const reference = runId + '-' + label, bearer = await token();
  const accepted = await api(prefix + '/orders', { method: 'POST', bearer, key: reference, body: { sourceSystem: 'scenario-driver', externalOrderRef: reference, storeId: 'store-06', priority: 5, lines: [{ sku, quantity: 1 }] } });
  assert.equal(accepted.status, 202, JSON.stringify(accepted));
  const order = (await api(prefix + '/orders/' + uuid(accepted.body.id), { bearer })).body;
  assert.equal(order.movements.length, 1, 'The recorded fixture must reserve one movement.');
  const item = { label, id: uuid(order.id), movementId: uuid(order.movements[0].movementId) }; evidence.orders.push(item); return item;
}
async function complete(order) {
  await until(async () => (await api(prefix + '/orders/' + order.id, { bearer: supervisor.bearer() })).body.state === 'COMPLETED', 'order ' + order.label + ' completes');
  for (const [owner, table] of [['core', 'inventory_ledger'], ['simulator', 'execution_ledger']]) assert.equal(query(owner, `SELECT count(*) FROM ${table} WHERE movement_id='${order.movementId}';`), '1');
}
async function migration() { const response = await api(prefix + '/migrations/' + sessionId, { bearer: supervisor.bearer() }); assert.equal(response.status, 200); return response.body; }
async function unblock() { for (const lane of lanes) assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: lane.blocked })).status, 200); lanes = []; }
try {
  provisionObservers();
  assert.equal(spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', 'adapter-api'], { encoding: 'utf8', windowsHide: true, timeout: 45000 }).status, 0);
  const gates = await api(controls, { target: 'adapter', bearer: await token() }); assert.equal(gates.status, 200);
  for (const flag of ['workersPaused', 'dispatchPaused', 'criticalStorage']) assert.equal(gates.body[flag], false);
  assert.equal(query('adapter', "SELECT count(*) FROM migration_sessions WHERE phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING','REVERSING');"), '0', 'Settle prior sessions before this bounded run.');
  supervisor = await humanSession('supervisor-a'); operator = await humanSession('operator-a');
  const pages = [supervisor.page, operator.page], pageErrors = [];
  for (const page of pages) { page.on('pageerror', error => pageErrors.push(error.message)); await page.getByRole('button', { name: 'Migrations', exact: true }).click(); }
  await supervisor.page.getByRole('heading', { name: 'One zone. One dispatch owner.', exact: true }).waitFor();
  await until(async () => await supervisor.page.locator('.route-card').count() === 2, 'both observed route cards');
  assert.equal(await operator.page.getByRole('button', { name: 'Confirm start migration', exact: true }).count(), 0);
  writeFileSync(resolve(directory, 'console-before.txt'), await supervisor.page.locator('main').ariaSnapshot());
  const routes = (await api(prefix + '/zones', { bearer: supervisor.bearer() })).body;
  const ambient = routes.find(route => route.zoneId === 'ambient'), chilled = routes.find(route => route.zoneId === 'chilled');
  assert.equal(ambient.owner, 'legacy-core'); assert.equal(ambient.state, 'ACTIVE');
  evidence.routesBefore = routes;
  const reason = runId + ': verify an ambient drain while chilled work continues.';
  const request = { expectedVersion: ambient.version, targetOwner: 'execution-service', reason };
  assert.equal((await api(prefix + '/zones/ambient/migrations', { method: 'POST', bearer: operator.bearer(), key: runId + '-denied', body: request })).status, 403);
  assert.equal((await api(prefix.replace('site-a', 'site-b') + '/zones/ambient/migrations', { method: 'POST', bearer: supervisor.bearer(), key: runId + '-wrong-site', body: request })).status, 404);
  assert.equal((await api(controls + '/migration-faults', { target: 'adapter', bearer: supervisor.bearer() })).status, 403);
  evidence.cases.push({ candidate: 'A36/A37', name: 'real operator console is read-only; migration mutation, site and test-control identities are enforced', status: 'passed' });

  lanes = (await simulator('/sim/v1/equipment')).body.lanes.filter(lane => lane.siteId === 'site-a' && lane.zoneId === 'ambient');
  assert.equal(lanes.length, 2); assert.ok(lanes.every(lane => !lane.blocked));
  for (const lane of lanes) assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: true })).status, 200);
  await until(async () => (await api(prefix + '/equipment', { bearer: supervisor.bearer() })).body.lanes.filter(lane => lane.zoneId === 'ambient').every(lane => lane.blocked), 'adapter observes both blocked ambient lanes');
  const allocated = await createOrder('allocated');
  await until(() => query('adapter', `SELECT count(*) FROM movement_allocations WHERE movement_id='${allocated.movementId}' AND state='ASSIGNED' AND owner='legacy-core';`) === '1', 'legacy-owned allocation waits without a lane');
  assert.equal(query('adapter', `SELECT count(*) FROM command_journal WHERE movement_id='${allocated.movementId}';`), '0');
  const card = supervisor.page.locator('.route-card').filter({ has: supervisor.page.getByRole('heading', { name: 'Ambient', exact: true }) });
  await card.getByRole('textbox', { name: 'Reason', exact: true }).fill(reason);
  const responsePromise = supervisor.page.waitForResponse(response => response.url().endsWith(prefix + '/zones/ambient/migrations') && response.request().method() === 'POST');
  await card.getByRole('button', { name: 'Confirm start migration', exact: true }).click();
  const createdResponse = await responsePromise; assert.equal(createdResponse.status(), 202);
  const created = await createdResponse.json(); sessionId = uuid(created.sessionId); evidence.sessionId = sessionId;
  const key = createdResponse.request().headers()['idempotency-key'];
  const retry = await api(prefix + '/zones/ambient/migrations', { method: 'POST', bearer: supervisor.bearer(), key, body: request });
  assert.equal(retry.status, 202); assert.equal(retry.body.sessionId, sessionId);
  assert.equal((await api(prefix + '/zones/ambient/migrations', { method: 'POST', bearer: supervisor.bearer(), key, body: { ...request, reason: reason + ' changed' } })).status, 409);
  await until(async () => (await migration()).blockers.items.some(item => item.movementId === allocated.movementId), 'drain identifies the exact unfinished allocation');
  const pending = await createOrder('pending', 'SKU-073');
  await until(() => query('adapter', `SELECT count(*) FROM movement_allocations WHERE movement_id='${pending.movementId}' AND state='PENDING' AND owner IS NULL AND epoch IS NULL;`) === '1', 'new ambient movement stays unassigned during drain');
  assert.equal(query('core', `SELECT count(*) FROM legacy_tasks WHERE movement_id='${pending.movementId}';`), '0');
  assert.equal(query('execution', `SELECT count(*) FROM execution_tasks WHERE movement_id='${pending.movementId}';`), '0');
  const independent = await createOrder('chilled-flow', 'SKU-072'); await complete(independent);
  const held = await migration(); assert.equal(held.phase, 'DRAINING'); assert.equal(held.targetEpoch, null); evidence.blocked = held;
  await supervisor.page.locator('#migration-' + sessionId).waitFor();
  await supervisor.page.screenshot({ path: resolve(directory, 'migration-drain.png'), fullPage: true });
  await unblock(); await complete(allocated);
  await until(async () => (await migration()).phase === 'OBSERVING', 'verified inventory permits an atomic owner/epoch switch');
  await complete(pending);
  for (let index = 0; index < 9; index++) await complete(await createOrder('sample-' + index, 'SKU-075'));
  await until(async () => { const observed = await migration(); if (observed.lastError === 'OBSERVATION_LATENCY_TARGET_MISSED') throw Error('The live migration sample missed its measured two-second dispatch target: ' + observed.observation.dispatchP99Millis + ' ms.'); return observed.phase === 'COMPLETED'; }, 'ten real new-owner movements satisfy the observation gate');
  const final = await migration(); evidence.completed = final;
  assert.equal(final.targetEpoch, ambient.epoch + 1); assert.equal(final.inventoryCount, final.verifiedCount); assert.equal(final.blockers.count, 0); assert.equal(final.observation.sampleCount, 10); assert.ok(final.observation.dispatchP99Millis <= 2000);
  assert.equal(final.observation.timingBasis, 'SIMULATOR_ACCEPTANCE_UPPER_BOUND');
  const afterRoutes = (await api(prefix + '/zones', { bearer: supervisor.bearer() })).body; evidence.routesAfter = afterRoutes;
  assert.deepEqual(afterRoutes.find(route => route.zoneId === 'chilled'), chilled);
  assert.equal(query('adapter', `SELECT count(*) FROM outbox WHERE event_type='ZoneOwnershipChanged.v1' AND envelope->'payload'->>'sessionId'='${sessionId}';`), '1');
  assert.equal(query('adapter', `SELECT count(*) FROM movement_allocations WHERE zone_id='ambient' AND owner='legacy-core' AND state='ASSIGNED';`), '0');
  assert.equal(query('execution', `SELECT count(*) FROM execution_tasks WHERE movement_id='${pending.movementId}' AND state='COMPLETED' AND epoch=${final.targetEpoch};`), '1');
  assert.equal(query('core', `SELECT count(*) FROM legacy_tasks WHERE movement_id='${pending.movementId}';`), '0');
  evidence.cases.push({ candidate: 'A29', name: 'supervisor console starts a finite ambient drain; chilled work continues; pending intents become new-owner tasks once', status: 'passed', sessionId, checkpointHash: final.checkpointHash, inventoryCount: final.inventoryCount, dispatchP99Millis: final.observation.dispatchP99Millis });

  evidence.cases.push(await verifyStaleOwner({ runId, supervisor }));
  await supervisor.page.getByRole('button', { name: 'Refresh routes', exact: true }).click();
  await supervisor.page.screenshot({ path: resolve(directory, 'migration-completed.png'), fullPage: true });
  writeFileSync(resolve(directory, 'console-completed.txt'), await supervisor.page.locator('main').ariaSnapshot());
  assert.deepEqual(pageErrors, []); evidence.consoleErrors = pageErrors;
  console.log('Passed ' + evidence.cases.length + ' zone migration process checks. ' + saveEvidence(runId, evidence));
} catch (failure) {
  if (sessionId && supervisor) try { evidence.currentSession = await migration(); } catch {}
  saveEvidence(runId, { ...evidence, failure: failure.message }); throw failure;
} finally {
  await unblock();
  await supervisor?.close(); await operator?.close();
}
