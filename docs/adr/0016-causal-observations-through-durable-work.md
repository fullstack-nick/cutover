# 0016 — Causal observations through durable work

Status: accepted, 8 September 2026.

HTTP and broker transport spans alone lose the original context when a later database poll resumes a task. Cutover uses the existing pinned Java agent with the OpenTelemetry API managed by the Spring Boot BOM (1.62.0). It installs no second tracing SDK or tracing starter in application code. This follows the [agent's API extension model](https://opentelemetry.io/docs/zero-code/java/agent/api/).

The outbox transaction stores a bounded W3C `traceparent` in the existing event envelope. Consuming an event restores its trace and correlation identity and makes its event ID the cause of newly emitted events. A later task or command poll reads its own retained movement envelope through existing aggregate/stream indexes. Replay retains original bytes and IDs, including this observational context. No new business migration or physical command field is needed.

Domain spans cover order/receipt admission, allocation, task dispatch, command journaling/investigation and inventory/sorting completion. Structured logs include synthetic resource, site, event and correlation IDs plus trace/span IDs. Exception messages, request bodies, credentials, baggage and tracestate are excluded by the manual instrumentation. Metric labels retain bounded categories. Invalid or absent trace context starts a new trace; metadata never selects a site, grants a role or permits physical execution.

Agent queues remain bounded at 512 spans, with batches of 128 and two-second export timeouts. Losing telemetry can lose observations, while the SQL audit and simulator execution ledger retain their independent durability guarantees. The independent simulator's physical effect is asserted from its ledger; the application trace includes the authenticated equipment HTTP client boundary. Trace retrieval is evidence of observations, not proof of a physical effect by itself.

After the documented retained-event horizon, a compacted context can be unavailable; later investigations still carry the stable resource ID but may begin a new trace. Historical events created before instrumentation likewise have no retroactively invented trace. `causal-trace-smoke.mjs` checks both products against Tempo and their owning business/physical ledgers. `healthy-load.mjs` measures the configured tracing overhead as part of the actual demonstration profile.
