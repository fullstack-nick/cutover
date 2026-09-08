import assert from 'node:assert/strict';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE = 'demo';
const { api, token, provisionObservers, query, saveEvidence } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
const { root, owners, target, until, simulatorRead, privateDirectory, writeJson, call, maintenanceLock } = await import('../../scripts/lib/local-platform.mjs');
const runId = `volume-observation-${Date.now()}`, directory = resolve(root, '.local/evidence', runId), platform = target('demo');
privateDirectory(directory);
const unlock = maintenanceLock(`volume-observation:${runId}`);
const forwards = {}, cases = [], prefix = '/api/v1/sites/site-a';
let administrator, operator, held = false, pausedContainer;
async function diagnostic(owner, bearer) {
  const response = await fetch(`${forwards[owner].origin}/internal/v1/platform/storage`, { headers: { Authorization: `Bearer ${bearer}` }, signal: AbortSignal.timeout(5000) });
  return { status: response.status, ...(response.status === 200 ? { body: await response.json() } : {}) };
}
function probe(signal) {
  assert.ok(['STOP', 'CONT'].includes(signal));
  const pod = platform.get('pod', 'application-db-0', 'cutover-platform');platform.owned(pod);
  assert.ok(pod.spec.containers.some(container => container.name === 'volume-probe'));
  const id = pod.status.containerStatuses.find(container => container.name === 'volume-probe').containerID.replace(/^containerd:\/\//, '');
  assert.match(id, /^[a-f0-9]{64}$/);
  if(signal === 'STOP')pausedContainer = id;else assert.equal(id, pausedContainer, 'The paused probe process was replaced; inspect the recorded fixture before resuming.');
  // Namespace PID 1 may ignore stop signals sent from its own namespace. Pause this exact task from its parent runtime.
  call('docker', ['exec', 'cutover-control-plane', 'ctr', '--namespace', 'k8s.io', 'tasks', signal === 'STOP' ? 'pause' : 'resume', id]);
}
try {
  platform.verify();provisionObservers();
  administrator = await humanSession('platform-admin');operator = await humanSession('operator-a');
  for (const [owner, service] of Object.entries(owners)) forwards[owner] = await platform.forward(service);
  const healthy = {};
  for (const owner of Object.keys(owners)) {
    const view = await diagnostic(owner, administrator.bearer());assert.equal(view.status, 200);assert.equal(view.body.volume.state, 'HEALTHY');healthy[owner] = view.body.volume;
    assert.equal((await diagnostic(owner, operator.bearer())).status, 403);
  }
  const before = await simulatorRead('/sim/v1/equipment'), bearer = await token();
  const order = { sourceSystem: 'scenario-driver', externalOrderRef: `${runId}-order`, storeId: 'store-07', priority: 5, lines: [{ sku: 'SKU-093', quantity: 1 }, { sku: 'SKU-094', quantity: 1 }] };
  const receipt = { sourceSystem: 'scenario-driver', externalReceiptRef: `${runId}-receipt`, counts: { REUSABLE: 1, NEEDS_CLEANING: 1, DAMAGED: 1 } };
  const madeOrder = await api(`${prefix}/orders`, { method: 'POST', bearer, key: order.externalOrderRef, body: order });assert.equal(madeOrder.status, 202);
  const madeReceipt = await api(`${prefix}/return-receipts`, { method: 'POST', bearer, key: receipt.externalReceiptRef, body: receipt });assert.equal(madeReceipt.status, 202);
  let outbound, returned;
  await until(async () => {
    outbound = (await api(`${prefix}/orders/${madeOrder.body.id}`, { bearer })).body;
    returned = (await api(`${prefix}/return-receipts/${madeReceipt.body.id}`, { bearer })).body;
    return outbound.state === 'COMPLETED' && returned.state === 'COMPLETED';
  }, 'both products complete using the deployed physical headroom guard', 120000);
  const ids = [...outbound.movements, ...returned.movements].map(item => item.movementId);assert.equal(new Set(ids).size, 5);
  for (const id of ids) assert.match(id, /^[a-f0-9-]{36}$/);
  const selected = ids.map(id => `'${id}'`).join(',');
  assert.equal(Number(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id IN (${selected});`)), 5);
  assert.equal(Number(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id IN (${selected});`)), 2);
  assert.equal(Number(query('returns', `SELECT count(*) FROM sorting_ledger WHERE movement_id IN (${selected});`)), 3);
  const physical = await simulatorRead('/sim/v1/equipment');assert.equal(physical.worldId, before.worldId);assert.equal(physical.journalGeneration, before.journalGeneration);
  cases.push({ candidate: 'A26', status: 'passed', name: 'deployed owner probes and role restrictions, followed by five single physical effects across both products', healthy, movementIds: ids, physical });
  const intakeCounts = [query('core', 'SELECT count(*) FROM orders;'), query('returns', 'SELECT count(*) FROM receipts;')];
  held = true;probe('STOP');
  await until(async () => (await diagnostic('core', administrator.bearer())).body.volume.state === 'STALE', 'the real stopped sidecar report expires', 22000);
  const stale = {};
  for (const owner of Object.keys(owners)) { const view = await diagnostic(owner, administrator.bearer());assert.equal(view.status, 200);assert.equal(view.body.volume.state, 'STALE');stale[owner] = view.body.volume; }
  for (const [path, body] of [[`${prefix}/orders`, { ...order, externalOrderRef: `${runId}-refused-order` }], [`${prefix}/return-receipts`, { ...receipt, externalReceiptRef: `${runId}-refused-receipt` }]]) {
    const response = await fetch(`http://localhost:8780${path}`, { method: 'POST', headers: { Authorization: `Bearer ${await token()}`, 'Content-Type': 'application/json', 'Idempotency-Key': `${runId}-${path.endsWith('orders') ? 'order' : 'receipt'}-held` }, body: JSON.stringify(body), signal: AbortSignal.timeout(8000) });
    assert.equal(response.status, 503);assert.ok(Number(response.headers.get('Retry-After')) >= 1);assert.equal((await response.json()).code, 'STORAGE_OBSERVATION');
  }
  assert.equal((await api(`${prefix}/orders/${madeOrder.body.id}`, { bearer })).status, 200);
  assert.equal((await api(`${prefix}/return-receipts/${madeReceipt.body.id}`, { bearer })).status, 200);
  assert.deepEqual([query('core', 'SELECT count(*) FROM orders;'), query('returns', 'SELECT count(*) FROM receipts;')], intakeCounts);
  probe('CONT');held = false;
  await until(async () => (await diagnostic('core', administrator.bearer())).body.volume.state === 'HEALTHY', 'the original sidecar resumes fresh observation', 12000);
  const after = await simulatorRead('/sim/v1/equipment');for (const key of ['worldId', 'journalGeneration', 'journalHighWater']) assert.equal(after[key], physical[key]);
  cases.push({ candidate: 'A26', status: 'passed', name: 'stopped probe expires across all five owners, refuses new intake and retains product reads without changing business or physical counts', stale, intakeCounts, physical: after });
  saveEvidence(runId, { cases, exclusions: 'Actual filesystem capacity pressure is verified separately on a bounded disposable volume; this live platform check suspends only its credential-free report process.' });
  console.log(JSON.stringify({ runId, status: 'passed', scenarios: cases.length, movements: ids.length }));
} catch (error) { writeJson(resolve(directory, 'failure.json'), { runId, cases, error: error.message });console.error(error.message);process.exitCode = 1; }
finally { try { if (held && pausedContainer) probe('CONT');for (const connection of Object.values(forwards)) connection.close();await administrator?.close();await operator?.close(); } finally { unlock(); } }
