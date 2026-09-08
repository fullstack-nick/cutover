// Explicit recovery of this tool's own interrupted pause. Never clears uncertainty in a physical command.
import assert from 'node:assert/strict';
import { existsSync, unlinkSync } from 'node:fs';
import { resolve } from 'node:path';
import { root, owners, jsonFile, writeJson, maintenanceLock, target, request, maintenanceToken, until, cleanupAbandonedForwards } from './lib/local-platform.mjs';

const name = process.argv[2];
assert.match(name ?? '', /^[a-z0-9][a-z0-9-]{2,63}$/);
assert.equal(process.argv.length, 3, 'Use node scripts/resume-checkpoint.mjs <checkpoint-name>.');
const directory = resolve(root, '.local/checkpoints', name), path = resolve(directory, 'recovery-journal.json');
const journal = jsonFile(path); assert.equal(journal.name, name);
if (journal.resumedAt && journal.cleanupErrors.length === 0) { console.log('This checkpoint already restored its prior controls.'); process.exit(0); }
const lock = resolve(root, '.local/operations/maintenance.lock');
if (existsSync(lock)) {
  const saved = jsonFile(lock); assert.ok([`backup:${name}`, `resume-checkpoint:${name}`].includes(saved.operation), 'Another maintenance operation owns the lock.');
  assert.ok(Number.isSafeInteger(saved.pid) && saved.pid > 0);
  let running = true;
  try { process.kill(saved.pid, 0); } catch (error) { if (error.code === 'ESRCH') running = false; else throw error; }
  assert.equal(running, false, 'The recorded maintenance process still exists; do not interrupt it.');
  assert.equal(jsonFile(lock).nonce, saved.nonce); unlinkSync(lock);
}
const unlock = maintenanceLock(`resume-checkpoint:${name}`), platform = target(), forwards = [];
const credentials = jsonFile(resolve(root, '.local/secrets/credentials.json'));
const controlPath = '/internal/v1/sites/site-a/test-controls';
try {
  platform.verify();
  cleanupAbandonedForwards(journal.pid);
  assert.ok(Object.keys(journal.prior).every(owner => Object.hasOwn(owners, owner)));
  assert.ok(journal.stopped.every(service => [...Object.values(owners), 'keycloak'].includes(service)));
  if (journal.stopped.includes('keycloak')) platform.scale('keycloak', 1, 'cutover-platform');
  await until(() => platform.get('deployment', 'keycloak', 'cutover-platform').status.readyReplicas === 1, 'identity resumes', 180000);
  for (const service of journal.stopped.filter(service => service !== 'keycloak')) platform.scale(service, 1);
  const proxy = await platform.forward('proxy'); forwards.push(proxy);
  for (const owner of Object.keys(journal.prior).reverse()) {
    if (!journal.paused[owner] && !journal.pending?.[owner]) continue;
    const service = owners[owner];
    await until(() => platform.get('deployment', service, 'cutover-apps').status.readyReplicas === 1, `${service} resumes`, 180000);
    const forward = await platform.forward(service); forwards.push(forward);
    const bearer = await maintenanceToken(proxy.origin, credentials);
    if (journal.pending?.[owner]) {
      const response = await request(forward.origin, controlPath, { bearer, ...journal.pending[owner] });
      const observed = await request(forward.origin, controlPath, { bearer }); assert.equal(observed.version, response.version, 'Controls changed after the recorded command.');
      journal.paused[owner] = observed; delete journal.pending[owner]; writeJson(path, journal);
    }
    const observed = await request(forward.origin, controlPath, { bearer });
    assert.equal(observed.version, journal.paused[owner].version, 'Controls changed outside this checkpoint. Inspect the audit before choosing a recovery action.');
    const values = Object.fromEntries(['intakePaused', 'dispatchPaused', 'workersPaused', 'criticalStorage', 'relayPaused', 'consumerPaused'].map(field => [field, journal.prior[owner][field]]));
    if (Object.entries(values).every(([field, value]) => observed[field] === value)) continue;
    const command = { method: 'POST', key: `checkpoint-${name}-${owner}-recover-${observed.version}`, body: { expectedVersion: observed.version, ...values, reason: `Resume the recorded prior controls after interrupted checkpoint ${name}.` } };
    journal.pending[owner] = command; writeJson(path, journal);
    const response = await request(forward.origin, controlPath, { bearer, ...command });
    const resumed = await request(forward.origin, controlPath, { bearer }); assert.equal(resumed.version, response.version);
    journal.paused[owner] = resumed; delete journal.pending[owner]; writeJson(path, journal);
  }
  journal.resumedAt = new Date().toISOString(); journal.cleanupErrors = []; writeJson(path, journal);
  console.log(`Restored the exact prior application controls for checkpoint ${name}. Its capture status remains ${journal.state}; verify its checksums before any restore.`);
} finally { for (const forward of forwards) forward.close(); unlock(); }
