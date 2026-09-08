import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, copyFileSync, appendFileSync, mkdirSync, existsSync, openSync, closeSync, readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { root, owners, sha, jsonFile, writeJson, privateDirectory, target, fingerprints, simulatorRead, until } from '../../scripts/lib/local-platform.mjs';
import { verifyCheckpoint } from '../../scripts/lib/checkpoint.mjs';
process.env.CUTOVER_PROFILE = 'demo';
const { saveEvidence } = await import('./client.mjs');

const runId = `checkpoint-${Date.now()}`, directory = resolve(root, '.local/evidence', runId), cases = [];
privateDirectory(directory);
const platform = target(), completeName = `${runId}-complete`, interruptedName = `${runId}-interrupted`;
let interruptedPid, activeCapture;
function launch(script, args, logName) {
  const fd = openSync(resolve(directory, logName), 'w', 0o600);
  const child = spawn(process.execPath, [resolve(root, script), ...args], { cwd: root, windowsHide: true, stdio: ['ignore', fd, fd] });
  const finished = new Promise((done, failed) => { child.on('error', failed); child.on('exit', (code, signal) => { closeSync(fd); done({ code, signal }); }); });
  return { child, finished };
}
function business() {
  const selected = { core: ['orders', 'stock', 'reservations', 'inventory_ledger'], adapter: ['zone_routes', 'command_journal', 'movement_allocations'], returns: ['receipts', 'receipt_counts', 'crate_counters', 'sorting_ledger'] };
  return Object.fromEntries(Object.entries(selected).map(([owner, tables]) => [owner, fingerprints(platform, owner).filter(item => tables.includes(item.table))]));
}
async function verifyResumed(name, before) {
  const journal = jsonFile(resolve(root, '.local/checkpoints', name, 'recovery-journal.json'));
  assert.ok(journal.resumedAt); assert.deepEqual(journal.cleanupErrors, []); assert.deepEqual(journal.pending, {});
  for (const [owner, service] of Object.entries(owners)) {
    assert.equal(platform.get('deployment', service, 'cutover-apps').status.readyReplicas, 1);
    const row = JSON.parse(platform.sql(owner, "SELECT row_to_json(c) FROM service_control c WHERE singleton;"));
    for (const [wire, sql] of Object.entries({ intakePaused: 'intake_paused', dispatchPaused: 'dispatch_paused', workersPaused: 'workers_paused', criticalStorage: 'critical_storage', relayPaused: 'relay_paused', consumerPaused: 'consumer_paused' })) assert.equal(row[sql], journal.prior[owner][wire]);
  }
  assert.equal(platform.get('deployment', 'keycloak', 'cutover-platform').status.readyReplicas, 1);
  assert.deepEqual(business(), before);
  assert.ok(!existsSync(resolve(root, '.local/operations/maintenance.lock')));
  await until(() => !readdirSync(resolve(root, '.local/processes')).some(file => file.startsWith(`maintenance-${journal.pid}-`)), 'temporary checkpoint forward records close');
  return journal;
}
try {
  platform.verify(); const physicalBefore = await simulatorRead('/sim/v1/equipment'), before = business();
  const completed = launch('scripts/backup.mjs', [`--name=${completeName}`], 'capture.log');
  activeCapture = completed;
  assert.equal((await completed.finished).code, 0, 'The complete capture failed. Inspect capture.log and its recovery journal.');
  activeCapture = null;
  const checkpoint = verifyCheckpoint(completeName); const journal = await verifyResumed(completeName, before);
  assert.equal(checkpoint.manifest.databases.length, 6);
  cases.push({ candidate: 'A46', name: 'stopped-writer checkpoint captures six consistent databases and restores prior controls', status: 'passed', limitation: 'Checkpoint capture only; fresh-cluster restoration is a separate required result.', checkpoint: completeName, manifestSha256: checkpoint.manifestSha256, durationMillis: Date.parse(journal.resumedAt) - Date.parse(journal.startedAt), frozenMillis: Date.parse(checkpoint.manifest.finishedAt) - Date.parse(checkpoint.manifest.writersStoppedAt), databases: checkpoint.manifest.databases.map(database => ({ owner: database.owner, tables: database.rows.length, sha256: database.sha256, replayEvents: database.replay?.events.length, quarantined: database.delivery?.quarantined })) });

  const corruptName = `${runId}-corrupt`, corruptDirectory = resolve(root, '.local/checkpoints', corruptName);
  privateDirectory(corruptDirectory);
  for (const file of [...checkpoint.manifest.artifacts.map(item => item.file), ...checkpoint.manifest.databases.map(item => item.file)]) { const path = resolve(corruptDirectory, file); mkdirSync(dirname(path), { recursive: true }); copyFileSync(resolve(checkpoint.directory, file), path); }
  const copy = structuredClone(checkpoint.manifest); copy.name = corruptName;
  const saveCopy = () => { writeJson(resolve(corruptDirectory, 'manifest.json'), copy); writeFileSync(resolve(corruptDirectory, 'manifest.sha256'), sha(readFileSync(resolve(corruptDirectory, 'manifest.json'))) + '\n'); };
  saveCopy(); verifyCheckpoint(corruptName);
  copy.artifacts[0].file = '../outside-checkpoint'; saveCopy();
  assert.throws(() => verifyCheckpoint(corruptName), /Unexpected checkpoint artifact path/);
  copy.artifacts[0].file = checkpoint.manifest.artifacts[0].file; saveCopy();
  appendFileSync(resolve(corruptDirectory, 'core.dump'), Buffer.from('deliberate checksum probe'));
  assert.throws(() => verifyCheckpoint(corruptName), /core dump checksum differs/);
  assert.equal(verifyCheckpoint(completeName).manifestSha256, checkpoint.manifestSha256, 'The original checkpoint must remain unchanged.');
  cases.push({ candidate: 'A46', name: 'corrupt dump and path substitution fail verification before restoration', status: 'passed', rejectedCopy: corruptName, originalUnchanged: true });

  const interrupted = launch('scripts/backup.mjs', [`--name=${interruptedName}`], 'interrupted-capture.log');
  activeCapture = interrupted;
  interruptedPid = interrupted.child.pid;
  const savedPath = resolve(root, '.local/checkpoints', interruptedName, 'recovery-journal.json');
  await until(() => {
    if (interrupted.child.exitCode !== null) throw new Error('The interruption fixture ended before its stopped-writer boundary.');
    if (!existsSync(savedPath)) return false;
    let saved; try { saved = jsonFile(savedPath); } catch { return false; }
    if (saved.state !== 'DUMPING') return false;
    assert.equal(saved.pid, interruptedPid); assert.equal(saved.stopped.length, 6);
    interrupted.child.kill(); return true;
  }, 'real checkpoint process reaches its stopped-writer dump boundary', 180000);
  await interrupted.finished; activeCapture = null; await delay(1000);
  assert.ok(!existsSync(resolve(root, '.local/checkpoints', interruptedName, 'manifest.sha256')));
  const resumed = launch('scripts/resume-checkpoint.mjs', [interruptedName], 'resume.log');
  assert.equal((await resumed.finished).code, 0, 'Interrupted checkpoint resume failed. Inspect resume.log.');
  const recovered = await verifyResumed(interruptedName, before);
  cases.push({ candidate: 'A46/A52', name: 'real checkpoint termination after all writers stop recovers from the saved control journal', status: 'passed', interruptedCheckpoint: interruptedName, phaseAtTermination: 'DUMPING', resumedAt: recovered.resumedAt, captureNotClaimedSuccessful: true, physicalCommandsInvented: 0 });
  const physicalAfter = await simulatorRead('/sim/v1/equipment');
  for (const field of ['worldId', 'journalGeneration', 'journalHighWater']) assert.equal(physicalAfter[field], physicalBefore[field]);
  saveEvidence(runId, { cases, physicalBefore, physicalAfter, preservedBusinessRows: before });
  console.log(`Checkpoint verification passed ${cases.length} checks: ${runId}`);
} catch (error) {
  if (activeCapture) { activeCapture.child.kill(); await activeCapture.finished; }
  for (const name of [interruptedName, completeName]) {
    const path = resolve(root, '.local/checkpoints', name, 'recovery-journal.json');
    if (existsSync(path) && !jsonFile(path).resumedAt) {
      const cleanup = launch('scripts/resume-checkpoint.mjs', [name], `${name}-cleanup.log`);
      if ((await cleanup.finished).code !== 0) cases.push({ name: 'checkpoint cleanup', status: 'failed', checkpoint: name, detail: 'Inspect the saved cleanup log and recovery journal before further maintenance.' });
    }
  }
  cases.push({ name: 'checkpoint verification', status: 'failed', error: error.message });
  saveEvidence(runId, { cases, completeName, interruptedName, interruptedPid }); throw error;
}
