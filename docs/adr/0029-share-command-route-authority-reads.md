# ADR 0029: share command route authority reads

Date: 9 September 2026. Status: implemented; all 16 command-journal checks passed. Complete repository and deployed qualification remain pending.

The command journal reads a route to stabilize its owner, epoch and state while recording, sending or investigating a command. These paths update their own allocation or command rows, but do not update the route. The shared `Allocations.lockRoute` helper nevertheless used `FOR UPDATE`. A transaction recording one command therefore prevented another command for a different allocation in the same zone from reading the unchanged authority until the first transaction committed.

This extra serialization is relevant to the [storage-stall diagnostic](../evidence/storage-latency-diagnostic.md), whose delayed interval contained WAL waits and elevated host disk latency. That diagnostic does not establish the route lock as the sole cause or predict how much a lock change will improve sustained performance.

Command route reads now use row-level `FOR SHARE`. Concurrent command readers can retain the same authority snapshot while updating distinct allocations. Owner, epoch and state changes still conflict with that lock, including updates that leave the route's primary key unchanged. This is why `FOR KEY SHARE` would be insufficient. Locks last through the existing transaction commit. [PostgreSQL row-lock modes](https://www.postgresql.org/docs/18/explicit-locking.html#LOCKING-ROWS).

Allocation registration, pending release, migration and route mutation keep their existing exclusive locks. The [ordered route-before-outbox-budget discipline](0021-bounded-inbox-transactions.md) also remains. No new worker, batch API, schema, authority source or durability setting is introduced. Shared tuple locks can still generate WAL and have a cost; this change removes a demonstrated reader conflict, not all database contention.

`independentCommandsShareAuthorityWhileOwnerChangesWaitForCommit` uses the real journal and two actual owner databases. It holds one command transaction open after its journal insert and observes competing PostgreSQL backend locks. Another allocation's command must commit before that first transaction is released. A concurrent owner/epoch update must remain blocked until release, and the previous owner's later dispatch must fail without inserting a command. The regression failed against the prior lock at **05:28:01 Europe/Berlin**. With `FOR SHARE`, all **16 command-journal checks** passed at **05:29:48**, including the mixed-zone inbox deadlock regression, with zero failures, errors or skips.

Original focused log SHA-256 values: before `5bf0bc0c606d71276a054bd480a21b3079f2a9a766f5d18483bed5ef32e981f7`; after `c3740642e7c488edc508b68d41cc135f4ee63e96d39c048c88ce83e519d278bb`. These are component checks. A50 remains failed until the unchanged complete workload passes on the deployed implementation.
