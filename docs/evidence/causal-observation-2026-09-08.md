# Causal observation — selected local evidence

Date: 8 September 2026. Local demo, source `6d2c97e` plus the recorded causal-tracing changes. These observations cover the application trace boundary and the independent simulator ledger; they are not the complete acceptance bundle.

The full backend run passed 150 checks at 09:57:35 Europe/Berlin with no failures, errors or skips. The applications use the pinned Java agent and its OpenTelemetry API, with explicit spans around domain work and propagation through the existing immutable event envelope. No second tracing SDK is installed in the runtime.

| Deployed run | Observed result |
| --- | --- |
| `causal-trace-1788855048465` | Two checks passed. A mixed-zone outbound order and mixed-classification receipt completed five movements, each with one matching physical/business effect. |
| Outbound trace `68a34d49afd1c53920720b473b7840f0` | 633 spans retrieved from local Tempo using the original HTTP trace ID. |
| Returns trace `150360a5f307d332a257360e14117162` | 840 spans retrieved with its original HTTP trace ID. |
| `retained-intent-1788855010368` | A supervisor replayed the unchanged quarantined intent from an earlier failed migration scenario. The same order completed with one physical/inventory effect after same-world absence and current-route checks. |

The captured traces contain event recording/publication/application, task coordination, command recording/investigation and inventory/sorting completion. Passive subscribers can appear in a trace without gaining dispatch authority. Equipment HTTP client spans end at the simulator boundary; physical execution is corroborated by its separate durable ledger.

The trace export check searched for the actual generated credentials and found none. The deployment initially failed at a stale console-forward PID after the application rollouts. A rechecked process-ownership guard repaired that final step; all application readiness and identity checks then passed. This failure and the earlier migration failure remain retained locally.

## Load qualification remains separate

`load-1788855149799` accepted 1,980 requests and completed 3,300 warm-up/measured movements once each. Its original task-stage timing reported 99.733% within two seconds and p99 482 ms. The audit found original eligibility-to-dispatch was **67.3% within two seconds, p99 9,982.546 ms**. A separate qualification record links to the unchanged original result by SHA-256. The run is insufficient for final A50 qualification.

The corrected harness includes assignment/publication time in its primary dispatch metric. The pre-fix 60-second diagnostic `load-diagnostic-1788856854826` measured **48.667% within two seconds, p99 10,304.704 ms**, with 185.055 client-backend WAL fsyncs per second. Its 350 physical/business effects and quantities matched. Short diagnostics make no A50 claim. The unarmed-fault fast path and subsequent measurements are recorded separately as they complete.

Reproduce with `node tools/scenario-driver/causal-trace-smoke.mjs` and, separately, `node tools/scenario-driver/healthy-load.mjs`. The latter requires sufficient existing stock, settled prior work, active execution-owned routes, unblocked lanes and no armed faults. It does not replenish inventory or reset physical history.
