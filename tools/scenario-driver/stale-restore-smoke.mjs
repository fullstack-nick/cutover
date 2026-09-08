import assert from 'node:assert/strict';
import { openSync, closeSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { root, target, simulatorRead, privateDirectory, jsonFile, writeJson, until, request, maintenanceToken } from '../../scripts/lib/local-platform.mjs';
import { verifyCheckpoint } from '../../scripts/lib/checkpoint.mjs';
process.env.CUTOVER_PROFILE = 'demo';
const { saveEvidence, api, token, simulator, credentials } = await import('./client.mjs');
const runId = `stale-restore-${Date.now()}`, checkpointName = `${runId}-checkpoint`, directory = resolve(root, '.local/evidence', runId);
privateDirectory(directory);console.log(`Starting stale-checkpoint restore check ${runId}.`);
const source = target('demo'), restored = target('restoration'), cases = [], prefix = '/api/v1/sites/site-a';
let lane, faultId, gatePaused = false, restorationStarted = false, resumed = false;
async function launch(script, args, log) {
  const fd = openSync(resolve(directory, log), 'w', 0o600);
  try { return await new Promise((done, failed) => { const child = spawn(process.execPath, [resolve(root, script), ...args], { cwd: root, windowsHide: true, stdio: ['ignore', fd, fd] });child.on('error', failed);child.on('exit', code => done(code)); }); }
  finally { closeSync(fd); }
}
const lifecycle = (action, log = `${action}.log`) => launch('scripts/restoration.mjs', [`--run=${runId}`, `--action=${action}`], log);
async function gate(paused) {
  const proxy = await source.forward('proxy'), adapter = await source.forward('equipment-adapter');
  try {
    const bearer = await maintenanceToken(proxy.origin, credentials), path = '/internal/v1/sites/site-a/test-controls';
    const before = await request(adapter.origin, path, { bearer });
    await request(adapter.origin, path, { method: 'POST', bearer, key: `${runId}-gate-${before.version}`, body: { expectedVersion: before.version, dispatchPaused: paused, reason: paused ? 'Prepare one stable command for the stale-checkpoint recovery experiment.' : 'Resume original dispatch after configuring the bounded recovery experiment.' } });
    gatePaused = paused;
  } finally { proxy.close();adapter.close(); }
}
async function receipt(label) {
  const body = { sourceSystem: 'scenario-driver', externalReceiptRef: `${runId}-${label}`, counts: { REUSABLE: 1, NEEDS_CLEANING: 0, DAMAGED: 0 } };
  const created = await api(`${prefix}/return-receipts`, { method: 'POST', body, bearer: await token(), key: body.externalReceiptRef });assert.equal(created.status, 202);
  const result = await api(`${prefix}/return-receipts/${created.body.id}`, { bearer: await token() });assert.equal(result.status, 200);assert.equal(result.body.movements.length, 1);
  return result.body;
}
async function settled(path) { let result;await until(async () => { result = await api(prefix + path, { bearer: await token() });return result.status === 200 && result.body.state === 'COMPLETED'; }, `original ${path} completes`, 120000);return result.body; }
async function restoreLane() { if (lane) { assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId: lane.laneId, blocked: lane.blocked })).status, 200);lane = null; } }
try {
  source.verify();const physicalBefore = await simulatorRead('/sim/v1/equipment');
  assert.equal(Number(source.sql('adapter', "SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');")), 0);
  await gate(true);const known = await receipt('known-outstanding'), knownMovement = known.movements[0].movementId;
  assert.match(knownMovement, /^[a-f0-9-]{36}$/);
  const fault = await simulator('/sim/v1/test-controls/faults', { kind: 'HOLD_EXECUTING', commandId: knownMovement, count: 1, delayMillis: 60000 });assert.equal(fault.status, 200);faultId = fault.body.faultId;
  await gate(false);
  await until(async () => { const result = await simulator(`/sim/v1/commands/${knownMovement}`);return result.status === 200 && result.body.state === 'EXECUTING'; }, 'known original command enters its durable execution hold', 30000);
  const laneId = source.sql('adapter', `SELECT payload->>'laneId' FROM command_journal WHERE command_id='${knownMovement}';`);
  lane = physicalBefore.lanes.find(item => item.siteId === 'site-a' && item.laneId === laneId);assert.ok(lane);assert.equal(lane.blocked, false);
  assert.equal((await simulator('/sim/v1/test-controls/lanes', { siteId: 'site-a', laneId, blocked: true })).status, 200);
  assert.equal((await simulator(`/sim/v1/test-controls/faults/${faultId}`, undefined, 'scenario', 'DELETE')).status, 200);faultId = null;
  assert.equal(await launch('scripts/backup.mjs', [`--name=${checkpointName}`], 'checkpoint.log'), 0);
  const checkpoint = verifyCheckpoint(checkpointName);
  assert.ok(checkpoint.manifest.unsettledCommands.some(command => command.commandId === knownMovement));
  assert.equal((await simulator(`/sim/v1/commands/${knownMovement}`)).body.state, 'EXECUTING');
  await restoreLane();await settled(`/return-receipts/${known.id}`);
  const newReturn = await receipt('after-checkpoint');
  const orderBody = { sourceSystem: 'scenario-driver', externalOrderRef: `${runId}-after-checkpoint`, storeId: 'store-08', priority: 5, lines: [{ sku: 'SKU-089', quantity: 1 }, { sku: 'SKU-090', quantity: 1 }] };
  const accepted = await api(`${prefix}/orders`, { method: 'POST', body: orderBody, bearer: await token(), key: orderBody.externalOrderRef });assert.equal(accepted.status, 202);
  const order = await settled(`/orders/${accepted.body.id}`);await settled(`/return-receipts/${newReturn.id}`);
  const postCheckpointMoves = JSON.parse(source.sql('core', `SELECT jsonb_agg(movement_id ORDER BY movement_id) FROM movement_intents WHERE order_id='${order.id}';`));
  postCheckpointMoves.push(newReturn.movements[0].movementId);assert.equal(postCheckpointMoves.length, 3);
  const physicalAfterWork = await simulatorRead('/sim/v1/equipment');assert.equal(physicalAfterWork.journalHighWater, physicalBefore.journalHighWater + 4);
  writeJson(resolve(directory, 'source-work.json'), { checkpoint: checkpointName, knownReceiptId: known.id, knownMovement, laterReceiptId: newReturn.id, laterOrderId: order.id, postCheckpointMoves, physicalBefore, physicalAfterWork });
  restorationStarted = true;assert.equal(await launch('scripts/restore.mjs', [`--name=${checkpointName}`, `--run=${runId}`], 'restore.log'), 0);
  const journalPath = resolve(root, '.local/restoration/runs', runId, 'journal.json'), journal = jsonFile(journalPath);
  assert.equal(journal.state, 'QUARANTINED');assert.equal(journal.recovery.lastError, 'MISSING_OR_CONFLICTING_BUSINESS_CONTEXT');
  assert.equal(journal.recovery.unresolvedCount, 3);
  const missing = JSON.parse(restored.sql('adapter', `SELECT jsonb_agg(command_id ORDER BY command_id) FROM restore_physical_findings WHERE restore_id='${journal.restoreId}' AND state='MISSING_BUSINESS_CONTEXT';`));
  assert.deepEqual(missing.sort(), postCheckpointMoves.sort());
  assert.equal(restored.sql('adapter', `SELECT state FROM command_journal WHERE command_id='${knownMovement}';`), 'COMPLETED');
  assert.equal(restored.sql('adapter', `SELECT state FROM restore_physical_findings WHERE restore_id='${journal.restoreId}' AND command_id='${knownMovement}';`), 'MATCHED');
  assert.equal(restored.sql('returns', `SELECT state FROM receipts WHERE receipt_id='${known.id}';`), 'COMPLETED');
  assert.equal(Number(restored.sql('returns', `SELECT count(*) FROM sorting_ledger WHERE movement_id='${knownMovement}';`)), 1);
  assert.equal(Number(restored.sql('core', `SELECT count(*) FROM orders WHERE order_id='${order.id}';`)), 0);
  assert.equal(Number(restored.sql('returns', `SELECT count(*) FROM receipts WHERE receipt_id='${newReturn.id}';`)), 0);
  assert.equal(restored.sql('adapter', 'SELECT restoration_required AND dispatch_paused FROM service_control WHERE singleton;'), 't');
  assert.notEqual(await lifecycle('Release', 'refused-release.log'), 0, 'Missing business intent cannot be acknowledged away.');
  const physicalEnd = await simulatorRead('/sim/v1/equipment');for (const key of ['worldId', 'journalGeneration', 'journalHighWater']) assert.equal(physicalEnd[key], physicalAfterWork[key]);
  assert.ok(journal.rpo.elapsedSeconds > 0);
  cases.push({ id: 'A47', name: 'known outstanding command completes once; three later physical movements with missing checkpoint intent are quarantined', status: 'passed', checkpoint: checkpointName, manifestSha256: checkpoint.manifestSha256, knownMovement, missingCommandIds: missing, rpo: journal.rpo, restoreDurationSeconds: journal.elapsedSeconds, physicalEnd, limitation: 'Detection and quarantine of the RPO gap; the stale application copy is deliberately not released.' });
  assert.equal(await lifecycle('ResumeDemo'), 0);resumed = true;source.verify();
  assert.equal(source.sql('core', `SELECT state FROM orders WHERE order_id='${order.id}';`), 'COMPLETED');
  assert.equal(source.sql('returns', `SELECT state FROM receipts WHERE receipt_id='${newReturn.id}';`), 'COMPLETED');
  assert.equal(await lifecycle('Remove'), 0);
  saveEvidence(runId, { cases, restoration: jsonFile(journalPath), source: jsonFile(resolve(directory, 'source-work.json')) });
  console.log(JSON.stringify({ runId, status: 'passed', scenarios: cases.length, checkpoint: checkpointName }));
} catch (error) { cases.push({ id: 'A47', status: 'failed', error: error.message });writeJson(resolve(directory, 'failure.json'), { runId, cases });console.error(error.message);process.exitCode = 1; }
finally {
  await restoreLane();if (faultId) await simulator(`/sim/v1/test-controls/faults/${faultId}`, undefined, 'scenario', 'DELETE');
  const journalPath = resolve(root, '.local/restoration/runs', runId, 'journal.json');
  if (restorationStarted && !resumed && existsSync(journalPath) && (jsonFile(journalPath).physicalInventoryAtPrimaryStop || jsonFile(journalPath).failedStage === 'STOPPING_PRIMARY')) await lifecycle('ResumeDemo', 'resume-after-failure.log');
  if (gatePaused) await gate(false);
}
