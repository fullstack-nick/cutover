import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { platformResources, migrationJob, identityMigrationJob, config, namespaces } from '../infra/kubernetes/base/platform.mjs';
import { policies } from '../infra/kubernetes/base/policies.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = path => readFileSync(resolve(root, path), 'utf8');
const credentials = JSON.parse(read('.local/secrets/credentials.json')).passwords;
const inventory = JSON.parse(read('.local/images/node-images.json'));
const images = Object.fromEntries(Object.entries(inventory).map(([name, value]) => [name, value.runtimeReference]));
for (const key of ['postgres', 'rabbitmq', 'operations-console', 'keycloak', 'legacy-core', 'equipment-adapter', 'collector', 'prometheus', 'grafana', 'tempo']) if (!images[key]) throw new Error(`Load the ${key} image before rendering.`);
function docker(args) {
  const result = spawnSync('docker', args, { encoding: 'utf8', windowsHide: true });
  if (result.status !== 0) throw new Error(`Docker preparation failed: ${result.stderr.trim()}`);
  return result.stdout.trim();
}
const simulatorName = 'cutover-dev-equipment-simulator-1';
let simulator = JSON.parse(docker(['inspect', simulatorName]))[0];
if (simulator.Config.Labels['dev.cutover.project'] !== 'cutover' || !simulator.State.Running) throw new Error('The independent Cutover simulator must be running.');
if (!simulator.NetworkSettings.Networks['cutover-kind']) {
  docker(['network', 'connect', 'cutover-kind', simulatorName]);
  simulator = JSON.parse(docker(['inspect', simulatorName]))[0];
}
const simulatorAddress = simulator.NetworkSettings.Networks['cutover-kind'].IPAddress;
if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(simulatorAddress)) throw new Error('Could not discover the simulator IPv4 endpoint.');
const { apps, platform, observability } = namespaces;
const objects = platformResources(images, { simulatorAddress });
const secretObject = (name, namespace, data) => ({ apiVersion: 'v1', kind: 'Secret', type: 'Opaque', metadata: { name, namespace, labels: { 'app.kubernetes.io/part-of': 'cutover' } }, data: Object.fromEntries(Object.entries(data).map(([key, value]) => [key, Buffer.from(value).toString('base64')])) });
const secret = (name, namespace, data) => objects.push(secretObject(name, namespace, data));
secret('database-admin', platform, { password: credentials.postgres_admin });
secret('database-init', platform, { 'init.sql': read('.local/secrets/application-init.sql') });
secret('rabbit-definitions', platform, { 'definitions.json': read('.local/secrets/rabbit-definitions.json') });
secret('keycloak-runtime', platform, { password: credentials.keycloak_runtime });
secret('keycloak-migrator', platform, { password: credentials.keycloak_migrator });
secret('identity-admin', platform, { password: credentials.keycloak_admin });
secret('identity-realm', platform, { 'cutover-realm.json': read('.local/secrets/realm/cutover-realm.json') });
secret('grafana-admin', observability, { password: credentials.grafana_admin });
for (const [name, owner] of [['legacy-core', 'core'], ['equipment-adapter', 'adapter']]) {
  secret(`${name}-runtime`, apps, { databasePassword: credentials[`${owner}_runtime`], brokerPassword: credentials[`rabbit_${owner}`], clientSecret: credentials[`client_${name}`] });
  secret(`${name}-migrator`, apps, { password: credentials[`${owner}_migrator`] });
}
secret('adapter-equipment', apps, { 'adapter.p12': readFileSync(resolve(root, '.local/secrets/equipment/adapter.p12')), 'trust.p12': readFileSync(resolve(root, '.local/secrets/equipment/trust.p12')), password: credentials.equipment_store });
objects.push(config('rabbit-config', platform, { 'rabbitmq.conf': read('infra/compose/rabbitmq.conf'), enabled_plugins: '[rabbitmq_management,rabbitmq_prometheus].\n' }));
objects.push(config('proxy-config', apps, { 'default.conf': read('infra/compose/proxy.conf').replace('http://keycloak:8080', `http://keycloak.${platform}.svc.cluster.local:8080`) }));
for (const name of ['collector', 'prometheus', 'tempo']) objects.push(config(`${name}-config`, observability, { 'config.yaml': read(`infra/kubernetes/base/config/${name}.yaml`) }));
objects.push(config('grafana-datasources', observability, { 'datasources.yaml': JSON.stringify({ apiVersion: 1, datasources: [
  { name: 'Cutover metrics', uid: 'cutover-prometheus', type: 'prometheus', access: 'proxy', url: 'http://prometheus:9090', isDefault: true, editable: false },
  { name: 'Cutover traces', uid: 'cutover-tempo', type: 'tempo', access: 'proxy', url: 'http://tempo:3200', editable: false },
] }) }));
objects.push(config('grafana-dashboards', observability, { 'providers.yaml': JSON.stringify({ apiVersion: 1, providers: [{ name: 'cutover', type: 'file', disableDeletion: true, editable: false, options: { path: '/var/lib/grafana/dashboards' } }] }) }));
const dashboard = { uid: 'cutover-platform', title: 'Cutover · local operations', schemaVersion: 39, version: 1, refresh: '15s', time: { from: 'now-15m', to: 'now' }, tags: ['cutover'], panels: [
  { id: 1, type: 'stat', title: 'Observed application targets', gridPos: { x: 0, y: 0, w: 8, h: 4 }, datasource: { type: 'prometheus', uid: 'cutover-prometheus' }, targets: [{ expr: 'sum(up{job="cutover-applications"})' }], options: { colorMode: 'value' } },
  { id: 2, type: 'timeseries', title: 'Application process memory', gridPos: { x: 0, y: 4, w: 12, h: 8 }, datasource: { type: 'prometheus', uid: 'cutover-prometheus' }, targets: [{ expr: 'sum by (service) (jvm_memory_used_bytes{job="cutover-applications"})', legendFormat: '{{service}}' }], fieldConfig: { defaults: { unit: 'bytes' } } },
  { id: 3, type: 'timeseries', title: 'HTTP request rate', gridPos: { x: 12, y: 4, w: 12, h: 8 }, datasource: { type: 'prometheus', uid: 'cutover-prometheus' }, targets: [{ expr: 'sum by (service) (rate(http_server_requests_seconds_count{job="cutover-applications"}[1m]))', legendFormat: '{{service}}' }] },
] };
objects.push(config('cutover-dashboard', observability, { 'platform.json': JSON.stringify(dashboard) }));
objects.push(...policies(simulatorAddress));
// Mounted subPath files and environment secrets require a pod restart when their contents change.
for (const workload of objects.filter(item => ['Deployment', 'StatefulSet'].includes(item.kind))) {
  const pod = workload.spec.template.spec;
  const references = new Set();
  for (const volume of pod.volumes ?? []) {
    if (volume.configMap) references.add(`ConfigMap/${volume.configMap.name}`);
    if (volume.secret) references.add(`Secret/${volume.secret.secretName}`);
  }
  for (const container of pod.containers) for (const variable of container.env ?? []) {
    if (variable.valueFrom?.secretKeyRef) references.add(`Secret/${variable.valueFrom.secretKeyRef.name}`);
  }
  const inputs = [...references].sort().map(reference => {
    const [kind, name] = reference.split('/');
    const value = objects.find(item => item.kind === kind && item.metadata.namespace === workload.metadata.namespace && item.metadata.name === name);
    if (!value) throw new Error(`Missing local configuration reference: ${reference}`);
    return { kind, name, data: value.data };
  });
  workload.spec.template.metadata.annotations = { 'dev.cutover/config-sha256': createHash('sha256').update(JSON.stringify(inputs)).digest('hex') };
}
const directory = resolve(root, '.local/kubernetes/demo'); mkdirSync(directory, { recursive: true });
function group(name, contents) {
  const path = resolve(directory, name); mkdirSync(path, { recursive: true });
  writeFileSync(resolve(path, 'resources.json'), JSON.stringify({ apiVersion: 'v1', kind: 'List', items: contents }, null, 2));
  writeFileSync(resolve(path, 'kustomization.yaml'), JSON.stringify({ apiVersion: 'kustomize.config.k8s.io/v1beta1', kind: 'Kustomization', resources: ['resources.json'] }, null, 2));
}
group('namespaces', objects.filter(item => item.kind === 'Namespace'));
group('foundation', objects.filter(item => !['Deployment', 'StatefulSet'].includes(item.kind) || ['application-db', 'rabbitmq'].includes(item.metadata.name)));
group('runtime', objects.filter(item => ['Deployment', 'StatefulSet'].includes(item.kind) && !['application-db', 'rabbitmq'].includes(item.metadata.name)));
group('migrations', [migrationJob('legacy-core', 'core', images['legacy-core']), migrationJob('equipment-adapter', 'adapter', images['equipment-adapter'])]);
group('identity-migration', [identityMigrationJob(images.keycloak)]);
writeFileSync(resolve(directory, 'manifest.json'), JSON.stringify({ renderedAt: new Date().toISOString(), simulatorAddress, images, resources: objects.map(item => `${item.kind}/${item.metadata.namespace ?? ''}/${item.metadata.name}`) }, null, 2));
console.log(`Rendered ${objects.length} scoped Kubernetes resources and three migration Jobs under ignored local storage.`);
