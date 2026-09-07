# Cutover — pre-development research

Research date: 7 September 2026.

This report resolves the engineering choices needed to start the [implementation plan](../planning/implementation-plan.md). It uses official project documentation, specifications, maintainer release records, package metadata, and direct local-machine inspection. Architectural decisions and capacity estimates below are recommendations for this project, not claims about an existing production system.

Research covered scope and naming, domain/state models, legacy extraction, contracts, persistence, messaging, uncertain physical effects, local Kubernetes, identity, network isolation, telemetry, offline dependencies, recovery, testing, licensing, public presentation, and the development machine. No application was implemented or deployed during research. Compatibility documentation does not substitute for compiling and running the chosen combination.

## Findings that materially change the starting plan

1. **Retain Git; remove delivery infrastructure.** Public GitHub hosting works with local Git without Actions, runners, a local Git server, or a continuous deployment controller. Manual image build/load and kubectl deployment satisfy the local-only and no-CI/CD constraints. [GitHub: About Git](https://docs.github.com/en/get-started/using-git/about-git).
2. **Java 21 is sufficient.** Spring Boot's documented runtime range and jOOQ 3.21 OSS support align with the installed Java major. PostgreSQL 18 is the documented OSS dialect baseline for jOOQ 3.21, so choose it rather than assuming any PostgreSQL major is equally supported. [Spring requirements](https://docs.spring.io/spring-boot/system-requirements.html), [jOOQ JDK matrix](https://www.jooq.org/download/support-matrix-jdk), [jOOQ database matrix](https://www.jooq.org/download/support-matrix).
3. **Select Kubernetes explicitly.** Calico 3.32 lists Kubernetes 1.34–1.36 as tested, while the kind release provides newer node images too. Use the published 1.36.4 image rather than kind's changing default. [Calico requirements](https://docs.tigera.io/calico/latest/getting-started/kubernetes/requirements), [kind 0.33.0 release](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0).
4. **Persistence needs a real boundary.** PostgreSQL 18's container data-directory layout differs from older examples. Local kind PVCs do not establish persistence after cluster deletion. Export backups separately and keep the simulator's database independent. [Official PostgreSQL image documentation](https://github.com/docker-library/docs/blob/master/postgres/README.md), [Kubernetes persistent volumes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/).
5. **Publisher confirmation alone is insufficient.** Unroutable messages can be positively confirmed. Outbox relays need mandatory routing/returns and confirmation handling; consumers still need deduplication. [RabbitMQ confirmations](https://www.rabbitmq.com/docs/confirms), [reliability guide](https://www.rabbitmq.com/docs/reliability).
6. **A useful demo can remain relatively small.** Tempo's monolithic mode supports local storage and does not require Kafka. A static console and ordinary reverse proxy avoid another application backend and an ingress-controller installation. [Tempo deployment modes](https://grafana.com/docs/tempo/latest/reference-tempo-architecture/deployment-modes/).
7. **The host is capable but currently crowded.** It has the major prerequisites, but kind is absent, only 4.4 GiB RAM was free at the snapshot, and existing containers occupy several standard ports. See [machine readiness](local-machine-readiness.md). This is an observed setup constraint, not a missing architecture decision.

## Version baseline

These are concrete starting candidates, checked on the research date. Before generating the implementation lockfile, verify artifact availability/checksums and execute the compatibility gate below. Never silently update a major dependency mid-implementation.

| Layer | Selected candidate | Evidence and implication |
| --- | --- | --- |
| Java | Temurin JDK 21; installed 21.0.10+7 | Direct `java`/`javac` inspection. Current jOOQ 3.21 OSS uses Java 21. Retain the major; review available maintenance updates at bootstrap. |
| Spring Boot | 4.1.1 | [Requirements](https://docs.spring.io/spring-boot/system-requirements.html). Boot manages the compatible dependency set; use its BOM. |
| jOOQ | OSS 3.21.7 | [Boot dependency table](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html). Align generator and runtime; `org.jooq` artifacts. |
| PostgreSQL | 18.6, official Debian-based container | [Release index](https://www.postgresql.org/docs/release/), [official image tags](https://github.com/docker-library/docs/blob/master/postgres/README.md). Same major/version for runtime, tests, codegen, and dump tools. |
| Migrations | Flyway OSS 12.4.0 as managed by Boot | [Boot BOM table](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html), [PostgreSQL module](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database). Include `org.flywaydb:flyway-database-postgresql`, not just core. |
| Java tests | Boot-managed JUnit/Jupiter 6.0.3 and Testcontainers 2.0.5 | [Managed coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html). Testcontainers 2 module names differ from old examples. |
| Build | Maven 3.9.16 through Maven Wrapper | [Maven download](https://maven.apache.org/download.cgi), [Wrapper](https://maven.apache.org/tools/wrapper/). No global Maven installation required for reviewers. |
| Frontend | Node 24; React 19.2.8; Vite 8.2.2; TypeScript 7.0.2; npm | Installed Node 24.19.0/npm 11.2.0; npm registry metadata inspected for React, Vite, and TypeScript. [Vite requirements](https://vite.dev/guide/) accept the installed Node major. Freeze remaining packages together and include platform-specific tool binaries in the offline cache. |
| Browser tests | Playwright 1.63.0, Chromium initially | npm package metadata checked. [Browser documentation](https://playwright.dev/docs/browsers) requires matching browser binaries; existing cache does not prove a match. |
| Container runtime | Existing Docker Desktop 4.88.1 / Engine 29.7.2 / Compose 5.4.0 | Working Linux engine directly inspected. [Testcontainers requirements](https://java.testcontainers.org/supported_docker_environment/) cover Docker Desktop on Windows; prove this exact runtime with the chosen Java dependencies. |
| Kubernetes tool | kind 0.33.0 | [Official quickstart](https://kind.sigs.k8s.io/docs/user/quick-start/). Missing from host PATH. |
| Kubernetes node | 1.36.4 | [Published node image](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0). Compatible minor with installed kubectl 1.36.1 and Calico's documented range. |
| Network policy | Calico OSS 3.32.2 | [Maintainer release](https://github.com/projectcalico/calico/releases/tag/v3.32.2), [kind setup](https://docs.tigera.io/calico/latest/getting-started/kubernetes/kind). Disable kind's default CNI before installing. |
| Manifests | kubectl embedded Kustomize 5.8.1 | Direct client inspection. [Kustomize in kubectl](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/). Vendor remote resources locally. |
| Broker | RabbitMQ 4.3.5 | [Maintainer release](https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.3.5). Use durable single-member quorum queues with explicit limitations. |
| Identity | Keycloak server 26.7.3; JS adapter 26.2.4 | [Official downloads](https://www.keycloak.org/downloads). Adapter/server releases are separate. [Database support](https://www.keycloak.org/server/db) includes PostgreSQL 18. |
| Traces/telemetry gateway | OpenTelemetry Collector 0.160.0 | [Maintainer release](https://github.com/open-telemetry/opentelemetry-collector-releases/releases/tag/v0.160.0). Select and lock a distribution containing the configured components. |
| Java instrumentation | OpenTelemetry Java agent 2.31.1 | [Maintainer release](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.31.1). One agent route; explicit API spans for domain work. |
| Metrics | Prometheus 3.14.0 | [Maintainer release](https://github.com/prometheus/prometheus/releases/tag/v3.14.0). Local storage, fixed retention, no hosted remote-write destination. |
| Dashboards | Grafana OSS 13.2.1 | [Maintainer release](https://github.com/grafana/grafana/releases/tag/v13.2.1). Provision dashboards and data sources; disable optional analytics/update checks. |
| Trace storage | Tempo 3.0.3, monolithic | [Maintainer release](https://github.com/grafana/tempo/releases/tag/v3.0.3), [local configuration](https://grafana.com/docs/tempo/latest/set-up-for-tracing/setup-tempo/deploy/locally/linux/). Use version-appropriate 3.x configuration. |
| Contracts | OpenAPI 3.1.2; AsyncAPI 3.0.0; JSON Schema 2020-12-compatible definitions | [OpenAPI](https://spec.openapis.org/oas/v3.1.2.html), [AsyncAPI](https://www.asyncapi.com/docs/reference/specification/v3.0.0). Prefer a tooling-supported 3.1 baseline over upgrading solely because another specification is newer. |
| Java schema validation | NetworkNT json-schema-validator 3.0.7 | [Maintainer documentation](https://github.com/networknt/json-schema-validator) identifies 3.x for Java 17+/Jackson 3 and supports draft 2020-12. Use only locally registered schemas. |

The selected published kind node reference is:

```text
kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed
```

Verify this release reference at acquisition. The kind release prose contained inconsistent default-version wording; selecting a listed explicit image avoids relying on that prose.

The listed infrastructure and Java-agent maintainer releases were also read through GitHub's public release API. Frontend versions were read using `npm view`, without installing packages. Exact container digests, transitive dependency licenses, wrapper/agent binary checksums, and the full npm graph remain acquisition-time lockfile outputs, not fabricated research results.

### Required compatibility gate

Before the walking skeleton expands, prove:

- Maven Wrapper runs with Java 21; its distribution checksum is verified.
- Boot starts; JSON dates/enums/unknown fields round-trip as the contracts specify.
- PostgreSQL 18 migrations run from empty and previous schema; jOOQ generation and a transactional query execute.
- The schema validator and code generators support the selected OpenAPI/JSON Schema versions and the application's serialization stack. Boot 4 uses newer library generations; do not paste Boot 3/Jackson 2 snippets untested.
- Testcontainers can connect to this Docker API, start PostgreSQL/RabbitMQ, and clean up only its own resources.
- Broker confirm/return handling works; reconnect uses the intended retry semantics.
- Keycloak issues a browser token; APIs verify the public issuer using reachable internal JWKS and an explicit audience.
- The node becomes Ready with Calico; one allowed and one forbidden network flow are observed.
- An OTLP span reaches the selected Tempo 3 configuration and a Prometheus metric appears without duplicate instrumentation.
- The reverse proxy serves built assets/API routes and the exact OIDC callback correctly from localhost.

These are implementation tests, not questions for the owner. A documented unsupported combination should trigger a narrow version adjustment and recorded ADR, not another broad architecture research phase.

## Architecture choices and alternatives

| Decision | Reason and tradeoff | Rejected first-release alternative |
| --- | --- | --- |
| Legacy-first, transport extraction | Establishes observable pre-migration behavior and leaves reservation authority stable. | Starting with already separated microservices would hide the modernization problem. |
| Four business/integration JVM processes plus simulator | Makes ownership and failure boundaries real without fragmenting every capability. | Separate inventory, order, scheduler, recovery, site, and audit services. |
| SQL routines only where intentionally legacy | Makes characterization and extraction concrete, while normal application logic remains testable Java. | Recreating a proprietary database language or migrating every database rule at once. |
| One application PostgreSQL server, separate databases | Affordable local footprint with enforceable database credentials. | Shared business schemas, or a database server for each tiny service. |
| Separate simulator database/server | Restore can preserve later virtual physical actions. | Restoring application and simulator to the same old checkpoint and claiming recovery correctness. |
| RabbitMQ with outbox/inbox | Fits bounded work queues and explicit acknowledgement semantics; duplicate effects remain application responsibility. | Kafka, CDC infrastructure, distributed transactions, or claims of exactly-once physical execution. |
| Polling outbox relay | Small operational footprint; latency and aggregate ordering must be designed. | Debezium solely to move a few hundred local events. |
| Adapter owns route/allocation ledger | A single durable command authority can reject stale/unauthorized dispatch. | Configuration flags in two independently running schedulers. |
| Strict zone drain | Easier proof of exclusive dispatch, with a visible period of queued unassigned work. | Reassigning in-flight work based on expiring leases. |
| Independent old/new scheduler implementations | Shadow comparisons can reveal semantic differences. | One shared scheduler function called twice, which proves little. |
| Java 21 | Already installed and compatible with the selected OSS libraries. | Upgrading Java major without a project need. |
| React/Vite static console | Enough for readable workflows, recovery, and reviewer evidence; no extra API backend. | SSR framework, 3D scene, animation-heavy warehouse mockup. |
| PKCE browser client | Standard Keycloak flow with a small runtime; requires XSS prevention and careful token handling. | Adding a BFF/session store or storing tokens in localStorage. |
| Localhost proxy and port-forward | Works without public DNS, hosts-file edits, a cloud load balancer, or an ingress operator. | Installing an ingress controller for one local browser endpoint. |
| kind/Kustomize/manual scripts | Preserves the Kubernetes learning objective without an automated delivery platform. | GitOps, hosted deployment, a registry that is not needed, or a runner. |
| Bounded local telemetry | Answers business questions while respecting this host's memory. | Large observability distributions, object-storage services, or mandatory Loki. |

The incremental migration follows the general [strangler fig pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/strangler-fig). The specific ownership, state, and cutover protocol are Cutover design decisions; no source asserts they are a ready-made correctness proof.

## Reliability research translated into requirements

The [transactional outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html) closes the local commit-to-publication gap but allows relay duplicates. Consequently the implementation needs inbox deduplication and independent business uniqueness; neither a UUID nor broker acknowledgement alone is enough.

[PostgreSQL row locks](https://www.postgresql.org/docs/18/explicit-locking.html) are the basis for stock reservation, capacity counters, and route-transition serialization. Use consistent lock ordering and bounded deadlock retries. `SKIP LOCKED` is appropriate for queue-like work claiming, not a way to read a consistent stock balance; see [SELECT locking clauses](https://www.postgresql.org/docs/18/sql-select.html).

[RabbitMQ queue caps](https://www.rabbitmq.com/docs/maxlength) must reject publication instead of discarding old accepted work. [Quorum-queue documentation](https://www.rabbitmq.com/docs/quorum-queues) informs the queue choice and highlights that dead-letter delivery semantics need explicit configuration. Cutover avoids a fragile retry-queue chain by retaining pending and quarantined messages transactionally in the consumer database.

The physical-command model is a project design: a stable movement/command identity, immutable payload fingerprint, independent simulator journal, and explicit unknown outcome. The recovery API requests investigation; it does not translate a timeout into a new movement. This requirement is tested against the simulator's execution ledger.

Per-aggregate sequence rules and monotonic terminal states handle duplicate and out-of-order observations. A route epoch fences old owners; a drained zone avoids overlapping authority. Event envelopes never carry authority that the adapter accepts without checking authenticated identity and its own durable ledger.

## Security, identity, and network findings

The [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter) uses in-memory tokens and supports the browser code flow. Use PKCE and narrowly scoped redirects, following the [OAuth security BCP](https://www.rfc-editor.org/rfc/rfc9700.html). APIs validate JWTs using [Spring Security's resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

Local OIDC has two address spaces: what the browser uses and what pods can reach. Configure a fixed public issuer, internal key retrieval, and trusted proxy handling, using [Keycloak hostname guidance](https://www.keycloak.org/server/hostname). Test host-header changes, incorrect audience, key refresh, and token expiry; never disable issuer validation to solve a networking problem.

[Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/) needs an enforcing network plugin. Calico installation alone is not a proof of the intended rules: test the exact required and forbidden paths. Infrastructure requires some privileges that application pods should not have.

Avoid the retired community ingress-nginx project for this new build. The [Kubernetes retirement announcement](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/) specifies March 2026; an ordinary maintained reverse-proxy container does not depend on that controller.

Network policy and database separation do not make an unauthenticated equipment protocol trustworthy. The plan adds locally generated mutual TLS for that boundary and restricts fault controls. Localhost browser HTTP remains a labelled local demonstration limitation.

Recovery audit belongs in durable application data, not only logs. Record actor, action, site, reason, expected/actual versions, causal IDs, before/after state, and evidence. A dashboard user is not automatically a business supervisor.

## Observability, offline mode, and recovery findings

[OpenTelemetry Collector configuration](https://opentelemetry.io/docs/collector/configuration/) requires components to be connected in service pipelines; merely defining an exporter does not activate it. [Java instrumentation guidance](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/) supports choosing one intentional instrumentation route. Prove request, broker, scheduler, and equipment correlation with an actual trace.

[Prometheus naming guidance](https://prometheus.io/docs/practices/naming/) supports bounded labels and meaningful units. Record unique order/command IDs in logs/traces; use low-cardinality site/zone/state/service labels for operational metrics.

[Spring health guidance](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html) cautions against including external dependencies in liveness. Cutover keeps degraded APIs reachable when they can accept work durably, and separates business readiness from monitoring health.

Offline readiness is a cache inventory problem across several stores: Maven, npm, Playwright, Docker images, and kind node containerd. `kind load` avoids a registry but still requires explicit node image loading. Bootstrap must include transitive images such as Calico dependencies and test helper images, plus locally bundled UI/documentation assets. This inventory is a project requirement rather than a promise from any one tool.

[PostgreSQL logical backup](https://www.postgresql.org/docs/18/backup-dump.html) supplies the database mechanism. Cross-service consistency is Cutover's responsibility: quiesce and record a checkpoint, then replay a known event range after restoration. A backup of one database does not describe the state of a separate simulator or message broker.

The required stale-checkpoint experiment makes the recovery limitation observable: accepted work since the checkpoint may be outside the recoverable application state even when physical completion is known. Preserve and report that discrepancy.

## Licensing, provenance, and public presentation

The owner chose MIT for original project code and documentation on 7 September 2026. The decision is recorded in the [repository LICENSE](../../LICENSE) and [decision record](../planning/open-decisions.md). Upstream components retain their own licenses and required notices. [Canonical MIT terms](https://opensource.org/license/mit).

Use OSS library/container distributions and their official sources. Generate an SBOM/license inventory at implementation release time; preserve required notices for copied manifests or bundled tools. Do not label all infrastructure as Apache-licensed: Grafana's core projects use AGPLv3, as described in [Grafana licensing](https://grafana.com/licensing/). Keep third-party services identifiable and avoid redistributing modified infrastructure binaries without checking their terms.

Docker Desktop's current terms include personal use among its free-use categories; that fits the stated personal portfolio scenario. This does not generalize to all workplace use. [Docker Desktop terms](https://docs.docker.com/subscription-billing/desktop-license/).

The original source archive is excluded before the first Git commit. Public material uses independent descriptions, original screenshots and diagrams, synthetic identifiers, and factual standard technology names. It must not present hiring or employer-specific context as the project's purpose.

The owner confirmed Cutover on 7 September 2026, matching the workspace folder. An unrelated enterprise-software business also uses [Cutover](https://cutover.com/). Preserve original project branding and content, and do not claim the name is globally unused. This is a recorded naming finding, not a legal trademark conclusion. The [decision record](../planning/open-decisions.md) reflects the owner's chosen name.

## What remains uncertain

No unresolved owner or architecture decision blocks development. The name Cutover and MIT license are confirmed. The plan chooses service boundaries, database ownership, migration authority, broker behavior, UI approach, local delivery, and recovery tradeoffs.

The following require actual implementation or environment setup:

| Uncertainty | How it is resolved | Earliest gate |
| --- | --- | --- |
| Exact chosen dependency graph compiles together | The compatibility smoke gate and locked artifacts | Phase 0–2 |
| This machine has enough free resources at runtime | Free capacity, doctor check, measured profile footprint | Before full demo |
| Calico/WSL kernel and simulator routing work as configured | One-node networking and mutual-TLS smoke checks | Phase 3 |
| Dispatch latency target is achievable | Fixed offered load, declared lane capacity, measured samples | Phase 8 |
| Offline cache is complete | Deny project external egress and execute the full prepared demo | Phase 8 |
| Recovery duration and data-loss window | Fresh restore and stale-checkpoint experiments | Phase 8 |

Research is complete at the pre-development level: the required engineering work and its verification are specified. Performance, successful fault handling, secure implementation, and reproducibility remain claims to earn through the acceptance matrix.
