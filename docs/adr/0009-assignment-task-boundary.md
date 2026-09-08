# ADR 0009 — preserve the baseline before changing task creation

Status: accepted, 8 September 2026.

The original reservation trigger creates movement intents and legacy tasks together. That behavior established a working legacy baseline. It must stop before a second scheduler receives authority, while every original task remains discoverable under its original ID.

The initial transition is a one-time, settled maintenance operation. It pauses core intake/dispatch and adapter dispatch, checks every original movement against its retained adapter allocation and command evidence, and records registration receipts in the two owning databases. Completed tasks require a verified completed adapter command. Cancelled tasks require the adapter's cancellation tombstone; a missing local allocation link is filled from that evidence. Missing physical context is a blocker, never permission to manufacture a completed allocation. The bounded inventory accepts at most 20,000 tasks in batches of 32.

Core V109 installs the checkpoint tables without changing the trigger. Adapter V109 installs durable registration receipts. Registration uses the core's normal restricted service credential, stable registration ID, immutable request hash and exact process-control versions. A response lost after an adapter commit can be retried without changing task IDs. Core commits its links only after it verifies all receipts and rechecks its paused inventory. The checkpoint retains the original snapshot, hash, receipts, counts and audit reason.

Core V112 refuses a populated database unless every original task has matching registration evidence at the current paused control version. It locks the affected tables for the DDL, retains the task rows and the characterized priority/reservation routines, then replaces the task-creating trigger with an intent-only trigger. Order acceptance still records each `MovementRequested` event in its transaction. An empty database can install the final schema directly.

The development Compose profile pins core migrations to V109 to keep its legacy-baseline demonstration reproducible. The final kind profile uses the latest schema; an explicit V109 deployment is only the preparatory step for an existing baseline. Numeric targets fail if the database is already newer and never perform a downgrade.

After this boundary, `MovementAssigned` creates the relevant owner's task. Core checks the event against its immutable movement intent and uses the original SQL priority routine for legacy assignments. Execution creates only its own task, with the characterized Java priority. An observation delivered to both owners does not create two tasks. The execution application has no runtime dependency on core and cannot originate adapter allocations.

The active execution worker persists a supplied-input Java proposal, checks its local task lease and process controls before submitting, and uses the movement ID as the stable command ID. Existing commands are observed separately, including unknown outcomes. Six exhausted transport observations pause the task; a version-checked supervisor action resumes investigation under the same IDs and records an audit. Only core records the inventory effect from verified completion. Execution changes its own task state. Its bounded decision-history quota records omissions without preventing accepted execution.

Shadow uses a separate database and credential. Its deployment condition disables the worker. Independent adapter identity checks still deny commands even if a software flag is wrong.

This initial settled transition is separate from a business zone migration. It does not demonstrate live zone draining, switch owner/epoch, or replace the required durable migration sessions, reversal and crash checks. Those remain the next implementation phase. An old image is not schema-compatible merely because V112 retains old columns: the chosen predecessor must understand assignment-created tasks.

Validation: ten boundary/registration checks and nineteen legacy workflow checks pass against real PostgreSQL databases, including refusal before registration, stale control versions, preserved task rows, a new assignment-created legacy task, and an execution-owned assignment without a core task. Thirteen execution checks pass, including six connected workflows across four independent databases. Process rollout and final acceptance evidence are tracked separately in the implementation ledger.
