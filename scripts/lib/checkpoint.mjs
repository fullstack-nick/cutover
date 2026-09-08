import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { root, databases, namespaces, sha, jsonFile } from './local-platform.mjs';

export function verifyCheckpoint(name) {
  assert.match(name, /^[a-z0-9][a-z0-9-]{2,63}$/, 'Use a checkpoint name, never an arbitrary path.');
  const directory = resolve(root, '.local/checkpoints', name);
  const bytes = readFileSync(resolve(directory, 'manifest.json'));
  assert.equal(sha(bytes), readFileSync(resolve(directory, 'manifest.sha256'), 'utf8').trim(), 'The checkpoint manifest checksum differs.');
  const manifest = JSON.parse(bytes); assert.equal(manifest.formatVersion, 1); assert.equal(manifest.name, name);
  assert.equal(manifest.source.cluster, 'cutover'); assert.equal(manifest.source.profile, 'demo');
  assert.deepEqual(manifest.databases.map(item => item.owner).sort(), [...databases].sort(), 'All six application owners are required exactly once.');
  const artifacts = ['resources.json', 'node-images.json', 'versions.lock.json', 'secrets/credentials.json', 'secrets/equipment/scenario.p12', 'secrets/equipment/ca.pem'];
  assert.deepEqual(manifest.artifacts.map(item => item.file).sort(), artifacts.sort(), 'Unexpected checkpoint artifact path.');
  for (const database of manifest.databases) {
    assert.equal(database.file, `${database.owner}.dump`, 'Unexpected database artifact path.');
    assert.equal(sha(readFileSync(resolve(directory, database.file))), database.sha256, `The ${database.owner} dump checksum differs.`);
    assert.ok(database.rows.length > 0); assert.ok(database.schema.length > 0);
    if (database.owner !== 'keycloak') {
      assert.ok(database.schema.every(item => item.success));
      assert.equal(manifest.controls[database.owner].workersPaused, true); assert.equal(manifest.controls[database.owner].dispatchPaused, true);
      assert.ok(Array.isArray(database.replay.events));
      assert.equal(new Set(database.replay.events.map(event => event.eventId)).size, database.replay.events.length);
    }
  }
  for (const artifact of manifest.artifacts) assert.equal(sha(readFileSync(resolve(directory, artifact.file))), artifact.sha256, `The ${artifact.file} checksum differs.`);
  for (const field of ['worldId', 'journalGeneration']) assert.equal(manifest.physicalStart[field], manifest.physicalEnd[field]);
  assert.equal(manifest.physicalEnd.completeHistory, true);
  assert.ok(manifest.physicalEnd.journalHighWater >= manifest.physicalStart.journalHighWater);
  const resources = jsonFile(resolve(directory, 'resources.json')).items;
  const allowedKinds = new Set(['Namespace', 'Service', 'Deployment', 'StatefulSet', 'ConfigMap', 'Secret', 'NetworkPolicy', 'EndpointSlice']);
  for (const item of resources) {
    assert.ok(allowedKinds.has(item.kind), 'Unexpected restored resource kind.');
    assert.ok(namespaces.includes(item.kind === 'Namespace' ? item.metadata.name : item.metadata.namespace), 'A checkpoint resource leaves the owned namespaces.');
    assert.equal(item.metadata.labels['app.kubernetes.io/part-of'], 'cutover');
    assert.ok(!item.metadata.ownerReferences, 'Old cluster owner references must not be restored.');
    if (item.kind === 'EndpointSlice') {
      assert.equal(item.metadata.name, 'equipment-simulator');
      assert.equal(item.metadata.labels['endpointslice.kubernetes.io/managed-by'], 'cutover-renderer', 'Selector-backed endpoints must be regenerated in the new cluster.');
    }
    if (item.spec?.template?.spec) {
      const pod = item.spec.template.spec;
      assert.equal(pod.automountServiceAccountToken, false); assert.equal(pod.securityContext.runAsNonRoot, true);
      assert.ok(!pod.hostNetwork && !pod.hostPID && !pod.hostIPC);
      assert.ok((pod.volumes ?? []).every(volume => !volume.hostPath));
      for (const container of pod.containers) {
        assert.equal(container.imagePullPolicy, 'Never'); assert.match(container.image, /@sha256:[a-f0-9]{64}$/);
        assert.equal(container.securityContext.allowPrivilegeEscalation, false); assert.ok(!container.securityContext.privileged);
      }
    }
  }
  return { directory, manifest, resources, manifestSha256: sha(bytes) };
}
