# Missing or incomplete operational observations

Start the named local forwards with `./scripts/forward.ps1 -Profile demo -Target prometheus -Action Start`, and repeat for `tempo` and `grafana`. Then run `node tools/scenario-driver/telemetry-smoke.mjs`. It checks actual scrape targets, trace retrieval and Grafana health. These are observation checks; review the owning API and durable records before concluding that work failed.

For a representative flow, run `node tools/scenario-driver/causal-trace-smoke.mjs`. It records one mixed-zone order and one mixed-classification receipt, verifies their single physical/business effects, and saves the original trace IDs and bounded exports under the private run directory. Look up a saved trace at `http://localhost:8782/api/traces/<traceId>` or use the Tempo data source in local Grafana. See [ADR 0016](../adr/0016-causal-observations-through-durable-work.md) for propagation boundaries.

If metrics are absent, check the target's `up`/last scrape error and the named service's readiness. A missing or old observation is not a healthy zero. If traces are absent while business records advance, inspect Collector/Tempo health and their bounded export failures. Recover those named observation services; replaying a business command is not a telemetry repair. Do not change a recorded command identity, bypass an ownership gate, or clear uncertain equipment evidence to obtain a complete trace.

The manual instrumentation exports synthetic IDs and sanitized error categories. Raw logs and exports remain private. Before selecting an export for the public portfolio, check it for credentials and unrelated machine data and retain only the evidence needed to explain the chain.
