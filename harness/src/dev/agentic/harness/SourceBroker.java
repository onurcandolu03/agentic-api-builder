package dev.agentic.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Permanently read-only, host-activated source data route. There is no source mutation operation. */
final class SourceBroker {
    private final SourceBoundary boundary;
    private final RepositoryFiles files;
    private final RepositoryFiles.Limits limits;
    private final Consumer<String> credentialCheck;
    private boolean authorized, closed, busy;
    private String invocationId, activeRole;
    private int operations, returnedBytes;
    private final Set<String> invocations = new HashSet<>();

    SourceBroker(SourceBoundary boundary, RepositoryFiles.Limits limits, Set<String> protectedPaths,
                 Set<String> excludedPaths, Consumer<String> credentialCheck) throws IOException {
        this.boundary = Objects.requireNonNull(boundary); this.limits = limits; this.credentialCheck = credentialCheck;
        boundary.verify();
        files = new RepositoryFiles(Path.of(boundary.metadata().resolvedRoot()), boundary.metadata().rootFilesystemIdentity(),
                limits, protectedPaths, excludedPaths, value -> {
                    credentialCheck.accept(value);
                    if (closed) throw new IllegalStateException("SOURCE_BROKER_CLOSED");
                });
    }

    /** Host calls only after independently verifying and retaining both canonical PASS checks. */
    void authorizeAccess(String expectedRootIdentity, Map<String, Object> discoveryCheckFingerprint,
                         Map<String, Object> sourceCheckFingerprint) throws IOException {
        try {
            idle(); boundary.verify();
            if (authorized || !boundary.metadata().rootFilesystemIdentity().equals(expectedRootIdentity))
                throw new IOException("SOURCE_ACCESS_GRANT_INVALID");
            fingerprint(discoveryCheckFingerprint, "DISCOVERY_CONTROL_CHECK_V1");
            fingerprint(sourceCheckFingerprint, "SOURCE_ACCESS_CHECK_V1");
            authorized = true;
        } catch (IOException | RuntimeException rejected) { close(); throw new IOException("SOURCE_ACCESS_GRANT_REJECTED"); }
    }

    void activate(String invocationId, String role) throws IOException {
        try {
            idle(); requireAuthorized();
            Json.identifier(invocationId);
            if (this.invocationId != null || !"00-source-analysis".equals(role) || !invocations.add(invocationId))
                throw new IOException("SOURCE_INVOCATION_REJECTED");
            this.invocationId = invocationId; this.activeRole = role; operations = 0; returnedBytes = 0;
        } catch (IOException | RuntimeException rejected) { close(); throw new IOException("SOURCE_INVOCATION_REJECTED"); }
    }

    Map<String, Object> execute(String invocationId, String role, String operation, String path, String query) throws IOException {
        try {
            idle(); current(invocationId, role); busy = true;
            if (++operations > limits.maxOperations()) throw new IOException("SOURCE_OPERATION_LIMIT");
            if (query != null && !operation.equals("SEARCH_SOURCE_TEXT") && !operation.equals("SEARCH_TEXT"))
                throw new IOException("SOURCE_UNUSED_TOOL_ARGUMENT");
            Object result = switch (operation) {
                case "READ_SOURCE_TEXT", "READ_TEXT" -> Json.object("text", RepositoryFiles.text(files.readBytes(path)), "complete", true, "truncated", false);
                case "LIST_SOURCE_PATHS", "LIST_PATHS" -> files.list(path);
                case "SEARCH_SOURCE_TEXT", "SEARCH_TEXT" -> files.search(path, query);
                case "INSPECT_SOURCE_METADATA", "INSPECT_METADATA" -> files.state(path, false);
                default -> throw new IOException("SOURCE_OPERATION_DENIED");
            };
            var envelope = Json.object("dataClassification", "UNTRUSTED_DATA", "operation", operation,
                    "invocationId", invocationId, "role", role, "scopeIdentity", boundary.metadata().rootFilesystemIdentity(),
                    "canonicalScope", files.canonicalScope(path), "success", true, "result", result,
                    "evidenceFingerprint", Json.evidenceFingerprint(result));
            files.safe(envelope); current(invocationId, role);
            returnedBytes = Math.addExact(returnedBytes, Json.bytes(envelope).length);
            if (returnedBytes > limits.maxReturnedBytes()) throw new IOException("SOURCE_RETURN_BYTE_LIMIT_INCOMPLETE");
            return envelope;
        } catch (IOException | RuntimeException rejected) { close(); throw new IOException("SOURCE_BROKER_REJECTED"); }
        finally { busy = false; }
    }

    RepositoryFiles.Snapshot hostSnapshot(Set<String> additionalPaths) throws IOException {
        try {
            idle(); requireAuthorized(); busy = true;
            RepositoryFiles.Snapshot snapshot = files.snapshot(additionalPaths);
            if (!snapshot.equals(files.snapshot(additionalPaths))) throw new IOException("SOURCE_OBSERVATION_CHANGED");
            requireAuthorized(); return snapshot;
        } catch (IOException | RuntimeException rejected) { close(); throw new IOException("SOURCE_SNAPSHOT_REJECTED"); }
        finally { busy = false; }
    }
    RepositoryFiles.Snapshot observeTerminatedSnapshot(Set<String> additionalPaths) throws IOException {
        try {
            if (!closed || busy) throw new IOException("SOURCE_TERMINAL_OBSERVATION_PHASE");
            busy = true;
            TargetBroker observer = new TargetBroker();
            var metadata = observer.inspect(Path.of(boundary.metadata().resolvedRoot()));
            if (!metadata.repositoryKind().equals("NON_GIT")
                    || !metadata.rootFilesystemIdentity().equals(boundary.metadata().rootFilesystemIdentity()))
                throw new IOException("SOURCE_TERMINAL_OBSERVATION_SCOPE");
            var result = files.observeTerminatedSnapshot(additionalPaths, credentialCheck);
            observer.verify(); return result;
        } catch (IOException | RuntimeException rejected) { throw new IOException("SOURCE_TERMINAL_OBSERVATION_UNAVAILABLE"); }
        finally { busy = false; }
    }
    void endInvocation() throws IOException {
        try { idle(); requireAuthorized(); invocationId = null; activeRole = null; }
        catch (IOException | RuntimeException rejected) { close(); throw new IOException("SOURCE_BROKER_REJECTED"); }
    }
    void close() { closed = true; authorized = false; invocationId = null; activeRole = null; boundary.close(); }
    private void idle() throws IOException {
        if (busy || closed) throw new IOException("SOURCE_REENTRANT_OR_CLOSED");
    }
    private void requireAuthorized() throws IOException {
        if (closed || !authorized) throw new IOException("SOURCE_ACCESS_GATE_REQUIRED");
        boundary.verify(); files.verify();
    }
    private void current(String invocation, String role) throws IOException {
        requireAuthorized();
        if (this.invocationId == null || !this.invocationId.equals(invocation) || !Objects.equals(activeRole, role))
            throw new IOException("SOURCE_INVOCATION_MISMATCH");
    }
    static void fingerprint(Map<String, Object> value, String expectedRole) throws IOException {
        if (value == null || !value.keySet().equals(Set.of("algorithm", "digest", "byteLength", "artifactRole"))
                || !"SHA-256".equals(value.get("algorithm")) || !(value.get("digest") instanceof String digest)
                || !digest.matches("[a-f0-9]{64}") || !(value.get("byteLength") instanceof Number length)
                || !(length instanceof Integer || length instanceof Long) || length.longValue() < 1
                || !expectedRole.equals(value.get("artifactRole")))
            throw new IOException("BROKER_GRANT_FINGERPRINT_INVALID");
    }
}
