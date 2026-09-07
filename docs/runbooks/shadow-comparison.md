# Scheduling comparison

Run `./mvnw.cmd -B -ntp -pl tools/shadow-checks -am test` to exercise the real legacy SQL and independent Java proposal against 1,000 seeded persisted inputs. The complete component export is under `tools/shadow-checks/target/shadow-evidence/`; generated output is ignored by Git.

After building and deploying the current application images, run `node tools/scenario-driver/shadow-smoke.mjs`. It records the same seeded input through the running core, waits for RabbitMQ delivery into the shadow database, retrieves and compares every stored proposal, then checks live ambient/chilled work. A bounded helper in the actual shadow pod attempts adapter allocation and command calls with its mode flag set to false. The adapter must return a 403 identity denial, and command/physical counts must remain unchanged.

The normal console origin exposes `/api/v1/sites/site-a/shadow-comparisons` and individual round details to authorized site readers. Each detail includes the exact input, its hash and both outputs. A stale comparison timestamp means no recent evidence; it does not mean scheduler agreement has been established for new traffic.

If comparisons differ, retain the round and inspect priority, eligible time, UUID ordering, lane state/version, command state and rejection precedence. A known command, including an unknown outcome, is never a new candidate. Do not change either output to make a recorded comparison pass.

The core's `shadow_observation_capacity` records pending/retained counts, bytes and omitted rounds. The shadow database has a separate `shadow_capacity`. Comparison capacity does not use the business outbox. Stop admitting seeded fixtures when their evidence would exceed retained capacity; export and keep the current evidence. Exhausted observation relays retain their original payload and identity. Operational replay controls and long-term evidence compaction remain listed in the implementation ledger until verified.

For exhausted legacy task transport calls, a supervisor can request `POST /api/v1/sites/site-a/tasks/{taskId}/recovery` with an idempotency key, the observed row version and a reason. This resumes status investigation with the retained movement and command identity. It cannot override an adapter ownership fence or authorize replay of an uncertain physical movement.
