import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { root } from './client.mjs';

export async function publishFrom(service, event, { exchange = `cutover.${service}.v1`, messageId = event.eventId, rawBody } = {}) {
  assert.ok(['legacy-core', 'equipment-adapter', 'execution-service', 'returns-service'].includes(service));
  const target = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-apps'];
  const inspected = spawnSync('kubectl', [...target, 'get', 'pods', '-l', `app.kubernetes.io/name=${service}`, '-o', 'json'], { encoding: 'utf8', windowsHide: true });
  assert.equal(inspected.status, 0);
  const candidates = JSON.parse(inspected.stdout).items.filter(pod => pod.metadata.labels['app.kubernetes.io/part-of'] === 'cutover' && !pod.metadata.deletionTimestamp && pod.status.containerStatuses?.every(container => container.ready));
  assert.equal(candidates.length, 1, 'The exact owned publisher must be ready.');
  const body = { exchange, routingKey: event.eventType, messageId, bodyBase64: Buffer.from(rawBody ?? JSON.stringify(event)).toString('base64') };
  return new Promise((done, failed) => {
    const child = spawn('kubectl', [...target, 'exec', '-i', candidates[0].metadata.name, '--', 'env', 'JAVA_TOOL_OPTIONS=', 'java', '-Xmx96m', '-Dloader.main=dev.cutover.platform.control.BrokerProbeMain', '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    child.stdout.on('data', chunk => { if (stdout.length < 65536) stdout += chunk; }); child.stderr.resume();
    const timer = setTimeout(() => { child.kill(); failed(new Error('The owned broker probe exceeded its 30-second process limit.')); }, 30000);
    child.on('error', failed);
    child.on('exit', code => {
      clearTimeout(timer);
      const result = stdout.split(/\r?\n/).filter(line => line.startsWith('{')).map(line => { try { return JSON.parse(line); } catch { return null; } }).find(value => ['CONFIRMED', 'RETURNED', 'PUBLISH_FAILED'].includes(value?.result));
      if (!result) { failed(new Error(`The bounded broker probe did not return a result (exit ${code}).`)); return; }
      done({ exitCode: code, ...result });
    });
    child.stdin.end(JSON.stringify(body));
  });
}
