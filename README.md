# Agentic API Builder

A controlled, agent-driven pipeline for analyzing and migrating existing Java
and Spring Boot APIs. The host runtime enforces access boundaries, retains
evidence, and requires MASTER acceptance between stages.

```text
MASTER
  → 00 source analysis
  → 01 target analysis
  → 02 migration planning
  → 03 domain/contracts
  → 04 persistence/mapping
  → 05 service/API
  → 06 tests
  → 07 validation
  → MASTER final result
```

The controlled runtime and its security boundaries are authoritative. Source
access is read-only; target writes require exact accepted-plan grants. Validation
requires a separate host-supplied trusted profile. Profiles support the fixed Java
contract-test fixture and a host-controlled offline Maven `test` boundary for
Spring Boot targets. See [Maven validation](harness/README.md#host-controlled-maven--spring-boot-validation)
for host provisioning and limits.

The pipeline is provider-independent. The host-owned `ProviderTransport` boundary
currently has one concrete adapter: direct OpenAI Responses, using the existing
HTTP transport. `OPENAI_API_KEY` is specific to that adapter. Migration JSON/YAML cannot
select providers, credentials, endpoints, executables or validation profiles.
OpenCode/company integration is a future reviewed adapter; its E2E behavior is
unproven. It must not own source/target authority, validation, MASTER acceptance
or process execution.

## Repository

- [MASTER.md](MASTER.md): orchestration and acceptance rules.
- [agents/](agents/): specialist specifications 00–07.
- [Orchestration contract](agents/contracts/orchestration-contract.md): shared
  authority, evidence, and handoff contracts.
- [harness/](harness/): controlled Java runtime, offline tests, and fixtures.
- [Harness documentation](harness/README.md): host entry points, dependency
  setup, security boundaries, supported profiles, and limitations.

## Offline validation

Use JDK 21 (`java` and `javac` on PATH), Bash, and a supported POSIX filesystem.
The canonical suite requires these existing local jars:

- `tools.jackson.core:jackson-core:3.1.5`
- `tools.jackson.core:jackson-databind:3.1.5`
- `com.fasterxml.jackson.core:jackson-annotations:2.21`
- `org.yaml:snakeyaml:2.6` (host-side data-only YAML event parser)

The test script looks in the corresponding paths under `~/.m2/repository` by
default. If the jars are stored elsewhere, set `HARNESS_JACKSON_CLASSPATH` to their
colon-separated Jackson paths and `HARNESS_YAML_CLASSPATH` to the SnakeYAML jar.
Supply these dependencies before running the suite; the
script fails if they are missing and never downloads dependencies.

```bash
bash harness/test.sh
```

For only offline config/YAML and launcher input guards, use
`bash harness/test.sh migration-input`. This does not run the canonical suite,
provider scenarios or the validation worker.
For only process-boundary checks and controlled-Java compatibility, use
`bash harness/test.sh validation`; no provider/network calls are needed.

The suite compiles the harness in a temporary directory and includes migration
config checks alongside the original 468 checks plus 25 provider-boundary checks:
70 harness, 12 source contract, 145 artifact contract, 29 broker, 95 execution
runtime, 56 implementation runtime, 53 validation runtime, 8 live preparation, and 25 provider boundary. Provider responses
are mocked; validation executes only the fixed local JDK worker with approved
temporary fixture sources. No API key is required.

## Current limits

The offline suite exercises the controlled 00–07 route. Live provider E2E remains
unproven. The [fixed-fixture live launcher](harness/README.md#first-live-fixture-invocation)
prepares a read-only source and disposable target and accepts host model/token
configuration; credentials come only from `OPENAI_API_KEY` in the host environment.
The launcher accepts `.json`, `.yaml`, and `.yml`. YAML is data-only and normalizes
through the same `MigrationInput` semantics as JSON. Provider, credentials,
endpoints, static authority, validation, filesystem and process authority remain
host-owned. See [YAML rules](harness/README.md#migration-config-formats).
Unrestricted project builds,
execution-profile Git-state support, durable recovery, and resume remain outside
the supported runtime. This is not a production-readiness claim.
