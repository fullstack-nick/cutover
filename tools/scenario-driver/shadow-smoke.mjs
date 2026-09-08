import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { randomUUID, createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const runId = 'shadow-' + Date.now(), cases = [], rounds = [];
const directory = resolve(root, '.local/evidence', runId); mkdirSync(directory, { recursive: true });
const fixtures = readFileSync(resolve(root, 'tools/shadow-checks/target/shadow-evidence/comparisons.jsonl'), 'utf8').trim().split(/\r?\n/).map(JSON.parse);
assert.equal(fixtures.length, 1000, 'Generate the complete 1,000-round SQL/Java component evidence first.');
assert.ok(fixtures.every(item => item.matches));
const kc = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-apps'];
async function until(check, description, timeout = 120000) {
  const deadline = Date.now() + timeout;
  do { if (await check()) return; await delay(500); } while (Date.now() < deadline);
  throw new Error('Timed out: ' + description);
}
function probe(body) {
  const pods = spawnSync('kubectl', [...kc, 'get', 'pods', '-l', 'app.kubernetes.io/name=shadow-scheduler', '-o', 'json'], { encoding: 'utf8', windowsHide: true });
  assert.equal(pods.status, 0);
  const candidates = JSON.parse(pods.stdout).items.filter(pod => pod.metadata.labels['app.kubernetes.io/part-of'] === 'cutover' && !pod.metadata.deletionTimestamp && pod.status.containerStatuses?.every(item => item.ready));
  assert.equal(candidates.length, 1);
  const result = spawnSync('kubectl', [...kc, 'exec', '-i', candidates[0].metadata.name, '--', 'env', 'JAVA_TOOL_OPTIONS=', 'CUTOVER_SHADOW_MODE=false', 'java', '-Xmx96m', '-Dloader.main=dev.cutover.platform.control.AdapterProbeMain', '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'], { input: JSON.stringify(body), encoding: 'utf8', windowsHide: true, timeout: 30000, maxBuffer: 65536 });
  assert.equal(result.status, 0, 'The identity probe must reach the adapter and produce an HTTP response.');
  const reply = result.stdout.split(/\r?\n/).filter(line => line.startsWith('{')).map(line => { try { return JSON.parse(line); } catch { return null; } }).find(item => Number.isInteger(item?.status));
  assert.ok(reply); assert.equal(reply.client, 'shadow-scheduler'); assert.equal(reply.shadowMode, 'false');
  return { ...reply, pod: candidates[0].metadata.name };
}
try {
  provisionObservers();
  assert.equal(query('adapter', "SELECT count(*) FROM zone_routes WHERE site_id='site-a' AND zone_id IN ('ambient','chilled') AND owner='legacy-core' AND state='ACTIVE';"), '2', 'Both outbound zones must be active under legacy ownership.');
  const retainedExecutionTasks = query('execution', "SELECT coalesce(jsonb_agg(task_id ORDER BY task_id),'[]') FROM execution_tasks;");
  for (const target of ['core-api', 'adapter-api']) assert.equal(spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', target], { encoding: 'utf8', windowsHide: true, timeout: 45000 }).status, 0);
  const capacity = JSON.parse(query('core', "SELECT row_to_json(c) FROM shadow_observation_capacity c;"));
  assert.ok(capacity.retained_count + 1100 < 4096, 'This run needs retained comparison headroom; do not erase accepted evidence to make it pass.');
  let bearer = await token(), renewedAt = Date.now();
  for (let index = 0; index < fixtures.length; index += 8) {
    if (Date.now() - renewedAt > 60000) { bearer = await token(); renewedAt = Date.now(); }
    const batch = fixtures.slice(index, index + 8);
    const recorded = await Promise.all(batch.map(async (fixture, offset) => {
      const result = await api('/internal/v1/sites/site-a/test-controls/scheduling-rounds', { target: 'core', method: 'POST', bearer, body: fixture.input });
      assert.equal(result.status, 200, 'The actual legacy service must durably capture each fixture.');
      assert.equal(result.body.inputHash, fixture.inputHash);
      return { index: index + offset, ...result.body, fixture };
    }));
    rounds.push(...recorded);
    if (rounds.length % 200 === 0) console.log('Persisted ' + rounds.length + ' seeded scheduling rounds through the running core.');
  }
  const ids = rounds.map(item => "'" + item.roundId + "'").join(',');
  await until(() => query('shadow', 'SELECT count(*) FROM shadow_comparisons WHERE round_id IN (' + ids + ');') === '1000', 'every recorded round crosses RabbitMQ and reaches the isolated shadow database');
  assert.equal(query('shadow', 'SELECT count(*) FROM shadow_comparisons WHERE NOT matches AND round_id IN (' + ids + ');'), '0');
  assert.equal(query('core', 'SELECT count(*) FROM shadow_observation_outbox WHERE published_at IS NOT NULL AND event_id IN (' + ids + ');'), '1000');
  const evidence = [];
  bearer = await token();
  for (let index = 0; index < rounds.length; index += 8) {
    const checked = await Promise.all(rounds.slice(index, index + 8).map(async round => {
      const response = await api('/api/v1/sites/site-a/shadow-comparisons/' + round.roundId, { bearer });
      assert.equal(response.status, 200);
      const actual = response.body;
      assert.equal(actual.inputHash, round.inputHash); assert.equal(actual.matches, true);
      assert.deepEqual(actual.input, round.fixture.input);
      assert.deepEqual(actual.legacyProposal, round.fixture.legacy);
      assert.deepEqual(actual.executionProposal, round.fixture.execution);
      return { index: round.index, ...actual };
    }));
    evidence.push(...checked);
  }
  const exported = evidence.map(item => JSON.stringify(item)).join('\n') + '\n';
  writeFileSync(resolve(directory, 'comparisons.jsonl'), exported);
  assert.equal((await api('/api/v1/sites/site-b/shadow-comparisons/' + rounds[0].roundId, { bearer })).status, 404);
  cases.push({ candidate: 'A27', status: 'passed', name: '1,000 persisted SQL decisions traverse the separate broker queue and compare in the isolated Java shadow process', seed: 840271, rounds: 1000, mismatches: 0, evidenceSha256: createHash('sha256').update(exported).digest('hex'), fixtureCoverage: JSON.parse(readFileSync(resolve(root, 'tools/shadow-checks/target/shadow-evidence/manifest.json'), 'utf8')).reasons });
  assert.equal(query('execution', "SELECT coalesce(jsonb_agg(task_id ORDER BY task_id),'[]') FROM execution_tasks;"), retainedExecutionTasks, 'Shadow comparison adds no execution tasks and preserves any completed history from an earlier owner epoch.');
  assert.equal(query('shadow', 'SELECT count(*) FROM execution_tasks;'), '0', 'The shadow handler writes no execution tasks.');

  const orderBody = { sourceSystem: 'scenario-driver', externalOrderRef: runId + '-live', storeId: 'store-08', priority: 7, lines: [{ sku: 'SKU-055', quantity: 1 }, { sku: 'SKU-056', quantity: 1 }] };
  const accepted = await api('/api/v1/sites/site-a/orders', { method: 'POST', bearer: await token(), body: orderBody, key: orderBody.externalOrderRef }); assert.equal(accepted.status, 202);
  let order;
  await until(async () => { order = (await api(accepted.body.statusUrl, { bearer })).body; return order.state === 'COMPLETED'; }, 'live ambient and chilled movements finish through the SQL decision coordinator');
  assert.equal(order.movements.length, 2);
  for (const movement of order.movements) {
    await until(() => Number(query('shadow', "SELECT count(*) FROM shadow_comparisons WHERE input->'candidates' @> '[{\"movementId\":\"" + movement.movementId + "\"}]'::jsonb;")) >= 1, 'a live decision also reaches shadow comparison');
    assert.equal(query('core', "SELECT count(*) FROM inventory_ledger WHERE movement_id='" + movement.movementId + "';"), '1');
    assert.equal(query('simulator', "SELECT count(*) FROM execution_ledger WHERE movement_id='" + movement.movementId + "';"), '1');
    assert.equal(query('execution', "SELECT count(*) FROM execution_tasks WHERE movement_id='" + movement.movementId + "';"), '0', 'A live legacy-owned movement must not create an extracted execution task.');
  }
  assert.equal(query('execution', "SELECT coalesce(jsonb_agg(task_id ORDER BY task_id),'[]') FROM execution_tasks;"), retainedExecutionTasks);
  cases.push({ candidate: 'A01/A27', status: 'passed', name: 'live SQL dispatch also emits identical snapshots while both temperature classes complete once', orderId: order.id, movements: order.movements.map(item => item.movementId) });
  const movement = order.movements[0];
  const allocation = await api('/internal/v1/sites/site-a/allocations/' + movement.movementId, { target: 'adapter', bearer: await token('legacy-core') }); assert.equal(allocation.status, 200);
  const physical = query('simulator', 'SELECT count(*) FROM execution_ledger;'), commands = query('adapter', 'SELECT count(*) FROM command_journal;');
  const commandDenied = probe({ method: 'PUT', path: '/internal/v1/sites/site-a/commands/' + movement.movementId, body: { allocationId: allocation.body.allocationId, epoch: allocation.body.epoch, laneId: movement.movement.zoneId + '-a', movement: movement.movement } });
  assert.equal(commandDenied.status, 403); assert.equal(commandDenied.code, 'DISPATCH_IDENTITY');
  const allocationDenied = probe({ method: 'POST', path: '/internal/v1/sites/site-a/allocations', body: { ...movement.movement, movementId: randomUUID(), reservationId: randomUUID(), loadId: randomUUID() } });
  assert.equal(allocationDenied.status, 403); assert.equal(allocationDenied.code, 'INTENT_SOURCE');
  assert.equal(query('simulator', 'SELECT count(*) FROM execution_ledger;'), physical); assert.equal(query('adapter', 'SELECT count(*) FROM command_journal;'), commands);
  cases.push({ candidate: 'A28', status: 'passed', name: 'the actual shadow pod reaches adapter HTTP with shadow mode false and its independent identity is denied', commandDenied, allocationDenied, physicalEffectsAdded: 0, commandRecordsAdded: 0 });
  console.log('Passed ' + cases.length + ' running shadow checks. ' + saveEvidence(runId, { cases, comparisonFile: 'comparisons.jsonl', roundIds: rounds.map(({ index, roundId, inputHash }) => ({ index, roundId, inputHash })) }));
} catch (error) {
  saveEvidence(runId, { cases, roundIds: rounds.map(({ index, roundId, inputHash }) => ({ index, roundId, inputHash })), failure: error.message });
  throw error;
}
