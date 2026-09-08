# Crate returns

The returns service owns receipt counts, sorting intents, tasks and destination counters. The adapter owns dispatch authority and physical command recovery. The core's stock ledger is not involved in crate sorting.

## Observe a receipt

Sign in to the local console and open **Returns**. The three destination cards show site totals for received, sorted and outstanding crates. Open a receipt reference to inspect its classifications and movements, then choose **Inspect movement evidence**. A sorted count means terminal equipment evidence was applied, not merely that a request was accepted.

The console polls local APIs and labels the last successful observation. An unavailable API displays a warning while retaining the previous observations. Site access and recovery roles are enforced by the server.

## Submit synthetic work

An authorized scenario or service identity can submit the following JSON to `POST /api/v1/sites/site-a/return-receipts` with its bearer token and a unique `Idempotency-Key` header:

```json
{
  "sourceSystem": "scenario-driver",
  "externalReceiptRef": "local-returns-example-001",
  "counts": { "REUSABLE": 8, "NEEDS_CLEANING": 3, "DAMAGED": 2 }
}
```

All three classification keys are required, each accepts 0–10,000 crates, and at least one count must be positive. Successful intake returns 202 with the receipt ID and status URL. Repeating the same source/reference and payload returns the original receipt. Changing the payload for that reference or an already used idempotency key conflicts.

Read APIs are `/return-receipts`, `/return-receipts/{id}`, `/return-counters` and `/return-tasks`, all under the site prefix. Receipt pages accept `limit` up to 100 and an opaque UUID cursor. Counters cover the whole site, while the receipt register shows one page.

## Recover waiting or uncertain work

- **Blocked:** Inspect the returns lane and freshness of equipment observations. Once the lane is available, the original task proceeds. Outbound lane faults do not block the returns lane.
- **Connection attempts paused:** Correct connectivity or identity availability. A supervisor can use **Resume status investigation** with a reason. The API at `/return-tasks/{id}/recovery` requires the current version and records an audit entry. A stale version conflicts; refresh before another action.
- **Reconciliation required:** Inspect the original command. Use the adapter's supervisor reconciliation action after the underlying evidence problem is corrected. The service will not create a replacement movement, invent completion or increment sorted counts during uncertainty.

An entirely completed receipt cannot become incomplete because an old accepted or unknown observation arrives. For a partly completed receipt, each remaining classification retains its own task state and reason.

## Verify independence

`node tools/scenario-driver/returns-smoke.mjs` runs the deployed demonstration profile. It requires healthy console and adapter API forwards and initially open controls. It creates synthetic work, tests duplicate receipt intake and distinct broker event IDs, checks owner-local and physical ledgers, blocks a selected lane, and stops/restarts only the owned returns deployment while outbound work continues. It preserves the existing simulator world and restores its selected fault controls. Evidence remains under ignored local storage.

The connected backend tests are `ReturnsWorkflowTest`. They use disposable PostgreSQL databases for returns, the adapter and the simulator, and deterministic transport ports. Those tests alone do not establish real HTTP, broker, Kubernetes or browser acceptance.
