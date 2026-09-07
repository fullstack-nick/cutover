import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { api, token, simulator, query, provisionObservers, roleCannotCreate, restartDevelopmentServices, saveEvidence } from './client.mjs';

provisionObservers();
const bearer = await token();
const claims = JSON.parse(Buffer.from(bearer.split('.')[1], 'base64url'));
assert.equal(claims.iss, 'http://localhost:8780/identity/realms/cutover');
assert.ok(claims.sites.includes('site-a'));
const prefix = `baseline-${Date.now()}`;
const cases = [];
async function run(name, work) { await work(); cases.push({ name, status: 'passed' }); console.log(`PASS ${name}`); }
async function completed(id, state = 'COMPLETED') {
  const deadline = Date.now() + 30000;
  let result;
  do {
    result = await api(`/api/v1/sites/site-a/orders/${id}`, { bearer });
    if (result.status === 200 && result.body.state === state) return result.body;
    await delay(250);
  } while (Date.now() < deadline);
  throw new Error(`Order did not reach ${state}: ${JSON.stringify(result)}`);
}
const payload = { sourceSystem: 'scenario-driver', externalOrderRef: prefix, storeId: 'store-01', priority: 5, lines: [{ sku: 'SKU-001', quantity: 2 }, { sku: 'SKU-002', quantity: 3 }] };
let id;
try {
  await run('real Keycloak token, durable HTTP intake, and immutable request key', async () => {
    const first = await api('/api/v1/sites/site-a/orders', { method: 'POST', key: prefix, body: payload, bearer });
    assert.equal(first.status, 202, JSON.stringify(first)); id = first.body.id;
    const repeat = await api('/api/v1/sites/site-a/orders', { method: 'POST', key: prefix, body: payload, bearer });
    assert.deepEqual(repeat.body, first.body);
    const conflict = await api('/api/v1/sites/site-a/orders', { method: 'POST', key: prefix, body: { ...payload, priority: 6 }, bearer });
    assert.equal(conflict.status, 409);
  });
  await run('authenticated core-to-adapter HTTP and adapter-to-simulator mutual TLS', async () => {
    const result = await completed(id); assert.equal(result.movements.length, 2);
    for (const movement of result.movements) {
      const command = await api(`/api/v1/sites/site-a/commands/${movement.movementId}`, { bearer });
      assert.equal(command.body.state, 'COMPLETED'); assert.ok(command.body.evidence.executionSequence > 0);
      assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement.movementId}';`), '1');
      assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movement.movementId}';`), '1');
    }
  });
  await run('site boundary, missing authentication, and tampered signature', async () => {
    assert.equal((await api(`/api/v1/sites/site-b/orders/${id}`, { bearer })).status, 404);
    assert.equal((await api(`/api/v1/sites/site-a/orders/${id}`)).status, 401);
    const parts = bearer.split('.'); parts[1] = Buffer.from(JSON.stringify({ ...claims, sites: ['site-b'] })).toString('base64url');
    assert.equal((await api(`/api/v1/sites/site-b/orders/${id}`, { bearer: parts.join('.') })).status, 401);
  });
  await run('ordinary runtime database roles cannot create tables', async () => {
    for (const owner of ['core', 'adapter', 'simulator', 'keycloak']) assert.ok(roleCannotCreate(owner), `${owner} unexpectedly has DDL permission`);
  });
  await run('equipment completion survives a real lost HTTP response', async () => {
    const configured = await simulator('/sim/v1/test-controls/faults', { kind: 'LOST_RESPONSE', count: 1, delayMillis: 5000 });
    assert.equal(configured.status, 200, JSON.stringify(configured));
    const reference = `${prefix}-lost`;
    const accepted = await api('/api/v1/sites/site-a/orders', { method: 'POST', key: reference, bearer, body: { ...payload, externalOrderRef: reference, lines: [{ sku: 'SKU-003', quantity: 1 }] } });
    assert.equal(accepted.status, 202, JSON.stringify(accepted));
    const result = await completed(accepted.body.id); const movement = result.movements[0].movementId;
    assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movement}';`), '1');
    assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement}';`), '1');
    assert.equal(query('adapter', `SELECT count(*) FROM outbox WHERE aggregate_id='${movement}' AND event_type='CommandOutcomeUnknown.v1';`), '1');
  });
  await run('blocked accepted work survives all three application process restarts', async () => {
    const before = await simulator('/sim/v1/equipment');
    const lanes = before.body.lanes.filter(lane => lane.siteId === 'site-a' && lane.zoneId === 'ambient');
    assert.equal(lanes.length, 2);
    let order;
    try {
      for (const lane of lanes) assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: true })).status, 200);
      const reference = `${prefix}-restart`;
      const accepted = await api('/api/v1/sites/site-a/orders', { method: 'POST', key: reference, bearer, body: { ...payload, externalOrderRef: reference, lines: [{ sku: 'SKU-005', quantity: 1 }] } });
      assert.equal(accepted.status, 202); order = accepted.body.id;
      await restartDevelopmentServices(['legacy-core', 'equipment-adapter', 'equipment-simulator']);
      const after = await simulator('/sim/v1/equipment');
      assert.equal(after.body.worldId, before.body.worldId);
      assert.equal(after.body.journalGeneration, before.body.journalGeneration);
      assert.equal(query('core', `SELECT state FROM orders WHERE order_id='${order}';`), 'RESERVED');
      assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id IN (SELECT movement_id FROM movement_intents WHERE order_id='${order}');`), '0');
    } finally {
      for (const lane of lanes) await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: lane.blocked });
    }
    const result = await completed(order); const movement = result.movements[0].movementId;
    assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movement}';`), '1');
    assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement}';`), '1');
  });
} catch (error) {
  saveEvidence(prefix, { scope: 'development baseline HTTP checks; not the full acceptance matrix', cases, failure: error.message });
  throw error;
}
console.log(`Evidence: ${saveEvidence(prefix, { scope: 'development baseline HTTP checks; not the full acceptance matrix', cases, orderId: id })}`);
