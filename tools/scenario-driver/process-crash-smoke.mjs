import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const runId = `process-crash-${Date.now()}`;
const cases = [], armed = [];
const path = '/internal/v1/sites/site-a/test-controls';
function kube(args) {
  const result = spawnSync('kubectl', ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-apps', ...args], { encoding: 'utf8', windowsHide: true, timeout: 60000 });
  if (result.status !== 0) throw new Error('The scoped Cutover process inspection failed.');
  return result.stdout;
}
function pod() {
  const pods = JSON.parse(kube(['get', 'pods', '-l', 'app.kubernetes.io/name=legacy-core', '-o', 'json'])).items;
  assert.equal(pods.length, 1); assert.equal(pods[0].metadata.labels['app.kubernetes.io/part-of'], 'cutover'); return pods[0];
}
function forward(target) {
  const result = spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', target], { encoding: 'utf8', windowsHide: true, timeout: 45000 });
  if (result.status !== 0) throw new Error(`The Cutover ${target} forward could not reconnect.`);
}
async function until(check, description, timeout = 120000) {
  const deadline = Date.now() + timeout;
  do { if (await check()) return; await delay(500); } while (Date.now() < deadline);
  throw new Error(`Timed out: ${description}`);
}
try {
  provisionObservers(); forward('console'); forward('core-api');
  for (const [candidate, checkpoint, eventType, sku] of [
    ['A11', 'AFTER_BUSINESS_COMMIT', 'OrderAccepted.v1', 'SKU-041'],
    ['A12', 'AFTER_BROKER_CONFIRM', 'MovementRequested.v1', 'SKU-042'],
    ['A13', 'AFTER_EFFECT_BEFORE_ACK', 'MovementCompleted.v1', 'SKU-043'],
  ]) {
    const bearer = await token();
    await until(() => Number(query('core', 'SELECT count(*) FROM outbox WHERE published_at IS NULL;')) === 0, 'previous core outbox settles');
    const before = pod();
    const controls = await api(path, { target: 'core', bearer }); assert.equal(controls.status, 200);
    const fault = await api(`${path}/faults`, { target: 'core', method: 'POST', bearer, key: `${runId}-${candidate}`, body: { expectedVersion: controls.body.version, checkpoint, eventType, reason: `Verify ${candidate}: durable one-shot process loss at ${checkpoint}.` } });
    assert.equal(fault.status, 200); armed.push(fault.body.faultId);
    const reference = `${runId}-${candidate}`;
    let acceptanceStatus;
    try {
      acceptanceStatus = (await api('/api/v1/sites/site-a/orders', { method: 'POST', bearer, key: reference, body: { sourceSystem: 'scenario-driver', externalOrderRef: reference, storeId: 'store-04', priority: 5, lines: [{ sku, quantity: 1 }] } })).status;
    } catch { acceptanceStatus = 'transport interrupted by deliberate process halt'; }
    await until(() => query('core', `SELECT count(*) FROM process_faults WHERE fault_id='${fault.body.faultId}' AND fired_at IS NOT NULL AND remaining=0;`) === '1', `${checkpoint} fires`);
    await until(() => {
      const current = pod(); return current.status.containerStatuses[0].restartCount > before.status.containerStatuses[0].restartCount && current.status.containerStatuses[0].ready;
    }, 'the same pod recovers from its halted application process');
    const after = pod(); assert.equal(after.metadata.uid, before.metadata.uid);
    assert.equal(after.status.containerStatuses[0].lastState.terminated.exitCode, 73);
    forward('core-api'); forward('console');
    const id = query('core', `SELECT order_id FROM orders WHERE site_id='site-a' AND source_system='scenario-driver' AND external_ref='${reference}';`);
    assert.match(id, /^[0-9a-f-]{36}$/);
    let order;
    await until(async () => { const response = await api(`/api/v1/sites/site-a/orders/${id}`, { bearer }); order = response.body; return response.status === 200 && order.state === 'COMPLETED'; }, 'committed order completes after process recovery');
    const eventId = query('core', `SELECT fired_event_id FROM process_faults WHERE fault_id='${fault.body.faultId}';`);
    let deliveries;
    if (candidate === 'A12') await until(() => (deliveries = Number(query('adapter', `SELECT COALESCE(max(deliveries),0) FROM inbox WHERE event_id='${eventId}';`))) >= 2, 'duplicate publication reaches the adapter inbox');
    if (candidate === 'A13') await until(() => (deliveries = Number(query('core', `SELECT COALESCE(max(deliveries),0) FROM inbox WHERE event_id='${eventId}';`))) >= 2, 'unacknowledged delivery is repeated after process loss');
    for (const movement of order.movements) {
      assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement.movementId}';`), '1');
      assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movement.movementId}';`), '1');
    }
    cases.push({ candidate, checkpoint, status: 'passed', acceptanceStatus, faultId: fault.body.faultId, eventId, orderId: id, movementIds: order.movements.map(item => item.movementId), deliveries, podUid: after.metadata.uid, restartsBefore: before.status.containerStatuses[0].restartCount, restartsAfter: after.status.containerStatuses[0].restartCount, exitCode: 73 });
    console.log(`Verified ${candidate}: ${checkpoint} through a real process restart.`);
  }
  console.log(`Passed ${cases.length} process crash checks. ${saveEvidence(runId, { cases })}`);
} catch (error) { saveEvidence(runId, { cases, failure: error.message }); throw error; }
finally {
  for (const id of armed) {
    try {
      forward('core-api'); const bearer = await token();
      const fault = (await api(`${path}/faults`, { target: 'core', bearer })).body.find(item => item.faultId === id);
      if (fault?.remaining) await api(`${path}/faults/${id}/clear`, { target: 'core', bearer, method: 'POST', key: `${id}-cleanup`, body: { expectedVersion: fault.version, reason: 'Clear an unfired checkpoint after the bounded process check.' } });
    } catch { console.error('A process fault may still be armed. Inspect the internal test-controls fault list before submitting more work.'); }
  }
}
