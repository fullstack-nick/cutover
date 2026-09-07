import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, simulator, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
const runId = `equipment-recovery-${Date.now()}`, cases = [], faults = [];
const controlPath = '/internal/v1/sites/site-a/test-controls';
let supervisor, operator, dispatchPaused = false;
async function until(check, description, timeout = 120000) {
  const deadline = Date.now() + timeout;
  do { if (await check()) return; await delay(400); } while (Date.now() < deadline);
  throw new Error(`Timed out: ${description}`);
}
async function pauseDispatch(value) {
  const bearer = await token();
  const current = await api(controlPath, { target: 'adapter', bearer }); assert.equal(current.status, 200);
  const result = await api(controlPath, { target: 'adapter', bearer, method: 'POST', key: `${runId}-dispatch-${current.body.version}`, body: { expectedVersion: current.body.version, dispatchPaused: value, reason: value ? 'Hold dispatch while selecting one deterministic equipment fault.' : 'Resume dispatch after the selected fault is durably configured.' } });
  assert.equal(result.status, 200); dispatchPaused = value;
}
async function selectedOrder(label, kind, sku, delayMillis = 0) {
  await pauseDispatch(true);
  const bearer = await token(), reference = `${runId}-${label}`;
  const created = await api('/api/v1/sites/site-a/orders', { method: 'POST', bearer, key: reference, body: { sourceSystem: 'scenario-driver', externalOrderRef: reference, storeId: 'store-05', priority: 5, lines: [{ sku, quantity: 1 }] } });
  assert.equal(created.status, 202);
  const order = (await api(`/api/v1/sites/site-a/orders/${created.body.id}`, { bearer })).body;
  assert.equal(order.movements.length, 1, 'The chosen fixture must have a reserved unit.');
  const movementId = order.movements[0].movementId;
  const fault = await simulator('/sim/v1/test-controls/faults', { kind, commandId: movementId, count: 1, delayMillis });
  assert.equal(fault.status, 200); faults.push(fault.body.faultId);
  await pauseDispatch(false);
  return { id: created.body.id, movementId, faultId: fault.body.faultId };
}
async function completed(order) {
  const bearer = await token();
  await until(async () => (await api(`/api/v1/sites/site-a/orders/${order.id}`, { bearer })).body.state === 'COMPLETED', 'order completion with verified physical evidence');
  assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${order.movementId}';`), '1');
  assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${order.movementId}';`), '1');
  assert.equal(query('simulator', `SELECT count(*) FROM simulator_commands WHERE movement_id='${order.movementId}';`), '1');
  return (await api(`/api/v1/sites/site-a/commands/${order.movementId}`, { bearer })).body;
}
async function restartSimulator() {
  const name = 'cutover-dev-equipment-simulator-1';
  const before = JSON.parse(spawnSync('docker', ['inspect', name], { encoding: 'utf8', windowsHide: true }).stdout)[0];
  assert.equal(before.Config.Labels['dev.cutover.project'], 'cutover');
  await new Promise((done, failed) => {
    const process = spawn('docker', ['restart', '--time', '5', name], { stdio: 'ignore', windowsHide: true });
    process.on('error', failed); process.on('exit', code => code === 0 ? done() : failed(new Error('Cutover simulator restart failed.')));
  });
  await until(() => {
    const state = JSON.parse(spawnSync('docker', ['inspect', name], { encoding: 'utf8', windowsHide: true }).stdout)[0];
    return state.State.Health.Status === 'healthy';
  }, 'restarted simulator becomes healthy');
  return before.State.StartedAt;
}
try {
  provisionObservers();
  const forward = spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', 'adapter-api'], { encoding: 'utf8', windowsHide: true, timeout: 45000 });
  assert.equal(forward.status, 0);
  const control = await api(controlPath, { target: 'adapter', bearer: await token() }); assert.equal(control.status, 200);
  assert.equal(control.body.dispatchPaused, false, 'This suite requires an initially enabled dispatch gate.');
  assert.equal(control.body.workersPaused, false);
  assert.equal(control.body.criticalStorage, false);
  assert.equal(query('adapter', "SELECT count(*) FROM command_journal WHERE state<>'COMPLETED';"), '0', 'Prior command investigations must be settled before this suite.');
  supervisor = await humanSession('supervisor-a'); operator = await humanSession('operator-a');
  const lost = await selectedOrder('lost', 'LOST_RESPONSE', 'SKU-045', 5000);
  const lostCommand = await completed(lost); assert.equal(lostCommand.attempts, 1);
  assert.ok(Number(query('adapter', `SELECT count(*) FROM outbox WHERE aggregate_id='${lost.movementId}' AND event_type='CommandOutcomeUnknown.v1';`)) >= 1);
  cases.push({ candidate: 'A18', name: 'execution with a lost response resolves through the original command', status: 'passed', ...lost, attempts: lostCommand.attempts });
  const before = await selectedOrder('before', 'BEFORE_ACCEPT', 'SKU-046');
  const beforeCommand = await completed(before); assert.equal(beforeCommand.attempts, 2);
  cases.push({ candidate: 'A19', name: 'retained absence permits a resend with the same ID', status: 'passed', ...before, attempts: beforeCommand.attempts });
  const executing = await selectedOrder('executing', 'HOLD_EXECUTING', 'SKU-047', 30000);
  await until(async () => (await simulator(`/sim/v1/commands/${executing.movementId}`)).body.state === 'EXECUTING', 'durable executing state');
  const worldBefore = (await simulator('/sim/v1/equipment')).body;
  assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${executing.movementId}';`), '0');
  const startedBefore = await restartSimulator();
  assert.equal((await simulator('/sim/v1/equipment')).body.worldId, worldBefore.worldId);
  const executionCommand = await completed(executing); assert.equal(executionCommand.attempts, 1);
  cases.push({ candidate: 'A20', name: 'simulator process restart during execution preserves atomic physical history', status: 'passed', ...executing, worldId: worldBefore.worldId, startedBefore });
  for (const [kind, sku] of [['HISTORY_GAP', 'SKU-048'], ['WORLD_MISMATCH', 'SKU-049']]) {
    const order = await selectedOrder(kind.toLowerCase(), kind, sku);
    let command;
    await until(async () => { command = (await api(`/api/v1/sites/site-a/commands/${order.movementId}`, { bearer: supervisor.bearer() })).body; return command.state === 'QUARANTINED'; }, 'explicit quarantine on incompatible history evidence');
    assert.equal(query('simulator', `SELECT count(*) FROM simulator_commands WHERE command_id='${order.movementId}';`), '0');
    const actionPath = `/api/v1/sites/site-a/commands/${order.movementId}/reconciliation`;
    const request = { expectedVersion: command.version, reason: 'The selected evidence fault is exhausted; investigate the retained simulator world without overriding its journal.' };
    const key = `${runId}-${kind}-recover`;
    const denied = await api(actionPath, { method: 'POST', bearer: operator.bearer(), key, body: request }); assert.equal(denied.status, 403);
    const wrongSite = await api(actionPath.replace('/site-a/', '/site-b/'), { method: 'POST', bearer: supervisor.bearer(), key, body: request }); assert.equal(wrongSite.status, 404);
    const recorded = await api(actionPath, { method: 'POST', bearer: supervisor.bearer(), key, body: request }); assert.equal(recorded.status, 200);
    assert.deepEqual((await api(actionPath, { method: 'POST', bearer: supervisor.bearer(), key, body: request })).body, recorded.body);
    assert.equal((await api(actionPath, { method: 'POST', bearer: supervisor.bearer(), key, body: { ...request, reason: 'Changed recovery reason with the already used idempotency key.' } })).status, 409);
    await completed(order);
    assert.equal(query('adapter', `SELECT count(*) FROM audit WHERE action='command-investigation' AND resource_id='${order.movementId}' AND site_id='site-a' AND before_version=${command.version} AND after_version=${command.version + 1} AND outcome='RECORDED';`), '1');
    cases.push({ candidate: 'A22/A39/A40', name: `${kind}: role/site checks and audited investigation preserve one effect`, status: 'passed', ...order, beforeVersion: command.version, recordedVersion: recorded.body.version });
  }
  const bearer = await token('legacy-core');
  const allocation = (await api(`/internal/v1/sites/site-a/allocations/${lost.movementId}`, { target: 'adapter', bearer })).body;
  const changed = await api(`/internal/v1/sites/site-a/commands/${lost.movementId}`, { target: 'adapter', bearer, method: 'PUT', body: { allocationId: allocation.allocationId, epoch: allocation.epoch, laneId: lostCommand.payload.laneId, movement: { ...allocation.movement, quantity: allocation.movement.quantity + 1 } } });
  assert.equal(changed.status, 409);
  assert.equal((await simulator(`/sim/v1/commands/${lost.movementId}`, { ...lostCommand.payload, quantity: lostCommand.payload.quantity + 1 }, 'adapter', 'PUT')).status, 409);
  assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE command_id='${lost.movementId}';`), '1');
  cases.push({ candidate: 'A21', name: 'adapter and simulator independently reject changed immutable command payloads', status: 'passed', commandId: lost.movementId });
  console.log(`Passed ${cases.length} equipment/recovery process checks. ${saveEvidence(runId, { cases })}`);
} catch (error) { saveEvidence(runId, { cases, failure: error.message }); throw error; }
finally {
  for (const id of faults) {
    try { await simulator(`/sim/v1/test-controls/faults/${id}`, undefined, 'scenario', 'DELETE'); }
    catch { console.error(`Inspect retained simulator fault ${id}; its cleanup request failed.`); }
  }
  if (dispatchPaused) await pauseDispatch(false);
  await supervisor?.close(); await operator?.close();
}
