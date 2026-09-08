# Selected checkpoint evidence — 8 September 2026

This is capture and interrupted-capture evidence for phase 8. It does not establish full A46 fresh-cluster restoration, A47 stale-checkpoint recovery, the fifteen-minute restoration target, or the complete A52 stop/reset contract.

## Executed checks

`./mvnw.cmd -B -ntp verify` passed **125 checks** with no failures, errors or skips at **06:09:41 Europe/Berlin**, in **9 minutes 5 seconds**. A preceding focused run passed 60 checks after a missing test import was corrected. The new tests exercise frozen duplicate-order requests, manual command investigation, phase faults and shared control/fault operations against actual PostgreSQL transactions.

The rebuilt applications deployed on the existing local kind platform without clearing data. `node tools/scenario-driver/checkpoint-smoke.mjs` then passed **three checks** in `checkpoint-1788841302213`, recorded at **06:24:09 Europe/Berlin**:

| Check | Observed result |
| --- | --- |
| Complete capture and resume | Six databases and 207 tables captured; all table fingerprints stable across the stopped-writer interval; prior control flags restored and all writer deployments ready. |
| Artifact integrity and scope | A copied dump with changed bytes and a substituted artifact path were rejected. The original checkpoint checksum stayed unchanged. |
| Real process interruption | The actual checkpoint process was terminated at `DUMPING`, after all six writer deployments stopped. The saved journal restored their prior flags/replicas and cleaned temporary forwards. The incomplete capture remained incomplete. |

Capture plus resumption took **77.243 seconds**. The interval from all writers stopping through final dump consistency checks took **16.400 seconds**. These measurements do not include creating or restoring another cluster.

The complete artifact is `checkpoint-1788841302213-complete`, with manifest SHA-256 `5e19bae4f5eb9304f8fecf967bf20d3e31f44d3f542d9355c4b107a8a207080b`. Required credentials and raw database/resource exports remain in private local storage.

| Database owner | Tables fingerprinted | Retained source events qualified for later replay |
| --- | ---: | ---: |
| Core | 29 | 604 |
| Adapter | 22 | 564 |
| Execution | 18 | 0 |
| Returns | 20 | 16 |
| Shadow | 18 | 0 |
| Identity | 100 | Not applicable |

All source outboxes and received/pending inbox work were settled before the freeze. One pre-existing adapter inbox quarantine was retained and counted. Core order/stock/reservation/inventory, adapter route/allocation/command, and returns receipt/count/sorting fingerprints remained unchanged after both complete and interrupted capture. The simulator retained its original world and journal generation at sequence **179** throughout; no test movement was invented.

## Tested source and images

The run used source revision `14d289e10437` plus the then-uncommitted checkpoint changes, the existing synthetic dataset, and the full demo profile. Raw evidence separately records actual pod images and the build/cache inventories. The simulator stayed on its prior independent runtime image; a newly built simulator image was not implicitly deployed.

| Runtime | Loaded image digest |
| --- | --- |
| Core | `sha256:9deacf9d151e609c3822a22c5bc6d4695428d9fac5eeae1e87ef8734daba37c4` |
| Adapter | `sha256:840963fc8982fab84580c9d6af599fcc7a08fc739901e1aca5ea5117224086ab` |
| Execution / shadow | `sha256:0b77252ec12836e8f1552c70c8da96a8fc53fd1f4aecf7317404c17fce54e112` |
| Returns | `sha256:36f013cc7a82e9d00e88c8a60eccfafd2481b098f3ac56c509226206976b4424` |
| Console | `sha256:694370ae5d5e44fb66d4e6fcf5af15e9b40580c2a539c34e97a1293d4b959141` |

## Failure retained

The earlier `checkpoint-1788841046248` exercised the capture/recovery mechanics, but its export included Kubernetes-generated endpoint addresses and old owner references. That checkpoint is **not qualified for restoration**. The exporter now retains only the explicit simulator endpoint, and the corrected verifier rejects the earlier artifact before restoration. The complete three-check process run was repeated after this correction.

Use the [checkpoint runbook](../runbooks/checkpoint.md) for the exact commands and [ADR 0013](../adr/0013-stopped-writer-application-checkpoints.md) for the freeze and recovery boundary. Fresh/stale restoration, physical storage pressure, mixed-product capacity, prepared offline operation and the final acceptance bundle remain required.
