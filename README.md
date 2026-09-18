# Agentic API Builder

Agentic API Builder is a controlled, agent-driven Java/Spring pipeline for
analyzing, planning, implementing, and validating API operations. It supports
two workflow modes:

| Workflow | Starting point |
| --- | --- |
| `MIGRATION` | Analyze an existing operation in a source project, then migrate or adapt it to a target project. |
| `NEW_OPERATION` | Analyze a structured operation request and the target project, then implement the requested operation without a source implementation. |

Both routes run through bounded host capabilities and MASTER acceptance gates.
The implemented runtime requires independently supplied implementation and
validation authority. Local scenarios use synthetic targets and mocked provider
responses; real provider/company integration E2E remains unproven.

Start with the workflow examples below and [offline testing](#offline-testing).
For a complete local NEW_OPERATION scenario with real Maven/JUnit execution,
see the opt-in [customer-filter demo](#customer-filter-synthetic-e2e).

## Control model

| Owner | Responsibility |
| --- | --- |
| **MASTER** | Orchestration and order, stage acceptance policy, and final acceptance based on eligible evidence. |
| **Harness / host runtime** | Actual enforcement: source/target access boundaries, trusted input registration, exact write grants, artifact fingerprints and lineage, observed repository effects, validation execution, and security/control gates. |
| **Specialists** | Produce the assigned analysis, plan, implementation, tests, or validation artifact within the host-issued capabilities. |

MASTER controls progression between stages, including implementation preflight
and acceptance after each stage. The diagrams show the logical specialist
sequence. An agent's success claim, a copied fingerprint, or a plan alone is
insufficient evidence and cannot grant access or bypass host checks.

## MIGRATION workflow

```text
00 Source Analysis
 → 01 Target Analysis
 → 02 Migration Planning
 → 03 Domain / Contracts
 → 04 Persistence / Mapping
 → 05 Service / API
 → 06 Tests
 → 07 Validation (host-controlled execution)
 → MASTER final acceptance
```

00 inspects the requested source operation through the read-only source broker.
01 gathers target evidence; 02 plans adaptations from the accepted analyses.
Implementation follows the accepted plan and host preflight. Source and target
authority remain separate throughout the run.

A minimal migration config is:

```yaml
workflow: MIGRATION
sourceProjectPath: /absolute/path/to/source-project
targetProjectPath: /absolute/path/to/target-project
sourceOperationName: readItem
```

Missing `workflow` defaults to `MIGRATION`. These three routing fields are
required; request/response fields, table hints, and a source class path are not
mandatory. Existing `migration.settings` routing is also supported, but the
workflow selector must remain at the top level. Source and target paths must be
absolute, normalized, and non-overlapping; runtime root/access checks also apply.

## NEW_OPERATION workflow

```text
00R Requirement Analysis
 → 01 Target Analysis
 → 02R Operation Planning
 → 03 Domain / Contracts
 → 04 Persistence / Mapping
 → 05 Service / API
 → 06 Tests
 → host-controlled Maven/JUnit validation
 → 07 Validation
 → MASTER final acceptance
```

NEW_OPERATION requires no source project, source analysis, or source-access gate.
It uses its own requirement, plan, implementation authority, and validation
lineage. A concise synthetic request is:

```yaml
workflow: NEW_OPERATION
targetProjectPath: /absolute/path/to/target-project
tableName: CATALOG_ITEM
operationType: INSERT
operationName: addCatalogItem
requestFields:
  - name: id
  - name: label
    description: Display label for the new item
responseFields:
  - name: id
  - name: label
```

These examples describe caller input. They do not provision a target or grant
implementation/process authority. The host must register an existing supported
target and separate reviewed policies. In particular, the current live launcher
does not supply NEW_OPERATION implementation or validation authority; its
accepted plan therefore stops before 03 unless an embedding host supplies them.

### Structured input contract

NEW_OPERATION uses a flat object. The loader accepts `.json`, `.yaml`, and `.yml`;
YAML is data-only and passes through the same input validation as JSON. It does
not perform environment interpolation, file inclusion, or object construction.
See the [config format rules](harness/README.md#migration-config-formats).

| Field | Accepted structure |
| --- | --- |
| `workflow` | Exactly `NEW_OPERATION`; the selector is case-sensitive. |
| `targetProjectPath` | Required absolute, normalized target path. |
| `tableName`, `operationName` | Nonblank strings expressing caller intent. |
| `operationType` | Exactly `GET`, `INSERT`, `UPDATE`, or `DELETE`. |
| `requestFields`, `responseFields` | Arrays of objects with required nonblank `name` and optional string `description`. Names must be unique within each array; no other field attributes are accepted. Empty arrays explicitly mean no fields. |
| `requirementText` | Optional string retained as business data. |
| `placement` | Optional object containing only `parentKey`, `parentOrder`, `filterOrder`, `before`, `after`, and `reference`. Order values are signed 64-bit integers; other values are nonblank strings. |

`tableName`, `operationType`, `operationName`, `requestFields`, and
`responseFields` must be present for requirement analysis to succeed. Missing
critical fields produce a blocked requirement; invalid supplied shapes reject.
Supplying both `before` and `after` blocks as conflicting placement. For example,
optional placement may be expressed as:

```yaml
placement:
  parentKey: CATALOG
  after: EXISTING_ITEM
```

Unknown top-level fields remain caller data, not normalized requirements or
runtime authority. `migration` and `settings` envelopes are invalid on this route.
Operation type alone establishes no database/framework behavior: keys, mappings,
endpoints, transactions, error handling, and ordering need target evidence and
an accepted plan.

### 00R Requirement Analysis

00R produces `operation-requirement.json` without repository tools. The host
independently recomputes deterministic normalization and rejects changed caller
values, invented facts, or a mismatching artifact. Free-form text and descriptions
cannot fill missing critical structured fields or resolve placement conflicts.

Explicit placement is preserved. Omitted placement remains unresolved for later
target analysis/planning; it does not imply an order. Target content inspection
starts only after MASTER accepts the requirement.

### 01 Target Analysis

01 is read-only. It gathers architecture, schema, component, and testing
conventions needed for planning through the authorized target broker. For
NEW_OPERATION, the host correlates structured observations with complete file
reads and requires technical plan values to occur in supporting excerpts.
These checks establish observation and traceability; literal occurrence alone
does not prove a semantic interpretation. MASTER must still assess that support.

### 02R Operation Planning

02R consumes the accepted requirement and target analysis and produces
`operation-plan.json`. It has no tools and does not edit the repository. The
plan covers field/table mappings, behavior, dependencies, placement, and test
and validation obligations, with component decisions for domain model, request
DTO, response DTO, persistence, mapper, service, API, and tests.

Resolved components use `REUSE`, `MODIFY`, or `CREATE`; evidenced
`NOT_APPLICABLE` components need no implementation. Unresolved decisions block.
The accepted placement strategies are `NOT_APPLICABLE`, `PRESERVE`, `EXPLICIT`,
`DERIVED`, and `SHIFT`. There is no generic automatic `APPEND` fallback.
Required placement/order without sufficient evidence blocks rather than
inventing a sequence. `SHIFT` additionally requires evidenced coordinated
changes and transaction consistency, with behavior/test/validation coverage;
the runtime supplies no generic shifting algorithm.

## Implementation authority

Plan acceptance does not authorize arbitrary repository mutation. For
NEW_OPERATION, the embedding host separately registers exact file paths,
independently reviewed complete file contents, and fingerprints binding those
contents to the plan's obligations. Writes must match these approved contents
before reaching the target broker.

The host assigns 03 domain/contracts, 04 persistence/mapping, 05 service/API,
and 06 tests. Grants bind the active invocation, role, target identity, exact
path/action, and expected prior state. `REUSE` and `NOT_APPLICABLE` grant no
writes. `CREATE` also needs an evidenced exact destination and an existing
parent directory; an observed directory alone does not authorize a new filename.
Each stage requires host effect checks and MASTER acceptance, even without writes.

Missing policy blocks with `NEW_OPERATION_IMPLEMENTATION_AUTHORITY_REQUIRED`.
After 06, missing separate validation authorization blocks with
`NEW_OPERATION_VALIDATION_AUTHORITY_REQUIRED`. The migration authority/profile
cannot substitute for either. See the
[implementation contract](agents/contracts/orchestration-contract.md#new_operation-controlled-implementation-profile).

## Test and validation chain

For NEW_OPERATION:

```text
06 writes test source
 → host rechecks accepted implementation, obligations, and validation authority
 → fixed offline Maven test executes the target's tests (JUnit in the fixtures)
 → host inspects fresh Surefire XML, required test identities, and file effects
 → tool-free 07 receives the host validation projection
 → MASTER accepts or rejects final validation
```

The host-only `ValidationProfile.mavenOperationTest(...)` binds the exact
accepted plan and maps its test obligations to reviewed `classname#method`
identities. Every required test must appear in fresh, consistent Surefire
reports without failure, error, or skip. XML is bounded and parsed with external
access disabled. Host checks also revalidate approved implementation content
and restrict build effects to the initially absent root `target/**` scope.
Post-execution state is checked through 07 and MASTER acceptance.

**Maven exit code 0 alone is not sufficient evidence.** Missing/unsupported
required evidence blocks; executed test failures, timeouts, or unauthorized
effects fail. 07's result must match the host projection, and final success
requires MASTER acceptance. Reports alone do not prove arbitrary business semantics.

MIGRATION retains its separate validation route: 07 can request only the fixed
host-authorized validation operation. Host profiles support the fixed Java
contract-test fixture or offline Maven `test`; the NEW_OPERATION Surefire
obligation mapping is specific to its separate profile. Neither route grants
agents arbitrary shell, Maven goals, process, or environment control.

Maven execution uses a host-selected Maven 3 installation and pre-provisioned
read-only cache, direct Java bootstrap, cleared child environment, isolated
settings, bounded output, and a timeout. Repository wrappers and `.mvn` launch
configuration are not accepted. See
[Maven provisioning and limits](harness/README.md#host-controlled-maven--spring-boot-validation).

## Offline testing

From the repository root, use JDK 21 (`java` and `javac` on PATH), Bash, and a
supported POSIX filesystem. The script compiles with `javac --release 21` and
uses disposable non-Git directories beneath canonical `${TMPDIR:-/tmp}`.
The canonical suite requires these existing local jars:

- `tools.jackson.core:jackson-core:3.1.5`
- `tools.jackson.core:jackson-databind:3.1.5`
- `com.fasterxml.jackson.core:jackson-annotations:2.21`
- `org.yaml:snakeyaml:2.6`

The script looks under `~/.m2/repository` by default. Alternatively, set
`HARNESS_JACKSON_CLASSPATH` to the colon-separated Jackson jar paths and
`HARNESS_YAML_CLASSPATH` to the SnakeYAML jar. Missing dependencies fail;
the script never downloads them.

```bash
bash harness/test.sh
```

The canonical suite exercises both workflows, input/contracts, brokers,
provider boundaries, implementation authority, and validation with mocked
provider responses. It includes fixed local Java execution and NEW_OPERATION
checks using a synthetic Maven bootstrap with real child compilation/test
execution. No API key or provider/network call is required. The real-Maven
customer-filter demo below runs separately.

Useful focused selectors in [test.sh](harness/test.sh):

| Command suffix | Scope |
| --- | --- |
| `migration-input` | JSON/YAML config and launcher input guards. |
| `workflow` | Workflow/requirement/planning checks, related contracts, and a migration implementation compatibility scenario. |
| `operation-implementation` | NEW_OPERATION implementation authority and effects. |
| `operation-validation` | NEW_OPERATION host validation and required test evidence. |
| `validation` | Process lifecycle, Maven boundary fixtures, and controlled-Java compatibility. |
| `validation-runtime` | Validation runtime scenarios. |

For example: `bash harness/test.sh operation-validation`. Focused selectors do
not replace the canonical suite. Process lifecycle checks need permission to
observe child processes; a restrictive execution environment can prevent that
evidence from being established.

## Customer-filter synthetic E2E

```bash
bash harness/test.sh customer-filter-demo
```

This opt-in [local fixture](harness/examples/customer-filter/README.md) exercises
a NEW_OPERATION `INSERT` through the controlled pipeline: add a filter after an
existing sibling, shift later siblings within the same parent, and preserve
the target's consistency/API conventions. Provider responses, including MASTER,
are mocked; implementation bytes are independently supplied fixture approvals.

The host runs real local Maven/JUnit against a disposable Spring Framework
target with an in-memory repository. The test is intended to verify insertion
order, sibling shifts, parent isolation, rejection without partial mutation,
API conventions, exact write effects, and required Surefire evidence. It starts
no HTTP server, executes no schema SQL, and uses no company database or provider.
It is not a production deployment or proof of company-provider integration or
autonomous model generation.

In addition to the harness jars, this selector needs a local Maven 3 installation
and the already cached dependencies/plugins from the fixture's
[pom.xml](harness/examples/customer-filter/target-project/pom.xml).
`HARNESS_DEMO_MAVEN_HOME` and `HARNESS_DEMO_MAVEN_CACHE` select trusted host paths;
defaults are documented in the demo README. The test makes a disposable read-only
cache copy and performs no downloads. Missing prerequisites fail the test.

## Provider boundary

The pipeline is provider-independent through the host-owned `ProviderTransport`
abstraction. The current concrete adapter is `ResponsesProtocolAdapter` for
direct OpenAI Responses, backed by `HttpResponsesClient`. It requires frozen
payloads, independent readback, and ordered context observations; a provider
without those capabilities is unsupported.

`OPENAI_API_KEY` is specific to that adapter and comes from the host environment.
Caller JSON/YAML cannot select providers, credentials, endpoints, executables,
static authority, or validation profiles. The
[fixed-fixture live launcher](harness/README.md#first-live-fixture-invocation)
accepts host model/token configuration and prepares a read-only migration source
and disposable target. Its availability does not establish live provider E2E.
Company/Mantis/OpenCode integration is future or unproven work, not an implemented
adapter; it must preserve the host's access, validation, and acceptance boundaries.

## Security and trust boundaries

Trusted specifications and host policies are explicitly registered separately
from caller business text, repository content, tool results, and validation
output. Those data sources cannot promote themselves into instructions or grants.
The source broker is permanently read-only; target mutation is limited to
host-issued file grants. Process execution belongs to the host validation
profile, with no unrestricted arbitrary-command capability exposed to agents.
Acceptance depends on retained artifacts, exact fingerprints, observed effects,
and the applicable host predicates. Hashes identify bytes, not authenticity.

These are bounded runtime checks, not an OS/container sandbox. Point-in-time
filesystem observations cannot exclude concurrent writers or transient effects.
Offline Maven prevents dependency resolution downloads under its policy; it
does not confine executable plugins/tests or provide network isolation. Build
code and dependencies need host review or external isolation. Secret screening
is conservative, not universal; review retained evidence before publication.

## Current limitations

- NEW_OPERATION implementation is bounded to independently approved exact files
  and contents. It does not establish correctness of arbitrary generated Java.
  Shared component paths, directory creation, and unrestricted mutation are
  outside this profile.
- GET/INSERT/UPDATE/DELETE express intent, not generic CRUD semantics. Missing
  technical facts or unsupported placement/order can block planning.
- Execution supports bounded non-Git POSIX project snapshots, not arbitrary Git
  worktrees. Validation accepts root `target/**` outputs; multi-module/larger
  builds or unsupported report layouts can exceed the supported boundary.
- Synthetic tests and the opt-in real-Maven demo do not prove company DB,
  real-provider, network, or production E2E behavior. The live CLI does not
  provision NEW_OPERATION's separate host policies.
- Evidence is current-session/in-memory. Durable recovery, resume, and automatic
  rollback are not implemented. This repository makes no production-readiness claim.

Some specifications retain earlier draft/milestone wording, including a stop
after 06 and claims that specialist execution is unavailable. The current
`ControlledPipeline` and shared NEW_OPERATION validation profile implement the
bounded routes described here; those older statements should not be read as
the current runtime capability inventory.

## Repository map

- [MASTER.md](MASTER.md): orchestration and acceptance specification.
- [agents/](agents/): specialist specifications, including 00R and 02R.
- [00R requirement contract](agents/00r-requirement-analysis.md): structured
  input and deterministic normalization.
- [02R operation plan contract](agents/02r-operation-planning.md): evidence,
  component decisions, placement, and obligation coverage.
- [Shared orchestration contract](agents/contracts/orchestration-contract.md):
  authority, evidence, handoffs, and NEW_OPERATION implementation/validation profiles.
- [harness/](harness/) and [harness/README.md](harness/README.md): host entry
  points, setup, profiles, and detailed boundaries.
- [Runtime source](harness/src/dev/agentic/harness/) and
  [tests](harness/test/dev/agentic/harness/): executable contracts and local checks.
- [Customer-filter example](harness/examples/customer-filter/): synthetic
  target, request, approved outputs, and focused demo instructions.
