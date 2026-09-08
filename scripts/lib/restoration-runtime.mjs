import assert from 'node:assert/strict';
import { owners, until, writeJson } from './local-platform.mjs';

export function ownerProcess(platform, owner, main, input, args = []) {
  assert.ok(Object.hasOwn(owners, owner));
  assert.ok(['dev.cutover.adapter.RestoreMain', 'dev.cutover.platform.messaging.ReplayMain'].includes(main));
  if (main.endsWith('RestoreMain')) assert.equal(owner, 'adapter');
  const output = platform.kube(['-n', 'cutover-apps', 'exec', '-i', `deployment/${owners[owner]}`, '--', 'env', 'CUTOVER_RESTORATION_MODE=true',
    'JAVA_TOOL_OPTIONS=-Xmx128m -XX:+ExitOnOutOfMemoryError -Dorg.jooq.no-logo=true -Dorg.jooq.no-tips=true',
    'java', `-Dloader.main=${main}`, '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher', ...args], { input: JSON.stringify(input), timeout: 60000 });
  const prefix = main.endsWith('RestoreMain') ? 'CUTOVER_RESTORE_RESULT=' : 'CUTOVER_REPLAY_RESULT=';
  const line = output.split(/\r?\n/).findLast(line => line.startsWith(prefix));
  assert.ok(line, 'The recovery process returned no structured result.');return JSON.parse(line.slice(prefix.length));
}
export const restoreStep = (platform, operation, input) => ownerProcess(platform, 'adapter', 'dev.cutover.adapter.RestoreMain', input, [operation]);
export function delivery(platform, owner) {
  return JSON.parse(platform.sql(owner, "SELECT jsonb_build_object('unpublished',(SELECT count(*) FROM outbox WHERE published_at IS NULL),'pending',(SELECT count(*) FROM inbox WHERE state IN ('RECEIVED','PENDING')),'quarantined',(SELECT count(*) FROM inbox WHERE state='QUARANTINED'));"));
}
export function intakeIdentities(platform) {
  return Object.fromEntries([['core', 'orders', 'order_id'], ['returns', 'receipts', 'receipt_id']].map(([owner, table, key]) => [owner,
    JSON.parse(platform.sql(owner, `SELECT jsonb_build_object('count',count(*),'sha256',encode(sha256(convert_to(coalesce(string_agg(${key}::text,'' ORDER BY ${key}),''),'UTF8')),'hex')) FROM ${table};`))]));
}
export async function replayCheckpoint(platform, checkpoint, journal, journalPath) {
  journal.replay ??= {};
  for (const database of checkpoint.manifest.databases.filter(item => item.owner !== 'keycloak')) {
    const events = database.replay.events.map(({ eventId, envelopeSha256 }) => ({ eventId, envelopeSha256 }));
    const progress = journal.replay[database.owner] ??= { confirmed: [], batches: 0, expected: events.length };
    assert.deepEqual(progress.confirmed, events.slice(0, progress.confirmed.length).map(event => event.eventId));
    while (progress.confirmed.length < events.length) {
      const batch = events.slice(progress.confirmed.length, progress.confirmed.length + 32);
      progress.pending = batch;writeJson(journalPath, journal);
      const result = ownerProcess(platform, database.owner, 'dev.cutover.platform.messaging.ReplayMain', batch);
      assert.equal(result.source, owners[database.owner]);assert.deepEqual(result.confirmed, batch.map(event => event.eventId));
      progress.confirmed.push(...result.confirmed);progress.batches++;delete progress.pending;writeJson(journalPath, journal);
    }
    console.log(`Restoration replay confirmed ${events.length} original ${database.owner} events.`);
  }
  await until(() => Object.keys(owners).every(owner => { const state = delivery(platform, owner);return state.unpublished === 0 && state.pending === 0; }), 'restored replay and known completion deliveries settle', 120000);
  journal.delivery = Object.fromEntries(Object.keys(owners).map(owner => [owner, delivery(platform, owner)]));
  for (const database of checkpoint.manifest.databases.filter(item => item.owner !== 'keycloak')) assert.equal(journal.delivery[database.owner].quarantined, database.delivery.quarantined, 'Replay added an unexpected message quarantine.');
  writeJson(journalPath, journal);
}
export async function reconcileRestore(platform, restoreId) {
  let result = restoreStep(platform, 'get', { restoreId });
  for (let page = 0; page < 201 && !['QUARANTINED', 'VERIFIED', 'RELEASED'].includes(result.state); page++) {
    result = restoreStep(platform, 'scan', { restoreId });
    if (result.scanCursor === result.observedHighWater) break;
  }
  for (let page = 0; page < 626 && !result.inventoryComplete && result.state !== 'QUARANTINED'; page++) result = restoreStep(platform, 'inventory', { restoreId });
  if (result.state === 'QUARANTINED') return result;
  for (let page = 0; page < 1251; page++) {
    const next = restoreStep(platform, 'absence', { restoreId });
    const unchanged = next.absenceCount === result.absenceCount;result = next;
    if (unchanged || result.state === 'QUARANTINED') break;
  }
  if (result.state === 'QUARANTINED') return result;
  // A known accepted command may complete while inventory pages are read. Extend the physical proof once more.
  result = restoreStep(platform, 'scan', { restoreId });
  if (result.state === 'QUARANTINED') return result;
  return restoreStep(platform, 'verify', { restoreId });
}
