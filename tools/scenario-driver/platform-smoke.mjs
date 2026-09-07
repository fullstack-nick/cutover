import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { token, api, query, simulator, provisionObservers, roleCannotCreate, saveEvidence } = await import('./client.mjs');
const runId = `platform-${Date.now()}`;
const cases = [];
try {
  provisionObservers();
  const bearer = await token();
  const equipment = await api('/api/v1/sites/site-a/equipment', { bearer });
  assert.equal(equipment.status, 200); assert.equal(equipment.body.stale, false); assert.equal(equipment.body.worldMismatch, false);
  cases.push({ name: 'kind adapter reaches the independent simulator over mutual TLS', status: 'passed', worldId: equipment.body.worldId });
  const order = await api('/api/v1/sites/site-a/orders', { method: 'POST', bearer, key: runId, body: { sourceSystem: 'scenario-driver', externalOrderRef: runId, storeId: 'store-02', priority: 3, lines: [{ sku: 'SKU-007', quantity: 1 }, { sku: 'SKU-008', quantity: 1 }] } });
  assert.equal(order.status, 202, JSON.stringify(order));
  let result; const deadline = Date.now() + 30000;
  do {
    result = await api(`/api/v1/sites/site-a/orders/${order.body.id}`, { bearer });
    if (result.body.state === 'COMPLETED') break;
    await delay(300);
  } while (Date.now() < deadline);
  assert.equal(result.body.state, 'COMPLETED', JSON.stringify(result));
  for (const movement of result.body.movements) {
    assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement.movementId}';`), '1');
    assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movement.movementId}';`), '1');
  }
  cases.push({ name: 'authenticated ambient/chilled order through kind services with one effect per movement', status: 'passed', orderId: order.body.id });
  assert.equal((await api(`/api/v1/sites/site-b/orders/${order.body.id}`, { bearer })).status, 404);
  cases.push({ name: 'site restriction enforced by cluster APIs', status: 'passed' });
  for (const owner of ['core', 'adapter', 'keycloak']) assert.ok(roleCannotCreate(owner));
  cases.push({ name: 'restored runtime roles remain unable to perform DDL', status: 'passed' });
  assert.equal((await simulator('/sim/v1/equipment')).body.worldId, equipment.body.worldId);
  console.log(`Passed ${cases.length} platform smoke checks. ${saveEvidence(runId, { cases })}`);
} catch (error) { saveEvidence(runId, { cases, failure: error.message }); throw error; }
