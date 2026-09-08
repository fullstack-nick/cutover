# N and N−1 contract fixtures

The predecessor event-envelope and movement schemas are byte-for-byte snapshots from commit `1b7f9a881a4333cf59b22b76870b5a830d803dd0`. `provenance.json` records source paths and SHA-256 hashes. They remain frozen when the current schemas evolve.

These purpose-built synthetic fixtures cover the `MovementRequested.v1` boundary:

| Producer fixture | Previous contract consumer | Current contract consumer |
| --- | --- | --- |
| `producer-n-minus-one.json` — established required fields | Accept | Accept |
| `producer-n-additive.json` — optional envelope and movement metadata | Accept | Accept |
| `producer-breaking.json` — integer quantity changed to a string | Reject | Reject |

The currently supported major version retains the same required shape. The additive fixture explicitly models an optional producer addition; it is not a claim that the live producer emits those annotation fields. Both consumers validate the envelope and its movement payload. The current production envelope decoder must also tolerate the allowed optional field. Frozen-schema hashes are checked so a future change cannot silently rewrite the predecessor contract to match itself.

Run the predeployment component gate from the repository root:

```powershell
./mvnw.cmd -B -ntp -o -pl tools/compatibility-checks -am test "-Dtest=ContractEvolutionTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

`-o` requires an acquired Maven cache; omit it for initial online acquisition. The full `verify` command also includes this gate. Its negative fixture must fail validation for both consumers while the valid control succeeds. Removing/renaming required fields, changing types or meanings, and incompatible enum additions require a new major contract or a separately demonstrated rollout strategy.

This is contract-consumer evidence. The [cached application rollback procedure](../../docs/runbooks/application-image-rollback.md) separately runs the selected actual predecessor adapter on the expanded schema, exchanging events with current product owners and checking physical/business effects. Schema validation alone cannot prove an older executable is safe to deploy.
