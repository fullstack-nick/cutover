# ADR 0013 — Stopped-writer application checkpoints

Status: implemented and verified for capture and interrupted-capture recovery, 8 September 2026. Fresh/stale restoration remains separate work.

## Context

Pausing external HTTP intake cannot make sequential database dumps consistent. Event consumers, relays, task coordinators, retention, manual investigation and identity sessions also mutate databases. The simulator is a separate source of physical truth and may finish accepted work after application processing pauses.

## Decision

Use one explicit local checkpoint operation. It acquires a private maintenance record, verifies the named cluster/resources, saves the current controls, pauses intake and dispatch, settles received/unpublished messages, then freezes the five application owners. It stops those writer deployments and Keycloak before exporting all six databases. It verifies that their runtime/migrator connections are absent and compares every table's count and sorted row-content SHA-256 across the dump interval. The simulator remains running with its volume untouched.

The shared worker control is a transactional write barrier. New order idempotency records, manual command investigations, fault arm/clear actions and background fault consumption respect it. Previously committed idempotent responses remain readable. The only control change permitted during a freeze explicitly resumes workers. Control writers acquire their exclusive row lock before checking the freeze, avoiding a shared-to-exclusive lock upgrade between competing control requests.

Persist each intended control command before sending it and each intended writer stop before scaling. Recovery journals use flushed temporary files and atomic replacement. An uncertain HTTP retry keeps its original key and payload. Normal cleanup restores exactly the observed flags with version checks. An explicit interrupted-capture recovery command rejects a live maintenance parent, resolves saved requests, and removes only verified abandoned Cutover forwards. A failed or interrupted capture does not become valid because its writers resumed.

Export deployment settings, required credentials, actual image identities, schema checksums, routes/epochs, outstanding commands, retained outbox replay identities and physical world/generation/high-water observations. Checkpoint directories restrict local access and remain Git-ignored. Checksum validation rejects changed files, substituted paths and old cluster owner references. Only the manually managed simulator EndpointSlice is exported; Kubernetes recreates selector-backed service endpoints.

## Consequences and evidence

This procedure creates a short application/identity interruption. In the selected run, capture plus resumption took 77.243 seconds; the interval after writer shutdown through completed dumps and consistency checks was 16.400 seconds. These are checkpoint timings, not restoration timings or availability guarantees.

The full backend suite passed 125 checks. The deployed checkpoint verifier passed three checks, including actual process termination at the stopped-writer boundary and subsequent recovery. It retained one existing adapter quarantine and left all selected business-table fingerprints and the simulator's world/generation/sequence unchanged. See the [checkpoint evidence](../evidence/checkpoint-2026-09-08.md).

The first export included controller-generated EndpointSlices. The corrected export excludes them and its verifier rejects the earlier artifact; the corrected process suite was rerun. The original archive remains available as failed restoration-preflight evidence.

A frozen application checkpoint does not rewind physical work, reconstruct missing post-checkpoint intent, protect against host-disk loss or preserve a broker's queues. Restoration must use separate empty application storage, recreate broker topology, replay the retained original event identities and reconcile against the current simulator before reopening dispatch. Those procedures remain required acceptance work.
