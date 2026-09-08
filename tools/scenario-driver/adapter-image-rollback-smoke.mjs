import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const selected = process.argv.find(arg => arg.startsWith('--image='))?.slice(8);
assert.match(selected ?? '', /^docker\.io\/cutover\/equipment-adapter@sha256:[a-f0-9]{64}$/, 'Select a previously recorded immutable local adapter image.');
const runId = 'adapter-image-rollback-' + Date.now(), prefix = '/api/v1/sites/site-a';
const evidence = { cases: [], selectedImage: selected, orders: [] };
const scope = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-apps'];
const controlPath = '/internal/v1/sites/site-a/test-controls';
let original, changed = false, gateChanged = false;
function capture(tool, args) {
  const result = spawnSync(tool, args, { encoding: 'utf8', windowsHide: true, timeout: 20000 });
  assert.equal(result.status, 0, tool + ' must complete its scoped operation.'); return result.stdout;
}
async function run(tool, args) {
  await new Promise((done, failed) => {
    const child = spawn(tool, args, { stdio: 'inherit', windowsHide: true });
    child.on('error', failed); child.on('exit', code => code === 0 ? done() : failed(Error(tool + ' failed with ' + code)));
  });
}
async function until(check, description, timeout = 90000) {
  const end = Date.now() + timeout;
  do { if (await check()) return; await delay(500); } while (Date.now() < end);
  throw Error('Timed out: ' + description);
}
function deployment() {
  const d = JSON.parse(capture('kubectl', [...scope, 'get', 'deployment', 'equipment-adapter', '-o', 'json']));
  assert.equal(d.metadata.labels['app.kubernetes.io/part-of'], 'cutover');
  assert.equal(d.spec.template.spec.containers.length, 1);
  assert.equal(d.spec.template.spec.containers[0].imagePullPolicy, 'Never'); return d;
}
async function forward() { await run('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', 'adapter-api']); }
async function controls() { const result = await api(controlPath, { target: 'adapter', bearer: await token() }); assert.equal(result.status, 200); return result.body; }
async function gate(paused) {
  const state = await controls(); if (state.dispatchPaused === paused) return;
  const result = await api(controlPath, { target: 'adapter', bearer: await token(), method: 'POST', key: runId + '-gate-' + state.version,
    body: { expectedVersion: state.version, dispatchPaused: paused, reason: 'Preserve the authoritative route while verifying a cached compatible application image.' } });
  assert.equal(result.status, 200); gateChanged = paused;
  assert.equal((await controls()).dispatchPaused, paused);
}
async function changeImage(image) {
  const d = deployment();
  await run('kubectl', [...scope, 'set', 'image', 'deployment/equipment-adapter', d.spec.template.spec.containers[0].name + '=' + image]);
  await run('kubectl', [...scope, 'rollout', 'status', 'deployment/equipment-adapter', '--timeout=180s']);
  await forward();
  const pods = JSON.parse(capture('kubectl', [...scope, 'get', 'pods', '-l', 'app.kubernetes.io/name=equipment-adapter', '-o', 'json'])).items;
  assert.equal(pods.length, 1); assert.equal(pods[0].spec.containers[0].image, image); assert.ok(pods[0].status.containerStatuses[0].ready);
  return { podUid: pods[0].metadata.uid, image, imageId: pods[0].status.containerStatuses[0].imageID };
}
function routes() { return JSON.parse(query('adapter', "SELECT jsonb_agg(jsonb_build_object('siteId',site_id,'zoneId',zone_id,'owner',owner,'epoch',epoch,'state',state,'version',version) ORDER BY site_id,zone_id) FROM zone_routes;")); }
try {
  provisionObservers(); await forward();
  assert.equal(capture('docker', ['inspect', '--format', '{{index .Config.Labels "io.x-k8s.kind.cluster"}}', 'cutover-control-plane']).trim(), 'cutover');
  original = deployment().spec.template.spec.containers[0].image; assert.notEqual(selected, original); evidence.originalImage = original;
  for (const image of [original, selected]) {
    const cached = JSON.parse(capture('docker', ['exec', 'cutover-control-plane', 'crictl', 'inspecti', image]));
    assert.ok(cached.status.repoDigests.includes(image), 'Both immutable images must already be cached.');
  }
  const state = await controls();
  for (const field of ['workersPaused', 'dispatchPaused', 'criticalStorage']) assert.equal(state[field], false);
  assert.equal(query('adapter', "SELECT coalesce((to_jsonb(c)->>'restoration_required')::boolean,false) FROM service_control c WHERE singleton;"), 'f', 'A predecessor without the restoration gate cannot run while recovery is held.');
  await gate(true);
  assert.equal(query('adapter', "SELECT count(*) FROM migration_sessions WHERE phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING','REVERSING');"), '0', 'This predecessor lacks the migration worker, so all sessions must be settled before image rollback.');
  assert.equal(query('adapter', "SELECT count(*) FROM movement_allocations WHERE state IN ('PENDING','ASSIGNED');"), '0');
  assert.equal(query('adapter', 'SELECT count(*) FROM migration_process_faults WHERE remaining=1;'), '0');
  evidence.routesBefore = routes(); assert.ok(evidence.routesBefore.every(route => route.state === 'ACTIVE'));
  evidence.schemaBefore = query('adapter', 'SELECT max(version::integer) FROM flyway_schema_history WHERE success;');
  assert.ok(Number(evidence.schemaBefore) >= 115, 'The expanded assignment-writer compatibility migration is required.');
  changed = true; evidence.previousProcess = await changeImage(selected);
  assert.deepEqual(routes(), evidence.routesBefore); await gate(false);
  const bearer = await token();
  for (let index = 0; index < 3; index++) {
    const ref = runId + '-' + index;
    const result = await api(prefix + '/orders', { method: 'POST', bearer, key: ref, body: { sourceSystem: 'scenario-driver', externalOrderRef: ref, storeId: 'store-06', priority: 5, lines: [{ sku: 'SKU-087', quantity: 1 }, { sku: 'SKU-088', quantity: 1 }] } });
    assert.equal(result.status, 202); assert.match(result.body.id, /^[a-f0-9-]{36}$/); evidence.orders.push(result.body.id);
  }
  const ids = evidence.orders.map(id => "'" + id + "'").join(',');
  await until(() => query('core', `SELECT count(*) FROM orders WHERE order_id IN (${ids}) AND state='COMPLETED';`) === '3', 'new traffic completes with the previous adapter');
  const movements = JSON.parse(query('core', `SELECT jsonb_agg(movement_id) FROM movement_intents WHERE order_id IN (${ids});`)); assert.equal(movements.length, 6);
  const movementIds = movements.map(id => { assert.match(id, /^[a-f0-9-]{36}$/); return "'" + id + "'"; }).join(',');
  for (const [owner, table] of [['core', 'inventory_ledger'], ['simulator', 'execution_ledger']])
    assert.equal(query(owner, `SELECT count(*) FROM (SELECT movement_id FROM ${table} WHERE movement_id IN (${movementIds}) GROUP BY movement_id HAVING count(*)=1) effects;`), '6');
  assert.equal(query('adapter', `SELECT count(*) FROM movement_allocations WHERE movement_id IN (${movementIds}) AND assigned_at IS NOT NULL AND state='COMPLETED';`), '6');
  assert.equal(query('adapter', `SELECT count(*) FROM movement_allocations a JOIN zone_routes r USING(site_id,zone_id) WHERE a.movement_id IN (${movementIds}) AND a.owner=r.owner AND a.epoch=r.epoch;`), '6');
  assert.equal(query('adapter', 'SELECT max(version::integer) FROM flyway_schema_history WHERE success;'), evidence.schemaBefore);
  assert.deepEqual(routes(), evidence.routesBefore); evidence.movements = movements;
  await gate(true); evidence.restoredProcess = await changeImage(original); changed = false; await gate(false);
  assert.deepEqual(routes(), evidence.routesBefore);
  evidence.cases.push({ candidate: 'A34', status: 'passed', name: 'cached previous adapter operates on expanded schema and current assignments with six single physical/inventory effects; restoring the current image preserves every route epoch' });
  console.log('Verified the previous adapter image against retained expanded data. ' + saveEvidence(runId, evidence));
} catch (failure) { saveEvidence(runId, { ...evidence, failure: failure.message }); throw failure; }
finally {
  try {
    if (changed) { await forward(); await gate(true); await changeImage(original); }
    if (gateChanged) await gate(false);
  } catch { console.error('The recorded adapter image check could not restore its original process/gate. Inspect the exact saved image and control state before continuing.'); }
}
