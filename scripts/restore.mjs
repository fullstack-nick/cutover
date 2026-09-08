// Application recovery into separate owned storage. The independent simulator is never restored or reset.
import assert from 'node:assert/strict';
import { existsSync, openSync, closeSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { verifyCheckpoint } from './lib/checkpoint.mjs';
import { root, owners, databases, jsonFile, writeJson, privateDirectory, maintenanceLock, target, call, until, simulatorRead, fingerprints } from './lib/local-platform.mjs';
import { provisionDatabases, ownerGrants } from './lib/owner-databases.mjs';
import { verifyImages, networkPlan, inspectPrimary, createCluster, restoredResources } from './lib/restoration-cluster.mjs';
import { restoreStep, replayCheckpoint, reconcileRestore, intakeIdentities } from './lib/restoration-runtime.mjs';

const argumentsByName = Object.fromEntries(process.argv.slice(2).map(value => { const match = value.match(/^--(name|run)=([a-z0-9][a-z0-9-]{2,63})$/);assert.ok(match, 'Use restore.mjs --name=<checkpoint> --run=<unique-recovery-name>.');return match.slice(1); }));
assert.equal(process.argv.length, 4);assert.equal(Object.keys(argumentsByName).length, 2);
const { name, run } = argumentsByName, checkpoint = verifyCheckpoint(name);
assert.ok(checkpoint.manifest.databases.find(database => database.owner === 'adapter').schema.some(item => item.version === '117'), 'This restore procedure requires a checkpoint captured with the V117 recovery tooling; older artifacts remain readable but need an explicit compatibility procedure.');
const directory = resolve(root, '.local/restoration/runs', run), journalPath = resolve(directory, 'journal.json');
assert.ok(!existsSync(directory), 'Recovery run names are immutable; inspect the previous journal and use its lifecycle procedure.');
const unlock = maintenanceLock(`restore:${run}`), platform = target('restoration');
const journal = { formatVersion: 1, run, name, restoreId: randomUUID(), pid: process.pid, state: 'PREFLIGHT', startedAt: new Date().toISOString(), manifestSha256: checkpoint.manifestSha256, imported: [], replay: {} };
const save = () => writeJson(journalPath, journal);
function apply(items) { if (items.length) platform.kube(['apply', '-f', '-'], { input: JSON.stringify({ apiVersion: 'v1', kind: 'List', items }) }); }
try {
  privateDirectory(resolve(root, '.local/restoration'));privateDirectory(directory);save();
  const images = await verifyImages(checkpoint), plan = networkPlan();journal.primary = inspectPrimary();journal.network = plan;
  const physical = await simulatorRead('/sim/v1/equipment');
  for (const key of ['worldId', 'journalGeneration']) assert.equal(physical[key], checkpoint.manifest.physicalEnd[key], 'The independent physical world differs from the checkpoint.');
  assert.equal(physical.completeHistory, true);assert.ok(physical.journalHighWater >= checkpoint.manifest.physicalEnd.journalHighWater);
  assert.equal((await simulatorRead('/sim/v1/test-controls/faults')).filter(item => item.remaining > 0).length, 0, 'Clear armed synthetic equipment faults before restoring.');
  journal.physicalStart = physical;journal.sourceRevision = call('git', ['rev-parse', 'HEAD']);save();
  // Persist the exact source node identity before stopping this one project workload.
  journal.state = 'STOPPING_PRIMARY';save();
  if (journal.primary.wasRunning) call('docker', ['stop', '--time', '30', 'cutover-control-plane']);
  assert.equal(JSON.parse(call('docker', ['inspect', 'cutover-control-plane']))[0].State.Running, false);
  journal.physicalInventoryAtPrimaryStop = await simulatorRead('/sim/v1/recovery-inventory?limit=1');save();
  journal.state = 'CREATING_CLUSTER';save();
  const address = await createCluster(platform, plan, images, directory, nodeId => { journal.restorationNodeId = nodeId;save(); });journal.simulatorAddress = address;
  const resources = restoredResources(checkpoint, address);writeJson(resolve(directory, 'resources.json'), { apiVersion: 'v1', kind: 'List', items: resources });
  journal.state = 'PROVISIONING_EMPTY_STORAGE';save();
  apply(resources.filter(item => item.kind === 'Namespace'));
  apply(resources.filter(item => item.kind !== 'Namespace' && !['Deployment', 'StatefulSet'].includes(item.kind)));
  apply(resources.filter(item => item.kind === 'StatefulSet' && ['application-db', 'rabbitmq'].includes(item.metadata.name)));
  await until(() => platform.get('statefulset', 'application-db', 'cutover-platform').status.readyReplicas === 1, 'fresh application database readiness', 180000);
  await until(() => platform.get('statefulset', 'rabbitmq', 'cutover-platform').status.readyReplicas === 1, 'fresh broker readiness', 180000);
  platform.verify();const credentials = jsonFile(resolve(checkpoint.directory, 'secrets/credentials.json'));
  provisionDatabases(platform, credentials);
  for (const owner of databases) assert.deepEqual(fingerprints(platform, owner), [], `${owner} restoration database is not empty.`);
  journal.state = 'IMPORTING';save();
  for (const database of checkpoint.manifest.databases) {
    journal.importing = database.owner;save();const fd = openSync(resolve(checkpoint.directory, database.file), 'r');
    try {
      platform.kube(['-n', 'cutover-platform', 'exec', '-i', 'application-db-0', '--', 'sh', '-c', `export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_restore -h 127.0.0.1 -U postgres --role=cutover_${database.owner}_migrator -d cutover_${database.owner} --single-transaction --exit-on-error --clean --if-exists --no-owner --no-acl`], { stdio: [fd, 'pipe', 'pipe'], timeout: 180000 });
    } finally { closeSync(fd); }
    assert.deepEqual(fingerprints(platform, database.owner), database.rows, `Restored ${database.owner} rows differ from the frozen checkpoint.`);
    platform.sql(database.owner, ownerGrants(database.owner));journal.imported.push(database.owner);delete journal.importing;save();
    console.log(`Restored ${database.owner}; every table fingerprint matches the checkpoint.`);
  }
  // Verify the entire frozen import before recording any recovery bookkeeping or starting a writer.
  for (const database of checkpoint.manifest.databases) assert.deepEqual(fingerprints(platform, database.owner), database.rows);
  journal.intakeIdentityBaseline = intakeIdentities(platform);
  journal.importVerifiedAt = new Date().toISOString();journal.state = 'INSTALLING_HOLDS';journal.frozenControls = {};save();
  for (const owner of Object.keys(owners)) {
    const version = checkpoint.manifest.controls[owner].version;assert.ok(Number.isSafeInteger(version));
    const result = platform.sql(owner, `BEGIN;
SELECT singleton FROM service_control WHERE singleton FOR UPDATE;
DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM service_control WHERE singleton AND workers_paused AND version=${version}) THEN RAISE EXCEPTION 'Checkpoint freeze changed'; END IF; END $$;
UPDATE service_control SET intake_paused=true,dispatch_paused=true,workers_paused=true,relay_paused=false,consumer_paused=false,version=version+1 WHERE singleton;
INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (gen_random_uuid(),'site-a','platform-recovery','restore-freeze','${journal.restoreId}','Restore validated checkpoint into isolated application storage.',${version},${version + 1},'HELD','{}');
COMMIT;
SELECT row_to_json(c) FROM service_control c WHERE singleton;`);
    journal.frozenControls[owner] = JSON.parse(result.split(/\r?\n/).findLast(line => line.startsWith('{')));save();
  }
  apply(resources.filter(item => ['Deployment', 'StatefulSet'].includes(item.kind) && !['application-db', 'rabbitmq'].includes(item.metadata.name)));
  platform.scale('keycloak', 1, 'cutover-platform');
  await until(() => platform.get('deployment', 'keycloak', 'cutover-platform').status.readyReplicas === 1, 'restored identity readiness', 180000);
  for (const service of Object.values(owners)) platform.scale(service, 1);
  for (const service of Object.values(owners)) await until(() => platform.get('deployment', service, 'cutover-apps').status.readyReplicas === 1, `${service} frozen readiness`, 180000);
  journal.state = 'RECONCILING';save();
  journal.recovery = restoreStep(platform, 'begin', { restoreId: journal.restoreId, checkpointName: name, manifestSha256: checkpoint.manifestSha256, worldId: physical.worldId, journalGeneration: physical.journalGeneration, checkpointAt: checkpoint.manifest.frozenAt, highWater: checkpoint.manifest.physicalEnd.journalHighWater, actor: 'platform-recovery', reason: 'Compare this isolated application restore with the retained independent physical journal.' });save();
  // Versioned owner-local control writes are performed while the original checkpoint workers are frozen.
  for (const owner of Object.keys(owners)) {
    const before = Number(platform.sql(owner, 'SELECT version FROM service_control WHERE singleton;'));
    const after = platform.sql(owner, `BEGIN;UPDATE service_control SET workers_paused=false,version=version+1 WHERE singleton AND workers_paused AND intake_paused AND dispatch_paused AND version=${before} RETURNING version;
INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (gen_random_uuid(),'site-a','platform-recovery','restore-reconcile','${journal.restoreId}','Resume original-message and original-command reconciliation with dispatch held.',${before},${before + 1},'DISPATCH_HELD','{}');COMMIT;`);
    assert.ok(after.split(/\r?\n/).includes(String(before + 1)), 'A recovery control version changed unexpectedly.');
  }
  await replayCheckpoint(platform, checkpoint, journal, journalPath);
  journal.recovery = await reconcileRestore(platform, journal.restoreId);
  journal.physicalEnd = await simulatorRead('/sim/v1/equipment');
  for (const key of ['worldId', 'journalGeneration']) assert.equal(journal.physicalEnd[key], physical[key]);
  journal.rpo = { checkpointAt: checkpoint.manifest.frozenAt, observedAt: journal.physicalEnd.observedAt, elapsedSeconds: (Date.parse(journal.physicalEnd.observedAt) - Date.parse(checkpoint.manifest.frozenAt)) / 1000, missingPhysicalContext: journal.recovery.unresolvedCount, missingCommandContext: journal.recovery.inventoryUnresolved };
  journal.state = journal.recovery.state === 'VERIFIED' ? 'VERIFIED_HELD' : 'QUARANTINED';journal.finishedAt = new Date().toISOString();journal.elapsedSeconds = (Date.parse(journal.finishedAt) - Date.parse(journal.startedAt)) / 1000;save();
  writeJson(resolve(root, '.local/restoration/active.json'), { run, restoreId: journal.restoreId, name, primaryId: journal.primary.id });
  console.log(`Restoration ${run}: ${journal.state}; dispatch remains held. Elapsed ${journal.elapsedSeconds.toFixed(3)} seconds. Private evidence: .local/restoration/runs/${run}.`);
} catch (error) {
  journal.failedStage = journal.state;journal.state = 'FAILED';journal.error = error.message;
  if (existsSync(directory)) save();
  console.error(`Restoration ${run} failed at ${journal.failedStage}: ${error.message}`);
  console.error('Keep the restored dispatch hold. Use the recorded restoration lifecycle operation to stop that node and resume the preserved demo; never reset the simulator.');
  process.exitCode = 1;
} finally { unlock(); }
