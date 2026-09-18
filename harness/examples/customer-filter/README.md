# Offline customer-filter demonstration

Run only this focused scenario from the repository root:

```bash
bash harness/test.sh customer-filter-demo
```

The demo uses a **real Maven 3 installation and already cached dependencies**, with no downloads. Set `HARNESS_DEMO_MAVEN_HOME` and `HARNESS_DEMO_MAVEN_CACHE` to independently trusted host paths if needed. Defaults are IntelliJ's `/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3` and `$HOME/.m2/repository`. The cache must contain the exact Spring, JUnit and Maven plugin versions in [pom.xml](target-project/pom.xml), including transitive dependencies. The test copies the cache into a disposable directory and makes that copy read-only; it does not change the original cache. Missing prerequisites fail closed.

## Scenario

[new-operation.json](new-operation.json) uses the actual supported input schema. The test replaces only its placeholder `targetProjectPath` with the canonical path of a disposable copy of [target-project](target-project). This is an existing Spring Framework application, with an in-memory repository, DTOs, service, annotated API, and an existing JUnit test. It does not require Spring Boot, an HTTP server, or a database. The SQL file documents logical mappings and is never executed.

The request adds **New Filter after Customer Title** under Customer Information. The target already supplies the seed hierarchy, field mappings, component conventions, exact creation directories, API path, and insertion/consistency rule. The fixture derives position 3 from the observed reference position 2 and the existing `positionAfter` rule. No business rule is added to harness runtime code.

| Component | Decision |
| --- | --- |
| Domain model, response DTO, mapper | REUSE, unchanged |
| Request DTO | CREATE |
| Repository | MODIFY: validate, shift following siblings in this parent, publish a complete copy under the existing monitor |
| Service, API | MODIFY: delegate insertion; preserve the evidenced POST path and 400/404 behavior |
| Operation tests | CREATE |

Expected order after the executed INSERT:

1. Customer Segment
2. Customer Title
3. New Filter
4. Customer Credit Score
5. Customer Age

## What runs and what is demonstrated

The real `ControlledPipeline` loads the repository's trusted specifications, observes the disposable target through its broker, validates and accepts exact artifacts, derives separate implementation and validation authorities, and enforces exact stage grants:

`00R → 01 → 02R → 03 → 04 → 05 → 06 → host Maven → 07 → MASTER → SUCCESS`

Provider responses are deterministic mocks. [approved](approved) contains independently reviewed output bytes used both by the scripted specialists and the separate host exact-file policy. This demonstrates orchestration, filesystem effects and executable behavior, **not autonomous model generation or independent model review**. The mock 07 and MASTER acknowledge host-validated contracts; host effects and fresh Surefire identities remain mandatory.

Maven uses the existing host-selected offline `test` policy, direct Java bootstrap, controlled empty environment, isolated settings/home, read-only cache, bounded output and timeout. Agents cannot choose commands or execute Maven. Four new JUnit tests verify the insertion order, shifts, other-parent isolation, rejection without partial mutation, consistency/API conventions; the existing seed test must also pass. Agent 06 writes test source; the host executes it. The test records actual order under Maven's allowed `target/` output scope.

All five exact test identities are required by the host profile. The fixture's Surefire configuration omits optional properties and passing-test console output from XML: console CDATA is outside the existing evidence scanner's supported format. Test identities/counts and failure evidence are still emitted and validated. No scanner or report acceptance rule is relaxed.

The focused test prints the resulting order, component decisions, exact changed paths, authority fingerprints, and a path to the full JSON evidence report retained in the system temporary directory. Project/cache copies are cleaned up. The report retains exact accepted artifact lineage, stage effects, host Maven/Surefire evidence, Agent 07 result, and MASTER decisions.

No real provider, database, network request, Mantis or company repository is used. Offline Maven is not OS/container confinement: the host must trust the installation, cached plugins/dependencies and fixture source. This focused scenario is opt-in; the broad pre-push harness/canonical milestone remains separate.
