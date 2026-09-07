# Cutover — local machine readiness

Read-only inspection on 7 September 2026. Values are snapshots; re-run preflight before implementation and before the full demo. No dependencies were installed, existing containers stopped, system settings changed, or public repository created during this inspection.

The machine has the main development prerequisites. **kind is the only missing mandatory host CLI for the chosen final platform.** Maven is absent globally but should be supplied through a checked-in wrapper. The full stack also needs more free RAM than was available at inspection.

## Hardware and runtime

| Item | Observed | Assessment |
| --- | --- | --- |
| OS | Windows 11 Pro, 64-bit, build 26200 | Suitable Windows host for the chosen workflow. |
| CPU | Intel Core i9-13900H; 14 cores, 20 logical processors | Enough CPU capacity in principle; existing workloads affect measurements. |
| RAM | 31.6 GiB total; 4.4 GiB free at snapshot | Total capacity is reasonable; available memory is insufficient for the proposed full demo concurrently. |
| Workspace volume | C:; about 164.9 GiB free | Adequate starting space. The workspace's D_DRIVE directory name does not put it on a D: volume. |
| Other volume | G:; about 156.6 GiB free | Available space observed; no changes or backup-location assumption made. |
| Docker runtime | Linux engine 29.7.2; Desktop 4.88.1; context desktop-linux | Client and server both responded successfully. |
| Docker memory view | About 15.43 GiB, 20 logical processors | Capacity visible to Docker, not currently free memory. |
| Existing Docker workloads | 20 running containers in the first snapshot | Their resource and port use must be respected. Counts may change. |
| WSL | 2.6.3.0; Linux kernel 6.6.87.2 | Docker's WSL distribution running; Ubuntu installed but stopped. |
| Virtualization | Hypervisor present and Docker Linux engine running | One CIM CPU field reported virtualization disabled; working WSL/Docker is stronger evidence. No BIOS change is indicated. |
| WSL customization | No user .wslconfig found | No custom memory-cap setting established by that file; use the actual Docker memory report. |

The final demo is initially budgeted at roughly 10–12 GiB working set plus headroom. This is an engineering estimate. Arrange approximately 12–14 GiB for Cutover's container runtime workload and adequate Windows/build headroom, then measure. A 32-GB host can be practical if other heavy projects are not running simultaneously.

Use sequential builds and test profiles, small connection pools, bounded telemetry, and one kind node. Do not stop unrelated containers or prune shared volumes automatically to make a test pass. The owner chooses which current workloads can be paused.

## Installed tools

| Tool | Observed version/status | Required action |
| --- | --- | --- |
| Git | 2.53.0.windows.2 | Ready. Initial directory had no .git metadata. |
| GitHub CLI | 2.88.1, authenticated API call succeeded | Ready for later publication; no repository created here. |
| JDK / compiler | Temurin 21.0.10+7, both java and javac available; JAVA_HOME set | Ready by major version; review maintenance patch at implementation bootstrap. |
| Maven | Not found on PATH | Supply Maven Wrapper and pinned Maven 3.9.16 distribution. Global installation optional. |
| Gradle | Not found on PATH | Not required; Maven selected. |
| Node.js | 24.19.0 | Compatible with selected frontend tooling. |
| npm | 11.2.0 | Available; use a committed package-lock and npm ci. |
| Corepack / pnpm | Found | Not required; do not depend on an app-bundled pnpm path. |
| Docker / Compose | Engine 29.7.2; Compose 5.4.0 | Ready; exact Testcontainers compatibility still needs a real project smoke test. |
| kind | Not found on PATH | Install pinned 0.33.0 before Kubernetes work. |
| kubectl | 1.36.1 | Ready for selected Kubernetes 1.36.4. |
| Kustomize | Embedded 5.8.1 via kubectl; no standalone executable | Ready; standalone install unnecessary. |
| Helm | Not found on PATH | Not required by the selected Kustomize plan. |
| psql | Not found on PATH | Use the PostgreSQL container's psql/pg_dump/pg_restore; native install optional. |
| PowerShell | Active 7.6.5 from Codex's bundled runtime | Available in this task. Document standalone PowerShell 7 for ordinary terminals instead of depending on a private Codex path. |
| Python | Python 3.11 installation found | Optional utilities only; not a required Cutover runtime. |
| Go, .NET | Found | Not required for the selected application. |
| winget | Found | Can install host CLIs later. |
| Playwright browsers | Chromium revisions 1223 and 1243 present | Cache exists, but compatibility with the future pinned Playwright revision is not established. |

Absent infrastructure executables such as a native Keycloak, RabbitMQ, or Prometheus server are not missing host prerequisites: these will run as project containers.

## Dependencies to acquire during implementation

| Category | Needed acquisition |
| --- | --- |
| Mandatory host tool | kind 0.33.0 Windows AMD64 executable, verified against its official checksum. |
| Reproducible Java build | Maven Wrapper scripts/configuration and Maven 3.9.16 distribution; Java dependency and plugin caches. |
| Frontend and tests | Locked npm packages, API/schema tooling, matching Playwright Chromium binary. |
| Images | Java build/runtime bases, PostgreSQL, RabbitMQ, Keycloak, reverse proxy, telemetry tools, kind node, all Calico components, and test helper images. |
| Project configuration | Pinned manifests, local realm/config templates, generated credentials, equipment certificates, retention/quotas, and network policies. |
| Optional outside-Codex convenience | Standalone PowerShell 7 if the ordinary terminal cannot find it. |

The official [kind quickstart](https://kind.sigs.k8s.io/docs/user/quick-start/) documents Windows installation and the winget package `Kubernetes.kind`. The implementation bootstrap should use the researched version and verify its checksum; an unpinned install is not the final reproducibility mechanism.

No Gitea, Argo CD, runner, cloud CLI, registry server, Oracle database, commercial jOOQ edition, Helm installation, or real warehouse software is needed.

## Ports and isolation

At the snapshot, listeners were present on 3000, 5432, 5672, 8080, 8081, 9090, and 15672. Do not reuse those defaults. No listener was observed on the checked 8780/8781 ports at that moment; this is not a reservation.

Use localhost 8780 for Cutover's console/API/identity proxy, and preflight optional management ports before starting forwards. Keep internal databases and broker ports unpublished by default. Disposable test containers should use dynamically assigned ports.

Use distinct Docker project labels/volumes, a cluster named cutover, separate test resources, and an explicit project kubeconfig. Do not inherit another project's current Kubernetes context.

Windows source editing is supported. Database files remain on Linux container storage or project Docker volumes; backup exports can be streamed to ignored host paths. Avoid placing live PostgreSQL data in a Windows source bind mount. Do not require moving the repository to WSL merely to begin.

## GitHub and publication

An authenticated personal GitHub account was observed. A read-only lookup of its proposed cutover repository returned that the repository could not be resolved; no accessible target was found at inspection. This supports the user's statement that it has not been created, but repository availability must be checked again at creation.

Keep local Git for version control. Later create the public remote under the owner's chosen account/slug, push only reviewed public files, and keep Actions/deployment automation absent. The original supplied plan and raw machine evidence are under ignored local storage and must never enter Git history.

## Inspection limits and readiness verdict

The audit checked executable discovery/version output, OS/CPU/memory/disk, Docker client/server and current resource use, WSL status, selected listeners, GitHub authentication/repository visibility, and browser-cache directory names.

It did not pull images, install tools, start a cluster, run the future application's tests, inspect private credentials, validate backups, benchmark the full stack, or prove an offline cache. Public release and compatibility documentation supplements the local audit.

**Ready for:** repository documentation, source scaffolding, Java/TypeScript development, and selected local tests after project dependencies are acquired.

**Needed before the full platform:** install kind, acquire/lock/cache project dependencies, free capacity without disturbing unrelated work, and pass the implementation's compatibility/network/storage smoke checks.
