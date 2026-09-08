import assert from 'node:assert/strict';
import { openSync, closeSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { root, owners, target, fingerprints, simulatorRead, privateDirectory, jsonFile, writeJson, call } from '../../scripts/lib/local-platform.mjs';
import { verifyCheckpoint } from '../../scripts/lib/checkpoint.mjs';
process.env.CUTOVER_PROFILE = 'demo';
const { saveEvidence, api } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
const runId = `restore-${Date.now()}`, checkpointName = `${runId}-checkpoint`, directory = resolve(root, '.local/evidence', runId);
privateDirectory(directory);const cases = [], primary = target('demo'), restored = target('restoration');let restorationStarted = false, resumed = false, supervisor, currentScenario = 'A46';
console.log(`Starting fresh-cluster restore check ${runId}.`);
async function launch(script, args, log) {
  const fd = openSync(resolve(directory, log), 'w', 0o600);
  try {
    return await new Promise((done, failed) => { const child = spawn(process.execPath, [resolve(root, script), ...args], { cwd: root, windowsHide: true, stdio: ['ignore', fd, fd] });child.on('error', failed);child.on('exit', code => done(code)); });
  } finally { closeSync(fd); }
}
const lifecycle = (action, log = `${action}.log`) => launch('scripts/restoration.mjs', [`--run=${runId}`, `--action=${action}`], log);
function business(platform) {
  const selected = { core: ['orders', 'stock', 'reservations', 'inventory_ledger', 'legacy_tasks'], adapter: ['zone_routes', 'command_journal', 'movement_allocations'], execution: ['execution_tasks'], returns: ['receipts', 'receipt_counts', 'crate_counters', 'sorting_ledger', 'return_tasks', 'return_movements'] };
  return Object.fromEntries(Object.entries(selected).map(([owner, tables]) => { const rows = fingerprints(platform, owner).filter(item => tables.includes(item.table));assert.equal(rows.length, tables.length, 'Every selected business table must exist.');return [owner, rows]; }));
}
function runtime(platform) {
  return ['cutover-apps', 'cutover-platform', 'cutover-observability'].flatMap(namespace => JSON.parse(platform.kube(['-n', namespace, 'get', 'pods', '-l', 'app.kubernetes.io/part-of=cutover', '-o', 'json'])).items.map(pod => ({ namespace, name: pod.metadata.name, uid: pod.metadata.uid, containers: (pod.status.containerStatuses ?? []).map(container => ({ name: container.name, image: container.image, imageId: container.imageID, ready: container.ready })) })));
}
try {
  primary.verify();assert.equal(primary.sql('adapter', 'SELECT max(version::int) FROM flyway_schema_history;'), '117');
  assert.equal(Number(primary.sql('adapter', "SELECT count(*) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');")), 0, 'Use a quiescent source for this experiment.');
  const before = business(primary), physicalBefore = await simulatorRead('/sim/v1/equipment');
  const pvcBefore = primary.get('pvc', 'data-application-db-0', 'cutover-platform').metadata.uid;
  writeJson(resolve(directory, 'before.json'), { business: before, physical: physicalBefore, sourceDatabaseVolume: pvcBefore, runtime: runtime(primary) });
  assert.equal(await launch('scripts/backup.mjs', [`--name=${checkpointName}`], 'checkpoint.log'), 0, 'Capture failed; inspect checkpoint.log.');
  const checkpoint = verifyCheckpoint(checkpointName);assert.deepEqual(business(primary), before);
  restorationStarted = true;
  assert.equal(await launch('scripts/restore.mjs', [`--name=${checkpointName}`, `--run=${runId}`], 'restore.log'), 0, 'Fresh-cluster restoration failed; inspect restore.log and its private journal.');
  const journalPath = resolve(root, '.local/restoration/runs', runId, 'journal.json');let journal = jsonFile(journalPath);
  assert.equal(journal.state, 'VERIFIED_HELD');assert.equal(journal.imported.length, 6);
  assert.notEqual(restored.get('pvc', 'data-application-db-0', 'cutover-platform').metadata.uid, pvcBefore);
  assert.equal(restored.sql('adapter', 'SELECT restoration_required FROM service_control WHERE singleton;'), 't');
  for (const owner of Object.keys(owners)) assert.equal(restored.sql(owner, 'SELECT intake_paused AND dispatch_paused AND NOT workers_paused FROM service_control WHERE singleton;'), 't');
  for (const database of checkpoint.manifest.databases.filter(item => item.owner !== 'keycloak')) assert.deepEqual(journal.replay[database.owner].confirmed, database.replay.events.map(event => event.eventId));
  assert.deepEqual(business(restored), before);writeJson(resolve(directory, 'restored-runtime.json'), runtime(restored));
  const findings = JSON.parse(restored.sql('adapter', "SELECT jsonb_build_object('physical',(SELECT count(*) FROM restore_physical_findings),'physicalUnresolved',(SELECT count(*) FROM restore_physical_findings WHERE state<>'MATCHED'),'inventory',(SELECT count(*) FROM restore_inventory_findings),'inventoryUnresolved',(SELECT count(*) FROM restore_inventory_findings WHERE state<>'MATCHED'),'absent',(SELECT count(*) FROM restore_absence_proofs));"));
  assert.equal(findings.physicalUnresolved, 0);assert.equal(findings.inventoryUnresolved, 0);
  assert.equal(await lifecycle('Release'), 0);journal = jsonFile(journalPath);
  assert.equal(journal.state, 'RELEASED');assert.ok(journal.restoreDurationSeconds <= 900, 'The quiescent restoration exceeded its 15-minute target.');
  assert.equal(restored.sql('adapter', 'SELECT restoration_required FROM service_control WHERE singleton;'), 'f');
  const after = await simulatorRead('/sim/v1/equipment');
  for (const key of ['worldId', 'journalGeneration', 'journalHighWater']) assert.equal(after[key], physicalBefore[key]);
  assert.deepEqual(business(restored), before);
  cases.push({ id: 'A46', name: 'fresh cluster, six verified imports, clean broker and original replay, current physical reconciliation and guarded release', status: 'passed', checkpoint: checkpointName, manifestSha256: checkpoint.manifestSha256, durationSeconds: journal.restoreDurationSeconds, targetSeconds: 900, findings, replay: Object.fromEntries(Object.entries(journal.replay).map(([owner, item]) => [owner, { events: item.confirmed.length, batches: item.batches }])), world: after });
  currentScenario = 'A52';
  const restoredVolume = restored.get('pvc', 'data-application-db-0', 'cutover-platform').metadata.uid;
  const controlsBeforeStop = Object.fromEntries(Object.keys(owners).map(owner => [owner, JSON.parse(restored.sql(owner, 'SELECT row_to_json(c) FROM service_control c WHERE singleton;'))]));
  assert.equal(await lifecycle('Stop'), 0);
  assert.equal(await lifecycle('Start'), 0);
  assert.equal(restored.get('pvc', 'data-application-db-0', 'cutover-platform').metadata.uid, restoredVolume);
  assert.deepEqual(business(restored), before);
  for (const owner of Object.keys(owners)) {
    const current = JSON.parse(restored.sql(owner, 'SELECT row_to_json(c) FROM service_control c WHERE singleton;'));
    for (const flag of ['intake_paused', 'dispatch_paused', 'workers_paused', 'critical_storage', 'relay_paused', 'consumer_paused']) assert.equal(current[flag], controlsBeforeStop[owner][flag], `${owner} restores its ${flag} control after a cold start`);
  }
  assert.equal(restored.sql('adapter', 'SELECT restoration_required FROM service_control WHERE singleton;'), 'f');
  const restartedPhysical = await simulatorRead('/sim/v1/equipment');for (const key of ['worldId', 'journalGeneration', 'journalHighWater']) assert.equal(restartedPhysical[key], physicalBefore[key]);
  cases.push({ candidate: 'A52', name: 'explicit restored-copy stop and cold start preserve its volume, business rows, controls and independent physical history', status: 'passed', limitation: 'Normal demo lifecycle and destructive reset are separate acceptance checks.' });
  currentScenario = 'A54';
  assert.equal(await lifecycle('Open'), 0);
  supervisor = await humanSession('supervisor-a');
  assert.equal((await api('/api/v1/sites/site-a/orders', { bearer: supervisor.bearer() })).status, 200);
  assert.equal((await api('/api/v1/sites/site-a/return-receipts', { bearer: supervisor.bearer() })).status, 200);
  await supervisor.page.screenshot({ path: resolve(directory, 'restored-console.png'), fullPage: true });await supervisor.close();supervisor = null;
  cases.push({ candidate: 'A54', name: 'restored identity and both product read APIs work through the documented local forward', status: 'passed', limitation: 'A read-only restore walkthrough; the full reviewer demonstration remains separate.' });
  currentScenario = 'A52';
  assert.equal(await lifecycle('ResumeDemo'), 0);resumed = true;primary.verify();assert.deepEqual(business(primary), before);
  assert.equal(primary.get('pvc', 'data-application-db-0', 'cutover-platform').metadata.uid, pvcBefore);
  const resumedPhysical = await simulatorRead('/sim/v1/equipment');for (const key of ['worldId', 'journalGeneration', 'journalHighWater']) assert.equal(resumedPhysical[key], physicalBefore[key]);
  cases.push({ candidate: 'A52', name: 'restoration stop and preserved-primary resume keep original storage and physical history', status: 'passed', limitation: 'This verifies the restoration lifecycle; the full normal stop/reset scenario remains separate.' });
  assert.equal(await lifecycle('Remove'), 0); // Explicit removal of this disposable application copy, after proof and safe primary resumption.
  saveEvidence(runId, { cases, checkpoint: checkpointName, restoration: jsonFile(journalPath), exclusions: 'Quiescent recovery experiment; no new business or physical work was submitted on the restored fork.' });
  console.log(JSON.stringify({ runId, status: 'passed', scenarios: cases.length, checkpoint: checkpointName }));
} catch (error) {
  cases.push({ id: currentScenario, status: 'failed', error: error.message });
  writeJson(resolve(directory, 'failure.json'), { runId, cases });console.error(error.message);process.exitCode = 1;
} finally {
  await supervisor?.close();
  const journalPath = resolve(root, '.local/restoration/runs', runId, 'journal.json');
  if (restorationStarted && !resumed && existsSync(journalPath) && (jsonFile(journalPath).physicalInventoryAtPrimaryStop || jsonFile(journalPath).failedStage === 'STOPPING_PRIMARY')) {
    const result = await lifecycle('ResumeDemo', 'resume-after-failure.log');if (result !== 0) console.error('The saved restoration lifecycle needs attention; original data and physical history are retained.');
  }
}
