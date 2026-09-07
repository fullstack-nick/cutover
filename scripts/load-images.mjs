import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(readFileSync(resolve(root, 'infra/versions.lock.json'), 'utf8'));
const directory = resolve(root, '.local/images/archives'); mkdirSync(directory, { recursive: true });
const manifestPath = resolve(root, '.local/images/node-images.json');
const inventory = existsSync(manifestPath) ? JSON.parse(readFileSync(manifestPath, 'utf8')) : {};
const sha = value => createHash('sha256').update(value).digest('hex');
function capture(tool, args) {
  const result = spawnSync(tool, args, { cwd: root, encoding: 'utf8', windowsHide: true, maxBuffer: 4 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${tool} failed: ${result.stderr.trim()}`);
  return result.stdout.trim();
}
function run(tool, args) {
  return new Promise((done, failed) => {
    const child = spawn(tool, args, { cwd: root, stdio: 'inherit', windowsHide: true });
    child.on('error', failed); child.on('exit', code => code === 0 ? done() : failed(new Error(`${tool} failed (${code}).`)));
  });
}
if (capture('docker', ['inspect', '--format', '{{index .Config.Labels "io.x-k8s.kind.cluster"}}', 'cutover-control-plane']) !== 'cutover') throw new Error('Cutover node ownership check failed.');
const selected = process.argv.includes('--calico') ? ['calicoCni', 'calicoNode', 'calicoControllers'] : ['postgres', 'rabbitmq', 'proxy', 'collector', 'prometheus', 'grafana', 'tempo'];
const images = selected.map(name => ({ name, reference: lock.images[name].reference }));
if (!process.argv.includes('--calico')) {
  for (const image of JSON.parse(readFileSync(resolve(root, '.local/images/manifest.json'), 'utf8')).images)
    if (image.service !== 'equipment-simulator') images.push({ name: image.service, reference: image.reference });
}
const only = process.argv.find(value => value.startsWith('--only='))?.slice(7);
if (only && !images.some(image => image.name === only)) throw new Error('Unknown image selection.');
for (const { name, reference } of images.filter(image => !only || image.name === only)) {
  const imageId = capture('docker', ['image', 'inspect', reference, '--format', '{{.Id}}']);
  const existing = inventory[name];
  if (existing?.source === reference && existing.sourceImageId === imageId) {
    const present = spawnSync('docker', ['exec', 'cutover-control-plane', 'crictl', 'inspecti', existing.runtimeReference], { encoding: 'utf8', windowsHide: true });
    if (present.status === 0 && JSON.parse(present.stdout).status.repoDigests.includes(existing.runtimeReference)) { console.log(`Verified ${name} already present in the Cutover node.`); continue; }
  }
  const identity = sha(reference + '|' + imageId).slice(0, 20);
  const archive = resolve(directory, `${name}-${identity}-amd64.tar`);
  // A project-local alias gives the single-platform archive a deterministic name without moving upstream tags.
  const alias = `cutover/cache:${name.toLowerCase()}-${identity}`;
  await run('docker', ['tag', reference, alias]);
  if (!existsSync(archive)) await run('docker', ['image', 'save', '--platform', 'linux/amd64', '--output', archive, alias]);
  const index = JSON.parse(capture('tar', ['-xOf', archive, 'index.json']));
  const candidates = index.manifests.filter(entry => entry.platform?.os === 'linux' && entry.platform?.architecture === 'amd64');
  if (candidates.length !== 1 || candidates[0].mediaType.includes('index')) throw new Error(`Unexpected single-platform archive for ${name}.`);
  const descriptor = candidates[0];
  const blob = spawnSync('tar', ['-xOf', archive, `blobs/sha256/${descriptor.digest.slice(7)}`], { cwd: root, windowsHide: true });
  if (blob.status !== 0 || 'sha256:' + sha(blob.stdout) !== descriptor.digest) throw new Error('Exported image manifest failed its digest check.');
  await run(resolve(root, '.local/tools/kind.exe'), ['load', 'image-archive', archive, '--name', 'cutover']);
  let repository = reference.split('@')[0].replace(/:[^/:]+$/, '');
  const first = repository.split('/')[0];
  if (!first.includes('.') && !first.includes(':') && first !== 'localhost') repository = 'docker.io/' + (repository.includes('/') ? repository : `library/${repository}`);
  const runtimeReference = `${repository}@${descriptor.digest}`;
  const importedAlias = descriptor.annotations['io.containerd.image.name'];
  await run('docker', ['exec', 'cutover-control-plane', 'ctr', '--namespace', 'k8s.io', 'images', 'tag', '--force', importedAlias, runtimeReference]);
  const verified = capture('docker', ['exec', 'cutover-control-plane', 'crictl', 'inspecti', runtimeReference]);
  if (!JSON.parse(verified).status) throw new Error(`CRI image lookup failed for ${name}.`);
  inventory[name] = { source: reference, sourceImageId: imageId, platform: 'linux/amd64', runtimeReference, manifestDigest: descriptor.digest, archive, archiveSha256: sha(readFileSync(archive)) };
  writeFileSync(manifestPath, JSON.stringify(inventory, null, 2) + '\n');
  console.log(`Loaded and verified ${name} (${descriptor.digest.slice(0, 23)}).`);
}
