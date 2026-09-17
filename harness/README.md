# Controlled provider-neutral harness

## Provider boundary

The migration pipeline is provider-independent. `ControlledPipeline`, `RoleExecutor`
and `ControlledHarness` own orchestration, trusted roles, static task authority,
source read-only enforcement, target grants, exact artifact hashing, readback
correlation, lineage, MASTER acceptance and validation. Repository contents and
additional migration fields remain untrusted data.

`ProviderTransport` is a trusted host adapter contract for logical turns, immutable
prepared payloads, creation observations and independently retrieved context.
The host freezes, screens and fingerprints the exact prepared payload before
passing the same object to dispatch. Adapters must send that payload without
reconstruction and expose actual protocol facts, not fabricated echoes. Adapters
are reviewed trusted host code, not a sandbox for arbitrary implementations.
The host verifies completion, configuration, predecessor, creation/readback identity
and output, and ordered context membership against its retained chain. Native IDs
are opaque; every adapter must support independent readback, ordered context and
frozen dispatch. Missing capabilities fail closed; there is no fallback or reset.
Exact raw wire evidence is retained alongside neutral observations. Repeated large
instruction/output fields are represented by host fingerprints where exact copies
already exist; this does not modify artifact text or relax direct readback equality.
`ExecutionGates` uses the retained logical invocation binding and the fingerprint
of the exact dispatched payload, without parsing a provider's wire JSON.

`ResponsesProtocolAdapter` is the current concrete implementation. It preserves
Responses request construction, continuation, completion/configuration checks,
output extraction, native ID validation and bounded input-item pagination.
`ResponsesClient` and `HttpResponsesClient` remain the low-level Responses transport;
legacy host constructors wrap them in the adapter. `OPENAI_API_KEY` belongs only
to this direct OpenAI transport, whose endpoint and security restrictions are fixed.

`HostProviderConfiguration` is an immutable, closed host profile factory. The
launcher selects `DIRECT_OPENAI_RESPONSES`; unknown profiles reject before opening
a transport. Migration JSON cannot select provider, model, endpoint, credentials,
executable, command, validation profile or static authority. No URL override,
class loading, provider CLI, automatic retry or fallback was added.

OpenCode/company integration requires a future reviewed adapter and concrete
company protocol/capability information. It is not implemented or proven E2E.
OpenCode must not become the authority for source/target access, validation,
MASTER acceptance or process execution. A provider that cannot supply the required
readback and context observations is unsupported. Real provider E2E remains unproven.

## Current execution runtime

`ControlledPipeline` adds a non-interactive, in-memory `SOURCE_TO_TARGET` route:
MASTER registration and gate acceptance → 00 → MASTER acceptance → 01 → MASTER
acceptance → 02 → MASTER acceptance → implementation preflight and MASTER acceptance
→ 03 domain/contracts → MASTER step acceptance → 04 persistence/mapping → MASTER
step acceptance → 05 service/API → MASTER step acceptance → 06 tests → MASTER step
acceptance → 07 controlled validation → MASTER validation acceptance → `SUCCESS`,
`FAILED`, or `BLOCKED` based on host evidence. The host must explicitly supply a
trusted `ValidationProfile`; without one the existing 07 boundary remains blocked.
`ImplementationRuntimeTest` exercises this route using the actual ten explicitly
selected trusted documents, mocked `ResponsesClient` responses, and temporary
source and target repositories. This is an offline execution proof. Live provider
E2E remains unproven; this is not a production-readiness claim.

The executable subset requires exactly one CREATE/MODIFY file for each of 03–06,
in that order, with distinct exact paths, unambiguous semantic role ownership,
safe existing parent directories and complete prerequisite/test traceability.
`ExecutionPlan` derives assignments from the exact MASTER-accepted migration
plan. The existing separately supplied `CALLER_RESOLUTION` static authority binds
each responsibility to host-selected expected UTF-8 bytes for independent checks;
model assertions cannot supply these predicates. Missing static authority still
blocks at `VALIDATION_EXECUTION_POLICY_AND_OBLIGATION_MECHANISM_REQUIRED`.
Unsupported ownership, paths, contracts or prerequisites block/fail before the
first grant. Preflight captures the target baseline and verifies source and target
are unchanged. Validation authority is issued separately after 06 MASTER acceptance.

The public Java entry point accepts explicit trusted root, `MigrationInput`,
`ControlledHarness.Config`, and a host-supplied `ProviderTransport` (or the
compatible `ResponsesClient` overload).
`ControlledPipeline.runJson(...)` blocks missing routing before provider access.
`LiveLauncher` adds the fixed-fixture live CLI described below. `MigrationInput`
reuses cached Jackson JSON parsing, preserves exact caller UTF-8 bytes and their
fingerprint, and retains unknown structured fields, including decimal values.
It recognizes `migration.settings.sourceProjectPath`, `targetProjectPath`, and
`sourceOperationName` (also their flat equivalents). Additional fields remain
caller data for specialists; they do not grant runtime authority. The host config
loader also accepts YAML, as described under [migration config formats](#migration-config-formats).

`RoleExecutor` selects exactly one registered role per invocation. Its outer
`HOST_EXECUTION_TURN_V1` binding records session/run, invocation, predecessor
invocation, previous provider response, global turn ordinal, trusted assembly and
role fingerprints, and input artifact fingerprints. Every provider create
explicitly resends the fixed trusted instructions. `previous_response_id` only
correlates conversation state. The existing create/retrieve/input-item checks
still reject changed configuration, reordered or unknown history, and reused
provider identities. Execution inspection summarizes those items by exact
fingerprint; the live identity registry and retained readback still hold the full
items. Role selection, tool requests and replies are strict JSON;
artifact text preserves legal whitespace rather than being treated as an
identifier. Structured output is validated before transport retention; readback
must finish before tool execution or artifact handoff.
Artifact replies use `artifactFingerprint: null`; the host computes the fingerprint
from the exact UTF-8 artifact text and binds it to the verified invocation/response.
A supplied legacy fingerprint must still match. Hashing does not confer acceptance.
The separate outer `hostTaskContext` supplies the exact retained static resolution
and its fingerprint, also included in the invocation's input fingerprints. Model
output and caller routing fields cannot replace this authority.

`ExecutionGates` retains canonical source/discovery declarations and checks over
the actual host configuration, trusted registry, launch selection, current
request binding and response lineage. MASTER receives the underlying observed
materials as well as their fingerprints. Checks describe this host route and
current session; mock responses are not provider authentication or attestation.
The initial registration turn has no content access. Accepted gates enable
separate content brokers without changing `TargetBroker`'s original transitions.

`SourceBroker` exposes bounded text reads, single-directory listings,
single-file literal searches and metadata to 00 only. It has no write method.
`TargetContentBroker` allows target analysis reads to 01; 02 has no repository
operations. Both verify the exact active invocation and root identity, reject
traversal, aliases, symlinks and regular-file hardlinks, and return explicitly
untrusted data. The source remains permanently read-only and only 00 can read it.
The pipeline issues target CREATE/MODIFY grants only after accepted-plan preflight.
Each immutable grant binds a unique ID, run, exact accepted source/target/plan
fingerprints, role, step, invocation, predecessor acceptance, canonical dispatch,
target root identity, exact path/action and observed before-state. Write requests
must name the current grant ID and expected content fingerprint (null for CREATE).
The broker rejects wrong bindings, replay, stale state and root/path substitution,
and retains exact after bytes and observed states before returning the receipt.
Implementation reads are confined to the assigned grant path. No shell,
subprocess, network, Git mutation, delete, rename or directory-creation operation
is exposed. Broker denials close access permanently.

Default bounds are 1 MiB per file, 2,048 entries, 128 search hits, 64 operations
and 4 MiB returned per invocation, 16 MiB per snapshot, and 96 provider turns per
session. Exceeding a bound rejects the operation with incomplete/unavailable
evidence; results are never silently truncated. Filesystem support is a bounded
POSIX, ASCII-relative-path, non-Git subset. Unicode relative names and Git
observations are unavailable. These are point-in-time checks, with no exclusive
writer control or race-free confinement claim.

`ArtifactContracts` validates analyst/planner schemas, identities, references and
status coherence. `ArtifactStore` retains exact bytes, fingerprints, provider and
invocation correlation, predecessor bindings and MASTER acceptance IDs. Candidate
receipt supplies no acceptance credit. BLOCKED/FAILED source output stops
dependent phases; validated nonaccepted output and MASTER rejection reasons
remain separately labelled for the caller. Repository snapshots before analysis,
at acceptance and at termination retain modeled observable effects. Unexpected
drift fails acceptance; unavailable terminal observation stays UNKNOWN.

The execution profile treats non-null coverage counts as exact unique-path counts
within the active invocation: `filesDiscovered` includes files listed, inspected
as metadata, read, or searched; `filesInspected` includes content reads/searches.
Repeated operations count once. Area `sampleSize` counts its `inspectedPaths`;
other or unavailable sampling units use the contracts' permitted `null` value.
Declared target modules and source/test/resource roots require observed directories.
`modulesDiscovered` counts distinct declared module directories;
`modulesInspected` counts those containing actual host content observations,
independent of artifact `evidenceIds`. Containment compares path components;
the root module `""` includes repository content observations.
These accounting rules are supplied with each specialist task. Directory presence
never grants file-content inspection credit. A validated, retained specialist FAILED
is latched before MASTER stop handling; subsequent stop failures remain separate
diagnostics and cannot replace its primary terminal status/code.

`ExecutionEvidence` supplies canonical dispatches, prerequisite proofs, independent
exact-file obligation audits, candidate step acceptance and the accepted ledger.
`ImplementationHandoff` validates the existing trusted specialist SUCCESS schema.
The strict implementation result wrapper contains `resultVersion: 1`, the exact
`migrationPlanFingerprint`, `predecessorAcceptance`, `grantEffects`, and `handoff`.
Every grant effect includes grant ID, role, invocation, path, action, and full
before/after states with content fingerprints. The host compares the complete
claimed effect set against broker observations, checks source immutability and
the entire modeled target state, then asks MASTER to accept the step. Only that
acceptance makes the exact result an `ArtifactStore` handoff and the canonical
step eligible as a prerequisite. Malformed or inconsistent results never reach
MASTER acceptance.

During 00–02 both repositories must remain unchanged. During implementation the
expected target state is the original baseline plus broker-observed authorized
writes. External changes never become a new baseline. On filesystems where a CREATE
increments its immediate parent directory link count, that exact one-link metadata
transition is observed and retained with the write; all other parent fields must
remain equal. Directory mutation authority is never issued. Boundaries report
`EXPECTED_EFFECT`, `UNEXPECTED_EFFECT`, or `UNKNOWN`; observable unexpected effects
stop execution. Final reports retain actual snapshots and mutation evidence.
MASTER rejection or malformed output leaves applied files in place and records
them as unaccepted; no rollback is claimed. These are point-in-time modeled-state
checks, without exclusive writer control or detection of transient changes that
are restored between observations.

Final implementation reports inspect each immutable host journal record separately
and inspect the remaining report fields together. Every record keeps its field
context and all existing scanner limits; individual model artifacts are never
split. This prevents repeated aggregate history from exhausting one inspection
budget without removing credential checks or exact retained evidence.

### Controlled 07 validation

The six-argument `ControlledPipeline` constructor accepts a host-created
`ValidationProfile.controlledJavaContractTest(hostReviewedSources)`. This explicit
opt-in supports the tiny Java fixture, whose POM declares no test framework or
build plugins. `CONTROLLED_JAVA_CONTRACT_TEST` compiles exactly four pinned source
files with the installed JDK and executes `ReadItemContractTest.verifiesIdentity`.
The host must independently review and trust those complete source bytes; neither
model output nor caller routing JSON can select this profile or its approved bytes.
This is a fixed fixture profile, not a Maven runner or general Java test discovery.

The validation command, exact argument vector, executable/worker fingerprints,
working root, cleared child environment (subject to OS-added metadata),
five-second timeout and output scopes are host-owned. There is no arbitrary shell, command/argument/cwd/env input, stdin,
network tool, Git operation or 07 repository mutation tool. Compiler annotation
processing and implicit source discovery are disabled. The model's sole request
is `RUN_VALIDATION` with its invocation, role, operation ID and authority ID.

An immutable, single-use authority binds the run, 07 invocation, accepted plan,
all 03–06 result fingerprints and MASTER acceptances, target filesystem identity,
profile, exact launch definition and replay identity. Immediately before launch,
the host revalidates accepted lineage, source immutability, implementation state,
root identities, pinned source bytes and an initially absent output directory.
Mode observations retain the original source permissions and expected implementation
permissions across 03–06; entering 07 cannot adopt permission drift as a baseline.
Drift blocks execution. No project wrapper or build script is discovered or run.

After execution, bounded snapshots compare the source and implementation baseline.
Only this profile's `target/**` output is classified `VALIDATION_EPHEMERAL_EFFECT`;
its binary files are fingerprinted without text decoding. Source and implementation
permission/mode changes are detected as well as content and identity changes.
Symlinks, hardlinks,
root replacement, Git metadata, source/configuration changes and effects elsewhere
fail closed. Existing snapshot bounds remain 1 MiB/file, 2,048 entries and 16 MiB
content per repository; oversized or unsafe observations never support PASS.
These remain point-in-time checks, not OS sandboxing or protection against transient
changes restored between observations. Executed fixture code must be host-trusted.

Each process stream retains at most 8 KiB (16 KiB total), drains excess output
while the process is observed, and ends its reader within a bounded terminal wait.
It reports retained-byte fingerprints, observed counts, truncation and completion.
No raw build log excerpts enter artifacts. Retained validation facts have a 256 KiB
cap; oversized effect sets retain a fingerprint, count and bounded examples, mark
full evidence unavailable, and cannot pass. Evidence bounds never downgrade a known
FAILED process/effect outcome to BLOCKED. Terminal observations preserve previously
observed unauthorized effects even if a later callback restores the files.
Terminal cleanup runs after success, nonzero exit, timeout and exceptional
execution. It repeatedly discovers descendants while the parent is alive, retains
observed handles after parent exit, and terminates survivors within a bounded
cleanup window. Failure to enumerate or terminate is a sanitized FAILED outcome.
This is lifecycle cleanup for observed descendants, not OS process confinement.
Nonzero exit and timeout remain FAILED; inability to safely launch is BLOCKED.
Unknown or incomplete execution and repository observations cannot pass.

The strict `VALIDATION_RESULT` binds the authority, invocation, predecessor lineage,
host execution reference, exit/timeout, exact observed repository effects, status
and failures. Host facts override model claims. MASTER receives the result and
independent evidence, and its acceptance is required for final SUCCESS. Host
execution facts and effects survive later journal/report rejection in the terminal
summary. No rollback or durable crash recovery is claimed. Live provider E2E
remains unproven; this is not production readiness.

### Host-controlled Maven / Spring Boot validation

Trusted embedding code can pass `ValidationProfile.mavenTest(hostMavenHome,
hostReadOnlyRepository)` to the same six-argument `ControlledPipeline` constructor.
The existing `LiveLauncher` continues to select its controlled-Java fixture profile;
there is no Maven command configuration in migration JSON/YAML or launcher input.

`HOST_MAVEN_TEST` runs the installed host JDK directly with an explicit
`ProcessBuilder` argv: fixed JVM flags, a host-generated Classworlds configuration,
one fingerprinted bootstrap jar from the reviewed Maven 3 installation, and the
fixed Maven arguments `--batch-mode --offline --no-transfer-progress
--strict-checksums`, isolated user/global settings, the host cache property,
`-Dstyle.color=never`, and `test`. Use a reviewed Maven 3.9.x distribution; Maven 4
and arbitrary distributions are not established by these tests. There is no shell,
PATH lookup, executable fallback, repository wrapper execution, or arbitrary-command
API. Host installation/cache paths must be canonical, disjoint from source and
target, and free of symlinks/hardlinks. Their bounded content/identity fingerprints
are checked again before launch; the exact argv fingerprint is bound to the authority.

The cwd is exactly the registered target root. Maven's multi-module root is set
explicitly to that root. Any `.mvn` directory in the observed target (including
nested paths) blocks launch: Maven core itself reads `maven.config` and
`extensions.xml`, even when shell wrappers are bypassed. Repository `jvm.config`
and `mvnw` are never executed or interpreted as launch policy. These checks use
the existing point-in-time repository boundary; they do not prevent concurrent
filesystem races or hostile build code from changing files after launch.

The child environment is cleared. Provider keys, tokens, credential variables,
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `MAVEN_OPTS`, `MAVEN_ARGS`,
`CLASSPATH`, `HOME` and shell startup variables are not inherited. OS runtime metadata
(such as macOS `__CF_USER_TEXT_ENCODING`) may still appear. `user.home`, Java's temp
directory, empty Maven user/global settings and launcher configuration are created
under the initially absent `target/.harness-maven` output scope. Host user settings,
credentials and toolchains are not loaded. The pre-provisioned offline cache must
have no write permission bits; this profile neither populates it nor downloads
missing dependencies. Provision only the artifacts needed by the reviewed build.
Installation/cache inspection is bounded to 8,192 entries, 64 MiB/file and 1 GiB/tree.

Maven gets a fixed 60-second timeout and the same 8 KiB-per-stream capture,
fingerprint-only `UNTRUSTED_VALIDATION_OUTPUT`, effect checks and FAILED/BLOCKED
rules as the Java profile. Timeout kills the process and observed descendants.
Successful exit supplies structured host evidence; 07's exact result and separate
MASTER acceptance are still required. No raw Maven log enters prompts or diagnostics.
Only root `target/**` is an accepted output scope; existing snapshot limits still
apply. Multi-module output directories and larger builds may therefore fail closed.

This supports the invocation boundary for a conventional single-module Spring Boot
Maven target with its dependencies already provisioned. POM lifecycle/plugin/test
semantics still execute build code: the host must review that code or supply external
OS isolation. Offline Maven resolution is not an OS network sandbox, and repository
observations are not confinement of hostile plugins or forked test processes. This
milestone adds no filesystem grant outside existing scopes and makes no production
readiness or full Spring Boot application E2E claim. OpenCode/company-provider
integration remains a separate adapter concern; real company-provider E2E is unproven.

Run only `bash harness/test.sh validation` for this boundary's focused offline tests.
They use a fake host Maven bootstrap, actual Java children, direct runtime fixtures,
JSON/YAML/YML through the mocked pipeline, a mocked MASTER rejection scenario,
deterministic process-tree fixtures and controlled-Java compatibility.
They need cached harness dependencies, no provider credentials or dependency downloads.
The descendant cleanup check needs OS permission to enumerate processes; a sandbox
that denies that capability cannot establish descendant cleanup. Runtime evidence
records cleanup unavailability while preserving the FAILED timeout result.
A separate local Maven 3.9.16 bootstrap check also completed the fixed offline
`test` goal on a dependency-free POM; it does not establish application test coverage.

Run `bash harness/test.sh` to compile the isolated Java harness and execute all
ten groups: `HarnessTest`, `SourceContractTest`, `ArtifactContractTest`,
`BrokerTest`, `RuntimeTest`, `ImplementationRuntimeTest`, `ValidationRuntimeTest`, `LiveLauncherTest`, `MigrationConfigLoaderTest`, and `ProviderTransportTest`. Tests use mocks and
temporary fixtures only; they do not resolve dependencies, execute project build
scripts or call an API. The validation group launches the fixed local JDK worker.

## First live fixture invocation

The host launcher requires JDK 21+ and the same cached Jackson and SnakeYAML 2.6
jars as `test.sh` (or explicit host `HARNESS_JACKSON_CLASSPATH` and
`HARNESS_YAML_CLASSPATH`). It never downloads dependencies.
Prepare a fresh fixture under a non-Git directory:

```bash
bash harness/live.sh prepare /private/tmp
```

This prints the absolute path of a new `controlled-live-*/migration.json`. The
source contains only `src/LegacyOperation.java`; its directories and file have no
write permission bits. The disposable target initially contains `pom.xml` and
`src/TargetConventions.java`. The POM is existing synthetic descriptive metadata;
its declared dependency is never resolved and no Maven/Spring/Gradle build runs.
The fixed code in `LiveFixture.java` is reviewed host authority: an identity method,
a String record, a pure mapper/service and one assertion, with no I/O or process calls.
The same four approved strings supply both independent static predicates and
`CONTROLLED_JAVA_CONTRACT_TEST` source pinning. They are never loaded from target
files or caller JSON. Static rules require 02 to plan the four component decisions
in domain, mapper, service, test order, using their exact responsibility descriptions
and paths; existing semantic ownership and traceability checks still apply.

With `OPENAI_API_KEY` already set privately in the host environment, run:

```bash
bash harness/live.sh run /absolute/path/to/agentic-api-builder \
  /private/tmp/controlled-live-XXXXXXXX/migration.json \
  "$LIVE_MODEL" 16384
```

`LIVE_MODEL` is a host-selected Responses model ID with sufficient context/output
capacity and account access. The CLI accepts exactly trusted root, UTF-8 JSON/YAML input,
model and positive max-output-token count; no credential, endpoint, static-authority,
profile, command or environment override is accepted from migration JSON/YAML or the model.
Credentials come only from `HttpResponsesClient.fromEnvironment()` reading
`OPENAI_API_KEY`. Do not put keys in config, command arguments, transcripts or files.
The launcher constructs the full six-argument pipeline and runs MASTER → 00–07,
emitting screened JSON evidence to stdout. Exit codes are 0 SUCCESS, 2 BLOCKED,
and 1 FAILED/launch rejection. Raw exception details are suppressed.

Use a fresh prepared target for every attempt; accepted or unaccepted mutations
remain in the disposable target, and no rollback is performed. The source stays
read-only throughout the run. No provider call is made by `prepare` or the tests.
Before the first live call, supply a valid environment key, select an accessible
model and allow HTTPS access to `api.openai.com`. Provider transport compatibility,
model contract compliance, context/output capacity and the complete live result
remain unproven until that invocation. The existing 30-second request timeout and
96-turn bound remain unchanged.

## Migration config formats

`LiveLauncher` selects serialization only from the host-provided filename's
case-sensitive `.json`, `.yaml`, or `.yml` extension. Other extensions reject;
contents cannot select a parser or provider. JSON continues through the existing
strict parser with exact bytes and fingerprints unchanged.

YAML uses pinned host-side `org.yaml:snakeyaml:2.6`, read from the local Maven cache
or the host's `HARNESS_YAML_CLASSPATH`. No runtime dependency download occurs.
Only its syntax-event parser is used: no YAML object constructors, class loading,
implicit type resolver, or application object binding. The loader emits bounded
strict JSON and calls the existing `MigrationInput.fromJson` validation path.
The resulting `MigrationInput` retains normalized JSON text/bytes and their
fingerprint; YAML comments, formatting and original byte identity are not retained.
Equivalent JSON and YAML have equal routing and caller-data semantics, while
serialization fingerprints need not match.

For example, the existing flat routing form can be written as:

```yaml
sourceProjectPath: /absolute/source
targetProjectPath: /absolute/target
sourceOperationName: readItem
```

The nested `migration.settings` form is also supported. Existing required string,
absolute normalized path, non-overlapping root and secret-screening checks apply
unchanged. Unknown fields are retained as untrusted caller data, exactly as in
existing JSON; a misspelled required routing field still rejects. They cannot
select providers, endpoints, credentials, static authority, validation profiles,
filesystem grants or executables/commands. These remain host-owned. There is no
environment interpolation, expression execution, file inclusion or URL fetching.

Scalar rules are fixed and independent of YAML version directives:

- Quoted and block scalars are strings, including quoted `"123"` and `"true"`.
- Plain `true`, `false`, `null`, and JSON-number syntax use existing JSON types,
  including integers and decimal values. Empty plain values become null; required
  routing values still reject null, booleans and numbers.
- Other plain scalars are strings: `yes`, `on`, `TRUE`, `~`, `012`, `0x10`, `.nan`
  and dates have no special YAML typing. Quote them to make string intent explicit.
- Mapping keys must be strings; boolean/null/number-looking keys must be quoted.
  Duplicate decoded keys reject at every level, including keys whose first value
  is null. Lists and maps normalize to the same JSON containers.

Explicit tags (including standard and Java/object tags), anchors, aliases,
merge keys, complex keys, YAML version/tag directives, malformed input and multiple
documents reject. YAML is bounded to 1 MiB of UTF-8 input and normalized JSON,
50 nested levels and 100,000 scalar/container/key nodes. Existing JSON limits and
validation may reject sooner. Parser errors are replaced with a fixed rejection
code; normal launcher diagnostics contain no source excerpts or raw exceptions.

Run only the offline config and launcher-input checks with:

```bash
bash harness/test.sh migration-input
```

This mode makes no provider/network calls and runs no validation worker or
canonical suite. YAML support makes no OpenCode/company integration or real
provider E2E claim.

## Original FOUNDATION_ONLY profile (still supported)

The remainder of this document describes the original `FOUNDATION_ONLY` profile
unless explicitly stated otherwise. The current `ControlledPipeline` execution
profile and its supported brokers/gates are described above.

`FOUNDATION_ONLY` supplies host evidence for a subsequent pre-analysis
instruction-discovery review. This profile does not run the migration workflow, implement
MASTER's acceptance predicates, issue `DISCOVERY_CONTROL_CHECK_V1`, or authorize
source or target content access. The live launcher above uses the controlled execution profile.

`ControlledHarness` owns one in-memory state, a fixed trusted chain, an immutable
request configuration, a target broker, an optional source metadata boundary,
response lineage, and reached evidence.
Its inspector reads those same objects. Inspection snapshots cannot change them.
Host material is labeled `HOST_EVIDENCE_V1`; it is not an accepted orchestration
artifact, an execution permission, or authenticated runtime evidence.

## Trust and instruction selection

The embedding host explicitly designates an absolute trusted specifications root
and a separate absolute target root. The five-argument constructor additionally
accepts an explicit source root for future `SOURCE_TO_TARGET` execution; the
existing four-argument constructor leaves source access unavailable. These
designations are host trust decisions:
path names and hashes cannot authenticate the original provenance of copied
bytes. A source, target, model response, or tool result must never supply these arguments
or implement the trusted `ResponsesClient` seam.

The chain contains the exact shared contract, MASTER, and specifications 00–07.
All ten documents are resolved and retained before any request. The role registry
assembles MASTER, 00, then 01–07; profile bindings use the contract's sorted role
order and cover all nine roles. Agent 00 has exact path
`agents/00-source-analysis.md` and artifact role `AGENT_00_SPECIFICATION`.
Symlinks below
the trusted root, hard-linked specification files, overlapping roots, malformed UTF-8,
and changed trusted-file identity/bytes block. Target aliases are rejected using root
metadata before any trusted instruction bytes are loaded. The host preamble selects only the active
role; the other specifications remain inactive references. No `AGENTS.md` loader,
cwd-based lookup, repository-selected configuration, or additional tool registration
exists. Every continuation explicitly includes the identical `instructions`
payload and uses the retained `previous_response_id`. There is no resume/import
operation and no fallback to a new context.

In `FOUNDATION_ONLY`, the registered model tool list is intentionally empty. Inspection is a
direct host interface. Function dispatch and model-visible target data tools are
future work, requiring their own protocol integration. Changing roles prepares
the next sequential role within the same logical context; execution of every
specialist remains blocked in this profile. Role selection never grants plan,
dispatch, source-read, target-read, mutation, or validation authority.

## Metadata and access

In `FOUNDATION_ONLY`, access starts `CLOSED`. The explicit host metadata route alone can enter
`METADATA_ONLY`. Content reads, mutation, and validation remain unavailable even
after local `DISCOVERY_GATE_READY`. A denied transition closes the broker and
terminates the harness; neither can silently reopen.

When the host designates source, `SourceBoundary` checks source, trusted, and
target directory metadata before any trusted bytes are loaded. All three roots
must be existing absolute, normalized NFC POSIX paths without symlink components
or aliases. Source must be disjoint from both other roots: equality or ancestor
overlap rejects, using both resolved path containment and root/ancestor filesystem
identities. Source has only `READ_ONLY` authority and `CLOSED`/`METADATA_ONLY`
states. It has no target phase enum, mutation API, content reader, or Git
classification. The target broker's existing metadata and access rules remain
unchanged.

`inspectSourceLocator(Path)` is a direct host metadata interface. It accepts an
existing contained absolute or relative file/module locator, rejects traversal,
symlinks, regular-file hardlinks, colon/reverse-solidus spellings, and escape from
the source root, and returns relative path/identity/kind metadata without reading
content. Locators are host `Path` values; this is not a canonical artifact path
parser or migration scope authorization. Root/ancestor identities are rechecked
at host boundaries, and failures close both access controllers. Source designation
and exact metadata fingerprints are retained in host configuration/evidence and
inspection, separately from target metadata. Source files, including AGENTS-like
files, cannot extend the trusted chain.

`FOUNDATION_ONLY` implements a testable source metadata boundary only. It does not emit an
accepted `SOURCE_READ_ONLY_CONTROL_V1` declaration or `SOURCE_ACCESS_CHECK_V1`
PASS, establish source discovery control,
or supply a source content broker. It does not run agent 00 or produce
`source-analysis.json`. Directory checks cannot exclude concurrent replacement,
other processes, alternate filesystem behavior, or filesystem races; they are
not OS isolation or race-free confinement.

The lifecycle is `INITIALIZED` → `TRUSTED_INPUTS_FROZEN` →
`TARGET_METADATA_REGISTERED` → `MASTER_CONTEXT_CREATED` →
`CREATION_EVIDENCE_CAPTURED` → `DISCOVERY_GATE_READY`. Creation evidence requires
a completed correlated response, retrieval, and input-item readback. A MASTER
continuation repeats those checks with the retained previous response. Missing
capabilities or trust drift end in `BLOCKED`; API/correlation failures end in
`FAILED`; explicit shutdown ends in `CLOSED`. Terminal contexts cannot resume.

The target metadata route uses no subprocesses. It accepts existing, absolute,
normalized, NFC POSIX roots with no symlink components and encodes identity as
`POSIX:<device-base10>:<inode-base10>`. Windows and unavailable identity block.
Non-Git roots are supported. Git support is deliberately restricted to direct
ordinary `.git` directories with bounded UTF-8 `HEAD` and `config`, safe `objects`
and `refs` directories, format 0, and a small core-only configuration whitelist.
That layout has the containing directory as its worktree root. Gitfiles, linked
worktrees, bare repositories, enclosing repositories, includes, extensions,
worktree overrides, and other configuration sections block. Even an ordinary
clone with remote configuration is outside this initial subset.

Only filesystem metadata and those two Git metadata files are read. No source,
README, AGENTS file, index, hook, or build descriptor is loaded. Root/ancestor and
Git metadata identity are rechecked at runtime boundaries. These checks describe
points in time; they do not exclude concurrent writers, transient replacement,
hard-link creation after inspection, or filesystem races.

## API boundary and evidence

The ordinary supported REST Responses API is used through Java's `HttpClient`.
No new SDK is installed. JSON uses locally supplied Jackson core/databind 3.1.5
and Jackson annotations 2.21 jars. The test script reads their standard paths under
`~/.m2/repository`, or the colon-separated `HARNESS_JACKSON_CLASSPATH` override.
See [dependency setup](../README.md#offline-validation). It never
resolves/downloads dependencies or requires a repository application build.

Official documentation consulted for this implementation:

- [Create response](https://developers.openai.com/api/reference/java/resources/responses/methods/create)
- [Retrieve response](https://developers.openai.com/api/reference/java/resources/responses/methods/retrieve)
- [List input items](https://developers.openai.com/api/reference/java/resources/responses/subresources/input_items/methods/list)
- [Conversation continuity](https://developers.openai.com/api/docs/guides/conversation-state)
- [Function calling and strict schemas](https://developers.openai.com/api/docs/guides/function-calling)
- [API errors](https://developers.openai.com/api/docs/guides/error-codes)
- [SDK usage](https://developers.openai.com/api/docs/libraries)

Creation, response retrieval, and ascending paginated input-item retrieval are
separate observations. Input-item listings may contain history and server-added
fields; they are not assumed to reproduce the original request byte for byte.
The host retains exact request text and safe response/readback text, correlates
the current host turn marker, and compares required effective request fields.
Every returned history item must match an ordered, unique member of the retained
request/response chain. Unknown users, altered assistant/reasoning items, tool or
child items, duplicate or reordered history, and extra instructions fail closed.
Historical items may be omitted by the listing; their absence is not claimed as
proof that they were never loaded. At most one leading instruction message with
the exact frozen payload is supported. Unrecognized readback forms block.
Model identifiers must match exactly; use a supported explicit model identifier
whose readback matches. Alias normalization can intentionally block.

Credentials enter only the transport constructor or `OPENAI_API_KEY` factory.
They are absent from runtime configuration and evidence. Known credentials and
common secret formats are rejected before retention; unsafe bodies and raw
exception messages are never retained or hashed for publication. This is not a
universal secret detector. The trusted host must supply non-secret instruction
inputs. Evidence is local current-session material and should be reviewed before
publication; paths and model output may themselves be sensitive.
Sensitive JSON field names carry credential context through every nested object
and array value. Empty structure with only null or empty-string leaves does not
by itself contain a credential value. For example,
`{"authorization":{"callerAuthorization":{},"checkpointReference":""}}`
is an inspectable specification shape; replacing either empty value with a
nonempty string, number, or boolean rejects. Whitespace and named placeholders
such as `"<redacted>"` remain ambiguous values and reject in credential context.
Every field name and string still receives configured-credential, recognized
secret-pattern, and decoded-fragment inspection. There is no document/path
exemption or sensitive-key whitelist. Arbitrary unknown material disguised only
as an ordinary field name cannot be distinguished from schema field names;
configured or recognizable secrets in field names still reject. Independent
prose-pattern checks remain conservative, including rejection of `password=null`
and `{"password":null}`.
JSON fragments in prose and markdown fences are inspected structurally, including
quoted JSON strings, decoded Unicode keys/values, and multiple objects/arrays.
Extraction tracks matching brackets and escaped quotes; `{placeholder}` and
`[README]` remain ordinary prose. Recognizable malformed JSON, invalid escapes,
and ambiguous quotes that overlap a JSON opener are rejected conservatively.
An escape-bearing prose gap after a closing quote also rejects: that quote could
otherwise be interpreted as opening an encoded credential string. Ordinary prose
quote gaps remain supported; valid JSON strings are decoded and inspected.
Each inspection has limits of 64 nesting levels, 4 Mi characters per fragment,
4,096 fragments, 100,000 visited values, and 32 Mi cumulative inspected/parsed
characters. Exceeding a limit fails closed. Failed raw
payload retention never substitutes a hash of rejected secret material. A failed
inspection exposes an explicit unavailable view instead of rejected configuration.

All fingerprints use the shared four-field SHA-256 representation. Original
text fingerprints cover exact UTF-8 bytes. Host JSON has explicit schema member
order, no floats or trailing newline, and deterministic encoding. These objects
remain provider-specific material, not newly invented canonical orchestration
schemas. Hashes provide identity and comparison, never authenticity.
The retained event count and complete evidence identity also detect removal,
reordering, or alteration of in-memory evidence; they are not tamper-proof storage.

## Focused checks

From this repository, run only:

```sh
bash harness/test.sh
```

This compiles the harness and runs all seven check groups in an isolated temporary
directory. Runtime checks use mocked transports and fixture
repositories. Contract checks parse the authoritative Markdown JSON structures,
check normative rules and exact trusted fingerprints, and verify that variable
caller data is representable. They also scan the complete current `MASTER.md`
and freeze the actual ten-document trusted chain through the public host path,
with transport methods that fail if called. They do not execute source discovery
or planning themselves; the runtime groups execute the mocked controlled route.
The script does not invoke Maven/Gradle or call the network.
Its validation group compiles and executes only host-approved
temporary fixture code through the fixed profile. Fixtures
are created beneath the canonical `${TMPDIR:-/tmp}` directory,
outside any real target. No API key is required or read by these tests.

## FOUNDATION_ONLY limitations

Live runtime evidence, canonical declaration/check construction and MASTER
acceptance, accepted gate delivery to specialists, migration authority bundles,
specialist dispatch, source and target content tools, mutation, validation, crash recovery,
and durable evidence storage are outside `FOUNDATION_ONLY`. The current execution
profile implements the mocked 00–07 route described above; live E2E,
the YAML adapter, Git-state support, and durability/resume remain unavailable. Local foundation readiness means
the retained MASTER creation/readback and fresh host observations are available
for that later review. It does not mean the discovery gate passed or E2E is ready.

The pre-existing `MASTER.md` scanner incompatibility is repaired: the old
populated-container check rejected the empty reconciliation authorization shape
shown above solely because its outer object contained field names. The full
current `MASTER.md` and complete trusted chain now pass local secret inspection
and public host freeze. Offline regression checks retain the exact instruction
payload and verify `TRUSTED_INPUTS_FROZEN`, `protocolAcceptance: NOT_EVALUATED`,
target access `CLOSED`, and no request, response, item, or discovery events. This
establishes instruction/scanner compatibility only; it does not establish live
launcher operation, discovery gate PASS, specialist execution, or E2E readiness.
