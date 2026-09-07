# 0002 — Local packaging and disposable schema generation

Status: accepted, 7 September 2026.

The legacy responsibilities remain in place for the first container milestone. Each application packages its own executable jar and image; moving a process into a container does not extract its business ownership.

The Maven reactor builds a small internal code-generation plugin. For each application it starts a labelled disposable PostgreSQL 18.6 instance, applies the technical and owner-specific Flyway migrations, generates jOOQ SQL types, and closes the instance. Generation and runtime share the Boot-managed jOOQ version. Generated sources stay under `target/`; the runtime build consumes them. No demonstration database URL or credential is accepted by the generator. This follows the database-first technique described by [jOOQ](https://blog.jooq.org/using-testcontainers-to-generate-jooq-code/) and [Testcontainers](https://testcontainers.com/guides/working-with-jooq-flyway-using-testcontainers/).

The clean verification run passed 20 component tests. A second generation produced the same hashes for all 98 generated Java files. These are packaging and component results; the complete 54-scenario platform matrix remains separate.

Locally built images combine Git revision, jar content, Dockerfile content, and the pinned base reference in their identity. Build manifests retain actual image IDs and whether the source tree was dirty. Runtime database accounts have DML privileges; one-shot migrators perform schema changes. Keycloak is bootstrapped under its migration account, stopped, and started with its runtime account. Real authenticated probes verify that each runtime account cannot create tables.

The development profile uses internal application and equipment networks. On the inspected Docker Engine, a container attached only to internal networks did not receive a requested host port mapping. The proxy and simulator therefore also have separate, project-owned access networks and bind their host ports only to loopback. Other services and databases retain their internal networks. [Docker's networking model](https://docs.docker.com/engine/network/) supports this multi-network arrangement. It does not establish final offline-egress or Kubernetes policy acceptance; those require their own traffic tests.

Keycloak's externally advertised hostname includes `/identity`, matching the issuer validated by every API. Internal token/JWKS requests use the service address. A real token check exposed and corrected an initial issuer path mismatch. The ordinary proxy exposes fixed public routes; simulator controls, internal service APIs, metrics, and database ports are not routed through it.
