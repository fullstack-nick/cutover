# Prepared offline runtime — current verified images

`offline-1788934734334` passed on 9 September 2026 at **08:24:06 Europe/Berlin**, using clean `822d376` images and driver source `f8332e6`. All six cases passed. [Runtime provenance](runtime-runs.json) records exact source, build and running-image identities.

The manually invoked command was `node tools/scenario-driver/offline-smoke.mjs`. With external egress denied on the three verified Cutover Docker bridges, it exercised local PKCE login, both products, five matching single physical/business effects, recovery of a selected lost response, simulator restart, whole-lab stop/start, cached predecessor rollback and restoration of the current adapter. The world, journal generation, completed products and route authority were preserved. Isolated browser checks also explicitly refused an external navigation.

The walkthrough verified ten healthy scrape targets and local trace retrieval. Three Grafana panels rendered with six application targets, six process-memory series and populated request-rate data. The actual captured image below was inspected.

![Populated local dashboard during the offline walkthrough](images/offline-dashboard-2026-09-09.png)

Cleanup ended in `DISABLED` state and restored the original DOCKER-USER hash. This is a scoped Cutover network test, not a machine-wide disconnect. The separate `cache-1788934677536` check verified the current assets and reported all 4,050 missing items in an isolated empty-cache fixture. The selected predecessor remains `volume-observation-1788851871227`; its original cached archive was verified before import.

The supplemental `offline-profile-1788934732188` check verifies the actual five owner/shadow tracing configurations before and after the rehearsal, restored image references after rollback, and unchanged identities, running states and restart counts for every unrelated container. The [domain/transport tracing profile](../adr/0027-domain-and-transport-tracing-profile.md) remains in effect.

Original offline result SHA-256: `84db879803d7ad494a3f8419105f8f36febed2c20a186f77c06b6cae3a74f671`. The earlier 9 September run `offline-1788910570635` remains in the runtime inventory, with original SHA-256 `a00f062939719e9ba7a8112e996ece880d24fe47fd508dc13a1fcc21673a622c`; the [8 September result](prepared-offline-2026-09-08.md) is also retained. This walkthrough does not qualify the separate sustained-load latency requirement.

The earlier same-day domain-tracing run `offline-1788919092593` remains retained with original SHA-256 `f31214107504395c4286d2cdbc5117512d807483ab607d38567180b4ba9fa312`. The current run verifies the images containing both bounded execution observation turns and the corrected migration timing gate.

The earlier bounded-observation run `offline-1788924041749` remains retained with original SHA-256 `80cf66e3f56d1fca5518559d4d90b77fd283d8d9e2523fcb7fdd3e82ef406395`. The new current-image run completed at `2026-09-09T06:24:06.895Z`; its lifecycle and rollback children are `demo-lifecycle-1788934785058` and `adapter-image-rollback-1788934968874`.
