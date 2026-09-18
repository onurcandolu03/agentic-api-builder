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
    private boolean used, preparationAttempted;
    private Map<String,Object> operationContext, testEvidence;
    private Map<String,Object> execution = Json.object("started", false, "completed", false);
    private Map<String,Object> effects = Json.object("classification", "NOT_OBSERVED");
    private RepositoryFiles.Snapshot acceptedAfter;
    private RepositoryFiles.Snapshot operationExecutionAfter;
    private String status = "BLOCKED", code = "VALIDATION_NOT_EXECUTED";

    ValidationRuntime(ValidationProfile profile, Path targetRoot,
                      RepositoryFiles.Snapshot sourceBefore, RepositoryFiles.Snapshot targetBefore,
                      String runId, RoleExecutor.Invocation invocation, ArtifactStore artifacts,
                      RepositoryFiles sourceObserver, RepositoryFiles targetObserver,
                      Map<String,Object> originalSourceModes, Map<String,Object> acceptedTargetModes) throws Exception {
        this.profile = profile; this.root = targetRoot;
        if (profile.operationTests() != null) throw new IllegalStateException("VALIDATION_PROFILE_WORKFLOW_MISMATCH");
        targetObserver.requireRoot(targetRoot);
        if (sourceBefore.rootFilesystemIdentity().equals(targetBefore.rootFilesystemIdentity()))
            throw new IllegalStateException("VALIDATION_SOURCE_TARGET_OVERLAP");
        profile.verifyRoots(sourceObserver.root(), targetRoot);
        this.sourceBefore = sourceBefore; this.targetBefore = targetBefore;
        if (invocation.role() != TrustedInputs.Role.VALIDATION) throw new IllegalStateException("VALIDATION_ROLE_DENIED");
        if (!runId.equals(artifacts.required("MIGRATION_PLAN").binding().get("runId")))
            throw new IllegalStateException("VALIDATION_RUN_MISMATCH");
        List<Map<String,Object>> predecessors = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            var a = artifacts.required(resultRole(i - 1));
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
                "profileId", profile.id(), "trustedProfile", profile.view(),
                "launchFingerprint", Json.evidenceFingerprint(profile.command(root)),
                "beforeSourceModes", sourceModes, "beforeTargetModes", targetModes,
                "beforeSourceFingerprint", Json.evidenceFingerprint(sourceBefore.entries()),
                "beforeTargetFingerprint", Json.evidenceFingerprint(targetBefore.entries()));
        if (Json.bytes(authority).length > ValidationProfile.FACT_BYTES / 2)
            throw new IllegalStateException("VALIDATION_AUTHORITY_EVIDENCE_LIMIT");
    }
    ValidationRuntime(ValidationProfile profile, Path root, RepositoryFiles.Snapshot targetBefore,
                      String runId, RoleExecutor.Invocation invocation, ArtifactStore artifacts,
                      RepositoryFiles targetObserver, Map<String,Object> targetModes,
                      Map<String,Object> operationContext) throws Exception {
        this.profile = profile; this.root = root; this.targetBefore = targetBefore;
        this.source = null; this.sourceBefore = null; this.sourceModes = null;
        this.target = targetObserver; this.targetModes = MigrationInput.immutable(targetModes);
        this.operationContext = MigrationInput.immutable(operationContext);
        target.requireRoot(root); profile.verifyTargetRoot(root);
        if (invocation.role() != TrustedInputs.Role.VALIDATION) throw new IllegalStateException("VALIDATION_ROLE_DENIED");
        var predecessors = ExecutionPlan.list(operationContext.get("implementationResults"));
        var last = ExecutionPlan.map(predecessors.getLast());
        if (predecessors.size() != 4 || !Objects.equals(invocation.predecessorInvocationId(), last.get("acceptanceInvocationId"))
                || !runId.equals(artifacts.required("OPERATION_PLAN").binding().get("runId")))
            throw new IllegalStateException("VALIDATION_STALE_PREDECESSOR");
        authority = Json.object("authorityVersion", 1, "authorityKind", "NEW_OPERATION_VALIDATION_AUTHORITY_V1",
                "runId", runId, "role", invocation.role().id, "invocationId", invocation.id(),
                "authorityId", runId + "-operation-validation-grant", "replayIdentity", invocation.id(),
                "operationContext", this.operationContext, "implementationResults", predecessors, "predecessorAcceptance", last,
                "targetRootIdentity", targetBefore.rootFilesystemIdentity(), "workingRoot", root.toString(),
                "profileId", profile.id(), "trustedProfile", profile.operationPolicy(),
                "launchFingerprint", Json.evidenceFingerprint(profile.command(root)),
                "beforeTargetModes", this.targetModes, "beforeTargetFingerprint", Json.evidenceFingerprint(targetBefore.entries()));
        if (Json.bytes(authority).length > ValidationProfile.FACT_BYTES / 2)
            throw new IllegalStateException("VALIDATION_AUTHORITY_EVIDENCE_LIMIT");
    }
    Map<String,Object> authorityFingerprint() { return Json.fingerprint("NEW_OPERATION_VALIDATION_AUTHORITY_V1", Json.bytes(authority)); }
    private String resultRole(int index) {
        return operationContext == null ? "IMPLEMENTATION_RESULT:STEP-00" + (index + 1)
                : "OPERATION_IMPLEMENTATION_RESULT:" + OperationImplementation.ROLES.get(index).id;
    }
    Map<String,Object> authority() { return authority; }
    String status() { return status; }
    String code() { return code; }
    boolean hasEffects() { return preparationAttempted || started(); }
    boolean started() { return Boolean.TRUE.equals(execution.get("started")); }
    Map<String,Object> facts() {
        var facts = new LinkedHashMap<>(Json.object("authority", authority, "execution", execution, "effects", effects, "status", status, "code", code));
        if (operationContext != null) { facts.put("validationAuthorityFingerprint", authorityFingerprint()); facts.put("testEvidence", testEvidence); }
        return MigrationInput.immutable(facts);
    }

    void precheck() throws Exception {
        try { if (source != null) source.verify(); } catch (Exception e) { block("PRE_VALIDATION_SOURCE_ROOT_CHANGED"); }
        try { target.verify(); } catch (Exception e) { block("PRE_VALIDATION_TARGET_ROOT_CHANGED"); }
        RepositoryFiles.Snapshot s, t;
        try { s = source == null ? null : source.snapshot(Set.of()); } catch (Exception e) { block("PRE_VALIDATION_SOURCE_UNSAFE"); return; }
        try { t = target.snapshot(Set.of()); } catch (Exception e) { block("PRE_VALIDATION_TARGET_UNSAFE"); return; }
        if (source != null && (!sourceBefore.equals(s) || !sourceModes.equals(source.validationModes(s)))) block("PRE_VALIDATION_SOURCE_CHANGED");
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
        var current = artifacts.required(resultRole(3));
        if (!current.acceptanceInvocationId().equals(active.predecessorInvocationId())
                || !current.fingerprint().equals(ExecutionPlan.map(authority.get("predecessorAcceptance")).get("fingerprint")))
            throw new IllegalArgumentException("VALIDATION_STALE_PREDECESSOR");
        // Re-read all accepted predecessors immediately at use; journal identity is also checked.
        for (int i = 1; i <= 4; i++) {
            var a = artifacts.required(resultRole(i - 1));
            var bound = ExecutionPlan.map(ExecutionPlan.list(authority.get("implementationResults")).get(i - 1));
            if (!a.fingerprint().equals(bound.get("fingerprint")) || !a.acceptanceInvocationId().equals(bound.get("acceptanceInvocationId")))
                throw new IllegalArgumentException("VALIDATION_STALE_PREDECESSOR");
        }
        if (operationContext != null) {
            var lineage = ExecutionPlan.map(operationContext.get("lineage"));
            for (var pair : Map.of("OPERATION_REQUIREMENT", "operationRequirementFingerprint", "TARGET_ANALYSIS", "targetAnalysisFingerprint", "OPERATION_PLAN", "operationPlanFingerprint").entrySet())
                if (!artifacts.required(pair.getKey()).fingerprint().equals(lineage.get(pair.getValue())))
                    throw new IllegalArgumentException("VALIDATION_OPERATION_LINEAGE_CHANGED");
        }
        used = true; precheck();
        Process process = null;
        ProcessTree tree = null;
        Capture out = null, err = null;
        boolean timedOut = false, interrupted = false, cleanupUnavailable = false;
        Integer exit = null;
        try {
            preparationAttempted = true;
            profile.prepare(root);
            if (source != null) source.verify(); target.requireRoot(root);
            ProcessBuilder builder = new ProcessBuilder(profile.command(root));
            builder.directory(root.toFile()); builder.environment().clear();
            process = builder.start();
            execution = Json.object("started", true, "completed", false, "profileId", profile.id());
            tree = new ProcessTree(process);
            tree.observeTree();
            if (tree.failure != null) throw new IllegalStateException(tree.failure);
            process.getOutputStream().close();
            out = new Capture(process.getInputStream()); err = new Capture(process.getErrorStream());
            out.start(); err.start();
            timedOut = !tree.await(profile.timeoutSeconds());
            if (!process.isAlive()) exit = process.exitValue();
            status = "FAILED";
            code = timedOut ? "VALIDATION_TIMEOUT" : exit == null ? "VALIDATION_PROCESS_TERMINATION_UNAVAILABLE"
                    : exit != 0 ? "VALIDATION_TESTS_FAILED" : "VALIDATION_PASSED";
            if (code.equals("VALIDATION_PASSED")) status = "SUCCESS";
        } catch (Exception unavailable) {
            interrupted = unavailable instanceof InterruptedException;
            status = started() ? "FAILED" : "BLOCKED";
            code = tree != null && tree.failure != null ? tree.failure
                    : started() ? "VALIDATION_EXECUTION_INTERRUPTED" : "VALIDATION_LAUNCH_UNAVAILABLE";
        } finally {
            // Every launched process terminalizes its retained tree, even when the parent already exited.
            if (tree != null) {
                tree.cleanup(); interrupted |= tree.interrupted;
                cleanupUnavailable = tree.failure != null;
                if (cleanupUnavailable && (status.equals("SUCCESS") || code.equals("VALIDATION_EXECUTION_INTERRUPTED"))) {
                    status = "FAILED"; code = tree.failure;
                }
            }
            for (Capture capture : new Capture[] {out, err}) if (capture != null) {
                try { capture.finish(tree != null && tree.failure == null && process != null && !process.isAlive()); }
                catch (InterruptedException stopped) { interrupted = true; capture.closeAsync(); }
            }
            if (interrupted && status.equals("SUCCESS")) { status = "FAILED"; code = "VALIDATION_EXECUTION_INTERRUPTED"; }
            if (status.equals("SUCCESS") && (out == null || err == null || !out.complete || !err.complete)) {
                status = "FAILED"; code = "VALIDATION_STREAM_CAPTURE_FAILED";
            }
            if (process != null) {
                execution = Json.object("started", true, "completed", !process.isAlive(), "profileId", profile.id(),
                        "exitCode", process.isAlive() ? null : process.exitValue(), "timedOut", timedOut,
                        "stdout", out == null ? null : out.view("VALIDATION_STDOUT"),
                        "stderr", err == null ? null : err.view("VALIDATION_STDERR"));
            }
            var retainedExecution = new LinkedHashMap<>(execution);
            retainedExecution.put("cleanupUnavailable", cleanupUnavailable);
            retainedExecution.put("processTree", tree == null ? null : tree.view());
            retainedExecution.put("preparationAttempted", preparationAttempted);
            execution = ExecutionPlan.map(Json.object("execution", retainedExecution).get("execution"));
            try {
                observe();
                if (operationContext != null && status.equals("SUCCESS")) {
                    try {
                        testEvidence = OperationTestEvidence.read(root, target.snapshot(Set.of()), profile.operationTests());
                        status = (String)testEvidence.get("status"); code = (String)testEvidence.get("code");
                    } catch (Exception rejected) { status = "BLOCKED"; code = "VALIDATION_REPORT_EVIDENCE_UNAVAILABLE"; }
                    observe();
                }
                if (operationContext != null && acceptedAfter != null) operationExecutionAfter = acceptedAfter;
            }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    /** Bounded observation/cleanup, not OS confinement. Handles survive parent exit/reparenting.
     * Package access permits deterministic process lifecycle regression tests without a launch-policy seam. */
    static final class ProcessTree {
        private static final int MAX_DESCENDANTS = 128, POLL_MILLIS = 25;
        private final Process parent;
        private final Set<ProcessHandle> retained = new LinkedHashSet<>();
        private String failure;
        private boolean interrupted;
        private int observations, cleanupPasses;
        ProcessTree(Process parent) { this.parent = parent; }
        private void fail(String reason) { if (failure == null) failure = reason; }
        private void discover(ProcessHandle ancestor) {
            try (var descendants = ancestor.descendants()) {
                var iterator = descendants.limit(MAX_DESCENDANTS + 1L).iterator();
                int count = 0;
                while (iterator.hasNext()) {
                    ProcessHandle child = iterator.next();
                    if (++count > MAX_DESCENDANTS || (!retained.contains(child) && retained.size() == MAX_DESCENDANTS)) {
                        fail("VALIDATION_DESCENDANT_LIMIT"); break;
                    }
                    retained.add(child);
                }
            } catch (RuntimeException unavailable) { fail("VALIDATION_DESCENDANT_OBSERVATION_UNAVAILABLE"); }
        }
        private void observeTree() {
            observations++;
            // Include retained descendants as roots: they may have been reparented already.
            var previous = List.copyOf(retained);
            if (parent.isAlive()) discover(parent.toHandle());
            for (ProcessHandle child : previous) if (child.isAlive()) discover(child);
        }
        boolean await(int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            do {
                observeTree();
                if (failure != null) throw new IllegalStateException(failure);
                if (!parent.isAlive()) return true;
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                parent.waitFor(Math.min(left, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)), TimeUnit.NANOSECONDS);
            } while (true);
        }
        private void terminateRetained() {
            for (ProcessHandle child : retained) try {
                if (child.isAlive()) child.destroyForcibly();
            } catch (RuntimeException unavailable) { fail("VALIDATION_PROCESS_TERMINATION_UNAVAILABLE"); }
        }
        private void terminateParent() {
            try { if (parent.isAlive()) parent.destroyForcibly(); }
            catch (RuntimeException unavailable) { fail("VALIDATION_PROCESS_TERMINATION_UNAVAILABLE"); }
        }
        private void pause() {
            try { Thread.sleep(POLL_MILLIS); }
            catch (InterruptedException stopped) { interrupted = true; }
        }
        void cleanup() {
            interrupted |= Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            // Leave a live parent briefly available to reap children and expose replacement children.
            // Fixed pass counts plus a shared deadline bound repeated discovery and termination.
            try {
                for (int pass = 0; pass < 20 && System.nanoTime() < deadline; pass++) {
                    cleanupPasses++; observeTree(); terminateRetained();
                    if (!parent.isAlive() && retained.stream().noneMatch(ProcessHandle::isAlive)) break;
                    pause();
                }
            } catch (RuntimeException unavailable) { fail("VALIDATION_PROCESS_TERMINATION_UNAVAILABLE"); }
            finally { terminateParent(); }
            try {
                for (int pass = 0; pass < 60 && System.nanoTime() < deadline; pass++) {
                    cleanupPasses++; observeTree(); terminateRetained(); terminateParent();
                    if (!parent.isAlive() && retained.stream().noneMatch(ProcessHandle::isAlive)) break;
                    pause();
                }
                terminateRetained(); terminateParent();
                if (parent.isAlive() || retained.stream().anyMatch(ProcessHandle::isAlive))
                    fail("VALIDATION_PROCESS_TERMINATION_UNAVAILABLE");
            } catch (RuntimeException unavailable) { fail("VALIDATION_PROCESS_TERMINATION_UNAVAILABLE"); }
        }
        Map<String,Object> view() {
            return Json.object("observedDescendants", retained.size(), "discoveryPasses", observations,
                    "cleanupPasses", cleanupPasses, "cleanupFailure", failure);
        }
    }
    /** Full bounded binary-capable observation: only output content bypasses text decoding. Links still reject. */
    void observe() {
        // Preserve a previously observed unauthorized effect even if later code restores it.
        Map<String,Object> knownUnauthorized = "UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS".equals(effects.get("classification")) ? effects : null;
        RepositoryFiles.Snapshot s = null, t = null;
        String sourceObservation = source == null ? "NOT_APPLICABLE" : "COMPLETE", targetObservation = "COMPLETE";
        try { s = source == null ? null : source.snapshot(Set.of()); } catch (Exception e) { sourceObservation = observationCode(e); }
        try { t = target.snapshot(Set.of()); } catch (Exception e) { targetObservation = observationCode(e); }
        Boolean sourceEqual = s == null ? null : sourceBefore.equals(s);
        Boolean targetEqual = t == null ? null : withoutOutputs(targetBefore).equals(withoutOutputs(t));
        if (operationExecutionAfter != null && t != null && !operationExecutionAfter.equals(t)) targetEqual = false;
        Map<String,Object> observedSourceModes = null, observedTargetModes = null;
        try { if (s != null) { observedSourceModes = source.validationModes(s); if (!sourceModes.equals(observedSourceModes)) sourceEqual = false; } }
        catch (Exception e) { sourceEqual = null; sourceObservation = observationCode(e); }
        try { if (t != null) { observedTargetModes = target.validationModes(t); if (!targetModes.equals(observedTargetModes)) targetEqual = false; } }
        catch (Exception e) { targetEqual = null; targetObservation = observationCode(e); }
        boolean safe = (source == null || Boolean.TRUE.equals(sourceEqual)) && Boolean.TRUE.equals(targetEqual);
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
                "targetChanges", changes, "sourceChanges", sourceChanges(s), "sourceBeforeFingerprint", sourceBefore == null ? null : Json.evidenceFingerprint(sourceBefore.entries()),
                "actualSource", s == null ? null : s.entries(), "actualTarget", t == null ? null : t.entries());
        if (unauthorized) { status = "FAILED"; code = "UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS"; }
        else if (!safe && !status.equals("FAILED")) { status = "BLOCKED"; code = "VALIDATION_REPOSITORY_OBSERVATION_UNAVAILABLE"; }
        if (safe && testEvidence != null) {
            for (Object report : ExecutionPlan.list(testEvidence.get("reports"))) {
                var r = ExecutionPlan.map(report);
                if (t.entries().stream().noneMatch(e -> r.get("pathKey").equals(e.get("pathKey")) && r.get("fingerprint").equals(e.get("contentFingerprint")))) {
                    status = "FAILED"; code = "VALIDATION_REPORT_CHANGED"; safe = false;
                }
            }
        }
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
        var result = new LinkedHashMap<>(Json.object("resultVersion", 1, "role", authority.get("role"), "invocationId", authority.get("invocationId"),
                "migrationPlanFingerprint", authority.get("migrationPlanFingerprint"), "implementationResults", authority.get("implementationResults"),
                "predecessorAcceptance", authority.get("predecessorAcceptance"), "authorityId", authority.get("authorityId"),
                "profileId", profile.id(), "hostExecutionEvidenceReference", Json.evidenceFingerprint(execution),
                "exitCode", execution.get("exitCode"), "timedOut", execution.get("timedOut"),
                "repositoryEffects", effects, "validationStatus", status, "failures", status.equals("SUCCESS") ? List.of() : List.of(code)));
        if (operationContext != null) {
            result.remove("migrationPlanFingerprint");
            result.put("workflow", "NEW_OPERATION"); result.put("lineage", operationContext.get("lineage"));
            result.put("implementationAuthorityFingerprint", operationContext.get("implementationAuthorityFingerprint"));
            result.put("validationAuthorityFingerprint", authorityFingerprint());
            result.put("obligationEvidence", Json.object("static", operationContext.get("staticObligationEvidence"),
                    "obligationsFingerprint", Json.evidenceFingerprint(operationContext.get("obligations")), "tests", testEvidence));
        }
        return MigrationInput.immutable(result);
    }
    void validateResult(Map<String,Object> result) {
        if (!started() || !Json.parse(Json.write(resultContract())).equals(result)) throw new IllegalArgumentException("VALIDATION_RESULT_HOST_EVIDENCE_MISMATCH");
    }
    static final class Capture extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
        private long observed;
        private volatile boolean complete, stop, readFailed, drained;
        Capture(InputStream input) { this.input = input; setDaemon(true); }
        @Override public void run() {
            byte[] buffer = new byte[1024];
            try {
                while (true) {
                    int available = input.available();
                    if (available > 0) {
                        int count = input.read(buffer, 0, Math.min(buffer.length, available));
                        if (count < 0) { drained = true; break; }
                        if (count == 0) { Thread.sleep(10); continue; }
                        synchronized (this) {
                            observed += count;
                            retained.write(buffer, 0, Math.min(count, ValidationProfile.STREAM_BYTES - retained.size()));
                        }
                    } else if (stop) { drained = true; break; }
                    else Thread.sleep(10);
                }
            } catch (IOException unavailable) { readFailed = true; }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }
        void finish(boolean treeClosed) throws InterruptedException {
            stop = true;
            join(1000);
            if (isAlive()) { interrupt(); join(1000); }
            complete = treeClosed && drained && !readFailed && !isAlive();
            closeAsync();
        }
        void closeAsync() {
            // Closing a process pipe can wait for a reader lock; never wait here.
            Thread closer = new Thread(() -> { try { input.close(); } catch (IOException ignored) { } });
            closer.setDaemon(true); closer.start();
        }
        synchronized Map<String,Object> view(String role) {
            return Json.object("retainedBytes", retained.size(), "observedBytes", observed, "truncated", observed > retained.size(),
                    "complete", complete, "classification", "UNTRUSTED_VALIDATION_OUTPUT", "retainedFingerprint", Json.fingerprint(role, retained.toByteArray()), "excerpts", List.of());
        }
    }
}
