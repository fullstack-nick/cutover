import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const runId = 'dispatch-latency-' + Date.now(), evidence = { orders: [], profile: '30 orders, two lines/order, one order/second; diagnostic rather than final ten-minute acceptance load' };
function brokerCpu() {
  const args = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-platform'];
  const inspected = spawnSync('kubectl', [...args, 'get', 'pod', 'rabbitmq-0', '-o', 'json'], { encoding: 'utf8', windowsHide: true }); assert.equal(inspected.status, 0);
  const pod = JSON.parse(inspected.stdout); assert.equal(pod.metadata.labels['app.kubernetes.io/part-of'], 'cutover');
  const reply = spawnSync('kubectl', [...args, 'exec', 'rabbitmq-0', '--', 'cat', '/sys/fs/cgroup/cpu.stat'], { encoding: 'utf8', windowsHide: true }); assert.equal(reply.status, 0);
  return { podUid: pod.metadata.uid, capturedAt: new Date().toISOString(), ...Object.fromEntries(reply.stdout.trim().split(/\r?\n/).map(line => { const [name, value] = line.split(' '); return [name, Number(value)]; })) };
}
try {
  provisionObservers(); assert.equal(query('adapter', 'SELECT count(*) FROM migration_process_faults WHERE remaining=1;'), '0');
  assert.equal(query('core', "SELECT count(*) FROM stock WHERE site_id='site-a' AND sku IN ('SKU-085','SKU-086') AND on_hand-reserved>=30;"), '2', 'Keep the diagnostic within existing synthetic stock.');
  const bearer = await token(); evidence.cpuBefore = brokerCpu(); const started = Date.now();
  for (let index = 0; index < 30; index++) {
    const ref = runId + '-' + index;
    const accepted = await api('/api/v1/sites/site-a/orders', { bearer, method: 'POST', key: ref, body: { sourceSystem: 'scenario-driver', externalOrderRef: ref, storeId: 'store-09', priority: 5, lines: [{ sku: 'SKU-085', quantity: 1 }, { sku: 'SKU-086', quantity: 1 }] } });
    assert.equal(accepted.status, 202); assert.match(accepted.body.id, /^[a-f0-9-]{36}$/); evidence.orders.push(accepted.body.id);
    await delay(Math.max(0, started + (index + 1) * 1000 - Date.now()));
  }
  const orderIds = evidence.orders.map(id => "'" + id + "'").join(',');
  const deadline = Date.now() + 60000;
  while (Number(query('core', 'SELECT count(*) FROM orders WHERE order_id IN (' + orderIds + ") AND state='COMPLETED';")) !== 30) { assert.ok(Date.now() < deadline, 'Every admitted diagnostic order must complete.'); await delay(500); }
  const movements = JSON.parse(query('core', 'SELECT jsonb_agg(movement_id) FROM movement_intents WHERE order_id IN (' + orderIds + ');')); assert.equal(movements.length, 60);
  const ids = movements.map(id => { assert.match(id, /^[a-f0-9-]{36}$/); return "'" + id + "'"; }).join(',');
  evidence.movements = JSON.parse(query('adapter', `SELECT jsonb_agg(jsonb_build_object('movementId',a.movement_id,'zone',a.zone_id,'owner',a.owner,'epoch',a.epoch,'assignedAt',a.assigned_at,'eligibleAt',a.movement->>'eligibleAt','recordedAt',c.created_at,'dispatchMillis',extract(epoch from(c.created_at-GREATEST(a.assigned_at,(a.movement->>'eligibleAt')::timestamptz)))*1000,'endToEndMillis',extract(epoch from(c.created_at-(a.movement->>'eligibleAt')::timestamptz))*1000,'attempts',c.attempts) ORDER BY a.assigned_at) FROM movement_allocations a JOIN command_journal c USING(site_id,movement_id) WHERE a.movement_id IN (${ids});`));
  for (const [owner, table] of [['core', 'inventory_ledger'], ['simulator', 'execution_ledger']]) assert.equal(query(owner, `SELECT count(*) FROM (SELECT movement_id FROM ${table} WHERE movement_id IN (${ids}) GROUP BY movement_id HAVING count(*)=1) unique_effects;`), '60');
  const latencies = evidence.movements.map(item => item.dispatchMillis).sort((a, b) => a - b);
  const total = evidence.movements.map(item => item.endToEndMillis).sort((a, b) => a - b);
  evidence.dispatchP99Millis = latencies[Math.ceil(latencies.length * .99) - 1]; evidence.eligibleToDispatchP99Millis = total[Math.ceil(total.length * .99) - 1];
  evidence.elapsedMillis = Date.now() - started; evidence.cpuAfter = brokerCpu();
  evidence.brokerCpu = { usedMicros: evidence.cpuAfter.usage_usec - evidence.cpuBefore.usage_usec, periods: evidence.cpuAfter.nr_periods - evidence.cpuBefore.nr_periods, throttledPeriods: evidence.cpuAfter.nr_throttled - evidence.cpuBefore.nr_throttled, throttledMicros: evidence.cpuAfter.throttled_usec - evidence.cpuBefore.throttled_usec };
  assert.ok(evidence.dispatchP99Millis <= 2000, 'The diagnostic dispatch p99 was ' + evidence.dispatchP99Millis + ' ms.');
  evidence.cases = [{ status: 'passed', name: 'sixty mixed-zone movements retain one physical and inventory effect each under a paced diagnostic workload' }];
  console.log('Measured 60 movements: assignment/eligibility-to-dispatch p99 ' + evidence.dispatchP99Millis + ' ms; eligibility-to-dispatch p99 ' + evidence.eligibleToDispatchP99Millis + ' ms. ' + saveEvidence(runId, evidence));
} catch (failure) { saveEvidence(runId, { ...evidence, failure: failure.message }); throw failure; }
