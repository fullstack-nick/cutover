import { namespaces } from './platform.mjs';
const pod = name => ({ matchLabels: { 'app.kubernetes.io/name': name } });
const namespace = name => ({ matchLabels: { 'kubernetes.io/metadata.name': name } });
const peer = (ns, name) => ({ namespaceSelector: namespace(ns), podSelector: pod(name) });
export function policies(simulatorAddress) {
  const result = [];
  const add = (ns, name, selector, spec) => result.push({ apiVersion: 'networking.k8s.io/v1', kind: 'NetworkPolicy', metadata: { namespace: ns, name, labels: { 'app.kubernetes.io/part-of': 'cutover' } }, spec: { podSelector: selector, ...spec } });
  for (const ns of Object.values(namespaces)) {
    add(ns, 'default-deny', {}, { policyTypes: ['Ingress', 'Egress'] });
    add(ns, 'dns', {}, { policyTypes: ['Egress'], egress: [{ to: [{ namespaceSelector: namespace('kube-system'), podSelector: { matchLabels: { 'k8s-app': 'kube-dns' } } }], ports: [{ protocol: 'UDP', port: 53 }, { protocol: 'TCP', port: 53 }] }] });
  }
  const { apps: a, platform: p, observability: o } = namespaces;
  const links = [];
  const connect = (fromNs, from, toNs, to, ports) => links.push({ fromNs, from, toNs, to, ports });
  for (const name of ['legacy-core', 'equipment-adapter', 'execution-service', 'returns-service']) {
    connect(a, name, p, 'application-db', [5432]); connect(a, name, p, 'rabbitmq', [5672]);
    connect(a, name, p, 'keycloak', [8080]); connect(a, name, o, 'collector', [4318]);
    connect(a, `migrate-${name}`, p, 'application-db', [5432]);
    connect(o, 'prometheus', a, name, [9091]);
  }
  for (const name of ['legacy-core', 'execution-service', 'returns-service']) connect(a, name, a, 'equipment-adapter', [8080]);
  for (const name of ['legacy-core', 'equipment-adapter', 'execution-service', 'returns-service']) connect(a, 'proxy', a, name, [8080]);
  connect(a, 'proxy', p, 'keycloak', [8080]);
  connect(p, 'keycloak', p, 'application-db', [5432]); connect(p, 'migrate-keycloak', p, 'application-db', [5432]);
  connect(o, 'collector', o, 'tempo', [4317]); connect(o, 'grafana', o, 'tempo', [3200]); connect(o, 'grafana', o, 'prometheus', [9090]);
  connect(o, 'prometheus', p, 'keycloak', [9000]); connect(o, 'prometheus', p, 'rabbitmq', [15692]);
  connect(o, 'prometheus', o, 'collector', [8888]); connect(o, 'prometheus', o, 'tempo', [3200]);
  for (const [index, link] of links.entries()) {
    const ports = link.ports.map(port => ({ protocol: 'TCP', port }));
    add(link.fromNs, `allow-${index}-to-${link.to}`, pod(link.from), { policyTypes: ['Egress'], egress: [{ to: [peer(link.toNs, link.to)], ports }] });
    add(link.toNs, `allow-${index}-from-${link.from}`, pod(link.to), { policyTypes: ['Ingress'], ingress: [{ from: [peer(link.fromNs, link.from)], ports }] });
  }
  add(a, 'adapter-equipment', pod('equipment-adapter'), { policyTypes: ['Egress'], egress: [{ to: [{ ipBlock: { cidr: `${simulatorAddress}/32` } }], ports: [{ protocol: 'TCP', port: 8443 }] }] });
  add(o, 'simulator-metrics', pod('prometheus'), { policyTypes: ['Egress'], egress: [{ to: [{ ipBlock: { cidr: `${simulatorAddress}/32` } }], ports: [{ protocol: 'TCP', port: 9091 }] }] });
  return result;
}
