# Prepared offline runtime — current verified images

`offline-1788910570635` passed on 9 September 2026 at **01:41:57 Europe/Berlin**, using clean `9dc5363` images and driver source `941c65c`. All six cases passed. [Runtime provenance](runtime-runs.json) records exact source, build and running-image identities.

The manually invoked command was `node tools/scenario-driver/offline-smoke.mjs`. With external egress denied on the three verified Cutover Docker bridges, it exercised local PKCE login, both products, five matching single physical/business effects, recovery of a selected lost response, simulator restart, whole-lab stop/start, cached predecessor rollback and restoration of the current adapter. The world, journal generation, completed products and route authority were preserved. Isolated browser checks also explicitly refused an external navigation.

The walkthrough verified ten healthy scrape targets and local trace retrieval. Three Grafana panels rendered with six application targets, six process-memory series and populated request-rate data. The actual captured image below was inspected.

![Populated local dashboard during the offline walkthrough](images/offline-dashboard-2026-09-09.png)

Cleanup ended in `DISABLED` state and restored the original DOCKER-USER hash. This is a scoped Cutover network test, not a machine-wide disconnect. The separate `cache-1788910462421` check verified the current assets and reported all 4,050 missing items in an isolated empty-cache fixture. The selected predecessor remains `volume-observation-1788851871227`; its original cached archive was verified before import.

Original offline result SHA-256: `a00f062939719e9ba7a8112e996ece880d24fe47fd508dc13a1fcc21673a622c`. The [previous executed offline result](prepared-offline-2026-09-08.md) remains historical evidence. This walkthrough does not qualify the separate sustained-load latency requirement.
