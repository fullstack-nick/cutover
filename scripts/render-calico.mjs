import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const inventory = JSON.parse(readFileSync(resolve(root, '.local/images/node-images.json'), 'utf8'));
const directory = resolve(root, '.local/kubernetes/calico'); mkdirSync(directory, { recursive: true });
const names = { calicoCni: 'quay.io/calico/cni', calicoNode: 'quay.io/calico/node', calicoControllers: 'quay.io/calico/kube-controllers' };
writeFileSync(resolve(directory, 'kustomization.yaml'), JSON.stringify({
  apiVersion: 'kustomize.config.k8s.io/v1beta1', kind: 'Kustomization', resources: ['../../../infra/kind/calico'],
  images: Object.entries(names).map(([key, name]) => ({ name, digest: inventory[key].manifestDigest })),
}, null, 2));
