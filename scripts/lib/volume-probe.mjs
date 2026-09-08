import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { call } from './local-platform.mjs';

export const volumeProbeScript = fileURLToPath(new URL('../../platform/service-starter/src/main/resources/ops/volume-probe.sh', import.meta.url));
const definition = fileURLToPath(new URL('../../platform/service-starter/src/main/resources/ops/volume-status.sql', import.meta.url));
export function volumeStatusSql(owner) {
  assert.ok(['core', 'adapter', 'execution', 'returns', 'shadow', 'simulator'].includes(owner));
  return readFileSync(definition, 'utf8') + `\nGRANT USAGE ON SCHEMA cutover_ops TO cutover_${owner}_runtime;\nGRANT EXECUTE ON FUNCTION cutover_ops.volume_status() TO cutover_${owner}_runtime;\n`;
}

export function installComposeVolumeStatus(group) {
  assert.ok(['application', 'simulator'].includes(group));
  const name = `cutover-dev-${group}-db-1`, container = JSON.parse(call('docker', ['inspect', name]))[0];
  assert.equal(container.Config.Labels['dev.cutover.project'], 'cutover');
  assert.equal(container.Config.Labels['com.docker.compose.project'], 'cutover-dev');
  assert.equal(container.Config.Labels['com.docker.compose.service'], `${group}-db`);
  assert.ok(container.Mounts.some(mount => mount.Name === (group === 'application' ? 'cutover-dev-application-data' : 'cutover-equipment-data')));
  const owners = group === 'application' ? ['core', 'adapter', 'execution', 'returns', 'shadow'] : ['simulator'];
  for (const owner of owners) call('docker', ['exec', '-i', name, 'sh', '-c', `export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d cutover_${owner} -q`], { input: volumeStatusSql(owner) });
}
