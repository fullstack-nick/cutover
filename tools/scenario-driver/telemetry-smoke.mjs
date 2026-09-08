import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { saveEvidence } = await import('./client.mjs');
const runId = `telemetry-${Date.now()}`;
const cases = [];
async function json(port, path) {
  const response = await fetch(`http://127.0.0.1:${port}${path}`, { signal: AbortSignal.timeout(6000) });
  assert.equal(response.status, 200, `${port}${path}`);
  return response.json();
}
try {
  const targets = await json(8781, '/api/v1/targets');
  assert.equal(targets.status, 'success');
  const active = targets.data.activeTargets;
  const expected = ['legacy-core', 'equipment-adapter', 'execution-service', 'shadow-scheduler', 'returns-service', 'equipment-simulator', 'rabbitmq', 'keycloak', 'collector', 'tempo'];
  const jobs = { rabbitmq: 'cutover-broker', keycloak: 'cutover-identity', collector: 'cutover-collector', tempo: 'cutover-tempo' };
  for (const name of expected) {
    const target = active.find(item => item.labels.service === name || item.labels.job === jobs[name]);
    assert.ok(target, `Missing scrape target: ${name}`);
    assert.equal(target.health, 'up', `${name}: ${target.lastError}`);
  }
  cases.push({ name: 'all ten expected Prometheus targets are actually scraped', status: 'passed', targets: active.map(({ labels, health, lastError }) => ({ labels, health, lastError })) });
  const deadline = Date.now() + 30000;
  let traces;
  do {
    traces = await json(8782, '/api/search?limit=5');
    if (traces.traces?.some(trace => trace.rootServiceName === 'legacy-core')) break;
    await delay(500);
  } while (Date.now() < deadline);
  const trace = traces.traces?.find(item => item.rootServiceName === 'legacy-core');
  assert.ok(trace, 'No application trace was indexed by Tempo.');
  const detail = await json(8782, `/api/traces/${trace.traceID}`);
  assert.ok((detail.batches ?? detail.resourceSpans ?? []).length > 0, 'Trace lookup returned no spans.');
  cases.push({ name: 'Java-agent application trace traverses Collector and is retrievable from Tempo', status: 'passed', traceId: trace.traceID });
  const grafana = await json(8783, '/api/health');
  assert.equal(grafana.database, 'ok');
  cases.push({ name: 'local Grafana database and HTTP health are ready', status: 'passed', version: grafana.version });
  console.log(`Passed ${cases.length} telemetry smoke checks. ${saveEvidence(runId, { cases })}`);
} catch (error) { saveEvidence(runId, { cases, failure: error.message }); throw error; }
