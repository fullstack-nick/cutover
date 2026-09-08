import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
process.env.CUTOVER_PROFILE = 'demo';
const { root, saveEvidence } = await import('./client.mjs');
const target = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover'];
const inventory = JSON.parse(readFileSync(resolve(root, '.local/images/node-images.json'), 'utf8'));
const simulator = JSON.parse(readFileSync(resolve(root, '.local/kubernetes/demo/manifest.json'), 'utf8')).simulatorAddress;
const id = `policy-${Date.now()}`;
const probes = [
  ['core', 'cutover-apps', 'legacy-core'], ['adapter', 'cutover-apps', 'equipment-adapter'], ['proxy', 'cutover-apps', 'proxy'],
  ['execution', 'cutover-apps', 'execution-service'], ['shadow', 'cutover-apps', 'shadow-scheduler'], ['returns', 'cutover-apps', 'returns-service'],
  ['outsider', 'cutover-apps', 'unauthorized-probe'], ['collector', 'cutover-observability', 'collector'],
  ['prometheus', 'cutover-observability', 'prometheus'], ['grafana', 'cutover-observability', 'grafana'],
];
function command(args, input) { return spawnSync('kubectl', [...target, ...args], { input, encoding: 'utf8', windowsHide: true, maxBuffer: 1024 * 1024 }); }
const namespace = key => probes.find(row => row[0] === key)[1];
const db = 'application-db.cutover-platform.svc.cluster.local', broker = 'rabbitmq.cutover-platform.svc.cluster.local', identity = 'keycloak.cutover-platform.svc.cluster.local';
const core = 'legacy-core.cutover-apps.svc.cluster.local', adapter = 'equipment-adapter.cutover-apps.svc.cluster.local';
const execution = 'execution-service.cutover-apps.svc.cluster.local', returns = 'returns-service.cutover-apps.svc.cluster.local', shadow = 'shadow-scheduler.cutover-apps.svc.cluster.local';
const collector = 'collector.cutover-observability.svc.cluster.local', tempo = 'tempo.cutover-observability.svc.cluster.local', prometheus = 'prometheus.cutover-observability.svc.cluster.local';
const matrix = [
  ['core', db, 5432, true], ['core', broker, 5672, true], ['core', identity, 8080, true], ['core', adapter, 8080, true], ['core', collector, 4318, true], ['core', simulator, 8443, false],
  ['adapter', simulator, 8443, true], ['adapter', core, 8080, true], ['adapter', execution, 8080, true], ['adapter', returns, 8080, false],
  ['returns', db, 5432, true], ['returns', broker, 5672, true], ['returns', identity, 8080, true], ['returns', adapter, 8080, true], ['returns', collector, 4318, true], ['returns', core, 8080, false], ['returns', execution, 8080, false], ['returns', simulator, 8443, false],
  ['execution', db, 5432, true], ['execution', broker, 5672, true], ['execution', identity, 8080, true], ['execution', adapter, 8080, true], ['execution', collector, 4318, true], ['execution', core, 8080, false], ['execution', returns, 8080, false], ['execution', simulator, 8443, false],
  ['shadow', db, 5432, true], ['shadow', broker, 5672, true], ['shadow', identity, 8080, true], ['shadow', adapter, 8080, true], ['shadow', collector, 4318, true], ['shadow', core, 8080, false], ['shadow', returns, 8080, false], ['shadow', simulator, 8443, false],
  ['proxy', core, 8080, true], ['proxy', adapter, 8080, true], ['proxy', identity, 8080, true], ['proxy', db, 5432, false], ['proxy', broker, 5672, false], ['proxy', simulator, 8443, false],
  ['proxy', execution, 8080, true], ['proxy', shadow, 8080, true], ['proxy', returns, 8080, true],
  ['outsider', core, 8080, false], ['outsider', identity, 8080, false], ['outsider', simulator, 8443, false],
  ['outsider', returns, 8080, false],
  ['collector', tempo, 4317, true], ['collector', db, 5432, false],
  ['prometheus', core, 9091, true], ['prometheus', adapter, 9091, true], ['prometheus', simulator, 9091, true], ['prometheus', identity, 9000, true], ['prometheus', broker, 15692, true], ['prometheus', collector, 8888, true], ['prometheus', tempo, 3200, true],
  ['prometheus', execution, 9091, true], ['prometheus', shadow, 9091, true], ['prometheus', returns, 9091, true],
  ['grafana', prometheus, 9090, true], ['grafana', tempo, 3200, true], ['grafana', db, 5432, false], ['grafana', simulator, 8443, false],
];
const cases = []; const created = [];
try {
  for (const [key, ns, app] of probes) {
    const name = `${id}-${key}`;
    const pod = { apiVersion: 'v1', kind: 'Pod', metadata: { name, namespace: ns, labels: { 'app.kubernetes.io/name': app, 'app.kubernetes.io/part-of': 'cutover-verification', 'dev.cutover.project': 'cutover', 'dev.cutover.purpose': 'network-probe' } }, spec: {
      automountServiceAccountToken: false, restartPolicy: 'Never', activeDeadlineSeconds: 300,
      securityContext: { runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: { type: 'RuntimeDefault' } },
      containers: [{ name: 'probe', image: inventory['legacy-core'].runtimeReference, imagePullPolicy: 'Never', command: ['bash', '-c', 'sleep 300'], resources: { requests: { cpu: '10m', memory: '16Mi' }, limits: { cpu: '200m', memory: '64Mi' } }, securityContext: { readOnlyRootFilesystem: true, allowPrivilegeEscalation: false, capabilities: { drop: ['ALL'] } } }],
    } };
    const result = command(['apply', '-f', '-'], JSON.stringify(pod)); assert.equal(result.status, 0, result.stderr); created.push([ns, name]);
  }
  for (const [ns, name] of created) assert.equal(command(['-n', ns, 'wait', '--for=condition=Ready', `pod/${name}`, '--timeout=90s']).status, 0);
  for (const [key, ns] of probes) {
    const lookup = command(['-n', ns, 'exec', `${id}-${key}`, '--', 'timeout', '3', 'getent', 'ahostsv4', identity]);
    assert.equal(lookup.status, 0, `${key}: DNS lookup failed`); cases.push({ source: key, protocol: 'resolver DNS', destination: identity, expected: 'allowed', status: 'passed' });
    const tcpDns = command(['-n', ns, 'exec', `${id}-${key}`, '--', 'timeout', '3', 'bash', '-c', 'exec 3<>/dev/tcp/10.96.0.10/53']);
    assert.equal(tcpDns.status, 0, `${key}: TCP DNS connection failed`); cases.push({ source: key, protocol: 'TCP', destination: 'cluster DNS', port: 53, expected: 'allowed', status: 'passed' });
  }
  for (const [source, destination, port, allowed] of matrix) {
    const result = command(['-n', namespace(source), 'exec', `${id}-${source}`, '--', 'timeout', '3', 'bash', '-c', `exec 3<>/dev/tcp/${destination}/${port}`]);
    const reached = result.status === 0;
    const test = { source, destination, port, expected: allowed ? 'allowed' : 'denied', reached, status: reached === allowed ? 'passed' : 'failed' }; cases.push(test);
    assert.equal(reached, allowed, JSON.stringify(test));
  }
  console.log(`Passed ${cases.length} real DNS/TCP policy checks. ${saveEvidence(id, { cases })}`);
} catch (error) { saveEvidence(id, { cases, failure: error.message }); throw error; }
finally { for (const [ns, name] of created) command(['-n', ns, 'delete', 'pod', name, '--grace-period=1', '--wait=false']); }
