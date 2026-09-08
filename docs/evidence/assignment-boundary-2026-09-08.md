# Assignment boundary — 8 September 2026

Milestone evidence from the local kind/Calico profile. This is not the complete A01–A54 acceptance bundle.

The full backend verification passed 88 checks at 01:59 Europe/Berlin in 5 minutes 40 seconds. Console generation, type checking and production build also passed. These include 1,000 identical persisted scheduling comparisons, ten guarded-boundary checks, and six connected execution workflows across four independent owner databases.

The live registration `c0f8b212-0430-405a-b3ff-8d12ccc9afdc` verified **33 original tasks** while core intake/dispatch and adapter dispatch were paused. The core control version was 6; adapter version was 19. All original task IDs were retained, including three cancelled tasks whose allocation links were filled from existing cancellation tombstones. The original inventory hash was `9ddc4c8af12ed6a3fd3828d8db4a697f19889ed66486471d0d82ebc24b4c7844`.

Core V112 applied only after this checkpoint. It retained the legacy rows and routines, replaced task-creating reservation behavior with movement-intent creation, and recorded the baseline count/registration IDs. The release script checked both trigger conditions and reopened the saved gates. No physical world or route owner/epoch changed.

Process run `assignment-boundary-1788826201899`, recorded at 02:10:20 Europe/Berlin, passed all three checks:

| Check | Observed result |
| --- | --- |
| Retained original inventory | All 33 task IDs still joined their original movement/allocation evidence. |
| Intent-to-assignment transition | With adapter workers paused: two retained intents, zero tasks, zero allocations. After resumption: two applied assignment inboxes, two completed legacy tasks at priority 600, one inventory and physical effect for each movement. |
| Independent execution owner | Two assignment observations applied, zero tasks for those legacy-owned movements; public task read worked, cross-site read returned 404, runtime DDL was denied. |

Order: `8fd58544-1503-4ba5-b82c-2a9b69b7479c`. Movements: `cb6f08c2-e0db-4b7d-9295-5e15dc4d6a87` and `d2fbbb61-b102-4cc3-b75e-dbd0892834b2`.

Tested source was `d77237b06b91c4709f3012a8451e63b5c89df4ea` plus the boundary implementation changes. Actual runtime image digests:

| Application | Digest |
| --- | --- |
| Legacy core | `sha256:ad341dd3a58dfd3ebce6859dc79b2304d8fd16e41f3e602db1597625beb549d5` |
| Equipment adapter | `sha256:b78a4c29a06b42f76c6bedd22929cd0bb5f4544d9d40aed6434d39567e33b260` |
| Execution and isolated shadow | `sha256:17a8536ba0180bf377ee6dbc9b076b373ea79e0c274eaffc1f385d79be6bc919` |

The first process run, `assignment-boundary-1788826138901`, completed its order but failed because the observer lacked permission to execute `legacy_priority`. The corrected read-only assertion compares the stored task priority with its characterized result; observer grants stayed restricted. The failed run and its original evidence remain retained locally.

Reproduce the transition with the [initial task-boundary runbook](../runbooks/initial-task-boundary.md), then run `node tools/scenario-driver/assignment-boundary-smoke.mjs`. An already transitioned database runs only the process check. The active Java worker is component-verified; real execution-owned traffic is a gate of the upcoming durable zone migration, not implied by observing another owner's assignments.
