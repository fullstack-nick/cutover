# Cutover — future implementation goal handoff

**Archived handoff:** development concluded on 9 September 2026 at the owner's request. The [development closeout](development-closeout.md) supersedes the instructions below to initiate or continue work. The original objective is retained as historical planning; A50 remains failed. Do not launch a new goal or resume diagnostics from this handoff without a new owner instruction.

Prepared 7 September 2026. This is a prompt and completion contract for a later implementation goal. It does not start a goal, schedule work, or claim that implementation has begun.

The owner has confirmed the name Cutover and the MIT repository license. Preserve the root LICENSE and separate upstream notices. Local execution, public GitHub hosting, project independence, and no CI/CD are already settled. No further owner decision is needed to begin development.

## Suggested goal objective

Build Cutover end to end as the independent local grocery-fulfilment and reusable-crate modernization platform specified in docs/planning/implementation-plan.md. Begin with a functioning database-heavy legacy baseline, characterize it, package and operate it locally, extract task orchestration, compare scheduling in shadow mode, perform controlled zone cutover and ownership reversal, onboard the independent returns product, and complete the full reliability, security, restoration, offline, and portfolio demonstration.

Use the research pack and owner decisions as the implementation baseline. Preserve the supplied original plan in local ignored storage without changing its bytes. Public project content must use independent names, original material, and synthetic data. Do not publish the private source archive or its employer-specific context.

Implement all required work and all 54 scenarios in docs/planning/acceptance-matrix.md, with reproducible evidence tied to the actual commit and images. Deliver tested Windows-local setup/verification/deployment/stop/reset/backup/restore scripts, an operations console, API/event contracts, service template, architecture diagrams, ADRs, product-onboarding guide, operational runbooks, measured lab targets, and a concise reviewer-friendly README.

Use local Git and the owner's public GitHub repository for source history. Build, test, and deploy through explicitly invoked local commands. Do not introduce CI/CD, GitHub Actions, runners, GitOps, cloud deployment, SaaS identity, hosted monitoring, or a mandatory image registry. The prepared runtime must function without external internet.

## Start order

1. Read implementation-plan.md, acceptance-matrix.md, the research report, and machine-readiness report. Check any applicable repository instructions.
2. Use the confirmed Cutover name and MIT license; retain the stated defaults for settled engineering choices.
3. Inspect current working files and local tools before editing. Re-run doctor-style capacity, ports, Docker, and target-context checks.
4. Initialize ordinary local Git if still absent. Keep .local ignored before the first commit. Create the public remote under the intended account when the public identity and material are ready.
5. Acquire kind and project dependencies as required, pin versions/digests/checksums, and execute the compatibility smoke gate. Do not assume cached tooling in the Codex runtime exists in ordinary terminals.
6. Work through phases 1–9 in order, preserving observable legacy behavior and recording milestone evidence.

## Working constraints

- Resolve routine implementation details autonomously within the plan. Do not repeatedly ask the owner to approve choices already settled.
- No installation or template may silently create cloud resources, a delivery controller, or a hosted runtime dependency.
- All physical behavior remains simulated. Do not connect real equipment or implement a proprietary equipment protocol.
- Never write another service's business tables as a shortcut.
- Never repeat a movement because its outcome is unknown, fabricate an acknowledgement, or force migration past unresolved work.
- Protect existing workloads: target only named Cutover resources, use a separate kubeconfig/context and local ports, and do not stop unrelated containers or prune their volumes without explicit authorization.
- Public docs must distinguish implemented, measured, planned, failed, and environment-blocked behavior.
- Build the complete project, including the smaller returns product and recovery/offline evidence; optional UI or infrastructure features do not substitute for required scope.
- No subagent delegation is requested by this handoff. Follow the active task's agent-use policy.

## Completion evidence

Maintain a progress ledger showing phase status, files/components completed, checks run, failures, fixes, and the next required work. Each acceptance result references the tested commit, image identities, test command, seed, and sanitized evidence.

A finished goal includes:

- Both genuine legacy and extracted workflows, with preserved characterization cases.
- An enforced ownership gate and successful shadow, cutover, reverse-migration, and image-rollback demonstrations.
- Proof that duplicate messages and lost equipment responses do not repeat business/physical effects.
- Independent returns ownership and onboarding through the shared platform template.
- Verified local identity, site/role authorization, network policies, and equipment authentication.
- Quiescent and stale-checkpoint restoration with the simulator's current world preserved.
- An offline prepared demo and accurate dependency/cache inventory.
- Actual latency/throughput/resource measurements with declared test conditions.
- Complete documentation, original screenshots/recording, and a reviewer quickstart verified against the final code.
- Public GitHub source with no private source material, secrets, vendor narrative, or CI/CD configuration.

Do not declare the goal complete while required acceptance work is missing or failing. Explain concrete environmental blockers with the evidence and next necessary action.
