import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { root, owners, jsonFile, writeJson, call, until, maintenanceLock, target, simulatorRead, request, maintenanceToken, controlWriteBarrier } from './lib/local-platform.mjs';
import { verifyCheckpoint } from './lib/checkpoint.mjs';
import { restorationNetwork, simulatorContainer, run as runCommand } from './lib/restoration-cluster.mjs';
import { restoreStep, reconcileRestore, delivery, intakeIdentities } from './lib/restoration-runtime.mjs';

const args = process.argv.slice(2), run = args.find(value => value.startsWith('--run='))?.slice(6), action = args.find(value => value.startsWith('--action='))?.slice(9);
assert.equal(args.length, 2);assert.match(run ?? '', /^[a-z0-9][a-z0-9-]{2,63}$/);
assert.ok(['Release', 'Reconcile', 'Open', 'Start', 'Stop', 'ResumeDemo', 'Remove'].includes(action), 'Use an explicit restoration lifecycle action.');
const directory = resolve(root, '.local/restoration/runs', run), journalPath = resolve(directory, 'journal.json'), journal = jsonFile(journalPath);
assert.equal(journal.run, run);
assert.match(journal.restoreId, /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/);
const platform = target('restoration'), unlock = maintenanceLock(`restoration-${action}:${run}`), forwards = [];
const save = () => writeJson(journalPath, journal);
const inspect = name => { const ids = call('docker', ['ps', '-a', '--filter', `name=^/${name}$`, '-q']);return ids ? JSON.parse(call('docker', ['inspect', name]))[0] : null; };
function primary() { const node = inspect('cutover-control-plane');assert.equal(node?.Config.Labels['io.x-k8s.kind.cluster'], 'cutover');assert.equal(node.Id, journal.primary.id, 'The preserved primary node identity changed.');return node; }
function restoration() { const node = inspect('cutover-restored-control-plane');assert.equal(node?.Config.Labels['io.x-k8s.kind.cluster'], 'cutover-restored');assert.equal(node.Id, journal.restorationNodeId, 'This node belongs to a different recovery run.');return node; }
function forward(action, profile, targetName) { call('pwsh', ['-NoProfile', '-NonInteractive', '-File', resolve(root, 'scripts/forward.ps1'), '-Action', action, '-Profile', profile, '-Target', targetName]); }
const forwardTargets = ['console', 'grafana', 'prometheus', 'tempo', 'core-api', 'adapter-api'];
function stopForwards(profile) { for (const name of forwardTargets) if (existsSync(resolve(root, '.local/processes', `${profile === 'restoration' ? 'restoration-' : ''}${name}-forward.json`))) forward('Stop', profile, name); }
async function readyProcesses(environment, nodeStartedAt) {
  for (const [namespace, service] of [...Object.values(owners).map(service => ['cutover-apps', service]), ['cutover-platform', 'keycloak'], ['cutover-apps', 'proxy']]) await until(() => {
    const pods = JSON.parse(environment.kube(['-n', namespace, 'get', 'pods', '-l', `app.kubernetes.io/name=${service},app.kubernetes.io/part-of=cutover`, '-o', 'json'])).items.filter(pod => !pod.metadata.deletionTimestamp);
    // A Ready value restored from etcd may still describe a process from before the Docker node stopped.
    return pods.length === 1 && pods[0].status.conditions?.some(condition => condition.type === 'Ready' && condition.status === 'True')
      && pods[0].status.containerStatuses?.length > 0 && pods[0].status.containerStatuses.every(container => container.ready && Date.parse(container.state.running?.startedAt ?? '') >= nodeStartedAt);
  }, `${service} has a ready process from the current ${environment.profile} node start`, 180000);
}
async function stopRestoration() {
  const node = inspect('cutover-restored-control-plane');if (node) restoration();stopForwards('restoration');
  if (node?.State.Running) {
    if (journal.intakeIdentityBaseline) {
      platform.verify();
      if (journal.stopCycle?.nodeStartedAt !== node.State.StartedAt) journal.stopCycle = { id: randomUUID(), nodeStartedAt: node.State.StartedAt, controls: {}, frozenVersions: {}, resumed: {} };
      save();
      for (const owner of Object.keys(owners)) {
        journal.stopCycle.controls[owner] ??= JSON.parse(platform.sql(owner, 'SELECT row_to_json(c) FROM service_control c WHERE singleton;'));save();
        platform.sql(owner, `BEGIN;
${controlWriteBarrier}
WITH prior AS (SELECT version FROM service_control WHERE singleton FOR UPDATE), changed AS
 (UPDATE service_control c SET intake_paused=true,dispatch_paused=true,workers_paused=true,version=c.version+1 FROM prior WHERE c.singleton AND c.version=prior.version RETURNING c.version)
INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail)
 SELECT gen_random_uuid(),'site-a','platform-recovery','restore-stop','${journal.restoreId}','Freeze the restored application before preserving its local storage.',prior.version,changed.version,'HELD','{}' FROM prior,changed;
COMMIT;`);
        journal.stopCycle.frozenVersions[owner] = Number(platform.sql(owner, 'SELECT version FROM service_control WHERE singleton;'));save();
      }
      journal.intakeAtStop = { nodeStartedAt: node.State.StartedAt, identities: intakeIdentities(platform), checkedAt: new Date().toISOString() };save();
    }
    call('docker', ['stop', '--time', '30', node.Id]);
    journal.physicalAtStop = await simulatorRead('/sim/v1/recovery-inventory?limit=1');save();
  }
  journal.stoppedAt = new Date().toISOString();save();
}
try {
  if (['Release', 'Reconcile', 'Open'].includes(action)) {
    assert.equal(primary().State.Running, false, 'Only one Cutover application environment may be active against this physical world.');
    assert.equal(restoration().State.Running, true);platform.verify();
  }
  if (action === 'Reconcile') {
    assert.ok(['VERIFIED_HELD', 'RECONCILING', 'QUARANTINED'].includes(journal.state), 'Reconciliation cannot replace an already released or incomplete import operation.');
    journal.recovery = await reconcileRestore(platform, journal.restoreId);journal.state = journal.recovery.state === 'VERIFIED' ? 'VERIFIED_HELD' : 'QUARANTINED';save();
    console.log(`Restoration ${run}: ${journal.state}; physical dispatch remains held.`);
  } else if (action === 'Release') {
    const checkpoint = verifyCheckpoint(journal.name);assert.equal(checkpoint.manifestSha256, journal.manifestSha256);
    assert.ok(['VERIFIED_HELD', 'RELEASING', 'RELEASED'].includes(journal.state), 'Resolve the recorded restore findings before release.');
    for (const database of checkpoint.manifest.databases.filter(item => item.owner !== 'keycloak')) {
      assert.deepEqual(journal.replay[database.owner]?.confirmed, database.replay.events.map(event => event.eventId), 'The complete original checkpoint replay range must be confirmed before release.');
      assert.ok(!journal.replay[database.owner].pending, 'An uncertain replay batch must retain its original event identities.');
    }
    assert.ok(Object.keys(owners).every(owner => { const state = delivery(platform, owner);return state.unpublished === 0 && state.pending === 0; }), 'Settle restored deliveries before release.');
    if (journal.state === 'RELEASED') { console.log(`Restoration ${run} was already released; the recorded action is unchanged.`); }
    else {
      journal.state = 'RELEASING';journal.releaseRequest ??= { restoreId: journal.restoreId, expectedVersion: journal.recovery.version, actor: 'platform-recovery', reason: 'Release the verified application checkpoint after original-event replay and current physical reconciliation.' };save();
      journal.recovery = restoreStep(platform, 'release', journal.releaseRequest);save();
      if (journal.recovery.state !== 'RELEASED') {
        journal.state = journal.recovery.state === 'SCANNING' ? 'RECONCILING' : 'QUARANTINED';delete journal.releaseRequest;save();throw new Error('Physical evidence changed before release; dispatch remains held. Reconcile the same saved session.');
      }
      // Restart only these five writers with ordinary retention, while their dispatch/intake gates remain shut.
      for (const service of Object.values(owners)) platform.kube(['-n', 'cutover-apps', 'set', 'env', `deployment/${service}`, 'CUTOVER_RESTORE_RETENTION_HELD-']);
      for (const service of Object.values(owners)) await until(() => {
        const state = platform.get('deployment', service, 'cutover-apps');return state.status.observedGeneration === state.metadata.generation && state.status.updatedReplicas === 1 && state.status.readyReplicas === 1;
      }, `${service} resumes ordinary message retention`, 180000);
      const proxy = await platform.forward('proxy');forwards.push(proxy);
      const credentials = jsonFile(resolve(checkpoint.directory, 'secrets/credentials.json'));
      journal.releasedControls ??= {};
      for (const owner of ['adapter', 'execution', 'shadow', 'core', 'returns']) {
        const connection = await platform.forward(owners[owner]);forwards.push(connection);
        const bearer = await maintenanceToken(proxy.origin, credentials), path = '/internal/v1/sites/site-a/test-controls';
        if (!journal.releasedControls[owner]) {
          const before = await request(connection.origin, path, { bearer });
          if (!journal.pendingControl) assert.ok(before.intakePaused && before.dispatchPaused, 'A restore gate changed outside this recorded release.');
          const values = Object.fromEntries(['intakePaused', 'dispatchPaused', 'workersPaused', 'criticalStorage', 'relayPaused', 'consumerPaused'].map(field => [field, checkpoint.manifest.priorControls[owner][field]]));
          journal.pendingControl ??= { owner, method: 'POST', key: `restore-${run}-${owner}-release`, body: { expectedVersion: before.version, ...values, reason: 'Restore the checkpoint source controls after verified independent physical reconciliation.' } };save();
          assert.equal(journal.pendingControl.owner, owner);
          const { method, key, body } = journal.pendingControl;
          journal.releasedControls[owner] = await request(connection.origin, path, { bearer, method, key, body });delete journal.pendingControl;save();
        }
      }
      journal.state = 'RELEASED';journal.releasedAt = new Date().toISOString();journal.restoreDurationSeconds = (Date.parse(journal.releasedAt) - Date.parse(journal.startedAt)) / 1000;save();
      console.log(`Restoration ${run} released after ${journal.restoreDurationSeconds.toFixed(3)} seconds; original source controls are restored.`);
    }
  } else if (action === 'Start') {
    assert.equal(primary().State.Running, false, 'The primary must remain stopped while this application copy resumes.');
    const node = restoration();assert.ok(journal.stopCycle && journal.physicalAtStop, 'This copy has no completed, recorded stop cycle.');
    if (!node.State.Running) {
      assert.equal(node.State.StartedAt, journal.intakeAtStop?.nodeStartedAt, 'The retained stop evidence belongs to a different node start.');
      const physical = await simulatorRead('/sim/v1/recovery-inventory?limit=1');
      for (const key of ['worldId', 'journalGeneration', 'totalCommands']) assert.equal(physical[key], journal.physicalAtStop[key], 'Another environment changed physical command history while this copy was stopped.');
      assert.equal(physical.completeHistory, true);journal.startingStopCycle = journal.stopCycle.id;save();call('docker', ['start', node.Id]);
    } else assert.equal(journal.startingStopCycle, journal.stopCycle.id, 'This copy was started outside its recorded lifecycle.');
    await readyProcesses(platform, Date.parse(restoration().State.StartedAt));platform.verify();
    const checkpoint = verifyCheckpoint(journal.name), credentials = jsonFile(resolve(checkpoint.directory, 'secrets/credentials.json'));
    const proxy = await platform.forward('proxy');forwards.push(proxy);
    const flags = { intakePaused: 'intake_paused', dispatchPaused: 'dispatch_paused', workersPaused: 'workers_paused', criticalStorage: 'critical_storage', relayPaused: 'relay_paused', consumerPaused: 'consumer_paused' };
    for (const owner of ['adapter', 'execution', 'shadow', 'core', 'returns']) {
      if (journal.stopCycle.resumed[owner]) continue;
      const connection = await platform.forward(owners[owner]);forwards.push(connection);const bearer = await maintenanceToken(proxy.origin, credentials);
      const body = { expectedVersion: journal.stopCycle.frozenVersions[owner], ...Object.fromEntries(Object.entries(flags).map(([wire, column]) => [wire, journal.stopCycle.controls[owner][column]])), reason: 'Resume this preserved application copy with its original stop-cycle controls and physical world.' };
      journal.stopCycle.resumed[owner] = await request(connection.origin, '/internal/v1/sites/site-a/test-controls', { method: 'POST', bearer, key: `restore-${journal.stopCycle.id}-${owner}-start`, body });save();
    }
    journal.restorationResumedAt = new Date().toISOString();save();console.log(`Resumed restoration ${run} with its preserved data, controls and physical world.`);
  } else if (action === 'Open') {
    stopForwards('demo');for (const name of ['console', 'grafana', 'prometheus', 'tempo']) forward('Start', 'restoration', name);
    console.log(`Opened restoration ${run} on the normal loopback console and dashboard ports.`);
  } else if (action === 'Stop') {
    await stopRestoration();console.log(`Stopped only restoration ${run}; its storage and the independent simulator remain intact.`);
  } else if (action === 'ResumeDemo') {
    primary();await stopRestoration();
    if (journal.intakeIdentityBaseline) {
      assert.equal(journal.intakeAtStop?.nodeStartedAt, restoration().State.StartedAt, 'The last stopped-copy proof belongs to a different node start.');
      assert.deepEqual(journal.intakeAtStop.identities, journal.intakeIdentityBaseline, 'The restored environment accepted additional orders or receipts; preserve that copy instead of resuming a stale primary.');
    }
    if (journal.physicalInventoryAtPrimaryStop) {
      const current = await simulatorRead('/sim/v1/recovery-inventory?limit=1');
      for (const key of ['worldId', 'journalGeneration', 'totalCommands']) assert.equal(current[key], journal.physicalInventoryAtPrimaryStop[key], 'Restoration added commands or changed the physical world. Keep that application storage; do not resume a stale primary.');
      assert.equal(current.completeHistory, true);
    } else assert.equal(journal.failedStage, 'STOPPING_PRIMARY', 'No physical baseline was recorded for safely resuming the primary.');
    if (!primary().State.Running) call('docker', ['start', journal.primary.id]);
    const nodeStartedAt = Date.parse(primary().State.StartedAt);
    const demo = target('demo');await until(() => { try { return demo.get('node', 'cutover-control-plane').status.conditions.some(item => item.type === 'Ready' && item.status === 'True'); } catch { return false; } }, 'preserved demo node restarts', 180000);
    await readyProcesses(demo, nodeStartedAt);
    stopForwards('restoration');forward('Start', 'demo', 'console');journal.primaryResumedAt = new Date().toISOString();save();
    console.log(`Resumed the preserved Cutover demo after stopping restoration ${run}.`);
  } else if (action === 'Remove') {
    // Removal is a separate explicit action; Stop and ResumeDemo never remove data.
    await stopRestoration();const node = inspect('cutover-restored-control-plane');
    if (node && journal.intakeIdentityBaseline) {
      assert.equal(journal.intakeAtStop?.nodeStartedAt, node.State.StartedAt, 'The stopped-copy proof is stale.');
      assert.deepEqual(journal.intakeAtStop.identities, journal.intakeIdentityBaseline, 'The restored copy contains additional accepted business work; preserve its storage.');
    }
    if (journal.physicalInventoryAtPrimaryStop) {
      const current = await simulatorRead('/sim/v1/recovery-inventory?limit=1');
      for (const key of ['worldId', 'journalGeneration', 'totalCommands']) assert.equal(current[key], journal.physicalInventoryAtPrimaryStop[key], 'The restored environment may own additional physical work; preserve its storage.');
      assert.equal(current.completeHistory, true);
    }
    if (node) { restoration();await runCommand(resolve(root, '.local/tools/kind.exe'), ['delete', 'cluster', '--name', 'cutover-restored', '--kubeconfig', platform.kubeconfig], resolve(directory, 'remove.log')); }
    const networkIds = call('docker', ['network', 'ls', '--filter', `name=^${restorationNetwork}$`, '-q']);
    if (networkIds) {
      let network = JSON.parse(call('docker', ['network', 'inspect', restorationNetwork]))[0];
      assert.equal(network.Labels['dev.cutover.project'], 'cutover');assert.equal(network.Labels['dev.cutover.profile'], 'restoration');
      const simulator = inspect(simulatorContainer);assert.equal(simulator?.Id, journal.primary.simulatorId);
      if (network.Containers?.[simulator.Id]) call('docker', ['network', 'disconnect', restorationNetwork, simulatorContainer]);
      network = JSON.parse(call('docker', ['network', 'inspect', restorationNetwork]))[0];assert.deepEqual(Object.keys(network.Containers ?? {}), [], 'Unexpected connected containers prevent restoration network removal.');
      call('docker', ['network', 'rm', restorationNetwork]);
    }
    journal.removedAt = new Date().toISOString();save();console.log(`Removed only the separately restored cluster and network for ${run}; evidence and source checkpoints remain private and intact.`);
  }
} catch (error) { journal.lifecycleError = { action, message: error.message, at: new Date().toISOString() };save();console.error(error.message);process.exitCode = 1; }
finally { for (const connection of forwards) connection.close();unlock(); }
