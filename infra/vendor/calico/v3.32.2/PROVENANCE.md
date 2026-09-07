# Calico 3.32.2 manifest

`calico.yaml` is the unchanged upstream file acquired on 7 September 2026 from [the versioned Calico source](https://raw.githubusercontent.com/projectcalico/calico/v3.32.2/manifests/calico.yaml).

SHA-256: `a8c828a06a87c629a282ebbc424895b77f3a030251993e41ea400a743675bb02` (350,324 bytes).

Upstream license: [Apache License 2.0](https://github.com/projectcalico/calico/blob/v3.32.2/LICENSE.md), reproduced at `licenses/Apache-2.0.txt` in this repository. Cutover's Kustomize overlay selects the IPv4 pod CIDR, VXLAN, iptables dataplane, and pinned images. The upstream bytes remain intact. The [maintainer's kind instructions](https://docs.tigera.io/calico/latest/getting-started/kubernetes/kind) explain disabling the default CNI and using Calico's manifest.

The node DaemonSet has infrastructure privileges to configure networking inside the dedicated kind node. Application workloads do not inherit those privileges.
