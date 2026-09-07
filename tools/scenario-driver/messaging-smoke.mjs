import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, token, api, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const runId = `messaging-${Date.now()}`;
const cases = [];
const kubernetes = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-platform'];
function kube(args) {
  const result = spawnSync('kubectl', [...kubernetes, ...args], { encoding: 'utf8', windowsHide: true, timeout: 60000 });
  if (result.status !== 0) throw new Error('The scoped Cutover broker lifecycle operation failed.');
  return result.stdout;
}
function brokerReplicas(count) {
  const resource = JSON.parse(kube(['get', 'statefulset', 'rabbitmq', '-o', 'json']));
  assert.equal(resource.metadata.labels['app.kubernetes.io/part-of'], 'cutover');
  kube(['scale', 'statefulset/rabbitmq', `--replicas=${count}`]);
}
async function until(check, description, timeout = 90000) {
  const deadline = Date.now() + timeout;
  do { if (await check()) return; await delay(250); } while (Date.now() < deadline);
  throw new Error(`Timed out: ${description}`);
}
const pending = owner => Number(query(owner, 'SELECT count(*) FROM outbox WHERE published_at IS NULL;'));
async function settled() {
  await until(() => pending('core') === 0 && pending('adapter') === 0, 'critical outboxes drain');
  await until(() => query('core', 'SELECT count(*) FROM outbox WHERE published_at IS NOT NULL;') === query('adapter', "SELECT count(*) FROM inbox WHERE envelope->>'source'='legacy-core' AND state='APPLIED';")
    && query('adapter', 'SELECT count(*) FROM outbox WHERE published_at IS NOT NULL;') === query('core', "SELECT count(*) FROM inbox WHERE envelope->>'source'='equipment-adapter' AND state='APPLIED';"), 'retained source streams finish application in their receiving owners');
  for (const owner of ['core', 'adapter']) {
    assert.equal(query(owner, "SELECT count(*) FROM inbox WHERE state IN ('PENDING','QUARANTINED');"), '0', `${owner} inbox has unresolved deliveries`);
    assert.equal(query(owner, 'SELECT unpublished_events FROM admission;'), '0');
  }
}
let brokerStopped = false;
try {
  provisionObservers(); await settled();
  cases.push({ name: 'pre-existing retained event history drains into owner inboxes', status: 'passed' });
  const bearer = await token();
  const accepted = await api('/api/v1/sites/site-a/orders', { method: 'POST', bearer, key: runId, body: { sourceSystem: 'scenario-driver', externalOrderRef: runId, storeId: 'store-03', priority: 4, lines: [{ sku: 'SKU-031', quantity: 1 }, { sku: 'SKU-032', quantity: 1 }] } });
  assert.equal(accepted.status, 202);
  let order;
  await until(async () => { order = (await api(`/api/v1/sites/site-a/orders/${accepted.body.id}`, { bearer })).body; return order.state === 'COMPLETED'; }, 'order completion');
  await settled();
  for (const movement of order.movements) {
    assert.equal(query('adapter', `SELECT count(*) FROM inbox WHERE envelope->>'aggregateId'='${movement.movementId}' AND envelope->>'eventType'='MovementRequested.v1' AND state='APPLIED';`), '1');
    assert.equal(query('core', `SELECT count(*) FROM inbox WHERE envelope->>'aggregateId'='${movement.movementId}' AND envelope->>'eventType'='MovementCompleted.v1' AND state='APPLIED';`), '1');
    assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movement.movementId}';`), '1');
    assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movement.movementId}';`), '1');
  }
  cases.push({ name: 'real broker delivery, durable inbox application, and one physical/inventory effect', status: 'passed', orderId: order.id, movementIds: order.movements.map(item => item.movementId) });
  if (process.argv.includes('--broker-outage')) {
    brokerReplicas(0); brokerStopped = true;
    kube(['wait', '--for=delete', 'pod/rabbitmq-0', '--timeout=45s']);
    const outage = await api('/api/v1/sites/site-a/orders', { method: 'POST', bearer, key: `${runId}-outage`, body: { sourceSystem: 'scenario-driver', externalOrderRef: `${runId}-outage`, storeId: 'store-03', priority: 4, lines: [{ sku: 'SKU-033', quantity: 1 }] } });
    assert.equal(outage.status, 202);
    const backlog = pending('core'); assert.ok(backlog > 0);
    brokerReplicas(1); brokerStopped = false;
    kube(['rollout', 'status', 'statefulset/rabbitmq', '--timeout=50s']);
    await until(async () => (await api(`/api/v1/sites/site-a/orders/${outage.body.id}`, { bearer })).body.state === 'COMPLETED', 'accepted outage work finishes');
    await settled();
    cases.push({ name: 'broker process outage retains accepted work and drains after recovery', status: 'passed', orderId: outage.body.id, observedCoreBacklog: backlog });
  }
  console.log(`Passed ${cases.length} messaging process checks. ${saveEvidence(runId, { cases })}`);
} catch (error) { saveEvidence(runId, { cases, failure: error.message }); throw error; }
finally { if (brokerStopped) brokerReplicas(1); }
