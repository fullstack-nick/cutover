import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, simulator, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
export async function verifyStaleOwner({ runId, supervisor, zone = 'ambient' }) {
  assert.ok(['ambient', 'chilled'].includes(zone));
  const prefix = '/api/v1/sites/site-a';
  const routes = (await api(prefix + '/zones', { bearer: supervisor.bearer() })).body, route = routes.find(item => item.zoneId === zone);
  assert.equal(route.state, 'ACTIVE');
  const formerOwner = route.owner === 'legacy-core' ? 'execution-service' : 'legacy-core';
  const retained = JSON.parse(query('adapter', `SELECT jsonb_build_object('movementId',c.movement_id,'epoch',c.epoch,'allocationId',c.allocation_id,'payload',c.payload,'movement',a.movement) FROM command_journal c JOIN movement_allocations a USING(site_id,movement_id) WHERE a.zone_id='${zone}' AND c.state='COMPLETED' AND c.owner='${formerOwner}' ORDER BY c.completed_at DESC LIMIT 1;`));
  assert.match(retained.movementId, /^[a-f0-9-]{36}$/);
  const bearer = await token(formerOwner);
  const repeated = await api('/internal/v1/sites/site-a/commands/' + retained.movementId, { target: 'adapter', bearer, method: 'PUT', body: { allocationId: retained.allocationId, epoch: retained.epoch, laneId: retained.payload.laneId, movement: retained.movement } });
  assert.equal(repeated.status, 200); assert.equal(repeated.body.state, 'COMPLETED'); assert.equal(repeated.body.commandId, retained.movementId);
  const lanes = (await simulator('/sim/v1/equipment')).body.lanes.filter(item => item.siteId === 'site-a' && item.zoneId === zone);
  assert.equal(lanes.length, 2); assert.ok(lanes.every(item => !item.blocked));
  let movementId, orderId, refused;
  async function until(check, label) { const end = Date.now() + 90000; do { if (await check()) return; await delay(400); } while (Date.now() < end); throw Error('Timed out: ' + label); }
  try {
    for (const lane of lanes) assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: true })).status, 200);
    await until(async () => (await api(prefix + '/equipment', { bearer: supervisor.bearer() })).body.lanes.filter(item => item.zoneId === zone).every(item => item.blocked), 'adapter observes the controlled lane hold');
    const accepted = await api(prefix + '/orders', { method: 'POST', bearer: await token(), key: runId + '-stale-owner', body: { sourceSystem: 'scenario-driver', externalOrderRef: runId + '-stale-owner', storeId: 'store-06', priority: 5, lines: [{ sku: zone === 'ambient' ? 'SKU-077' : 'SKU-078', quantity: 1 }] } });
    assert.equal(accepted.status, 202); orderId = accepted.body.id; assert.match(orderId, /^[a-f0-9-]{36}$/);
    const order = (await api(prefix + '/orders/' + orderId, { bearer: supervisor.bearer() })).body; assert.equal(order.movements.length, 1); movementId = order.movements[0].movementId; assert.match(movementId, /^[a-f0-9-]{36}$/);
    await until(() => query('adapter', `SELECT count(*) FROM movement_allocations WHERE movement_id='${movementId}' AND state='ASSIGNED';`) === '1', 'new-owner allocation is retained while its lane is blocked');
    const allocation = (await api('/internal/v1/sites/site-a/allocations/' + movementId, { target: 'adapter', bearer })).body;
    assert.equal(allocation.owner, route.owner); assert.equal(allocation.epoch, route.epoch);
    refused = await api('/internal/v1/sites/site-a/commands/' + movementId, { target: 'adapter', bearer, method: 'PUT', body: { allocationId: allocation.allocationId, epoch: allocation.epoch, laneId: lanes[0].laneId, movement: allocation.movement } });
    assert.equal(refused.status, 409); assert.equal(refused.body.code, 'STALE_OWNER');
    assert.equal(query('adapter', `SELECT count(*) FROM command_journal WHERE movement_id='${movementId}';`), '0');
    assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movementId}';`), '0');
  } finally { for (const lane of lanes) assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: lane.blocked })).status, 200); }
  await until(async () => (await api(prefix + '/orders/' + orderId, { bearer: supervisor.bearer() })).body.state === 'COMPLETED', 'the authorized current owner completes the original movement');
  for (const movement of [retained.movementId, movementId]) {
    assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement}';`), '1');
    assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movement}';`), '1');
  }
  return { candidate: 'A30', name: 'real former-owner credentials return an existing terminal result and cannot dispatch a new-epoch allocation', status: 'passed', formerOwner, currentOwner: route.owner, epoch: route.epoch, priorMovement: retained.movementId, rejectedMovement: movementId, orderId, refusal: refused.body.code };
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const runId = 'migration-stale-owner-' + Date.now(); let supervisor;
  try {
    provisionObservers(); assert.equal(spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', 'adapter-api'], { encoding: 'utf8', windowsHide: true, timeout: 45000 }).status, 0);
    supervisor = await humanSession('supervisor-a');
    const result = await verifyStaleOwner({ runId, supervisor });
    console.log('Passed the current-epoch fencing check. ' + saveEvidence(runId, { cases: [result] }));
  } catch (failure) { saveEvidence(runId, { failure: failure.message }); throw failure; }
  finally { await supervisor?.close(); }
}
