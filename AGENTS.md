# Working on Cutover

Development concluded on 9 September 2026 at the owner's request. Read `docs/planning/development-closeout.md` for the final disposition. Do not resume development, performance qualification or the cancelled administrator trace unless the owner explicitly requests new work.

For any newly requested work, read `docs/planning/implementation-plan.md`, `acceptance-matrix.md`, and `implementation-progress.md` before changing scope. The original 54-scenario qualification contract is preserved; A50 remains failed at closeout. Keep the progress ledger honest; a component test does not establish a platform acceptance result.

Use Java 21, the Maven Wrapper, PostgreSQL migrations, and the established owner boundaries. Run meaningful checks for the changes. Database tests require the Docker Linux engine and use disposable, project-labelled containers. Keep domain types and persistence entities out of shared technical modules.

The application runtime is entirely local. Do not add CI/CD, GitHub Actions, runners, GitOps, cloud resources, hosted identity, or remote runtime assets. Git and the public GitHub repository remain the source-history tools.

Use Cutover branding, original code and synthetic data. Preserve the MIT license and upstream notices. `.local/` contains private source material, credentials, caches, and raw evidence: never stage it or copy its source narrative into public files. The original planning archive may only receive append-only additions.

Only target named Cutover resources during lifecycle operations. Do not prune Docker resources, delete unrelated data, alter the default Kubernetes context, or stop unrelated workloads without explicit authorization. Local workload permissions are recorded in ignored operational notes.

This repository does not request subagent delegation. Follow the active task's agent-use policy.
