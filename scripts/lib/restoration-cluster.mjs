import assert from 'node:assert/strict';
import { createReadStream, openSync, closeSync, existsSync, writeFileSync } from 'node:fs';
import { resolve, relative, isAbsolute } from 'node:path';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { root, call, until, jsonFile, namespaces, owners } from './local-platform.mjs';

export const restorationNetwork = 'cutover-restored-kind';
export const simulatorContainer = 'cutover-dev-equipment-simulator-1';
const kind = resolve(root, '.local/tools/kind.exe');
export async function run(tool, args, log, options = {}) {
  const fd = openSync(log, 'a', 0o600);
  try {
    await new Promise((done, failed) => {
      const child = spawn(tool, args, { cwd: root, windowsHide: true, stdio: ['ignore', fd, fd], ...options });
      const timer = setTimeout(() => { child.kill(); failed(new Error(`${tool} exceeded the bounded restoration step; inspect its private log.`)); }, 300000);
      child.on('error', error => { clearTimeout(timer); failed(error); });
      child.on('exit', code => { clearTimeout(timer); code === 0 ? done() : failed(new Error(`${tool} failed during restoration; inspect its private log.`)); });
    });
  } finally { closeSync(fd); }
}
async function fileHash(path) { const digest = createHash('sha256'); for await (const bytes of createReadStream(path)) digest.update(bytes); return digest.digest('hex'); }
export async function verifyImages(checkpoint) {
  const inventory = jsonFile(resolve(checkpoint.directory, 'node-images.json'));
  const expected = new Set(checkpoint.resources.flatMap(item => (item.spec?.template?.spec?.containers ?? []).map(container => container.image)));
  for (const key of ['calicoCni', 'calicoNode', 'calicoControllers']) { assert.ok(inventory[key]); expected.add(inventory[key].runtimeReference); }
  const selected = [];
  for (const reference of expected) {
    const entry = Object.values(inventory).find(item => item.runtimeReference === reference);
    assert.ok(entry, `Checkpoint runtime image has no retained cache entry: ${reference}`);
    const path = resolve(entry.archive), scope = relative(resolve(root, '.local/images/archives'), path);
    assert.ok(scope && !scope.startsWith('..') && !isAbsolute(scope), 'An image archive leaves the Cutover cache.');
    assert.ok(existsSync(path), `Required checkpoint image archive is missing: ${scope}`);
    assert.equal(await fileHash(path), entry.archiveSha256, `Image archive checksum differs: ${scope}`);
    const index = JSON.parse(call('tar', ['-xOf', path, 'index.json']));
    const descriptors = index.manifests.filter(item => item.platform?.os === 'linux' && item.platform?.architecture === 'amd64');
    assert.equal(descriptors.length, 1); assert.equal(descriptors[0].digest, entry.manifestDigest);
    assert.ok(reference.endsWith(`@${entry.manifestDigest}`));
    selected.push({ ...entry, alias: descriptors[0].annotations['io.containerd.image.name'] });
  }
  const lock = jsonFile(resolve(checkpoint.directory, 'versions.lock.json'));
  const node = JSON.parse(call('docker', ['image', 'inspect', lock.images.kindNode.reference]))[0];
  assert.ok(node.RepoDigests?.some(reference => reference.endsWith(lock.images.kindNode.reference.slice(lock.images.kindNode.reference.indexOf('@')))), 'The pinned kind node image must be cached.');
  return { inventory, selected, nodeImage: lock.images.kindNode.reference };
}
function range(cidr) {
  const [address, length] = cidr.split('/'); const octets = address.split('.').map(Number); const bits = Number(length);
  assert.ok(octets.length === 4 && octets.every(value => Number.isInteger(value) && value >= 0 && value <= 255) && Number.isInteger(bits) && bits >= 0 && bits <= 32);
  const ip = octets.reduce((value, part) => value * 256 + part, 0), size = 2 ** (32 - bits), start = Math.floor(ip / size) * size;
  return [start, start + size - 1];
}
function overlaps(a, b) { const left = range(a), right = range(b); return left[0] <= right[1] && right[0] <= left[1]; }
export function networkPlan() {
  const ids = call('docker', ['network', 'ls', '-q']).split(/\r?\n/).filter(Boolean);
  const networks = ids.length ? JSON.parse(call('docker', ['network', 'inspect', ...ids])) : [];
  assert.ok(!networks.some(network => network.Name === restorationNetwork), 'A restoration network already exists; use its recorded lifecycle procedure.');
  const occupied = networks.flatMap(network => network.IPAM.Config ?? []).map(item => item.Subnet).filter(value => value?.includes('.'));
  if (process.platform === 'win32') {
    const output = call('pwsh', ['-NoProfile', '-NonInteractive', '-Command', 'ConvertTo-Json -Compress -InputObject @(Get-NetRoute -AddressFamily IPv4 | Select-Object -ExpandProperty DestinationPrefix)']);
    occupied.push(...JSON.parse(output).filter(value => value !== '0.0.0.0/0'));
  }
  const podSubnet = '10.245.0.0/16', serviceSubnet = '10.97.0.0/16';
  for (const subnet of [podSubnet, serviceSubnet]) assert.ok(!occupied.some(existing => overlaps(subnet, existing)), `The restoration subnet ${subnet} overlaps an existing local route.`);
  const candidates = [...Array.from({ length: 16 }, (_, index) => `172.${28 + Math.floor(index / 4)}.${240 + index % 4}.0/24`),
    ...Array.from({ length: 16 }, (_, index) => `10.246.${240 + index}.0/24`)];
  const dockerSubnet = candidates.find(subnet => !occupied.some(existing => overlaps(subnet, existing)));
  assert.ok(dockerSubnet, 'No reserved restoration network candidate is available.');
  return { podSubnet, serviceSubnet, dockerSubnet };
}
export function inspectPrimary() {
  const nodes = JSON.parse(call('docker', ['ps', '-a', '--filter', 'name=^/cutover-restored-control-plane$', '--format', 'json']) || 'null');
  assert.equal(nodes, null, 'The existing restoration node must be handled through its saved lifecycle record.');
  const primary = JSON.parse(call('docker', ['inspect', 'cutover-control-plane']))[0];
  assert.equal(primary.Config.Labels['io.x-k8s.kind.cluster'], 'cutover');
  const simulator = JSON.parse(call('docker', ['inspect', simulatorContainer]))[0];
  assert.equal(simulator.Config.Labels['com.docker.compose.project'], 'cutover-dev');
  assert.equal(simulator.Config.Labels['com.docker.compose.service'], 'equipment-simulator');
  assert.equal(simulator.State.Running, true);
  return { id: primary.Id, wasRunning: primary.State.Running, simulatorId: simulator.Id };
}
export async function createCluster(platform, plan, images, directory, created) {
  const config = resolve(directory, 'cluster.json');
  writeFileSync(config, JSON.stringify({ kind: 'Cluster', apiVersion: 'kind.x-k8s.io/v1alpha4', networking: { apiServerAddress: '127.0.0.1', disableDefaultCNI: true, podSubnet: plan.podSubnet, serviceSubnet: plan.serviceSubnet }, nodes: [{ role: 'control-plane', labels: { 'dev.cutover.project': 'cutover', 'dev.cutover.profile': 'restoration' } }] }));
  call('docker', ['network', 'create', '--driver', 'bridge', '--label', 'dev.cutover.project=cutover', '--label', 'dev.cutover.profile=restoration', '--subnet', plan.dockerSubnet, restorationNetwork]);
  try {
    await run(kind, ['create', 'cluster', '--name', platform.cluster, '--image', images.nodeImage, '--config', config, '--kubeconfig', platform.kubeconfig, '--retain', '--wait', '0s'], resolve(directory, 'cluster.log'), { env: { ...process.env, KIND_EXPERIMENTAL_DOCKER_NETWORK: restorationNetwork } });
  } finally {
    const nodeId = call('docker', ['ps', '-a', '--filter', `name=^/${platform.cluster}-control-plane$`, '-q']);
    if (nodeId) { const node = JSON.parse(call('docker', ['inspect', nodeId]))[0];assert.equal(node.Config.Labels['io.x-k8s.kind.cluster'], platform.cluster);created(node.Id); }
  }
  for (const entry of images.selected) {
    await run(kind, ['load', 'image-archive', entry.archive, '--name', platform.cluster], resolve(directory, 'images.log'));
    call('docker', ['exec', `${platform.cluster}-control-plane`, 'ctr', '--namespace', 'k8s.io', 'images', 'tag', '--force', entry.alias, entry.runtimeReference]);
    // CRI observes containerd image/tag events asynchronously after the import command returns.
    await until(() => {
      try { return JSON.parse(call('docker', ['exec', `${platform.cluster}-control-plane`, 'crictl', 'inspecti', entry.runtimeReference])).status.repoDigests.includes(entry.runtimeReference); }
      catch { return false; }
    }, `CRI recognizes the imported ${entry.runtimeReference.split('@')[0]} digest`, 15000);
    console.log(`Restoration cache loaded ${entry.runtimeReference.split('@')[0]}.`);
  }
  let calico = call('kubectl', ['kustomize', 'infra/kind/calico']);
  for (const [key, name] of Object.entries({ calicoCni: 'cni', calicoNode: 'node', calicoControllers: 'kube-controllers' })) {
    const match = new RegExp(`quay\\.io/calico/${name}@sha256:[a-f0-9]{64}`, 'g'); assert.ok(match.test(calico));
    calico = calico.replace(match, images.inventory[key].runtimeReference);
  }
  assert.ok(calico.includes('10.244.0.0/16'));
  calico = calico.replaceAll('10.244.0.0/16', plan.podSubnet).replaceAll('imagePullPolicy: IfNotPresent', 'imagePullPolicy: Never');
  writeFileSync(resolve(directory, 'calico.yaml'), calico);
  platform.kube(['apply', '--server-side', '--field-manager=cutover-restoration', '-f', '-'], { input: calico });
  await until(() => platform.get('node', `${platform.cluster}-control-plane`).status.conditions.some(condition => condition.type === 'Ready' && condition.status === 'True'), 'restoration node and Calico readiness', 180000);
  call('docker', ['network', 'connect', restorationNetwork, simulatorContainer]);
  const simulator = JSON.parse(call('docker', ['inspect', simulatorContainer]))[0];
  const address = simulator.NetworkSettings.Networks[restorationNetwork].IPAddress;
  assert.ok(address && overlaps(`${address}/32`, plan.dockerSubnet));return address;
}
export function restoredResources(checkpoint, simulatorAddress) {
  const resources = structuredClone(checkpoint.resources);
  const oldAddress = resources.find(item => item.kind === 'EndpointSlice' && item.metadata.name === 'equipment-simulator').endpoints[0].addresses[0];
  for (const item of resources) {
    if (item.kind === 'EndpointSlice') item.endpoints[0].addresses = [simulatorAddress];
    if (item.kind === 'NetworkPolicy') for (const rule of item.spec.egress ?? []) for (const destination of rule.to ?? []) if (destination.ipBlock?.cidr === `${oldAddress}/32`) destination.ipBlock.cidr = `${simulatorAddress}/32`;
    if (item.kind === 'Deployment' && [...Object.values(owners), 'keycloak'].includes(item.metadata.name)) item.spec.replicas = 0;
    if (item.kind === 'Deployment' && Object.values(owners).includes(item.metadata.name)) item.spec.template.spec.containers[0].env.push({ name: 'CUTOVER_RESTORE_RETENTION_HELD', value: 'true' });
    if (item.kind === 'Namespace') assert.ok(namespaces.includes(item.metadata.name));
  }
  return resources;
}
