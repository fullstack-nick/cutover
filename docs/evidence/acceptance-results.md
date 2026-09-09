# Acceptance status

Status: final verification in progress, 9 September 2026. The implementation is **not yet fully qualified**: sustained latency remains open.

The [acceptance contract](../planning/acceptance-matrix.md) defines the required outcomes. This table maps every ID to its evidence and remaining check. Historical runtime results establish only their recorded application versions. [Selected runtime provenance](runtime-runs.json) includes commands, source/build revisions, actual running image identities, assertions and hashes of the private original results. A component check is identified explicitly; it does not establish an unexecuted platform scenario. Failed attempts remain in the [implementation ledger](../planning/implementation-progress.md).

| ID | Evidence available / remaining qualification |
| --- | --- |
| A01 | `functional-1788887744619`: actual ambient/chilled HTTP order completes with compatible allocations and single physical/inventory effects. |
| A02 | The same functional run exhausts real stock with exact partial/total shortages and conservation of every requested quantity; order-register UI evidence is separate. |
| A03 | The same functional run returns identical original responses for original/fresh keys with one order/reservation set; original-key retry remains readable during admission refusal. |
| A04 | The same functional run rejects changed payloads under both key paths. Independent returns, cancellation and work-console runs cover their owner-specific conflicts. |
| A05 | `functional-1788887744619`: six concurrent two-line HTTP requests with alternating input order consume only existing last units, with no overselling or leaked reservations. Deterministic transaction retry also has PostgreSQL component checks. |
| A06 | `returns-1788838277326`: mixed reusable/cleaning/damaged destinations and independent count/physical reconciliation. |
| A07 | The same returns run publishes repeated requests/completions with new event IDs and proves single effects. |
| A08 | `cancellation-1788817068207`: unstarted cancellation releases once; started/unknown work retains its reservation. |
| A09 | PostgreSQL checks reject regressing/conflicting observations; `storage-repair-1788819273019` exposes a sequence gap and repairs it through original-event replay. |
| A10 | The same functional run rejects eight invalid inputs without order, reservation, idempotency or receipt writes. [Raw HTTP boundary evidence](http-boundaries-2026-09-08.md), `problem-responses-1788891930309`, separately asserts seven authentication, role, request and proxy responses on the corrected runtime, with unchanged owner rows. |
| A11 | `process-crash-1788898621595`: the subscription runtime exits after business commit and completes the retained original after restart. |
| A12 | The same run exits after publisher confirmation and records duplicate delivery with single effects. |
| A13 | The same run exits after effect commit before acknowledgement, then deduplicates the repeated delivery. Separate real PostgreSQL/quorum checks cover multi-message crash and transaction rollback. |
| A14 | `mandatory-return-1788898920446`: actual positive confirm with mandatory return retains the original business outbox; restoring the exact binding delivers the same IDs and one physical/sorting effect. |
| A15 | Current `9dc5363` runtime, `broker-capacity-1788910166484`: actual broker absence, 800 accepted requests/1,600 retained events, controlled refusals and 802 single effects after recovery. The earlier complete run is retained in runtime provenance. |
| A16 | `queue-overflow-1788898769893`: actual critical/shadow quorum limits reject publication, retain the original head/source bytes, and drain 10,001/1,001 duplicate deliveries with single effects. Both products continue while shadow is full; audited original replay succeeds on the subscription runtime. |
| A17 | `storage-repair-1788819273019`: poison exhaustion, retained bytes, predecessor correction and audited reprocessing; typed raw-quarantine ownership regressions pass in components. |
| A18 | `equipment-recovery-1788814389493`: lost response resolves against the same command, with one execution. |
| A19 | The same equipment run proves retained pre-acceptance absence before resending the original ID. |
| A20 | The same equipment run restarts the simulator and preserves its atomic load position/ledger. |
| A21 | The same equipment run verifies adapter and simulator rejection of changed immutable payloads. |
| A22 | The same equipment run and [restoration checks](restore-2026-09-08.md) retain explicit evidence gaps rather than inventing physical history. |
| A23 | `returns-1788838277326`: alternate compatible outbound work and independent returns continue during one outbound-lane fault. |
| A24 | `platform-resilience-1788883850354`: both products accept retained work while the adapter process is absent; its return produces five original single effects. |
| A25 | The same resilience run stops all four monitoring processes while both products commit 25 single movements. Business pods survive and scrapes recover; deployed telemetry queues and exporter timeouts are bounded. |
| A26 | `volume-pressure-1788851160214` applies actual bounded filesystem pressure; `volume-observation-1788851871227` expires observations across all five database owners. |
| A27 | `shadow-1788869933650`: 1,000 persisted identical-input comparisons with zero unexplained differences and real observation delivery. |
| A28 | The same shadow run denies dispatch independently of the mode flag. `authentication-1788880772251` additionally denies business intake while preserving permitted snapshot reads. |
| A29 | `zone-migration-1788870698705`: finite ambient drain while chilled work flows, reconciled inventory, single active owner/epoch. |
| A30 | The same migration run rejects former-owner commands; an already terminal command can return its retained result. |
| A31 | `migration-crash-1788932966281`: five actual phase-specific adapter exits/restarts resume one durable session, with the corrected post-commit observation proof. |
| A32 | `migration-reversal-1788831400131`: concurrent real supervisor sessions produce one transition and a version conflict. |
| A33 | The same reversal run remains blocked on an identified unknown command; a timeout cannot authorize the switch. |
| A34 | Actual predecessor adapter image operates on expanded schema in rollback/volume-observation runs. `offline-1788887275782` additionally verifies cached predecessor import, actual rollback and restoration of the current image under scoped external-egress denial. |
| A35 | `migration-reversal-1788932786983`: old allocated work drains first; retained unassigned work receives the new epoch without duplication; all ten new-owner movements retain post-commit timing proof. |
| A36 | [Frozen N−1 schemas and producer fixtures](../../contracts/compatibility/README.md): both optional-addition directions pass and a breaking quantity type fails. These are schema-consumer checks; actual image operation is A34. |
| A37 | Full Maven generation from disposable empty databases, guarded previous-schema migrations, schema-readiness checks and actual runtime DDL/site/owner-boundary denials. [Boundary evidence](assignment-boundary-2026-09-08.md). |
| A38 | [Returns scaffold provenance and onboarding](../onboarding/returns-service.md), separate schema/models, independent reactor dependencies and live product coexistence. |
| A39 | Cancellation, migration, returns and work-console browser/API runs deny operator recovery and record reasoned supervisor actions with versions/audit. |
| A40 | Actual site-a/site-b lists, lookups and mutations are denied across the six owner APIs in authentication and work-console runs; event-site checks have separate durable-inbox regressions. |
| A41 | `authentication-1788880772251` verifies actual signatures, wrong audience, tampered claims, absent/unknown keys, real expiry and refreshed PKCE. Trusted-signer invalid-claim combinations also have decoder component checks. |
| A42 | The same authentication run denies ordinary core publication on the adapter exchange and quarantines spoofed source claims without state changes. |
| A43 | The same authentication run rejects absent/untrusted client certificates and accepts the retained adapter certificate. Secret-file ACL/public-source checks are separate. |
| A44 | `policy-1788852008554`: 83 actual traffic assertions covering DNS, product/service boundaries, equipment and telemetry. |
| A45 | Separate application and broker restarts pass. `platform-resilience-1788883850354` replaces the database pod on its existing PVCs, preserves recorded responses and five accepted effects, and verifies unavailable intake returns 503 without creating an order. |
| A46 | `restore-1788849841860`: six databases, 211 fingerprints, 1,207 original replay events and 183 matching physical/command records. Release took 648.572 seconds. |
| A47 | `stale-restore-1788848295108`: one later known completion and three absent intents are distinguished without repeated physical work. RPO gap 516.516 seconds. |
| A48 | [Current prepared offline rehearsal](prepared-offline-2026-09-09.md), `offline-1788924041749`: actual login, both products, lost-response recovery, simulator and whole-lab restarts, cached predecessor import/rollback, telemetry and inspected populated dashboards under scoped denial; exact firewall cleanup verified. The current domain/transport tracing configuration and original application images are restored after rollback; unrelated container identities and states are preserved. |
| A49 | `cache-1788923962341`: all 4,050 installed assets match the verified `beeabc1` images, excluding mutable Chromium diagnostic logs. An isolated empty-file-cache fixture fails explicitly and identifies all missing tools, Maven/npm packages, image archives and browser revisions; existing caches/daemon images remain intact. |
| A50 | **Failed.** The [latest full run](healthy-load.md), `load-1788930132552` on `dd13096`, includes all 3,000 measured movements with complete original-trace post-transaction proof: **93.967% within two seconds, p99 6,936 ms**. All 1,980 requests returned 202, all 3,300 total effects completed once and all six databases recorded zero deadlocks. Default durability and actual tracing configurations remained unchanged. All 67 resource samples plus 750 host/743 database observations are retained. The unchanged at-least-99% sustained target remains unmet. |
| A51 | Actual order/returns/task/recovery/migration/audit workflows, mobile views, stale data, version conflicts, keyboard close and focus restoration are recorded. [Final populated console captures](reviewer-walkthrough-2026-09-08.md) come from the passed complete reviewer rehearsal. |
| A52 | `demo-lifecycle-1788866307975` preserves data on stop/start. `reset-bootstrap-1788887847395` repeats explicit checkpoint-backed new-world reset and fresh platform creation without changing unrelated containers; prepared-image bootstrap took 604.419 seconds. |
| A53 | Private archive exclusion, public source, MIT and disabled Actions are established. Final tracked/history/secret/asset review and publication remain pending. |
| A54 | [Complete reviewer rehearsal](reviewer-walkthrough-2026-09-08.md), `reviewer-1788893203051`: six commands passed sequentially in 289.463 seconds, followed by five inspected populated views. `reset-bootstrap-1788887847395` establishes the separate prepared-image fresh bootstrap in 604.419 seconds. |

All IDs are required. A failed latency target or missing runtime check prevents final completion, even when every component test passes.
