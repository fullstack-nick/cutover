# Dependency and container inventory

The [generated inventory](dependencies.json) records the five packaged Java applications built from clean revision `b3b09db156545c264e03bec410eab04a7992b72c`: **104 unique bundled Java libraries**, **105 entries across both npm lockfiles**, and **13 pinned infrastructure/base images**. Each Java executable is checked against the SHA-256 recorded when its image was built. Shadow scheduling uses the same executable as extracted execution, under a separate runtime identity.

Java library hashes and embedded Maven coordinates are read from the actual executable JARs. Available embedded license and notice paths are listed without guessing absent metadata. Npm versions, integrity values and declared license strings come from the complete lockfiles, including development and optional platform packages. The platform inventory includes pinned image digests, tool versions and the Java agent. Container operating-system packages remain documented by their upstream images; they are not represented as application libraries here.

After rebuilding the local images, regenerate this inventory with:

```powershell
./scripts/dependency-inventory.ps1
```

The command uses local artifacts only, refuses a dirty image-build manifest or a JAR that differs from its image build, and writes inside this repository. [Third-party notices](../../THIRD_PARTY_NOTICES.md) distinguish the original MIT-licensed project from upstream components, which retain their own licenses.
