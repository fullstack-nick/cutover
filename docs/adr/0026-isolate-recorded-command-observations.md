# ADR 0026: isolate recorded-command observations

Date: 9 September 2026. Status: implemented; ten focused execution workflow checks and all 200 repository checks passed; deployed measurement pending.

The execution coordinator receives a bounded context containing multiple movements. Previously, invalid immutable-payload evidence for one already-recorded command escaped to the group-level handler. That handler deferred every task in the group, including healthy known commands that could have been observed safely.

Observation validation failures now defer only the affected task. A failure to obtain the shared adapter context still applies to the group. Each state update retains its own short transaction, durability barrier, live-lease condition and terminal-state guard. Healthy evidence cannot validate a neighboring command, and malformed evidence cannot cause a new dispatch of an existing command.

The four-database regression records two genuine commands, then changes the returned quantity for only one command in a deterministic test port. The previous code moved the healthy task into reconciliation; the corrected code observes it as in progress while keeping the affected task in reconciliation with `COMMAND_EVIDENCE_MISMATCH`. The adapter's original payload remains unchanged, exactly two dispatch calls occurred, and no physical completion is invented. This reproduces a failure-isolation defect independently of the latency experiments.

Already-recorded commands also become eligible for fallback status observation after 500 ms instead of 300 ms. Direct completion messages continue to update tasks as they arrive. Undispatched deferrals retain their 300 ms interval, transport retries retain the established bounded backoff, and the sixteen-task selection bound and reserved retry cohort remain unchanged. This reduces repeated writes of unchanged task views; it does not change the latency denominator, equipment freshness rule or database durability settings. The interval is a next-eligibility delay, not a guarantee of wall-clock response time.

The focused before check failed at 01:58:43 Europe/Berlin. All ten execution workflow cases passed at 02:04:23 in a 92-second reactor run. Original log SHA-256 values: before `184fb96e9455998abdec9b11b5eb6a5868ca4d63219cc3e2b1288bbfa7f140b4`; after `bdeee0f658b7e5afd7b960dd1a09af62ea66eed719c7b3a2973cef054df03a11`. Full repository verification and deployed load qualification follow separately.

Complete offline verification of `88c2d8839cad938eac975ab78dd3c2aa87b68667` passed at 02:18:01 Europe/Berlin: 200 checks in 25 classes, zero failures, errors or skips, in 552 seconds. [Backend evidence](../evidence/backend-checks.json) records the original log hash. Runtime performance remains a separate qualification.
