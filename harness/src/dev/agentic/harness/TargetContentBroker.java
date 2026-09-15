package dev.agentic.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Content capability complement; TargetBroker's metadata/discovery transition remains closed. */
final class TargetContentBroker {
    record WriteGrant(String grantId, String invocationId, String role, String rootIdentity,
                      String pathKey, String action, Map<String, Object> expectedBeforeState,
                      Map<String, Object> authority) {
        WriteGrant {
            for (String value : List.of(grantId, invocationId, role, rootIdentity)) Json.identifier(value);
            expectedBeforeState = ExecutionPlan.map(Json.object("state", Objects.requireNonNull(expectedBeforeState)).get("state"));
            authority = ExecutionPlan.map(Json.object("authority", Objects.requireNonNull(authority)).get("authority"));
        }
        Map<String,Object> view() {
            return Json.object("grantId", grantId, "invocationId", invocationId, "role", role,
                    "rootFilesystemIdentity", rootIdentity, "pathKey", pathKey, "action", action,
                    "expectedBeforeState", expectedBeforeState, "authority", authority);
        }
    }
    private static final Set<String> IMPLEMENTATION_ROLES = Set.of("03-domain-contract-implementation",
            "04-persistence-mapping-implementation", "05-service-api-implementation", "06-test-implementation");
    private final TargetBroker metadata;
    private final RepositoryFiles files;
    private final RepositoryFiles.Limits limits;
    private final Consumer<String> credentialCheck;
    private boolean authorized, closed, busy;
    private String invocationId, activeRole;
    private int operations, returnedBytes;
    private Set<String> readablePaths = Set.of();
    private Map<String, WriteGrant> writes = Map.of();
    private final Set<String> completedWrites = new HashSet<>(), invocations = new HashSet<>(), issuedGrants = new HashSet<>();
    private final List<Map<String,Object>> mutations = new ArrayList<>();

    TargetContentBroker(TargetBroker metadata, RepositoryFiles.Limits limits, Set<String> protectedPaths,
                        Set<String> excludedPaths, Consumer<String> credentialCheck) throws IOException {
        this.metadata = Objects.requireNonNull(metadata); this.limits = limits; this.credentialCheck = credentialCheck; metadata.verify();
        files = new RepositoryFiles(Path.of(metadata.metadata().resolvedRoot()), metadata.metadata().rootFilesystemIdentity(),
                limits, protectedPaths, excludedPaths, value -> {
                    credentialCheck.accept(value);
                    if (closed) throw new IllegalStateException("TARGET_CONTENT_CLOSED");
                });
    }

    /** Package-private host grant; provider data cannot call this or grant itself a phase. */
    void authorizeDiscovery(String expectedRootIdentity, Map<String, Object> declarationFingerprint,
                            Map<String, Object> checkFingerprint) throws IOException {
        try {
            idle(); metadata.verify();
            if (authorized || !metadata.metadata().rootFilesystemIdentity().equals(expectedRootIdentity))
                throw new IOException("TARGET_DISCOVERY_GRANT_INVALID");
            SourceBroker.fingerprint(declarationFingerprint, "RUNTIME_CAPABILITY_DECLARATION");
            SourceBroker.fingerprint(checkFingerprint, "DISCOVERY_CONTROL_CHECK_V1");
            authorized = true;
        } catch (IOException | RuntimeException rejected) { close(); throw new IOException("TARGET_DISCOVERY_GRANT_REJECTED"); }
    }

    /** Caller is the trusted coordinator after exact plan, role, predecessor and before-state acceptance. */
    void activate(String invocationId, String role, Set<String> readablePaths, List<WriteGrant> writes) throws IOException {
        try {
            idle(); busy = true; requireAuthorized(); Json.identifier(invocationId);
            if (this.invocationId != null || !invocations.add(invocationId)
                    || !(role.equals("01-target-analysis") || role.equals("MASTER") || role.equals("07-validation")
                    || IMPLEMENTATION_ROLES.contains(role))) throw new IOException("TARGET_ROLE_DENIED");
            if (!writes.isEmpty() && (!IMPLEMENTATION_ROLES.contains(role) || !metadata.metadata().repositoryKind().equals("NON_GIT")))
                throw new IOException("TARGET_MUTATION_PHASE_OR_GIT_UNSUPPORTED");
            Set<String> reads = new HashSet<>(), collisionKeys = new HashSet<>();
            for (String path : readablePaths) {
                RepositoryFiles.canonicalPath(path);
                if (files.prohibited(path) || !collisionKeys.add(path.toLowerCase(Locale.ROOT)))
                    throw new IOException("TARGET_READ_SCOPE_REJECTED");
                reads.add(path);
            }
            Map<String, WriteGrant> grants = new HashMap<>();
            for (WriteGrant grant : writes) {
                RepositoryFiles.canonicalPath(grant.pathKey());
                if (!invocationId.equals(grant.invocationId()) || !role.equals(grant.role())
                        || !metadata.metadata().rootFilesystemIdentity().equals(grant.rootIdentity())
                        || !issuedGrants.add(grant.grantId())) throw new IOException("TARGET_GRANT_BINDING_OR_REPLAY");
                if (files.prohibited(grant.pathKey()) || !Set.of("CREATE", "MODIFY").contains(grant.action())
                        || !grant.pathKey().equals(grant.expectedBeforeState().get("pathKey"))
                        || grants.put(grant.pathKey(), grant) != null)
                    throw new IOException("TARGET_WRITE_GRANT_REJECTED");
                if (!reads.contains(grant.pathKey()) && !collisionKeys.add(grant.pathKey().toLowerCase(Locale.ROOT)))
                    throw new IOException("TARGET_WRITE_PATH_COLLISION");
                Map<String, Object> observed = files.state(grant.pathKey(), true);
                if (!observed.equals(grant.expectedBeforeState()) || (grant.action().equals("CREATE")
                        ? !observed.get("existence").equals("ABSENT") : !observed.get("fileType").equals("REGULAR_FILE")))
                    throw new IOException("TARGET_WRITE_BEFORE_STATE_REJECTED");
                reads.add(grant.pathKey());
            }
            requireAuthorized(); this.invocationId = invocationId; activeRole = role;
            this.readablePaths = Set.copyOf(reads); this.writes = Map.copyOf(grants);
            completedWrites.clear(); operations = 0; returnedBytes = 0;
        } catch (IOException | RuntimeException rejected) { close(); throw new IOException("TARGET_INVOCATION_REJECTED"); }
        finally { busy = false; }
    }

    Map<String, Object> execute(String invocationId, String role, String operation, String path, String query,
                                Map<String, Object> expectedBeforeFingerprint, String content, String grantId) throws IOException {
        try {
            idle(); current(invocationId, role); busy = true;
            if (++operations > limits.maxOperations()) throw new IOException("TARGET_OPERATION_LIMIT");
            boolean mutation = operation.equals("WRITE_TARGET_TEXT") || operation.equals("CREATE_TARGET_FILE");
            if (mutation ? query != null : grantId != null || content != null || expectedBeforeFingerprint != null
                    || query != null && !operation.equals("SEARCH_TARGET_TEXT") && !operation.equals("SEARCH_TEXT"))
                throw new IOException("TARGET_UNUSED_TOOL_ARGUMENT");
            Object result;
            if (mutation) {
                WriteGrant grant = writes.get(path);
                String action = operation.equals("CREATE_TARGET_FILE") ? "CREATE" : "MODIFY";
                if (!IMPLEMENTATION_ROLES.contains(role) || grant == null || !grant.action().equals(action)
                        || !Objects.equals(grantId, grant.grantId())
                        || !invocationId.equals(grant.invocationId()) || !role.equals(grant.role())
                        || !metadata.metadata().rootFilesystemIdentity().equals(grant.rootIdentity())
                        || !completedWrites.add(grantId)) throw new IOException("TARGET_WRITE_DENIED");
                String parent = RepositoryFiles.parentKey(path);
                Map<String,Object> parentBefore = files.state(parent, false);
                result = files.write(path, action, grant.expectedBeforeState(), expectedBeforeFingerprint, content);
                // Retain effects before any result-delivery failure can close this broker.
                mutations.add(Json.object("grant", grant.view(), "beforeState", grant.expectedBeforeState(),
                        "afterState", result, "exactAfterText", content));
                Map<String,Object> parentAfter = files.state(parent, false);
                if (!parentBefore.equals(parentAfter)) {
                    if (!action.equals("CREATE") || !RepositoryFiles.createParentTransition(path, parentBefore, parentAfter))
                        throw new IOException("TARGET_PARENT_EFFECT_REJECTED");
                    // This is an observed consequence of the CREATE, never directory mutation authority.
                    Map<String,Object> retained = new LinkedHashMap<>(mutations.getLast());
                    retained.put("parentMetadataEffect", Json.object("beforeState", parentBefore, "afterState", parentAfter));
                    mutations.set(mutations.size() - 1, ExecutionPlan.map(Json.object("mutation", retained).get("mutation")));
                }
            } else {
                if (!(role.equals("01-target-analysis") || role.equals("MASTER")) && !readablePaths.contains(path))
                    throw new IOException("TARGET_READ_PATH_DENIED");
                result = switch (operation) {
                    case "READ_TARGET_TEXT", "READ_TEXT" -> Json.object("text", RepositoryFiles.text(files.readBytes(path)), "complete", true, "truncated", false);
                    case "LIST_TARGET_PATHS", "LIST_PATHS" -> {
                        if (!(role.equals("01-target-analysis") || role.equals("MASTER"))) throw new IOException("TARGET_LIST_PHASE_DENIED");
                        yield files.list(path);
                    }
                    case "SEARCH_TARGET_TEXT", "SEARCH_TEXT" -> files.search(path, query);
                    case "INSPECT_TARGET_METADATA", "INSPECT_METADATA" -> files.state(path, false);
                    default -> throw new IOException("TARGET_OPERATION_DENIED");
                };
            }
            var envelope = Json.object("dataClassification", "UNTRUSTED_DATA", "operation", operation,
                    "invocationId", invocationId, "role", role, "scopeIdentity", metadata.metadata().rootFilesystemIdentity(),
                    "canonicalScope", files.canonicalScope(path), "success", true, "result", result,
                    "evidenceFingerprint", Json.evidenceFingerprint(result));
            files.safe(envelope); current(invocationId, role);
            returnedBytes = Math.addExact(returnedBytes, Json.bytes(envelope).length);
            if (returnedBytes > limits.maxReturnedBytes()) throw new IOException("TARGET_RETURN_BYTE_LIMIT_INCOMPLETE");
            return envelope;
        } catch (IOException | RuntimeException rejected) { close(); throw new IOException("TARGET_CONTENT_REJECTED"); }
        finally { busy = false; }
    }

    RepositoryFiles.Snapshot hostSnapshot(Set<String> additionalPaths) throws IOException {
        try {
            idle(); requireAuthorized(); busy = true;
            if (!metadata.metadata().repositoryKind().equals("NON_GIT")) throw new IOException("SEMANTIC_GIT_INDEX_UNIMPLEMENTED");
            RepositoryFiles.Snapshot snapshot = files.snapshot(additionalPaths);
            if (!snapshot.equals(files.snapshot(additionalPaths))) throw new IOException("TARGET_OBSERVATION_CHANGED");
            requireAuthorized(); return snapshot;
        } catch (IOException | RuntimeException rejected) { close(); throw new IOException("TARGET_SNAPSHOT_REJECTED"); }
        finally { busy = false; }
    }
    RepositoryFiles.Snapshot observeTerminatedSnapshot(Set<String> additionalPaths) throws IOException {
        try {
            if (!closed || busy) throw new IOException("TARGET_TERMINAL_OBSERVATION_PHASE");
            busy = true;
            TargetBroker observer = new TargetBroker();
            var current = observer.inspect(Path.of(metadata.metadata().resolvedRoot()));
            if (!current.repositoryKind().equals("NON_GIT") || !current.equals(metadata.metadata()))
                throw new IOException("TARGET_TERMINAL_OBSERVATION_SCOPE");
            var result = files.observeTerminatedSnapshot(additionalPaths, credentialCheck);
            observer.verify(); return result;
        } catch (IOException | RuntimeException rejected) { throw new IOException("TARGET_TERMINAL_OBSERVATION_UNAVAILABLE"); }
        finally { busy = false; }
    }
    List<Map<String,Object>> mutationEvidence() { return List.copyOf(mutations); }
    void endInvocation() throws IOException {
        try { idle(); requireAuthorized(); invocationId = null; activeRole = null; readablePaths = Set.of(); writes = Map.of(); completedWrites.clear(); }
        catch (IOException | RuntimeException rejected) { close(); throw new IOException("TARGET_CONTENT_REJECTED"); }
    }
    void close() { closed = true; authorized = false; invocationId = null; activeRole = null; writes = Map.of(); metadata.close(); }
    private void idle() throws IOException { if (closed || busy) throw new IOException("TARGET_REENTRANT_OR_CLOSED"); }
    private void requireAuthorized() throws IOException {
        if (closed || !authorized) throw new IOException("TARGET_DISCOVERY_GATE_REQUIRED");
        metadata.verify(); files.verify();
    }
    private void current(String invocation, String role) throws IOException {
        requireAuthorized();
        if (this.invocationId == null || !this.invocationId.equals(invocation) || !Objects.equals(activeRole, role))
            throw new IOException("TARGET_INVOCATION_MISMATCH");
    }
}
