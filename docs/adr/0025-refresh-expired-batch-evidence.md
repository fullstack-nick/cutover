# ADR 0025: refresh expired evidence within a scheduling batch

Date: 9 September 2026. Status: implemented; nine focused execution workflow checks passed; complete verification and deployed qualification pending.

The execution coordinator reads one bounded context from the adapter for its leased tasks. A previous dispatch or durable database operation can take long enough that this batch's snapshot exceeds the five-second observation lifetime. The coordinator previously continued evaluating that old snapshot even when the adapter had already recorded a newer valid observation. Remaining tasks then became blocked and waited for another polling turn.

This occurred in the retained decisions for movement `46640973-e634-409d-84fd-5004a2fa70a6` during failed full run `load-1788906898401`. The batch reused an observation from 22:40:48.626763 UTC; its 22:40:53.713675 decision rejected that observation as stale. A subsequent context read contained newer evidence and allowed the movement to proceed. The complete latency was 17,803.663 ms, including earlier queueing; refreshing the batch does not account for all of that delay.

Before another decision, the coordinator now checks the retained observation's age. If it has expired or is in the future, it reads current adapter context for the remaining leased movements, reconciles any commands already recorded there, and evaluates the same scheduling rule. An unchanged stale observation still blocks dispatch. Route ownership, epoch, allocation identity, immutable command payload and the live lease remain required. The original sixteen-task batch bounds the number of refreshes. A group containing only recorded commands performs observation work without retaining an empty scheduling decision.

The focused regression uses four real owner databases and a controllable clock. The first dispatch advances time by six seconds. When the adapter has a new observation, the second movement must still obtain its durable command record in the same turn. When equipment evidence remains stale, it must stay blocked. The corrected fixture failed the first case and passed the second against the previous code at 01:01:03 Europe/Berlin. The earlier fixture error mixed two dispatch owners; that failed attempt is retained separately.

All nine execution workflow cases passed after the correction at 01:05:06, including lost replies, retry limits, checkpoint freezing, physical uncertainty and retained-decision capacity. No physical effect is invented by the regression. Full repository verification and deployed measurement follow separately. Host storage stalls were also captured during the preceding diagnostic; this change addresses the confirmed stale-batch amplifier and makes no claim that it eliminates every latency outlier.

Private log SHA-256 values: corrected before `822f0dc07bb7a2cbc0f342551c85ae59242a16b0c38b11448b6620e5ca5ff9c0`; focused after `d15ad5c920db48a5e5334d239e64c02d8e8a41b0ffd09fab139873adab9a2edd`.
