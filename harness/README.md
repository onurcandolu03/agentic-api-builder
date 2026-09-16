# Controlled Responses harness

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
`ControlledHarness.Config`, and a host-supplied `ResponsesClient`.
`ControlledPipeline.runJson(...)` blocks missing routing before provider access.
There is no live CLI or credential loading in this launcher. `MigrationInput`
reuses cached Jackson JSON parsing, preserves exact caller UTF-8 bytes and their
fingerprint, and retains unknown structured fields, including decimal values.
It recognizes `migration.settings.sourceProjectPath`, `targetProjectPath`, and
`sourceOperationName` (also their flat equivalents). Additional fields remain
caller data for specialists; they do not grant runtime authority. A safe YAML
adapter remains future launcher work. No dependencies were added.

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
working root, empty environment, five-second timeout and output scopes are
host-owned. There is no arbitrary shell, command/argument/cwd/env input, stdin,
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

Each process stream retains at most 8 KiB (16 KiB total), drains excess output,
and reports retained-byte fingerprints, observed counts, truncation and completion.
No raw build log excerpts enter artifacts. Retained validation facts have a 256 KiB
cap; oversized effect sets retain a fingerprint, count and bounded examples, mark
full evidence unavailable, and cannot pass. Evidence bounds never downgrade a known
FAILED process/effect outcome to BLOCKED. Terminal observations preserve previously
observed unauthorized effects even if a later callback restores the files. Timeout kills the process and observed
descendants. Nonzero exit and timeout remain FAILED; inability to safely launch is
BLOCKED. Unknown/incomplete execution or repository observations cannot pass.

The strict `VALIDATION_RESULT` binds the authority, invocation, predecessor lineage,
host execution reference, exit/timeout, exact observed repository effects, status
and failures. Host facts override model claims. MASTER receives the result and
independent evidence, and its acceptance is required for final SUCCESS. Host
execution facts and effects survive later journal/report rejection in the terminal
summary. No rollback or durable crash recovery is claimed. Live provider E2E
remains unproven; this is not production readiness.

Run `bash harness/test.sh` to compile the isolated Java harness and execute all
seven groups: `HarnessTest`, `SourceContractTest`, `ArtifactContractTest`,
`BrokerTest`, `RuntimeTest`, `ImplementationRuntimeTest`, and `ValidationRuntimeTest`. Tests use mocks and
temporary fixtures only; they do not resolve dependencies, execute project build
scripts or call an API. The validation group launches the fixed local JDK worker.

## Original FOUNDATION_ONLY profile (still supported)

The remainder of this document describes the original `FOUNDATION_ONLY` profile
unless explicitly stated otherwise. The current `ControlledPipeline` execution
profile and its supported brokers/gates are described above.

`FOUNDATION_ONLY` supplies host evidence for a subsequent pre-analysis
instruction-discovery review. This profile does not run the migration workflow, implement
MASTER's acceptance predicates, issue `DISCOVERY_CONTROL_CHECK_V1`, or authorize
source or target content access. There is deliberately no executable live launcher.

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
No new SDK is installed. JSON uses the Jackson 3.1.5 jars already established by
the repository's Spring Boot dependency setup; `pom.xml` is unchanged. The test
script needs those already cached jars and never resolves/downloads dependencies.

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
The script does not invoke Maven/Gradle, run this repository’s application tests,
or call the network. Its validation group compiles and executes only host-approved
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
