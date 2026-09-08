# Onboarding the returns product

The second product was generated from the technical template with:

```powershell
node scripts/scaffold-service.mjs returns-service returns
```

The directory now exists; the generator deliberately refuses to overwrite it. See [scaffold provenance](../../apps/returns-service/.scaffold.json) for its four source-template hashes. The same generator created the execution service earlier, but neither product shares its domain model with the template.

| Supplied by the template / starter | Supplied by returns development |
| --- | --- |
| Maven parent, Java 21, Boot packaging | Product module and receipt/coordinator beans |
| Owner-local Flyway and disposable jOOQ generation | V116 receipt, counter, task and sorting-ledger schema |
| JWT verification, request bounds, problem details, site checks | Receipt read/write routes and supervisor recovery permissions |
| Transactional inbox/outbox, stream history, bounded retry and quarantine | Movement request/assignment handling and proof-checked sorting effects |
| Health, technical telemetry and checkpoint controls | Independent task coordinator and receipt lifecycle |
| Scaffold identity migration | Separate database, client, broker permissions and manifest wiring |

The product has two runtime integration paths: RabbitMQ and the equipment adapter. It never queries a core/execution table or calls their APIs. Its Maven production dependency is `service-starter`. The connected tests depend only on `test-support`, `equipment-adapter` and `equipment-simulator`; a focused returns build's reactor therefore excludes core and execution.

```powershell
.\mvnw.cmd -B -ntp -pl apps/returns-service -am '-Dtest=ReturnsWorkflowTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

After verification, the ordinary manual build/load/deploy commands package the service, create only its owner-local schema through a migration Job, and start it with its runtime identity. The platform renderer adds fixed console routes and a Prometheus target; the explicit network policies already declare the second-product connection matrix. Generating the module does not automatically deploy it.

The development responsibilities and tradeoffs are in [ADR 0012](../adr/0012-independent-crate-returns.md). Operator behavior and recovery are in the [returns runbook](../runbooks/returns.md). Component and process evidence must be recorded separately in the implementation ledger.
