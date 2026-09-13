# Controlled Responses harness foundation

This isolated Java 21 library supplies host evidence for a **future** pre-01
instruction-discovery review. It does not run the migration workflow, implement
MASTER's acceptance predicates, issue `DISCOVERY_CONTROL_CHECK_V1`, or authorize
target content access. There is deliberately no executable live launcher.

`ControlledHarness` owns one in-memory state, a fixed trusted chain, an immutable
request configuration, a target broker, response lineage, and reached evidence.
Its inspector reads those same objects. Inspection snapshots cannot change them.
Host material is labeled `HOST_EVIDENCE_V1`; it is not an accepted orchestration
artifact, an execution permission, or authenticated runtime evidence.

## Trust and instruction selection

The embedding host explicitly designates an absolute trusted specifications root
and a separate absolute target root. This designation is a host trust decision:
path names and hashes cannot authenticate the original provenance of copied
bytes. A target, model response, or tool result must never supply these arguments
or implement the trusted `ResponsesClient` seam.

The chain contains the exact shared contract, MASTER, and specifications 01–07.
All nine documents are resolved and retained before any request. Symlinks below
the trusted root, hard-linked source files, overlapping roots, malformed UTF-8,
and changed source identity/bytes block. Target aliases are rejected using root
metadata before any trusted source bytes are loaded. The host preamble selects only the active
role; the other specifications remain inactive references. No `AGENTS.md` loader,
cwd-based lookup, target-selected configuration, or additional tool registration
exists. Every continuation explicitly includes the identical `instructions`
payload and uses the retained `previous_response_id`. There is no resume/import
operation and no fallback to a new context.

The registered model tool list is intentionally empty. Inspection is currently a
direct host interface. Function dispatch and model-visible target data tools are
future work, requiring their own protocol integration. Changing roles prepares
the next sequential role within the same logical context; execution of every
specialist remains blocked in this milestone. Role selection never grants plan,
dispatch, target-read, mutation, or validation authority.

## Metadata and access

Access starts `CLOSED`. The explicit host metadata route alone can enter
`METADATA_ONLY`. Content reads, mutation, and validation remain unavailable even
after local `DISCOVERY_GATE_READY`. A denied transition closes the broker and
terminates the harness; neither can silently reopen.

The lifecycle is `INITIALIZED` → `TRUSTED_INPUTS_FROZEN` →
`TARGET_METADATA_REGISTERED` → `MASTER_CONTEXT_CREATED` →
`CREATION_EVIDENCE_CAPTURED` → `DISCOVERY_GATE_READY`. Creation evidence requires
a completed correlated response, retrieval, and input-item readback. A MASTER
continuation repeats those checks with the retained previous response. Missing
capabilities or trust drift end in `BLOCKED`; API/correlation failures end in
`FAILED`; explicit shutdown ends in `CLOSED`. Terminal contexts cannot resume.

The metadata route uses no subprocesses. It accepts existing, absolute,
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

This compiles and runs only the new harness and its standalone test class in an
isolated temporary directory. It does not invoke Maven/Gradle, compile application
sources, run project tests, execute a migration role, or call the network. Fixtures
are created beneath `/private/tmp` when available (otherwise canonical `/tmp`),
outside any real target. No API key is required or read by these tests.

## Remaining work

Live runtime evidence, canonical declaration/check construction and MASTER
acceptance, accepted gate delivery to specialists, migration authority bundles,
specialist dispatch, target content tools, mutation, validation, crash recovery,
and durable evidence storage are outside this milestone. Local readiness means
the retained MASTER creation/readback and fresh host observations are available
for that later review. It does not mean the discovery gate passed or E2E is ready.
