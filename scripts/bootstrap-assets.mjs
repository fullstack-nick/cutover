import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(readFileSync(resolve(root, 'infra/versions.lock.json'), 'utf8'));
const offline = process.argv.includes('--offline');
const missing = [];
for (const [name, image] of Object.entries(lock.images)) {
  if (process.argv.includes('--build-only') && !['javaRuntime', 'keycloak'].includes(name)) continue;
  const args = ['image', 'inspect', image.reference, '--format', '{{.Id}}'];
  let present = spawnSync('docker', args, { encoding: 'utf8', windowsHide: true }).status === 0;
  if (!present && !offline) {
    console.log(`Acquiring pinned ${name} ${image.version}`);
    const pull = spawnSync('docker', ['pull', image.reference], { stdio: 'inherit', windowsHide: true });
    present = pull.status === 0;
  }
  if (!present) missing.push(`image: ${image.reference}`);
}
const agent = resolve(root, '.local/assets/opentelemetry-javaagent.jar');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
if ((!existsSync(agent) || sha(readFileSync(agent)) !== lock.javaAgent.sha256) && !offline) {
  const response = await fetch(lock.javaAgent.url, { signal: AbortSignal.timeout(60000) });
  if (!response.ok) throw new Error(`Agent acquisition failed: ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (sha(bytes) !== lock.javaAgent.sha256) throw new Error('Agent checksum verification failed.');
  mkdirSync(dirname(agent), { recursive: true }); writeFileSync(agent, bytes);
}
if (!existsSync(agent) || sha(readFileSync(agent)) !== lock.javaAgent.sha256) missing.push('Java agent with the locked checksum');
const calico = readFileSync(resolve(root, 'infra/vendor/calico/v3.32.2/calico.yaml'));
if (sha(calico) !== lock.calicoManifest.sha256) missing.push('Unmodified pinned Calico manifest');
if (missing.length) { console.error(`Missing or invalid assets:\n${missing.join('\n')}`); process.exitCode = 1; }
else console.log('Pinned base images, Java agent and Calico source verified. This does not yet certify the full offline demonstration cache.');
