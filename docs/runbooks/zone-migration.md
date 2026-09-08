# Drain, migrate and reverse an outbound zone

Use the local operations console at `http://localhost:8780/?view=migrations`. Sign in as a seeded supervisor and select the intended site. The operator view is read-only. `supervisor-a` and `supervisor-a2` are separate fictional identities for concurrent-action verification; their generated passwords remain in ignored local credentials.

The route card shows its active owner, ownership epoch and row version. Check the equipment observations before starting. Enter a specific reason and confirm the migration. The session appears in the register and advances from its persisted state; the browser does not drive its progress.

During `DRAINING`, new intents wait unassigned. Previously allocated tasks keep their original owner. Inspect the listed movement IDs if work is blocked. Restore a lane, repair a dependency, or investigate an uncertain command through the documented recovery API. Never infer that an operation failed physically from a lost HTTP response, and never release its reserved stock to unblock a migration.

`RECONCILING` compares the retained intent, reservation, task, allocation, command and physical evidence. The register shows verified/inventory counts. Checkpoint details include the full session ID, actor, reason, inventory hash, checkpoint hash, physical world and journal high water. Missing history and incompatible evidence require investigation.

`READY_TO_SWITCH` is a durable checkpoint, not an instruction to change a database row manually. The adapter rechecks it under the command-admission route lock, atomically increments the epoch and switches owner. `OBSERVING` then verifies the first ten new-epoch movements and a two-second dispatch target. Submit normal synthetic orders for that zone to provide the sample. A failed latency sample stays recorded.

If six owner-evidence transport attempts exhaust, repair the unavailable dependency, enter a reason and use **Resume evidence collection**. This reuses the session and movement identities. If a response is interrupted, the form's **Retry original request** retains the original idempotency key and payload. A version conflict means the observed state changed; refresh and review it.

Before the switch, **Cancel this unswitched session** keeps the original owner/epoch and releases pending unassigned work under that owner. After a switch, use **Reverse ownership** on the active route. Reversal performs the complete drain and reconciliation again; it cannot abandon a backlog or bypass an unknown command. A reversed observation session remains linked to its successful child.

If the reversal's own latency sample fails, it can itself be reversed. Once a later session completes, its immediate parent is `REVERSED` and older unresolved ancestors are `SUPERSEDED`. All failed samples remain visible; the lineage audit identifies the session that eventually settled the route.

API equivalents are recorded in `contracts/openapi/operations.v1.json`: site-scoped zone/session reads, creation with expected route version, and recovery/cancellation with expected session version. Mutation requests require a reason, supervisor role and `Idempotency-Key`. The console refreshes migration observations every three seconds and disables actions while its last refresh failed.

For explicit process verification, run the documented scenario driver against the owned demo profile. Migration phase faults are separate internal test controls, unavailable to ordinary human sessions. Each consumes itself before halting the adapter process. Clear an unfired fault by its exact ID/version when ending a failed check; retain the migration and all physical history. No deployment or recovery procedure should issue a forced ownership update.

`scripts/ensure-local-users.mjs` adds missing configured fictional users after a local deployment, preserving existing passwords and site memberships. It defines `sites` as an administrator-only managed profile attribute; ordinary users cannot edit their authorization scope. This follows Keycloak's [managed-attribute permissions](https://www.keycloak.org/docs/latest/server_admin/index.html#_user-profile). The pinned 26.7.3 API represents disabled unmanaged attributes by an absent policy field: its [policy enum](https://www.keycloak.org/docs-api/26.7.3/javadocs/org/keycloak/representations/userprofile/config/UPConfig.UnmanagedAttributePolicy.html) has no `DISABLED` value.
