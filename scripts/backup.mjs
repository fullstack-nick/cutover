// A stopped-writer application checkpoint. The simulator and its volume are never stopped or copied.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, copyFileSync, mkdirSync, existsSync, openSync, closeSync } from 'node:fs';
import { resolve } from 'node:path';
import { root, owners, databases, namespaces, sha, jsonFile, writeJson, privateDirectory, call, until, maintenanceLock, target, request, maintenanceToken, simulatorRead, fingerprints } from './lib/local-platform.mjs';

const name = process.argv.find(value => value.startsWith('--name='))?.slice(7);
if (!/^[a-z0-9][a-z0-9-]{2,63}$/.test(name ?? '') || process.argv.slice(2).length !== 1) throw new Error('Use node scripts/backup.mjs --name=<unique-lowercase-checkpoint-name>.');
const directory = resolve(root, '.local/checkpoints', name);
if (existsSync(directory)) throw new Error('Checkpoint names are immutable. Use a new name after inspecting any earlier failure.');
const unlock = maintenanceLock(`backup:${name}`), platform = target('demo'), forwards = new Map();
const journal = { name, pid: process.pid, state: 'PREFLIGHT', startedAt: new Date().toISOString(), prior: {}, paused: {}, pending: {}, stopped: [], cleanupErrors: [] };
let proxy, credentials, restartsRequired = false;
const controlPath = '/internal/v1/sites/site-a/test-controls';
function saveJournal() { writeJson(resolve(directory, 'recovery-journal.json'), journal); }
async function control(owner, values, stage) {
  const bearer = await maintenanceToken(proxy.origin, credentials);
  const origin = forwards.get(owner).origin;
  const before = await request(origin, controlPath, { bearer });
  if (journal.paused[owner]) assert.equal(before.version, journal.paused[owner].version, `The ${owner} controls changed outside this checkpoint.`);
  const command = { method: 'POST', key: `checkpoint-${name}-${owner}-${stage}`, body: { expectedVersion: before.version, ...values, reason: `Application checkpoint ${name}: ${stage}; preserve accepted work and the independent physical journal.` } };
  journal.pending[owner] = command; saveJournal();
  let after;
  try { after = await request(origin, controlPath, { bearer, ...command }); }
  catch { after = await request(origin, controlPath, { bearer, ...command }); } // Same key and bytes after an uncertain transport result.
  const observed = await request(origin, controlPath, { bearer }); assert.equal(observed.version, after.version);
  journal.paused[owner] = observed; delete journal.pending[owner]; saveJournal(); return before;
}
function delivery(owner) {
  return JSON.parse(platform.sql(owner, "SELECT jsonb_build_object('unpublished',(SELECT count(*) FROM outbox WHERE published_at IS NULL),'pending',(SELECT count(*) FROM inbox WHERE state IN ('RECEIVED','PENDING')),'quarantined',(SELECT count(*) FROM inbox WHERE state='QUARANTINED'));"));
}
function cleanResource(item) {
  const result = structuredClone(item); delete result.status;
  for (const key of ['uid', 'resourceVersion', 'creationTimestamp', 'generation', 'managedFields', 'selfLink', 'ownerReferences']) delete result.metadata[key];
  delete result.metadata.annotations?.['kubectl.kubernetes.io/last-applied-configuration'];
  if (result.kind === 'Service') {
    for (const key of ['clusterIP', 'clusterIPs', 'ipFamilies', 'ipFamilyPolicy', 'healthCheckNodePort']) delete result.spec[key];
    for (const port of result.spec.ports) delete port.nodePort;
  }
  return result;
}
try {
  privateDirectory(directory); saveJournal(); platform.verify();
  credentials = jsonFile(resolve(root, '.local/secrets/credentials.json'));
  for (const namespace of ['cutover-apps', 'cutover-platform']) {
    const jobs = JSON.parse(platform.kube(['-n', namespace, 'get', 'jobs', '-o', 'json'])).items;
    assert.ok(jobs.every(job => !job.status?.active), 'Finish active migrations before creating a checkpoint.');
  }
  for (const [owner, service] of Object.entries(owners)) {
    const deployment = platform.owned(platform.get('deployment', service, 'cutover-apps'));
    assert.equal(deployment.spec.replicas, 1, 'The checkpoint procedure requires the complete running demo profile.');
    assert.equal(deployment.status.readyReplicas, 1, 'A writer is not ready. Repair it before checkpoint capture.');
    forwards.set(owner, await platform.forward(service));
    assert.equal(Number(platform.sql(owner, 'SELECT count(*) FROM process_faults WHERE remaining>0;')), 0, 'Clear armed process-exit faults before checkpoint capture.');
  }
  assert.equal(Number(platform.sql('adapter', 'SELECT count(*) FROM migration_process_faults WHERE remaining>0;')), 0, 'Clear armed migration-exit faults before checkpoint capture.');
  assert.equal(platform.sql('adapter', "SELECT coalesce((to_jsonb(c)->>'restoration_required')::boolean,false) FROM service_control c WHERE singleton;"), 'f', 'Complete the current restoration before capturing another application checkpoint.');
  const identity = platform.owned(platform.get('deployment', 'keycloak', 'cutover-platform'));
  assert.equal(identity.spec.replicas, 1); assert.equal(identity.status.readyReplicas, 1);
  proxy = await platform.forward('proxy');
  journal.runtime = [];
  for (const namespace of namespaces) journal.runtime.push(...JSON.parse(platform.kube(['-n', namespace, 'get', 'pods', '-l', 'app.kubernetes.io/part-of=cutover', '-o', 'json'])).items.map(pod => ({ namespace, name: pod.metadata.name, uid: pod.metadata.uid, containers: (pod.status.containerStatuses ?? []).map(container => ({ name: container.name, image: container.image, imageId: container.imageID, ready: container.ready, restartCount: container.restartCount })) })));
  const physicalStart = await simulatorRead('/sim/v1/equipment', credentials);
  assert.equal(physicalStart.completeHistory, true, 'Capture requires a known simulator history.');
  const armedEquipment = (await simulatorRead('/sim/v1/test-controls/faults', credentials)).filter(fault => fault.remaining > 0);
  assert.equal(armedEquipment.length, 0, 'Clear or consume armed synthetic equipment faults before checkpoint capture.');
  for (const owner of Object.keys(owners)) {
    const status = await request(forwards.get(owner).origin, controlPath, { bearer: await maintenanceToken(proxy.origin, credentials) });
    for (const flag of ['workersPaused', 'criticalStorage', 'relayPaused', 'consumerPaused']) assert.equal(status[flag], false, `${owner} ${flag} prevents a settled checkpoint.`);
    journal.prior[owner] = status;
  }
  saveJournal(); journal.state = 'PAUSING'; saveJournal();
  for (const owner of ['core', 'returns']) await control(owner, { intakePaused: true }, 'pause-intake');
  for (const owner of Object.keys(owners)) await control(owner, { dispatchPaused: true }, 'pause-dispatch');
  await until(() => Object.keys(owners).every(owner => { const state = delivery(owner); return state.unpublished === 0 && state.pending === 0; }), 'source outboxes and received inbox work settle', 90000);
  for (const owner of Object.keys(owners)) await control(owner, { workersPaused: true }, 'freeze-writers');
  journal.frozenAt = new Date().toISOString(); journal.state = 'STOPPING_WRITERS'; saveJournal();
  // Persist intent before each scale; interrupted cleanup can safely restore only these named deployments.
  restartsRequired = true;
  for (const service of [...Object.values(owners), 'keycloak']) {
    journal.stopped.push(service); saveJournal(); platform.scale(service, 0, service === 'keycloak' ? 'cutover-platform' : 'cutover-apps');
  }
  await platform.writersStopped(); journal.writersStoppedAt = new Date().toISOString(); journal.state = 'DUMPING'; saveJournal();
  for (const forward of forwards.values()) forward.close(); forwards.clear();
  const manifest = { formatVersion: 1, name, source: { profile: 'demo', cluster: platform.cluster, revision: call('git', ['rev-parse', 'HEAD']), dirty: call('git', ['status', '--porcelain']).length > 0, runtime: journal.runtime }, startedAt: journal.startedAt, frozenAt: journal.frozenAt, writersStoppedAt: journal.writersStoppedAt,
    databases: [], physicalStart, physicalFrozen: await simulatorRead('/sim/v1/equipment', credentials), controls: journal.paused, priorControls: journal.prior,
    routes: JSON.parse(platform.sql('adapter', "SELECT jsonb_agg(to_jsonb(r) ORDER BY site_id,zone_id) FROM zone_routes r;")),
    unsettledCommands: JSON.parse(platform.sql('adapter', "SELECT coalesce(jsonb_agg(jsonb_build_object('commandId',command_id,'movementId',movement_id,'siteId',site_id,'owner',owner,'state',state,'version',version) ORDER BY command_id),'[]'::jsonb) FROM command_journal WHERE state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION');")),
    replayHorizonDays: 7, restorePolicy: 'Separate empty application cluster; dispatch disabled; live simulator history must be reconciled; original event and command IDs only.', artifacts: [] };
  const resources = [];
  for (const namespace of namespaces) {
    resources.push(cleanResource(platform.owned(platform.get('namespace', namespace))));
    for (const kind of ['services', 'deployments', 'statefulsets', 'configmaps', 'secrets', 'networkpolicies', 'endpointslices']) {
      const items = JSON.parse(platform.kube(['-n', namespace, 'get', kind, '-l', 'app.kubernetes.io/part-of=cutover', '-o', 'json'])).items;
      // Kubernetes regenerates selector-backed endpoints with the new pod addresses and service UIDs.
      resources.push(...items.filter(item => item.kind !== 'EndpointSlice' || item.metadata.labels['endpointslice.kubernetes.io/managed-by'] === 'cutover-renderer').map(item => cleanResource(platform.owned(item))));
    }
  }
  writeJson(resolve(directory, 'resources.json'), { apiVersion: 'v1', kind: 'List', items: resources });
  writeJson(resolve(directory, 'node-images.json'), jsonFile(resolve(root, '.local/images/node-images.json')));
  writeJson(resolve(directory, 'versions.lock.json'), jsonFile(resolve(root, 'infra/versions.lock.json')));
  const privateFiles = ['credentials.json', 'equipment/scenario.p12', 'equipment/ca.pem'];
  for (const file of privateFiles) { const destination = resolve(directory, 'secrets', file); mkdirSync(resolve(destination, '..'), { recursive: true }); copyFileSync(resolve(root, '.local/secrets', file), destination); }
  for (const owner of databases) {
    const rowsBefore = fingerprints(platform, owner);
    const file = `${owner}.dump`, fd = openSync(resolve(directory, file), 'wx', 0o600);
    try { platform.kube(['-n', 'cutover-platform', 'exec', 'application-db-0', '--', 'sh', '-c', `export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_dump -h 127.0.0.1 -U postgres --role=cutover_${owner}_migrator -d cutover_${owner} -Fc --no-owner --no-acl --exclude-schema=cutover_ops`], { stdio: ['ignore', fd, 'pipe'], timeout: 180000 }); }
    finally { closeSync(fd); }
    assert.deepEqual(fingerprints(platform, owner), rowsBefore, `${owner} tables changed during their dump.`);
    const schema = owner === 'keycloak' ? JSON.parse(platform.sql(owner, "SELECT jsonb_agg(jsonb_build_object('id',id,'author',author,'checksum',md5sum) ORDER BY orderexecuted) FROM databasechangelog;")) : JSON.parse(platform.sql(owner, "SELECT jsonb_agg(jsonb_build_object('version',version,'script',script,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM flyway_schema_history;"));
    const replay = owner === 'keycloak' ? undefined : JSON.parse(platform.sql(owner, "SELECT jsonb_build_object('events',coalesce(jsonb_agg(jsonb_build_object('eventId',event_id,'source',source,'siteId',site_id,'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'createdAt',created_at,'publishedAt',published_at,'envelopeSha256',encode(sha256(convert_to(envelope::text,'UTF8')),'hex')) ORDER BY created_at,event_id),'[]'::jsonb),'bytes',coalesce(sum(payload_bytes),0)) FROM outbox;"));
    manifest.databases.push({ owner, file, sha256: sha(readFileSync(resolve(directory, file))), rows: rowsBefore, schema, ...(replay ? { replay, delivery: delivery(owner) } : {}) });
    console.log(`Checkpoint captured ${owner}; all application writers remain stopped.`);
  }
  // Check every database again after the final dump: sequential dumps must describe one frozen interval.
  await platform.writersStopped();
  for (const database of manifest.databases) assert.deepEqual(fingerprints(platform, database.owner), database.rows, `${database.owner} changed during the distributed dump interval.`);
  manifest.physicalEnd = await simulatorRead('/sim/v1/equipment', credentials);
  for (const field of ['worldId', 'journalGeneration']) assert.equal(manifest.physicalEnd[field], physicalStart[field]);
  assert.equal(manifest.physicalEnd.completeHistory, true);
  manifest.finishedAt = new Date().toISOString();
  for (const file of ['resources.json', 'node-images.json', 'versions.lock.json', ...privateFiles.map(file => `secrets/${file}`)]) manifest.artifacts.push({ file, sha256: sha(readFileSync(resolve(directory, file))) });
  writeJson(resolve(directory, 'manifest.json'), manifest);
  writeFileSync(resolve(directory, 'manifest.sha256'), sha(readFileSync(resolve(directory, 'manifest.json'))) + '\n');
  journal.state = 'CAPTURED'; journal.finishedAt = manifest.finishedAt; saveJournal();
} catch (error) {
  journal.state = 'FAILED'; journal.error = error.message; if (existsSync(directory)) saveJournal(); process.exitCode = 1;
  console.error(`Checkpoint ${name} failed: ${error.message}`);
} finally {
  try {
    if (Object.keys(journal.pending).length) throw new Error('A control response remains uncertain. Resume using the exact command recorded in the checkpoint recovery journal.');
    if (restartsRequired) {
      platform.scale('keycloak', 1, 'cutover-platform');
      await until(() => platform.get('deployment', 'keycloak', 'cutover-platform').status.readyReplicas === 1, 'identity restarts', 180000);
      for (const service of Object.values(owners)) platform.scale(service, 1);
      for (const [owner, service] of Object.entries(owners)) {
        await until(() => platform.get('deployment', service, 'cutover-apps').status.readyReplicas === 1, `${service} restarts`, 180000);
        forwards.set(owner, await platform.forward(service));
      }
    }
    // Reverse acquisition order and restore exactly the observed gates. A changed control version stops cleanup.
    for (const owner of Object.keys(journal.paused).reverse()) {
      const prior = journal.prior[owner];
      const values = Object.fromEntries(['intakePaused', 'dispatchPaused', 'workersPaused', 'criticalStorage', 'relayPaused', 'consumerPaused'].map(field => [field, prior[field]]));
      await control(owner, values, 'resume-prior-controls');
    }
    if (Object.keys(journal.paused).length) { journal.resumedAt = new Date().toISOString(); saveJournal(); }
  } catch (error) { journal.cleanupErrors.push(error.message); saveJournal(); console.error('Checkpoint cleanup needs attention. Inspect the saved recovery journal; do not change command identities or reset data.'); process.exitCode = 1; }
  for (const forward of forwards.values()) forward.close(); proxy?.close(); unlock();
}
if (!process.exitCode) console.log(`Checkpoint ${name} is complete, checksum verified, and the prior application controls are restored. Artifacts and credentials remain private under .local/checkpoints/${name}.`);
