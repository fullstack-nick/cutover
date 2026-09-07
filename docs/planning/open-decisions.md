# Cutover — decision record and development readiness

7 September 2026. All owner decisions needed to begin development are settled. Engineering defaults are specified in the implementation plan. Do not restart broad architecture selection before development.

## Confirmed: public name and repository identity

**Owner-confirmed decision:** Cutover, with repository slug cutover. The owner explicitly supplied this correction on 7 September 2026. It matches the current folder name.

The public documents now use Cutover. The local source archive retains the initial attachment and the earlier research snapshot, followed by an append-only correction.

An unrelated enterprise-software business also uses [Cutover](https://cutover.com/). Record this existing use without copying its product names, visual identity, documentation, or claims. The owner's selected name remains Cutover; this research does not establish global name exclusivity.

The default repository owner is the authenticated personal GitHub account inspected during research. A read-only lookup did not find an accessible cutover repository there. If another owner or organization is intended, state its exact name before creation. Public visibility and no CI/CD are already settled requirements; they do not need another approval.

No folder rename is needed.

## Confirmed: repository license

**Owner-confirmed decision:** MIT for original project code and documentation. The owner explicitly chose MIT on 7 September 2026. The repository's [LICENSE](../../LICENSE) contains the license text, using the collective attribution "Cutover contributors."

Preserve separate upstream licenses and notices for dependencies and copied infrastructure resources. The original-code license does not relicense those resources. The canonical terms are available from the [Open Source Initiative](https://opensource.org/license/mit).

## Ready to begin development

There are no remaining owner or architecture questions that must be answered before development starts. The confirmed name is Cutover and the confirmed repository license is MIT. The implementation plan, acceptance matrix, and goal handoff define the work and completion criteria.

Tool installation, dependency locking, compatibility smoke checks, and measuring runtime capacity are implementation tasks. Their outcomes must be verified during development; research does not establish that the future application already builds or passes acceptance tests.

## Environment action before the full demo

The snapshot had 4.4 GiB free RAM and 20 existing running containers. Install kind and make room for the planned full platform. The owner should choose which unrelated workloads to pause; the implementation must not stop them or prune their data automatically.

This does not block all development. Begin with small development/test profiles, then run the full stack after the doctor check confirms capacity. If no workloads can be paused, revisit the demo resource budget before attempting acceptance runs.

## Decisions already made by the research

| Topic | Selected answer |
| --- | --- |
| Git and public GitHub | Keep both; local history and manual push. |
| CI/CD and GitOps | Omit all runners/controllers/workflows. |
| Runtime/deployment | Local Docker and one kind node with Calico. |
| Language and data access | Java 21, Spring Boot, jOOQ OSS, PostgreSQL 18. |
| Build and manifests | Maven Wrapper, npm, Kustomize, manual scripts. |
| Service ownership | Core retains stock; execution owns extracted tasks; returns owns its own product; adapter owns dispatch authority. |
| Migration safety | Deterministic shadow comparison, strict zone drain, adapter route epoch and stable movement ownership. |
| Equipment uncertainty | Independent durable simulator ledger; stable command IDs; query/reconcile unknown outcomes. |
| Identity | Local Keycloak, PKCE browser client, API-enforced role/site access, independent service credentials. |
| UI | Small React/TypeScript operations console; original design, accessible states, recovery evidence. |
| Observability | Collector, Prometheus, Grafana, single-process Tempo; structured logs; no mandatory Loki. |
| Offline guarantee | Prepared local runtime/restart/rollback; explicitly acquired cache. |
| Backup | Quiescent checkpoint plus separate stale-restore experiment preserving the simulator world. |
| Portfolio standard | Honest README, architecture/ADRs/runbooks/onboarding, measured evidence, reproducible demo. |
| Completion | All 54 acceptance scenarios; no replacement of central work with optional features. |

Owner replies should become a dated addendum in the local planning archive and a clear accepted decision in the public planning files. The original supplied text must remain unchanged.
