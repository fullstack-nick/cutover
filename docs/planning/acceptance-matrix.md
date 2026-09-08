# Cutover — acceptance and evidence matrix

Completion contract established 7 September 2026. The criteria below remain the implementation contract; see the separate [executed acceptance status](../evidence/acceptance-results.md) for current results and open gates.

Every run records commit, exact image IDs/digests, versions, profile, host resource allocation, seed, world ID, start/end time, attempted/accepted/rejected counts, invariant results, and commands to reproduce it. Store raw output in ignored local storage; publish selected redacted evidence. Do not replace database/simulator assertions with screenshots.

## Functional and data correctness

| ID | Scenario | Required observable result |
| --- | --- | --- |
| A01 | Fully available ambient/chilled order | Correct zone compatibility; all reservations consumed once; order completes only after every movement is confirmed. |
| A02 | Partial and total shortage | Reserved/short quantities add to requested quantities; no negative availability; exact terminal shortage states; no automatic replenishment. |
| A03 | Repeat order with same key and reference | One logical order, one reservation set, one movement per reservation. Return the recorded response. |
| A04 | Same key, changed payload; new key, repeated external reference | Changed payload conflicts; a new key cannot bypass business uniqueness. Repeat for returns and recovery actions. |
| A05 | Concurrent orders competing for the last units | No overselling or negative counters. Sum of committed reservations never exceeds stock; deterministic deadlock retry behavior. |
| A06 | Mixed-classification crate receipt | Reusable/cleaning/damaged movements use correct destinations. Received and sorted counts reconcile independently. |
| A07 | Repeated return messages with different event IDs | Business-reference uniqueness prevents duplicate crate counts and duplicate movements. |
| A08 | Cancellation before any physical acceptance | Reservations release once. Attempt during unknown/started work conflicts and does not release stock. |
| A09 | Late and out-of-order state observations | Completed states do not regress; sequence gaps become visible and repairable. |
| A10 | Invalid site, SKU, quantity, enum, oversize body, duplicate lines | Rejection with documented problem response before business writes or resource exhaustion. |

## Message and equipment failures

| ID | Controlled fault | Required observable result |
| --- | --- | --- |
| A11 | Crash after business commit, before outbox publish | Restart publishes the pending event; accepted work remains discoverable. |
| A12 | Crash after broker confirm, before outbox mark | Duplicate delivery occurs; inventory/task/receipt effects remain single. |
| A13 | Crash consumer after effect commit, before acknowledgement | Delivery is repeated; inbox dedup and business constraints prevent repeated effects. |
| A14 | Positive publish confirm but unroutable mandatory message | Return is detected; outbox is not falsely marked delivered. Fix routing and replay successfully. |
| A15 | Stop RabbitMQ during intake | Durable local backlog grows within limits; controlled 503/Retry-After before quota breach; recovery drains without loss. |
| A16 | Exhaust queue capacity | Reject-publish/nack propagates into relay retry; no drop-head data loss; shadow observation cannot block critical dispatch. |
| A17 | Poison event and retry exhaustion | Original payload/IDs retained in durable quarantine; no hot redelivery loop; audited reprocessing after a real correction. |
| A18 | Simulator executes but response is lost | Adapter observes timeout/unknown, queries same command, discovers completion; simulator ledger contains exactly one execution. |
| A19 | Disconnect before simulator durably accepts | Status proof permits resend of same stable ID; no new physical command identity. |
| A20 | Restart simulator while executing | Load position and command ledger remain atomic; resumed work executes once. |
| A21 | Same command ID with changed destination or quantity | Adapter and simulator independently reject immutable-payload conflict. |
| A22 | Unknown history or changed simulator world ID | No automatic dispatch/retry; explicit reconciliation state and clear evidence gap. |
| A23 | Block a single outbound lane | Lane backlog visible; eligible alternate/unrelated lanes and returns continue; chilled routing remains valid. |
| A24 | Stop adapter or its database | No equipment bypass; pending requests remain durable elsewhere; recovery resumes from journal and allocation records. |
| A25 | Stop monitoring components | Business commits and dispatch continue; telemetry buffers remain bounded; missing observations clearly distinguishable from healthy state. |
| A26 | Critical storage pressure | New intake/dispatch stops before journal durability becomes unsafe; reads/recovery diagnostics remain available where possible; accepted records are retained. |

## Migration and compatibility

| ID | Scenario | Required observable result |
| --- | --- | --- |
| A27 | At least 1,000 deterministic shadow decisions | Identical input hashes, old/new outputs, and zero unexplained differences; tie, blocked, chilled, and recovery cases covered. |
| A28 | Shadow instance attempts a command | Adapter rejects its identity regardless of shadow-mode application flags. |
| A29 | One zone cuts over while other work flows | Finite drain inventory accounted for; zero outstanding allocated work at epoch switch; exactly one active dispatch owner per zone. |
| A30 | Delayed command from old owner after cutover | Fencing/route validation rejects it or returns a prior terminal result without redispatch. |
| A31 | Crash/restart at each migration phase | Session resumes from durable phase; no split ownership, silently abandoned intents, or forced completion. |
| A32 | Two supervisors initiate a migration concurrently | Expected-version/route locking permits one state transition; the other receives a conflict. |
| A33 | Attempt cutover with an unknown command | Session remains blocked and identifies exact command; timeout cannot bypass the barrier. |
| A34 | Roll back a compatible application image | N−1 image operates on expanded schema; route ownership remains unchanged. |
| A35 | Reverse business ownership | Existing allocated work drains with its owner; pending unassigned work gets the new epoch afterward; no backlog duplication. |
| A36 | Producer/consumer N and N−1 combinations | Additive fixture works both ways; deliberately breaking fixture fails contract checks before deployment. |
| A37 | jOOQ generation/migration from empty and previous schema | Reproducible generated types; expected constraints/routines; app roles cannot run migrations or access other databases. |
| A38 | Returns scaffolded from shared template | Product-specific code and database ownership independent; changing fulfilment internals does not require returns model changes. |

## Security, platform, recovery, and reviewer experience

| ID | Scenario | Required observable result |
| --- | --- | --- |
| A39 | Operator attempts reconciliation/cutover | API denies it even when called directly; allowed supervisor action records actor, site, reason, before/after versions, and outcome. |
| A40 | Site-a identity guesses site-b IDs | Lists, lookups, mutations, recovery, and event processing cannot cross site scope; no object-existence leak. |
| A41 | Invalid JWT issuer/audience/expiry/signature; user-modified site | Request denied; no reliance on frontend checks. Unknown keys fail closed. |
| A42 | Forge equipment completion from ordinary service credentials | Broker publish permission or adapter authentication denies it; task/inventory state unchanged. |
| A43 | Untrusted equipment TLS client | Simulator rejects it; valid adapter certificate succeeds; private keys absent from Git and telemetry. |
| A44 | Calico policy isolation | Explicit allowed/denied connection matrix verified with traffic, including DNS, simulator access, telemetry, and forbidden cross-service paths. |
| A45 | Restart app pod, PostgreSQL process, and broker separately | Durable state survives within documented storage boundary; no duplicate effect or lost accepted order. |
| A46 | Fresh cluster from quiescent checkpoint | Application restore with dispatch disabled; recreated broker/replay range; all checkpoint work reconciled; restore duration measured. |
| A47 | Restore older application checkpoint after later physical moves | Simulator remains untouched. Later completions recognized or quarantined for missing context; no repeated movement; explicit RPO gap. |
| A48 | Prepared offline demo | Local login, both products, failure recovery, restart, cached image rollback, and dashboards work under Cutover-only external-egress denial. |
| A49 | Cold-cache detection | Doctor/cache verification lists missing images/packages/browser versions and fails clearly; no misleading offline-ready claim. |
| A50 | Declared healthy load for 10 minutes after warm-up | Publish dispatch p99/percentage within 2 seconds, backlog age, throughput, host/VM memory and CPU, and exact excluded blocked-work counts. |
| A51 | UI and role walkthrough | Real login, lists/details, shortages, staleness, recovery reason/conflicts, migration blockers, and returns; keyboard navigation and accessible states. |
| A52 | Normal stop and explicit reset | Stop preserves data; reset targets only named Cutover resources and changes simulator world ID only when deliberately requested. |
| A53 | Public-repository review | No ignored source archive, secrets, private logs, unrelated vendor narrative, workflow files, hosted runtime endpoints, or fictional success evidence. |
| A54 | Reviewer quickstart and recorded demonstration | Exact documented commands work; screenshots/recording match implementation; README accurately states limitations and measured results. |

## Evidence format

Use a per-run directory containing:

- `manifest.json`: run metadata, versions, image identities, hardware/profile, seeds, and scenario selection.
- `results.json` and a short Markdown report: pass/fail/blocked per A-ID, not a single aggregate green result.
- Exported assertion queries/counts for inventory, allocations, journals, inbox/outbox, receipts, and audit.
- Sanitized logs and trace links/exports demonstrating one representative causal chain.
- Screenshots or a short recording for console behavior, labelled with date and commit.

For A18, for example, show one external order, one reservation, one logical movement, repeated transport attempts of the same command ID, one simulator execution, one consumed inventory effect, and the final order state. A screenshot of a green order alone is insufficient.

Tests use isolated datasets/resources and deterministic fault switches. Use bounded condition polling rather than long arbitrary sleeps. A test may query an owning service's database for assertions under a dedicated read-only test credential; runtime applications still cannot read another owner's tables.

All A-IDs are required for the final portfolio scope. A documented failure remains a failure. If an environmental limit blocks a scenario, report that specific limitation and do not call the implementation complete.
