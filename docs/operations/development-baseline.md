# Running the current development baseline

This is the legacy process milestone, not the final portfolio demonstration. It runs core, adapter, independent simulator, PostgreSQL, RabbitMQ, Keycloak, and a temporary status page. The extracted product services and complete operations console are subsequent milestones.

Use Windows PowerShell, Java/JDK 21, Node 24, and Docker Desktop's Linux engine. Run commands from the repository root:

```powershell
./scripts/bootstrap-tools.ps1
./mvnw.cmd -B -ntp clean verify
./scripts/dev.ps1 -Action Start
node tools/scenario-driver/baseline.mjs
./scripts/dev.ps1 -Action Status
./scripts/dev.ps1 -Action Stop
```

Initial dependency acquisition needs internet. The Maven Wrapper checks its distribution checksum. Code generation creates disposable PostgreSQL instances from the real migrations. Tests use separate disposable databases. Neither operation reads the running demonstration's business tables.

The page and local identity are served at `http://localhost:8780`. The scenario driver reaches simulator test controls at `https://localhost:18784` using a separate client certificate. The adapter connects through the private equipment network. Both host ports bind only to loopback. The driver tests intake, duplicate conflicts, site/authentication boundaries, restricted database roles, a lost equipment response, and accepted work surviving all three application process restarts. It temporarily blocks the two ambient lanes and restores their prior state in cleanup.

Passwords, certificate keys, realm configuration, image manifests, and raw evidence are generated under ignored `.local/`. Keep that directory private. Rerunning configuration preserves existing credentials and world data. Never run a demonstration reset to solve a migration or uncertain-equipment error.

`Stop` preserves named volumes. Application and simulator data use different database containers and volumes. This baseline script only addresses the `cutover-dev` Compose project. It keeps a simulator used by a running kind environment available, and Start refuses to activate an older development database copy when a preserved Cutover kind node exists. The kind platform has a separate lifecycle. No build, test, or deployment runs on a schedule or through a hosted pipeline.

Windows can reserve TCP ranges even when no listener appears. The initial simulator port fell into such a reserved range on this host and was changed to 18784. A bind failure requires checking `netsh interface ipv4 show excludedportrange protocol=tcp`; do not remove Windows reservations or stop unrelated applications to claim the port.
