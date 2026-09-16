package dev.agentic.harness;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One fixed-profile execution. No command construction or environment interface is model-visible. */
final class ValidationRuntime {
    private final ValidationProfile profile;
    private final Path root;
    private final RepositoryFiles source, target;
    private final RepositoryFiles.Snapshot sourceBefore, targetBefore;
    private final Map<String,Object> authority;
    private final Map<String,Object> sourceModes, targetModes;
    private boolean used;
    private Map<String,Object> execution = Json.object("started", false, "completed", false);
    private Map<String,Object> effects = Json.object("classification", "NOT_OBSERVED");
    private RepositoryFiles.Snapshot acceptedAfter;
    private String status = "BLOCKED", code = "VALIDATION_NOT_EXECUTED";

    ValidationRuntime(ValidationProfile profile, Path targetRoot,
                      RepositoryFiles.Snapshot sourceBefore, RepositoryFiles.Snapshot targetBefore,
                      String runId, RoleExecutor.Invocation invocation, ArtifactStore artifacts,
                      RepositoryFiles sourceObserver, RepositoryFiles targetObserver,
                      Map<String,Object> originalSourceModes, Map<String,Object> acceptedTargetModes) throws Exception {
        this.profile = profile; this.root = targetRoot; this.sourceBefore = sourceBefore; this.targetBefore = targetBefore;
        if (invocation.role() != TrustedInputs.Role.VALIDATION) throw new IllegalStateException("VALIDATION_ROLE_DENIED");
        if (!runId.equals(artifacts.required("MIGRATION_PLAN").binding().get("runId")))
            throw new IllegalStateException("VALIDATION_RUN_MISMATCH");
        List<Map<String,Object>> predecessors = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            var a = artifacts.required("IMPLEMENTATION_RESULT:STEP-00" + i);
            if (!List.of(TrustedInputs.Role.DOMAIN, TrustedInputs.Role.PERSISTENCE, TrustedInputs.Role.SERVICE,
                    TrustedInputs.Role.TESTS).get(i - 1).id.equals(a.binding().get("role")) || !runId.equals(a.binding().get("runId")))
                throw new IllegalStateException("VALIDATION_PREDECESSOR_ROLE_MISMATCH");
            predecessors.add(Json.object("fingerprint", a.fingerprint(), "acceptanceInvocationId", a.acceptanceInvocationId(),
                    "acceptanceProviderResponseId", a.acceptanceProviderResponseId()));
        }
        if (!Objects.equals(invocation.predecessorInvocationId(), predecessors.getLast().get("acceptanceInvocationId")))
            throw new IllegalStateException("VALIDATION_STALE_PREDECESSOR");
        source = sourceObserver; target = targetObserver;
        sourceModes = ExecutionPlan.map(Json.object("state", originalSourceModes).get("state"));
        targetModes = ExecutionPlan.map(Json.object("state", acceptedTargetModes).get("state"));
        authority = Json.object("authorityVersion", 1, "runId", runId, "role", invocation.role().id,
                "invocationId", invocation.id(), "authorityId", runId + "-validation-grant", "replayIdentity", invocation.id(),
                "migrationPlanFingerprint", artifacts.required("MIGRATION_PLAN").fingerprint(),
                "implementationResults", predecessors, "predecessorAcceptance", predecessors.getLast(),
                "targetRootIdentity", targetBefore.rootFilesystemIdentity(), "workingRoot", root.toString(),
                "profileId", ValidationProfile.ID, "trustedProfile", profile.view(),
                "beforeSourceModes", sourceModes, "beforeTargetModes", targetModes,
                "beforeSourceFingerprint", Json.evidenceFingerprint(sourceBefore.entries()),
                "beforeTargetFingerprint", Json.evidenceFingerprint(targetBefore.entries()));
        if (Json.bytes(authority).length > ValidationProfile.FACT_BYTES / 2)
            throw new IllegalStateException("VALIDATION_AUTHORITY_EVIDENCE_LIMIT");
    }
    Map<String,Object> authority() { return authority; }
    String status() { return status; }
    String code() { return code; }
    boolean started() { return Boolean.TRUE.equals(execution.get("started")); }
    Map<String,Object> facts() { return Json.object("authority", authority, "execution", execution, "effects", effects, "status", status, "code", code); }

    void precheck() throws Exception {
        try { source.verify(); } catch (Exception e) { block("PRE_VALIDATION_SOURCE_ROOT_CHANGED"); }
        try { target.verify(); } catch (Exception e) { block("PRE_VALIDATION_TARGET_ROOT_CHANGED"); }
        RepositoryFiles.Snapshot s, t;
        try { s = source.snapshot(Set.of()); } catch (Exception e) { block("PRE_VALIDATION_SOURCE_UNSAFE"); return; }
        try { t = target.snapshot(Set.of()); } catch (Exception e) { block("PRE_VALIDATION_TARGET_UNSAFE"); return; }
        if (!sourceBefore.equals(s) || !sourceModes.equals(source.validationModes(s))) block("PRE_VALIDATION_SOURCE_CHANGED");
        if (!targetBefore.equals(t) || !targetModes.equals(target.validationModes(t))) block("PRE_VALIDATION_IMPLEMENTATION_CHANGED");
        try { profile.verify(t); } catch (Exception e) { block("VALIDATION_TRUSTED_PROFILE_UNAVAILABLE"); }
    }
    private void block(String reason) { code = reason; throw new IllegalStateException(reason); }

    synchronized void execute(RoleExecutor.Invocation active, Map<String,Object> request, ArtifactStore artifacts) throws Exception {
        ExecutionPlan.fields(request, Set.of("operationId", "operation", "invocationId", "role", "authorityId"));
        if (used) throw new IllegalArgumentException("VALIDATION_AUTHORITY_REPLAY");
        if (active.role() != TrustedInputs.Role.VALIDATION || !active.role().id.equals(request.get("role"))
                || !authority.get("role").equals(request.get("role"))) throw new IllegalArgumentException("VALIDATION_ROLE_DENIED");
        if (!active.id().equals(request.get("invocationId")) || !authority.get("invocationId").equals(active.id()))
            throw new IllegalArgumentException("VALIDATION_INVOCATION_MISMATCH");
        if (!authority.get("authorityId").equals(request.get("authorityId")) || !"RUN_VALIDATION".equals(request.get("operation")))
            throw new IllegalArgumentException("VALIDATION_AUTHORITY_MISMATCH");
        var current = artifacts.required("IMPLEMENTATION_RESULT:STEP-004");
        if (!current.acceptanceInvocationId().equals(active.predecessorInvocationId())
                || !current.fingerprint().equals(ExecutionPlan.map(authority.get("predecessorAcceptance")).get("fingerprint")))
            throw new IllegalArgumentException("VALIDATION_STALE_PREDECESSOR");
        // Re-read all accepted predecessors immediately at use; journal identity is also checked.
        for (int i = 1; i <= 4; i++) {
            var a = artifacts.required("IMPLEMENTATION_RESULT:STEP-00" + i);
            var bound = ExecutionPlan.map(ExecutionPlan.list(authority.get("implementationResults")).get(i - 1));
            if (!a.fingerprint().equals(bound.get("fingerprint")) || !a.acceptanceInvocationId().equals(bound.get("acceptanceInvocationId")))
                throw new IllegalArgumentException("VALIDATION_STALE_PREDECESSOR");
        }
        used = true; precheck();
        Process process = null;
        Capture out = null, err = null;
        boolean timedOut = false, interrupted = false, cleanupUnavailable = false; Integer exit = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(profile.command());
            builder.directory(root.toFile()); builder.environment().clear();
            process = builder.start();
            // Preserve launch before any stream, wait, observation or evidence operation can fail.
            execution = Json.object("started", true, "completed", false, "profileId", ValidationProfile.ID);
            process.getOutputStream().close();
            out = new Capture(process.getInputStream()); err = new Capture(process.getErrorStream());
            out.start(); err.start();
            timedOut = !process.waitFor(ValidationProfile.TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (timedOut) kill(process);
            if (process.isAlive()) { code = "VALIDATION_PROCESS_TERMINATION_UNAVAILABLE"; }
            else exit = process.exitValue();
            out.finish(); err.finish();
            execution = Json.object("started", true, "completed", !process.isAlive(), "profileId", ValidationProfile.ID,
                    "exitCode", exit, "timedOut", timedOut, "stdout", out.view("VALIDATION_STDOUT"), "stderr", err.view("VALIDATION_STDERR"));
            status = "FAILED";
            code = timedOut ? "VALIDATION_TIMEOUT" : exit == null ? "VALIDATION_PROCESS_TERMINATION_UNAVAILABLE"
                    : exit != 0 ? "VALIDATION_TESTS_FAILED" : !out.complete || !err.complete ? "VALIDATION_STREAM_CAPTURE_FAILED" : "VALIDATION_PASSED";
            if (code.equals("VALIDATION_PASSED")) status = "SUCCESS";
        } catch (Exception unavailable) {
            interrupted = unavailable instanceof InterruptedException;
            status = started() ? "FAILED" : "BLOCKED";
            code = timedOut ? "VALIDATION_TIMEOUT" : exit != null && exit != 0 ? "VALIDATION_TESTS_FAILED"
                    : started() ? "VALIDATION_EXECUTION_INTERRUPTED" : "VALIDATION_LAUNCH_UNAVAILABLE";
        } finally {
            if (process != null && process.isAlive()) {
                try { kill(process); }
                catch (InterruptedException stopped) { interrupted = true; cleanupUnavailable = true; }
                catch (RuntimeException unavailable) { cleanupUnavailable = true; }
                if (cleanupUnavailable && !status.equals("FAILED")) {
                    status = "FAILED"; code = "VALIDATION_PROCESS_TERMINATION_UNAVAILABLE";
                }
            }
            // Even interrupted capture/cleanup retains all independently known launch/exit facts.
            if (process != null && !execution.containsKey("exitCode")) {
                execution = Json.object("started", true, "completed", !process.isAlive(), "profileId", ValidationProfile.ID,
                        "exitCode", process.isAlive() ? null : process.exitValue(), "timedOut", timedOut,
                        "stdout", out == null ? null : out.view("VALIDATION_STDOUT"),
                        "stderr", err == null ? null : err.view("VALIDATION_STDERR"));
            }
            var retainedExecution = new LinkedHashMap<>(execution);
            retainedExecution.put("cleanupUnavailable", cleanupUnavailable);
            execution = ExecutionPlan.map(Json.object("execution", retainedExecution).get("execution"));
            try { observe(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    private static void kill(Process p) throws InterruptedException {
        List<ProcessHandle> descendants = List.of();
        RuntimeException descendantFailure = null;
        try {
            descendants = p.descendants().toList();
            descendants.forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException unavailable) { descendantFailure = unavailable; }
        finally { p.destroyForcibly(); }
        // Parent exit is independently observed even if descendant enumeration is unavailable.
        p.waitFor(1, TimeUnit.SECONDS);
        descendants.forEach(ProcessHandle::destroyForcibly);
        if (descendantFailure != null) throw descendantFailure;
    }
    /** Full bounded binary-capable observation: only output content bypasses text decoding. Links still reject. */
    void observe() {
        // Preserve a previously observed unauthorized effect even if later code restores it.
        Map<String,Object> knownUnauthorized = "UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS".equals(effects.get("classification")) ? effects : null;
        RepositoryFiles.Snapshot s = null, t = null;
        String sourceObservation = "COMPLETE", targetObservation = "COMPLETE";
        try { s = source.snapshot(Set.of()); } catch (Exception e) { sourceObservation = observationCode(e); }
        try { t = target.snapshot(Set.of()); } catch (Exception e) { targetObservation = observationCode(e); }
        Boolean sourceEqual = s == null ? null : sourceBefore.equals(s);
        Boolean targetEqual = t == null ? null : withoutOutputs(targetBefore).equals(withoutOutputs(t));
        Map<String,Object> observedSourceModes = null, observedTargetModes = null;
        try { if (s != null) { observedSourceModes = source.validationModes(s); if (!sourceModes.equals(observedSourceModes)) sourceEqual = false; } }
        catch (Exception e) { sourceEqual = null; sourceObservation = observationCode(e); }
        try { if (t != null) { observedTargetModes = target.validationModes(t); if (!targetModes.equals(observedTargetModes)) targetEqual = false; } }
        catch (Exception e) { targetEqual = null; targetObservation = observationCode(e); }
        boolean safe = Boolean.TRUE.equals(sourceEqual) && Boolean.TRUE.equals(targetEqual);
        boolean unauthorized = Boolean.FALSE.equals(sourceEqual) || Boolean.FALSE.equals(targetEqual)
                || unsafeObservation(sourceObservation) || unsafeObservation(targetObservation);
        String classification = safe ? "VALIDATION_EPHEMERAL_EFFECT"
                : unauthorized ? "UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS" : "UNKNOWN_VALIDATION_REPOSITORY_EFFECTS";
        List<Map<String,Object>> changes = new ArrayList<>();
        if (t != null) {
            Map<String,Map<String,Object>> before = states(targetBefore), after = states(t);
            Set<String> paths = new TreeSet<>(before.keySet()); paths.addAll(after.keySet());
            for (String path : paths) if (!Objects.equals(before.get(path), after.get(path)))
                changes.add(Json.object("pathKey", path, "classification", ValidationProfile.ephemeral(path)
                        ? "VALIDATION_EPHEMERAL_EFFECT" : "UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECT",
                        "before", before.get(path), "after", after.get(path)));
        }
        effects = Json.object("classification", classification,
                "sourceUnchanged", sourceEqual, "targetImplementationUnchanged", targetEqual,
                "sourceObservation", sourceObservation, "targetObservation", targetObservation,
                "actualSourceModes", observedSourceModes, "actualTargetModes", observedTargetModes,
                "targetChanges", changes, "sourceChanges", sourceChanges(s), "sourceBeforeFingerprint", Json.evidenceFingerprint(sourceBefore.entries()),
                "actualSource", s == null ? null : s.entries(), "actualTarget", t == null ? null : t.entries());
        if (unauthorized) { status = "FAILED"; code = "UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS"; }
        else if (!safe && !status.equals("FAILED")) { status = "BLOCKED"; code = "VALIDATION_REPOSITORY_OBSERVATION_UNAVAILABLE"; }
        if (safe) acceptedAfter = t;
        if (knownUnauthorized != null) {
            effects = knownUnauthorized; acceptedAfter = null;
            status = "FAILED"; code = "UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS";
        }
        if (Json.bytes(facts()).length > ValidationProfile.FACT_BYTES) {
            // Never publish an incomplete observation as PASS. Preserve independently known
            // outcomes and bounded exact change examples, explicitly marking omitted evidence.
            effects = Json.object("classification", effects.get("classification"), "sourceUnchanged", sourceEqual,
                    "targetImplementationUnchanged", targetEqual, "observation", "EVIDENCE_LIMIT_EXCEEDED",
                    "sourceObservation", sourceObservation, "targetObservation", targetObservation,
                    "completeEffectFingerprint", Json.evidenceFingerprint(effects), "targetChangeCount", changes.size(),
                    "sourceChangeCount", sourceChanges(s).size(),
                    "targetChangeExamples", changes.stream().limit(4).toList());
            if (safe && !status.equals("FAILED")) { status = "BLOCKED"; code = "VALIDATION_EVIDENCE_LIMIT"; }
            acceptedAfter = null;
            while (Json.bytes(facts()).length > ValidationProfile.FACT_BYTES) {
                var examples = ExecutionPlan.list(effects.get("targetChangeExamples"));
                if (examples.isEmpty()) throw new IllegalStateException("VALIDATION_FACT_BOUND_INVARIANT");
                var bounded = new LinkedHashMap<>(effects);
                bounded.put("targetChangeExamples", examples.subList(0, examples.size() - 1));
                effects = ExecutionPlan.map(Json.object("bounded", bounded).get("bounded"));
            }
        }
    }
    private List<Map<String,Object>> sourceChanges(RepositoryFiles.Snapshot observed) {
        if (observed == null) return List.of();
        var before = states(sourceBefore); var after = states(observed);
        Set<String> paths = new TreeSet<>(before.keySet()); paths.addAll(after.keySet());
        return paths.stream().filter(p -> !Objects.equals(before.get(p), after.get(p)))
                .map(p -> Json.object("pathKey", p, "classification", "UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECT",
                        "before", before.get(p), "after", after.get(p))).toList();
    }
    private static boolean unsafeObservation(String code) {
        return Set.of("REPOSITORY_PATH_UNSAFE", "REPOSITORY_ROOT_CHANGED", "REPOSITORY_STATE_CHANGED",
                "REPOSITORY_PATH_ALIAS", "GIT_CONTENT_OBSERVATION_UNSUPPORTED").contains(code);
    }
    private static String observationCode(Exception e) {
        String message = e.getMessage();
        return message != null && message.matches("[A-Z_]{1,100}") ? message : "VALIDATION_OBSERVATION_UNAVAILABLE";
    }
    private static Map<String,Map<String,Object>> states(RepositoryFiles.Snapshot s) {
        Map<String,Map<String,Object>> result = new TreeMap<>(); s.entries().forEach(e -> result.put((String)e.get("pathKey"), e)); return result;
    }
    private static RepositoryFiles.Snapshot withoutOutputs(RepositoryFiles.Snapshot s) {
        return new RepositoryFiles.Snapshot(s.rootFilesystemIdentity(), s.entries().stream()
                .filter(e -> !ValidationProfile.ephemeral((String)e.get("pathKey"))).toList(), s.protectedPaths(), s.excludedPaths());
    }
    Boolean terminalTargetMatches(RepositoryFiles.Snapshot observed) {
        if (!Boolean.TRUE.equals(effects.get("targetImplementationUnchanged"))) return (Boolean)effects.get("targetImplementationUnchanged");
        return acceptedAfter == null ? null : acceptedAfter.equals(observed);
    }
    RepositoryFiles.Snapshot targetSnapshot() throws Exception { return target.snapshot(Set.of()); }

    Map<String,Object> resultContract() {
        return Json.object("resultVersion", 1, "role", authority.get("role"), "invocationId", authority.get("invocationId"),
                "migrationPlanFingerprint", authority.get("migrationPlanFingerprint"), "implementationResults", authority.get("implementationResults"),
                "predecessorAcceptance", authority.get("predecessorAcceptance"), "authorityId", authority.get("authorityId"),
                "profileId", ValidationProfile.ID, "hostExecutionEvidenceReference", Json.evidenceFingerprint(execution),
                "exitCode", execution.get("exitCode"), "timedOut", execution.get("timedOut"),
                "repositoryEffects", effects, "validationStatus", status, "failures", status.equals("SUCCESS") ? List.of() : List.of(code));
    }
    void validateResult(Map<String,Object> result) {
        if (!started() || !Json.parse(Json.write(resultContract())).equals(result)) throw new IllegalArgumentException("VALIDATION_RESULT_HOST_EVIDENCE_MISMATCH");
    }
    private static final class Capture extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
        private long observed;
        private volatile boolean complete;
        Capture(InputStream input) { this.input = input; setDaemon(true); }
        @Override public void run() {
            byte[] buffer = new byte[1024];
            try { int count; while ((count = input.read(buffer)) != -1) synchronized (this) {
                observed += count;
                retained.write(buffer, 0, Math.min(count, ValidationProfile.STREAM_BYTES - retained.size()));
            } complete = true; } catch (IOException ignored) { /* Evidence explicitly records incomplete capture. */ }
        }
        void finish() throws Exception { join(1000); if (isAlive()) { input.close(); join(1000); } }
        synchronized Map<String,Object> view(String role) {
            return Json.object("retainedBytes", retained.size(), "observedBytes", observed, "truncated", observed > retained.size(),
                    "complete", complete, "retainedFingerprint", Json.fingerprint(role, retained.toByteArray()), "excerpts", List.of());
        }
    }
}
