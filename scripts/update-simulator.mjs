import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { simulator, root } from '../tools/scenario-driver/client.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const name = 'cutover-dev-equipment-simulator-1';
async function docker(args, capture = false, environment = process.env) {
  return new Promise((done, failed) => {
    const child = spawn('docker', args, { cwd: root, windowsHide: true, env: environment, stdio: ['ignore', capture ? 'pipe' : 'inherit', 'pipe'] });
    let output = '';
    child.stdout?.on('data', chunk => { output += chunk; });
    child.stderr.on('data', () => {});
    child.on('error', failed); child.on('exit', code => code === 0 ? done(output) : failed(new Error('The scoped Cutover simulator update failed.')));
  });
}
const compose = ['compose', '--project-name', 'cutover-dev', '--project-directory', root, '--file', resolve(root, 'infra/compose/dev.yml'), '--env-file', resolve(root, '.local/images/images.env'), '--env-file', resolve(root, '.local/secrets/dev.env')];
const before = JSON.parse(await docker(['inspect', name], true))[0];
const database = JSON.parse(await docker(['inspect', 'cutover-dev-simulator-db-1'], true))[0];
for (const item of [before, database]) {
  assert.equal(item.Config.Labels['dev.cutover.project'], 'cutover');
  assert.equal(item.Config.Labels['com.docker.compose.project'], 'cutover-dev');
  assert.equal(item.State.Running, true);
}
assert.ok(database.Mounts.some(mount => mount.Name === 'cutover-equipment-data'));
const worldBefore = (await simulator('/sim/v1/equipment')).body;
let updated = false;
try {
  await docker([...compose, 'stop', 'equipment-simulator']);
  await docker([...compose, 'run', '--rm', '--no-deps', 'migrate-simulator']);
  await docker([...compose, 'up', '-d', '--no-deps', '--wait', '--wait-timeout', '120', 'equipment-simulator']);
  updated = true;
} finally {
  if (!updated) await docker([...compose, 'up', '-d', '--no-deps', '--wait', '--wait-timeout', '120', 'equipment-simulator'], false, { ...process.env, CUTOVER_EQUIPMENT_SIMULATOR_IMAGE: before.Config.Image });
  const after = JSON.parse(await docker(['inspect', name], true))[0];
  if (before.NetworkSettings.Networks['cutover-kind'] && !after.NetworkSettings.Networks['cutover-kind']) await docker(['network', 'connect', 'cutover-kind', name]);
}
const worldAfter = (await simulator('/sim/v1/equipment')).body;
assert.equal(worldAfter.worldId, worldBefore.worldId);
assert.equal(worldAfter.journalGeneration, worldBefore.journalGeneration);
assert.ok(worldAfter.journalHighWater >= worldBefore.journalHighWater);
mkdirSync(resolve(root, '.local/operations'), { recursive: true });
writeFileSync(resolve(root, '.local/operations/simulator-update.json'), JSON.stringify({ at: new Date().toISOString(), priorImage: before.Image, worldBefore, worldAfter }, null, 2));
console.log('Updated the independent Cutover simulator with its physical data preserved. Run deploy.ps1 now to refresh the discovered endpoint and policies.');
