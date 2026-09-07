# Independent service scaffold

This template contains only local service infrastructure: Java 21, the technical starter, owner-local migrations and generated SQL types, JWT authentication, durable delivery hooks, health and telemetry configuration. It contains no order, stock, task, receipt or equipment persistence model.

Run `node scripts/scaffold-service.mjs execution-service execution` or `node scripts/scaffold-service.mjs returns-service returns` from the repository root. The generator refuses an existing directory, validates the names, records the exact template hashes in `.scaffold.json`, and adds the new Maven module. Implement the product's own message handler, tables and APIs after generation. The initial handler deliberately rejects unsupported events.

Database/broker/client provisioning, image packaging, network policy and deployment remain explicit reviewed source changes. Generating files never starts a service or publishes anything. Product onboarding is complete only after ownership, contracts, duplicates, authorization and operational behavior have been verified.
