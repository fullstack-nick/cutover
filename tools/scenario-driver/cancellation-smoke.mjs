import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, api, token, simulator, query, provisionObservers, saveEvidence } = await import('./client.mjs');
const { humanSession } = await import('./human-session.mjs');
const runId = `cancellation-${Date.now()}`, cases = [], faults = [];
const controlPath = '/internal/v1/sites/site-a/test-controls';
let supervisor, operator, paused = false;
async function until(check, description, timeout = 100000) {
  const deadline = Date.now() + timeout;
  do { if (await check()) return; await delay(350); } while (Date.now() < deadline);
  throw new Error(`Timed out: ${description}`);
}
async function pauseDispatch(value) {
  const bearer = await token();
  const current = await api(controlPath, { target: 'adapter', bearer }); assert.equal(current.status, 200);
  const result = await api(controlPath, { target: 'adapter', bearer, method: 'POST', key: `${runId}-gate-${current.body.version}`, body: { expectedVersion: current.body.version, dispatchPaused: value, reason: value ? 'Hold dispatch to inspect a wholly unstarted cancellation fixture.' : 'Resume dispatch after the cancellation assertion has been recorded.' } });
  assert.equal(result.status, 200); paused = value;
}
async function create(label, skus) {
  const reference = `${runId}-${label}`, bearer = await token();
  const accepted = await api('/api/v1/sites/site-a/orders', { method: 'POST', bearer, key: reference, body: { sourceSystem: 'scenario-driver', externalOrderRef: reference, storeId: 'store-06', priority: 5, lines: skus.map(sku => ({ sku, quantity: 2 })) } });
  assert.equal(accepted.status, 202);
  const order = (await api(`/api/v1/sites/site-a/orders/${accepted.body.id}`, { bearer })).body;
  assert.equal(order.movements.length, skus.length, 'Chosen synthetic SKUs must have stock.');
  return order;
}
const pathFor = order => `/api/v1/sites/site-a/orders/${order.id}/cancellation`;
const requestFor = order => ({ expectedVersion: order.version, reason: 'Cancel the entire unstarted order after inspecting its reserved movement inventory.' });
async function readOrder(order) { return (await api(`/api/v1/sites/site-a/orders/${order.id}`, { bearer: supervisor.bearer() })).body; }
function assertNoRelease(order) { assert.equal(query('core', `SELECT count(*) FROM reservation_releases r JOIN order_cancellations c USING(cancellation_id) WHERE c.order_id='${order.id}';`), '0'); }
async function openInConsole(order) {
  const page = supervisor.page;
  await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('button', { name: /^Orders/ }).click();
  await page.getByRole('heading', { name: 'Order register', exact: true }).waitFor();
  for (let i = 0; i < 30; i++) {
    const target = page.getByRole('button', { name: order.externalOrderRef, exact: true });
    if (await target.count()) { await target.click(); return; }
    const next = page.getByRole('button', { name: 'Next page', exact: true });
    if (!(await next.isEnabled())) break;
    await Promise.all([page.waitForResponse(response => response.url().includes('/orders?limit=25&cursor=')), next.click()]);
    await page.getByRole('button', { name: 'Refresh', exact: true }).waitFor();
  }
  throw new Error('The created order could not be found in the real paginated console.');
}
try {
  provisionObservers();
  const forward = spawnSync('pwsh', ['-NoProfile', '-File', resolve(root, 'scripts/forward.ps1'), '-Target', 'adapter-api'], { encoding: 'utf8', windowsHide: true, timeout: 45000 }); assert.equal(forward.status, 0);
  const control = await api(controlPath, { target: 'adapter', bearer: await token() }); assert.equal(control.status, 200);
  assert.equal(control.body.dispatchPaused, false); assert.equal(control.body.workersPaused, false); assert.equal(control.body.criticalStorage, false);
  supervisor = await humanSession('supervisor-a'); operator = await humanSession('operator-a');

  await pauseDispatch(true);
  const order = await create('unstarted', ['SKU-051', 'SKU-052']);
  const request = requestFor(order), key = `${runId}-unstarted`;
  assert.equal((await api(pathFor(order), { method: 'POST', body: request, key, bearer: operator.bearer() })).status, 403);
  assert.equal((await api(pathFor(order).replace('/site-a/', '/site-b/'), { method: 'POST', body: request, key, bearer: supervisor.bearer() })).status, 404);
  await supervisor.page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await supervisor.page.getByRole('button', { name: 'Refresh', exact: true }).waitFor();
  await openInConsole(order);
  await supervisor.page.getByRole('textbox', { name: 'Cancellation reason', exact: true }).fill(request.reason);
  await supervisor.page.getByRole('button', { name: 'Confirm order cancellation', exact: true }).click();
  await supervisor.page.getByText('Cancellation confirmed. Reserved stock was released once.', { exact: true }).waitFor();
  const cancelled = await readOrder(order); assert.equal(cancelled.state, 'CANCELLED');
  assert.equal(query('core', `SELECT count(*),sum(quantity) FROM reservation_releases r JOIN order_cancellations c USING(cancellation_id) WHERE c.order_id='${order.id}';`), '2|4');
  assert.equal(query('core', `SELECT count(*) FROM audit WHERE resource_id='${order.id}' AND action='order-cancellation-result' AND outcome='CANCELLED';`), '1');
  await supervisor.page.keyboard.press('Escape');
  await pauseDispatch(false);
  for (const movement of order.movements) {
    assert.equal(query('simulator', `SELECT count(*) FROM simulator_commands WHERE movement_id='${movement.movementId}';`), '0');
    assert.equal(query('adapter', `SELECT state FROM movement_allocations WHERE movement_id='${movement.movementId}';`), 'CANCELLED');
  }
  cases.push({ candidate: 'A08/A39/A40/A51', name: 'real supervisor UI cancels all unstarted lines; role/site denial and one release per reservation', status: 'passed', orderId: order.id, movementIds: order.movements.map(item => item.movementId) });

  await pauseDispatch(true);
  const started = await create('started', ['SKU-053']);
  const movementId = started.movements[0].movementId;
  const fault = await simulator('/sim/v1/test-controls/faults', { kind: 'HOLD_EXECUTING', commandId: movementId, count: 1, delayMillis: 30000 });
  assert.equal(fault.status, 200); faults.push(fault.body.faultId);
  await pauseDispatch(false);
  await until(async () => (await simulator(`/sim/v1/commands/${movementId}`)).body.state === 'EXECUTING', 'durable physical acceptance before cancellation');
  const current = await readOrder(started); assert.equal(current.state, 'RESERVED');
  const deniedRequest = requestFor(current), deniedKey = `${runId}-started`;
  const denied = await api(pathFor(started), { method: 'POST', body: deniedRequest, key: deniedKey, bearer: supervisor.bearer() });
  assert.equal(denied.status, 409); assert.equal(denied.body.code, 'MOVEMENT_STARTED_OR_UNKNOWN'); assertNoRelease(started);
  assert.equal((await api(pathFor(started), { method: 'POST', body: deniedRequest, key: deniedKey, bearer: supervisor.bearer() })).status, 409);
  await until(async () => (await readOrder(started)).state === 'COMPLETED', 'denied cancellation resumes normal completion');
  assert.equal(query('simulator', `SELECT count(*) FROM execution_ledger WHERE movement_id='${movementId}';`), '1');
  assert.equal(query('core', `SELECT count(*) FROM inventory_ledger WHERE movement_id='${movementId}';`), '1'); assertNoRelease(started);
  cases.push({ candidate: 'A08', name: 'started work refuses cancellation, retains stock and completes exactly once', status: 'passed', orderId: started.id, movementId });

  await pauseDispatch(true);
  const race = await create('race', ['SKU-054']);
  const raceRequest = requestFor(race), bearer = supervisor.bearer();
  const responses = await Promise.all(['a', 'b'].map(suffix => api(pathFor(race), { method: 'POST', body: raceRequest, key: `${runId}-race-${suffix}`, bearer })));
  assert.deepEqual(responses.map(value => value.status).sort(), [200, 409]);
  const winner = responses.findIndex(value => value.status === 200);
  const winningKey = `${runId}-race-${winner === 0 ? 'a' : 'b'}`;
  assert.deepEqual((await api(pathFor(race), { method: 'POST', body: raceRequest, key: winningKey, bearer })).body, responses[winner].body);
  assert.equal((await api(pathFor(race), { method: 'POST', body: { ...raceRequest, reason: 'A changed reason must conflict with the previous successful key.' }, key: winningKey, bearer })).status, 409);
  assert.equal(query('core', `SELECT count(*) FROM reservation_releases r JOIN order_cancellations c USING(cancellation_id) WHERE c.order_id='${race.id}';`), '1');
  await pauseDispatch(false);
  cases.push({ candidate: 'A04/A08', name: 'concurrent requests and changed-key payload cannot duplicate reservation release', status: 'passed', orderId: race.id });
  console.log(`Passed ${cases.length} cancellation process checks. ${saveEvidence(runId, { cases })}`);
} catch (error) { saveEvidence(runId, { cases, failure: error.message }); throw error; }
finally {
  for (const id of faults) try { await simulator(`/sim/v1/test-controls/faults/${id}`, undefined, 'scenario', 'DELETE'); } catch { console.error(`Inspect retained cancellation fixture fault ${id}; cleanup failed.`); }
  if (paused) await pauseDispatch(false);
  await supervisor?.close(); await operator?.close();
}
