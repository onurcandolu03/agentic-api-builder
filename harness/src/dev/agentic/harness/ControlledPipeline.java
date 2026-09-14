package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Non-interactive current-session launcher. Transport is explicitly supplied by the trusted host. */
public final class ControlledPipeline {
    public record Result(String status, String code, Map<String,Object> report) {}
    private final Path trustedRoot;
    private final MigrationInput input;
    private final ControlledHarness.Config config;
    private final ResponsesClient client;
    private final byte[] staticResolution;
    private final Evidence evidence;
    private final ArtifactStore artifacts;
    private final List<Map<String,Object>> phases = new ArrayList<>();
    private final List<Map<String,Object>> nonacceptedOutputs = new ArrayList<>();
    private final List<Map<String,Object>> orchestrationFailures = new ArrayList<>();
    private Halt specialistFailure;
    private ControlledHarness harness;
    private RoleExecutor roles;
    private ExecutionGates gates;
    private SourceBroker source;
    private TargetContentBroker target;
    private RepositoryFiles.Snapshot sourceBaseline, targetCurrent;
    private Map<String,Object> finalEffects;
    private Map<String,Object> bundleFingerprint;
    private boolean started, terminal;
    private String status = "BLOCKED", code = "NOT_STARTED";

    public ControlledPipeline(Path trustedRoot, MigrationInput input, ControlledHarness.Config config,
                              ResponsesClient client) {
        this(trustedRoot, input, config, client, null);
    }
    /** Optional exact caller resolution is preserved for preflight; implementation remains unavailable. */
    public ControlledPipeline(Path trustedRoot, MigrationInput input, ControlledHarness.Config config,
                              ResponsesClient client, byte[] staticResolution) {
        this.trustedRoot = Objects.requireNonNull(trustedRoot); this.input = Objects.requireNonNull(input);
        this.config = Objects.requireNonNull(config); this.client = Objects.requireNonNull(client);
        this.staticResolution = staticResolution == null ? null : staticResolution.clone();
        evidence = new Evidence(text -> {
            client.rejectCredentialMaterial(text);
            if (terminal) throw new IllegalStateException("PIPELINE_TERMINATED");
        });
        artifacts = new ArtifactStore(evidence);
    }

    /** Sparse JSON entry point: missing routing blocks before any provider call; no prompts or fallback. */
    public static Result runJson(Path trustedRoot, byte[] callerBytes, ControlledHarness.Config config, ResponsesClient client) {
        MigrationInput input;
        try { input = MigrationInput.fromJson(callerBytes); }
        catch (IllegalArgumentException rejected) {
            boolean missing = "MIGRATION_INPUT_MISSING_ROUTING".equals(rejected.getMessage())
                    || callerBytes == null || callerBytes.length == 0;
            String status = missing ? "BLOCKED" : "FAILED";
            String code = missing ? "CALLER_ROUTING_INPUT_REQUIRED" : "CALLER_INPUT_REJECTED";
            return new Result(status, code, Json.object("status", status, "code", code,
                    "acceptedArtifacts", List.of(), "realProviderE2EProven", false));
        }
        return new ControlledPipeline(trustedRoot, input, config, client).run();
    }

    public synchronized Result run() {
        if (started) {
            terminal = true;
            if (harness != null) harness.close();
            throw new IllegalStateException("PIPELINE_ALREADY_STARTED_OR_REENTRANT");
        }
        started = true;
        try {
            evidence.safe(input.text());
            evidence.append("CALLER_MIGRATION_REQUEST_REGISTERED", Json.object("exactText", input.text(),
                    "fingerprint", input.fingerprint()));
            if (staticResolution != null) {
                String exact = MigrationInput.utf8(staticResolution); evidence.safe(exact);
                ExecutionPlan.readStaticAuthority(staticResolution);
                evidence.append("CALLER_RESOLUTION_REGISTERED", Json.object("resolutionReference", "host-static-authority",
                        "exactText", exact, "fingerprint", resolutionFingerprint()));
            }
            harness = new ControlledHarness(trustedRoot, input.sourceRoot(), input.targetRoot(), config, client, true);
            harness.freezeTrustedInputs(); harness.registerTargetMetadata();
            roles = new RoleExecutor(harness, evidence);
            source = new SourceBroker(harness.executionSourceBoundary(), RepositoryFiles.Limits.defaults(),
                    Set.of(), Set.of(), this::safeContent);
            target = new TargetContentBroker(harness.executionTargetMetadata(), RepositoryFiles.Limits.defaults(),
                    Set.of(), Set.of(), this::safeContent);
            master("REGISTER_INPUTS", Json.object("caller", input.text(), "callerFingerprint", input.fingerprint(),
                    "sourceMetadata", harness.executionSourceBoundary().metadata().view(),
                    "targetMetadata", harness.executionTargetMetadata().metadata().view()));
            gates = new ExecutionGates(harness, evidence, trustedRoot);
            master("ACCEPT_RUNTIME_GATES", Json.object("gatePurpose", "INPUT_REGISTRATION"));
            Map<String,Object> delivered = gates.delivery();
            target.authorizeDiscovery(targetIdentity(), map(delivered.get("discoveryDeclarationFingerprint")),
                    map(delivered.get("discoveryCheckFingerprint")));
            source.authorizeAccess(sourceIdentity(), map(delivered.get("discoveryCheckFingerprint")),
                    map(delivered.get("sourceCheckFingerprint")));
            // This subset observes a complete, bounded, non-Git fixture. Git state is never approximated.
            if (!"NON_GIT".equals(harness.executionTargetMetadata().metadata().repositoryKind()))
                throw new Halt("BLOCKED", "SEMANTIC_GIT_INDEX_OBSERVATION_UNAVAILABLE");
            if (java.nio.file.Files.exists(input.sourceRoot().resolve(".git"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new Halt("BLOCKED", "SOURCE_GIT_OBSERVATION_UNAVAILABLE");
            sourceBaseline = source.hostSnapshot(Set.of());
            targetCurrent = target.hostSnapshot(Set.of());
            recordObservation("PRE_ANALYSIS", sourceBaseline, targetCurrent);
            analyze(TrustedInputs.Role.SOURCE_ANALYSIS, "SOURCE_ANALYSIS", "source-analysis.json");
            analyze(TrustedInputs.Role.ANALYSIS, "TARGET_ANALYSIS", "target-analysis.json");
            analyze(TrustedInputs.Role.PLANNING, "MIGRATION_PLAN", "migration-plan.json");
            preflightAndImplement();
        } catch (Halt stopped) { status = stopped.status; code = stopped.code; }
        catch (ControlledHarness.Stop stopped) {
            status = "FAILED"; code = safeCode(stopped);
        } catch (IllegalArgumentException malformed) { status = "FAILED"; code = "MALFORMED_AUTHORITY_OR_RESPONSE"; }
        catch (IllegalStateException unavailable) { status = "BLOCKED"; code = safeCode(unavailable); }
        catch (Exception unavailable) { status = "FAILED"; code = "REPOSITORY_OR_RUNTIME_BOUNDARY_REJECTED"; }
        finally {
            if (source != null) source.close();
            if (target != null) target.close();
            observeFinalEffects();
            if (harness != null) harness.close();
            if (specialistFailure != null) { status = specialistFailure.status; code = specialistFailure.code; }
        }
        Map<String,Object> report;
        try {
            report = Json.object("status", status, "code", code, "migrationMode", "SOURCE_TO_TARGET",
                    "runId", roles == null ? null : roles.runId(), "runAuthorityBundleFingerprint", bundleFingerprint,
                    "phases", phases, "roleTurns", roles == null ? List.of() : roles.history(),
                    "acceptedArtifacts", artifacts.views(), "nonacceptedOutputs", nonacceptedOutputs,
                    "orchestrationFailures", orchestrationFailures,
                    "finalRepositoryEffects", finalEffects,
                    "gateArtifacts", gates == null ? null : gates.delivery(),
                    "evidence", evidence.snapshot(), "realProviderE2EProven", false,
                    "limitations", List.of("CURRENT_SESSION_IN_MEMORY", "POINT_IN_TIME_FILESYSTEM_CHECKS",
                            "NO_PROCESS_OR_NETWORK_TOOLS", "NO_GIT_STATE_IMPLEMENTATION", "NO_LIVE_PROVIDER_VERIFICATION"));
            evidence.safe(Json.write(report));
        } catch (RuntimeException rejected) {
            status = "FAILED"; code = specialistFailure == null ? "FINAL_EVIDENCE_UNAVAILABLE" : specialistFailure.code;
            List<Map<String,Object>> fallbackFailures = new ArrayList<>(orchestrationFailures);
            // Existing entries were screened before retention (or are fixed host diagnostics).
            // Preserve those without copying any of the rejected report or exception payload.
            fallbackFailures.add(Json.object("stage", "FINAL_REPORT", "code", "FINAL_EVIDENCE_UNAVAILABLE"));
            report = Json.object("status", status, "code", code, "realProviderE2EProven", false,
                    "orchestrationFailures", fallbackFailures);
        }
        terminal = true;
        return new Result(status, code, report);
    }

    private void analyze(TrustedInputs.Role role, String artifactRole, String filename) throws Exception {
        if (role == TrustedInputs.Role.ANALYSIS) artifacts.required("SOURCE_ANALYSIS");
        if (role == TrustedInputs.Role.PLANNING) { artifacts.required("SOURCE_ANALYSIS"); artifacts.required("TARGET_ANALYSIS"); }
        RoleExecutor.Invocation invocation = roles.begin(role, null, authorities(), null);
        gates.check("BEFORE_INVOCATION", invocation, authorities(), null);
        if (role == TrustedInputs.Role.SOURCE_ANALYSIS) source.activate(invocation.id(), role.id);
        if (role == TrustedInputs.Role.ANALYSIS) target.activate(invocation.id(), role.id, Set.of(), List.of());
        Map<String,String> observedPaths = new HashMap<>();
        List<Map<String,Object>> receipts = new ArrayList<>();
        AtomicReference<Map<String,Object>> artifact = new AtomicReference<>();
        RoleExecutor.Reply reply;
        while (true) {
            Map<String,Object> data = Json.object("task", "PRODUCE_ROLE_ARTIFACT", "artifactName", filename,
                    "artifactRole", artifactRole, "caller", input.text(), "callerFingerprint", input.fingerprint(),
                    "inspectionAccounting", "Non-null counts are exact distinct paths in this invocation: filesDiscovered counts listed/metadata/read/search files; "
                            + "filesInspected counts read/search content files; sampleSize counts inspectedPaths. Repeated operations count once. "
                            + "modulesDiscovered counts declared observed module directories; modulesInspected counts those containing host CONTENT observations, independent of evidenceIds. "
                            + "Use null for counts with unavailable or different sampling units. Roots require observed directories.",
                    "acceptedArtifacts", artifacts.views(), "runtimeGates", gates.delivery(),
                    "sourceScopeIdentity", sourceIdentity(), "targetScopeIdentity", targetIdentity(),
                    "availableOperations", operations(role), "toolProtocol", toolShape(), "toolResults", receipts);
            reply = roles.turn(invocation, data, artifactRole, exact -> {
                Map<String,Object> candidate = ArtifactContracts.validate(role, exact.getBytes(StandardCharsets.UTF_8), input, artifacts.bytes());
                observedEvidence(candidate, role, observedPaths);
                artifact.set(candidate);
            });
            checkLive();
            gates.check("CONTEXT_ENTRY", invocation, authorities(), null);
            if (!reply.kind().equals("TOOL_REQUEST")) break;
            receipts.add(executeTool(invocation, reply.toolRequest(), observedPaths));
        }
        if (role == TrustedInputs.Role.SOURCE_ANALYSIS) source.endInvocation();
        if (role == TrustedInputs.Role.ANALYSIS) target.endInvocation();
        unchanged();
        gates.check("AFTER_INVOCATION", invocation, authorities(), null);
        roles.finish(invocation);
        String reported = (String) artifact.get().get("status");
        evidence.append("ANALYSIS_OR_PLAN_VERIFIED", Json.object("invocationId", invocation.id(), "role", role.id,
                "providerResponseId", reply.providerResponseId(), "artifactFingerprint", reply.artifactFingerprint(),
                "reportedStatus", reported, "observedRepositoryEffects", "NONE"));
        phases.add(Json.object("role", role.id, "invocationId", invocation.id(), "status", reported));
        if (!"SUCCESS".equals(reported)) {
            Map<String,Object> stoppedOutput = Json.object("acceptanceStatus", "NOT_ACCEPTED",
                    "role", role.id, "invocationBinding", reply.binding(), "providerResponseId", reply.providerResponseId(),
                    "exactText", reply.artifactText(), "fingerprint", reply.artifactFingerprint(), "reportedStatus", reported);
            evidence.append("VALIDATED_NONACCEPTED_OUTPUT", stoppedOutput);
            nonacceptedOutputs.add(stoppedOutput);
            Halt specialistStop = new Halt(reported.equals("FAILED") ? "FAILED" : "BLOCKED", role == TrustedInputs.Role.SOURCE_ANALYSIS
                    ? "SOURCE_ANALYSIS_" + reported : role == TrustedInputs.Role.ANALYSIS
                    ? "TARGET_ANALYSIS_" + reported : "MIGRATION_PLAN_" + reported);
            if (reported.equals("FAILED")) {
                specialistFailure = specialistStop;
                status = specialistStop.status; code = specialistStop.code;
            }
            try {
                master(reported.equals("FAILED") ? "STOP_FAILED" : "STOP_BLOCKED", Json.object(
                        "subjectInvocationId", invocation.id(), "candidateArtifactText", reply.artifactText(),
                        "candidateFingerprint", reply.artifactFingerprint(), "reportedStatus", reported));
            } catch (RuntimeException rejection) {
                if (!reported.equals("FAILED")) throw rejection;
                retainStopFailure(invocation.id(), rejection);
                throw specialistStop;
            }
            throw specialistStop;
        }
        RoleExecutor.Reply acceptance = master("ACCEPT_ARTIFACT", Json.object("subjectInvocationId", invocation.id(),
                "subjectRole", role.id, "subjectProviderResponseId", reply.providerResponseId(),
                "candidateArtifactText", reply.artifactText(), "candidateFingerprint", reply.artifactFingerprint(),
                "independentVerification", Json.object("schema", "PASS", "identity", "PASS", "observedEffects", "NONE",
                        "readEvidence", receipts)));
        unchanged();
        artifacts.accept(artifactRole, reply.artifactText(), reply.binding(), reply.providerResponseId(),
                (String)acceptance.binding().get("invocationId"), acceptance.providerResponseId());
    }

    private void retainStopFailure(String subjectInvocationId, RuntimeException failure) {
        // Do not retain arbitrary exception text, provider payloads, or stacks.
        Map<String,Object> diagnostic = Json.object("stage", "MASTER_STOP_FAILED", "subjectInvocationId", subjectInvocationId,
                "exceptionType", failure.getClass().getSimpleName(), "code", failure instanceof Halt halt ? halt.code
                        : failure instanceof IllegalStateException state ? safeCode(state) : "MASTER_STOP_RESPONSE_OR_RUNTIME_REJECTED",
                "acceptanceStatus", "NOT_ACCEPTED");
        try {
            evidence.append("ORCHESTRATION_FAILURE_OBSERVED", diagnostic);
            orchestrationFailures.add(diagnostic);
        } catch (RuntimeException retentionFailure) {
            // Fixed host-only fallback preserves the secondary failure without retaining rejected material.
            orchestrationFailures.add(Json.object("stage", "MASTER_STOP_FAILED", "code", "ORCHESTRATION_DIAGNOSTIC_RETENTION_REJECTED",
                    "acceptanceStatus", "NOT_ACCEPTED"));
        }
    }

    private RoleExecutor.Reply master(String action, Map<String,Object> details) {
        if (gates == null && !action.equals("REGISTER_INPUTS"))
            throw new IllegalStateException("INITIAL_REGISTRATION_ONLY_WITHOUT_GATES");
        RoleExecutor.Invocation invocation = roles.begin(TrustedInputs.Role.MASTER, bundleFingerprint, authorities(), null);
        Map<String,Object> gateDelivery = gates == null ? null : gates.check(
                action.equals("ACCEPT_RUNTIME_GATES") ? "INPUT_REGISTRATION" : action.equals("ACCEPT_ARTIFACT")
                        ? "AUTHORITY_ACCEPTANCE" : "BEFORE_INVOCATION", invocation, authorities(), bundleFingerprint);
        Map<String,Object> proposed = Json.object("action", action, "subjectFingerprint", Json.evidenceFingerprint(details));
        RoleExecutor.Reply reply = roles.turn(invocation, Json.object("proposedDecision", proposed,
                "details", details, "runtimeGates", gateDelivery), null, ignored -> {});
        checkLive();
        if (gates != null) gates.check("AFTER_INVOCATION", invocation, authorities(), bundleFingerprint);
        roles.finish(invocation);
        if ("BLOCKED".equals(reply.decision()) || "FAILED".equals(reply.decision())) {
            Map<String,Object> rejection = Json.object("acceptanceStatus", "NOT_ACCEPTED", "role", "MASTER",
                    "invocationBinding", reply.binding(), "providerResponseId", reply.providerResponseId(),
                    "proposedDecision", proposed, "decision", reply.decision(), "reasons", reply.reasons());
            evidence.append("MASTER_REJECTION_OBSERVED", rejection);
            nonacceptedOutputs.add(rejection);
            throw new Halt((String)reply.decision(), "MASTER_REJECTED_" + action);
        }
        evidence.append("MASTER_DECISION_OBSERVED", Json.object("invocationBinding", reply.binding(),
                "providerResponseId", reply.providerResponseId(), "decision", reply.decision()));
        return reply;
    }

    private Map<String,Object> executeTool(RoleExecutor.Invocation invocation, Map<String,Object> request,
                                           Map<String,String> observedPaths) throws Exception {
        checkLive(); harness.verifyExecutionBoundary();
        String scope = (String)request.get("scope"), operation = (String)request.get("operation"), path = (String)request.get("path");
        if (!operations(invocation.role()).contains(operation)) throw new Halt("FAILED", "ROLE_OPERATION_DENIED");
        boolean sourceOperation = scope.equals("SOURCE");
        if (!(sourceOperation || scope.equals("TARGET")) || !Objects.equals(request.get("rootFilesystemIdentity"),
                sourceOperation ? sourceIdentity() : targetIdentity())) throw new Halt("FAILED", "TOOL_ROOT_SCOPE_MISMATCH");
        Map<String,Object> result;
        if (sourceOperation) {
            if (invocation.role() != TrustedInputs.Role.SOURCE_ANALYSIS || request.get("content") != null
                    || request.get("expectedBeforeFingerprint") != null)
                throw new Halt("FAILED", "SOURCE_AUTHORITY_DENIED");
            result = source.execute(invocation.id(), invocation.role().id, operation, path, (String)request.get("query"));
        } else {
            if (invocation.role() == TrustedInputs.Role.SOURCE_ANALYSIS || invocation.role() == TrustedInputs.Role.PLANNING)
                throw new Halt("FAILED", "TARGET_AUTHORITY_DENIED");
            result = target.execute(invocation.id(), invocation.role().id, operation, path, (String)request.get("query"),
                    request.get("expectedBeforeFingerprint") == null ? null : map(request.get("expectedBeforeFingerprint")),
                    (String)request.get("content"));
        }
        if (operation.startsWith("READ_") || operation.startsWith("SEARCH_")) observedPaths.put(path, "CONTENT");
        if (operation.startsWith("LIST_")) {
            observedPaths.putIfAbsent(path, "DIRECTORY");
            for (Object entry : ExecutionPlan.list(map(result.get("result")).get("entries"))) {
                Map<String,Object> listed = map(entry);
                observedPaths.putIfAbsent((String)listed.get("pathKey"), (String)listed.get("fileType"));
            }
        }
        if (operation.startsWith("INSPECT_"))
            observedPaths.putIfAbsent(path, (String)map(result.get("result")).get("fileType"));
        Map<String,Object> receipt = Json.object("format", "UNTRUSTED_DATA_V1", "operationId", request.get("operationId"),
                "requestFingerprint", Json.evidenceFingerprint(request), "result", result);
        evidence.append("BROKER_OPERATION_OBSERVED", receipt);
        harness.verifyExecutionBoundary(); checkLive();
        return receipt;
    }

    private void observedEvidence(Map<String,Object> artifact, TrustedInputs.Role role, Map<String,String> observations) {
        if (role == TrustedInputs.Role.PLANNING) return;
        observedInventory(artifact, role, observations);
        for (Object value : ExecutionPlan.list(artifact.get("evidence"))) {
            Map<String,Object> entry = map(value);
            String path = (String)entry.get("path");
            String required = "DIRECTORY".equals(entry.get("kind")) ? "DIRECTORY" : "CONTENT";
            if (path != null && !required.equals(observations.get(path)))
                throw new IllegalArgumentException("UNOBSERVED_ANALYSIS_EVIDENCE");
        }
        for (Object value : ExecutionPlan.list(map(artifact.get("analysisCoverage")).get("areas"))) {
            countObserved(map(value), "sampleSize", ExecutionPlan.list(map(value).get("inspectedPaths")).size());
            for (Object path : ExecutionPlan.list(map(value).get("inspectedPaths"))) {
                String observed = observations.get((String)path);
                if (!"CONTENT".equals(observed) && !"DIRECTORY".equals(observed))
                    throw new IllegalArgumentException("UNOBSERVED_INSPECTED_PATH");
            }
        }
        if (role == TrustedInputs.Role.SOURCE_ANALYSIS) {
            if (artifact.get("operationEntryPoint") != null)
                observedEntry(map(artifact.get("operationEntryPoint")), observations);
            for (Object candidate : ExecutionPlan.list(map(artifact.get("analysisScope")).get("candidateEntryPoints")))
                observedEntry(map(candidate), observations);
            for (Object hop : ExecutionPlan.list(artifact.get("callChain"))) observedEntry(map(hop), observations);
        }
    }
    private void observedEntry(Map<String,Object> entry, Map<String,String> observations) {
        if ("OBSERVED".equals(entry.get("findingStatus")) && !"CONTENT".equals(observations.get(entry.get("path"))))
            throw new IllegalArgumentException("UNOBSERVED_ANALYSIS_ENTRY_PATH");
    }
    private void observedInventory(Map<String,Object> artifact, TrustedInputs.Role role, Map<String,String> observations) {
        Map<String,Object> inventory = map(map(artifact.get("analysisCoverage")).get("inventory"));
        countObserved(inventory, "filesInspected", observations.values().stream().filter("CONTENT"::equals).count());
        countObserved(inventory, "filesDiscovered", observations.values().stream()
                .filter(kind -> kind.equals("CONTENT") || kind.equals("REGULAR_FILE")).count());
        if (role != TrustedInputs.Role.ANALYSIS) return;
        Set<String> modules = new HashSet<>();
        long inspected = 0;
        for (Object value : ExecutionPlan.list(artifact.get("modules"))) {
            var module = map(value); String path = (String)module.get("path");
            requireDirectory(path, observations);
            if (!modules.add(path)) throw new IllegalArgumentException("DUPLICATE_MODULE_PATH");
            for (String key : List.of("sourceRoots", "testRoots", "resourceRoots"))
                for (Object root : ExecutionPlan.list(module.get(key))) requireDirectory((String)root, observations);
            // Observation keys are already canonical repository-relative paths. Path.startsWith
            // compares components, so src/foo never contains src/foobar. Empty module is root.
            boolean contentObserved = observations.entrySet().stream()
                    .filter(entry -> "CONTENT".equals(entry.getValue()))
                    .map(entry -> Path.of(entry.getKey()))
                    .anyMatch(file -> path.isEmpty() || file.startsWith(Path.of(path))
                            && file.getNameCount() > Path.of(path).getNameCount());
            if (contentObserved) inspected++;
        }
        countObserved(inventory, "modulesDiscovered", modules.size());
        countObserved(inventory, "modulesInspected", inspected);
    }
    private void requireDirectory(String path, Map<String,String> observations) {
        if (!"DIRECTORY".equals(observations.get(path))) throw new IllegalArgumentException("UNOBSERVED_MODULE_DIRECTORY");
    }
    private void countObserved(Map<String,Object> inventory, String key, long observed) {
        Object claim = inventory.get(key);
        if (claim != null && ((Number)claim).longValue() != observed)
            throw new IllegalArgumentException("INSPECTION_INVENTORY_MISMATCH");
    }
    private void unchanged() throws Exception {
        RepositoryFiles.Snapshot currentSource = source.hostSnapshot(Set.of()), currentTarget = target.hostSnapshot(Set.of());
        recordObservation("ACCEPTANCE", currentSource, currentTarget);
        if (!sourceBaseline.equals(currentSource) || !targetCurrent.equals(currentTarget))
            throw new Halt("FAILED", "UNAUTHORIZED_REPOSITORY_EFFECTS");
    }
    private void preflightAndImplement() throws Exception {
        if (staticResolution == null) blockedPreflight("VALIDATION_EXECUTION_POLICY_AND_OBLIGATION_MECHANISM_REQUIRED");
        ExecutionPlan.StaticAuthority authority = ExecutionPlan.readStaticAuthority(staticResolution);
        ExecutionPlan.parse(Json.parse(artifacts.required("MIGRATION_PLAN").text()), authority.rules());
        // No plan grants or implementation invocation are issued until canonical acceptance is integrated.
        blockedPreflight("CANONICAL_IMPLEMENTATION_ACCEPTANCE_UNAVAILABLE");
    }
    private void blockedPreflight(String blocker) {
        phases.add(Json.object("phase", "WHOLE_PLAN_PREFLIGHT", "status", "BLOCKED", "reason", blocker));
        master("STOP_BLOCKED", Json.object("reason", blocker, "planFingerprint", artifacts.required("MIGRATION_PLAN").fingerprint(),
                "implementation", "NOT_PERFORMED", "validation", "NOT_PERFORMED"));
        throw new Halt("BLOCKED", blocker);
    }
    private void recordObservation(String stage, RepositoryFiles.Snapshot sourceState, RepositoryFiles.Snapshot targetState) {
        evidence.append("READ_ONLY_REPOSITORY_OBSERVATION", Json.object("stage", stage,
                "source", snapshotView(sourceState), "target", snapshotView(targetState),
                "limits", "POINT_IN_TIME_MODELED_STATE_ONLY"));
    }
    private void observeFinalEffects() {
        if (sourceBaseline == null || targetCurrent == null) {
            finalEffects = Json.object("observation", "NOT_REACHED", "mutationAuthorityIssued", false); return;
        }
        try {
            RepositoryFiles.Snapshot observedSource = source.observeTerminatedSnapshot(Set.of());
            RepositoryFiles.Snapshot observedTarget = target.observeTerminatedSnapshot(Set.of());
            recordObservation("TERMINAL", observedSource, observedTarget);
            boolean equal = sourceBaseline.equals(observedSource) && targetCurrent.equals(observedTarget);
            finalEffects = Json.object("observation", "COMPLETE_WITHIN_MODELED_SCOPE", "sourceUnchanged", sourceBaseline.equals(observedSource),
                    "targetUnchanged", targetCurrent.equals(observedTarget), "mutationAuthorityIssued", false);
            if (!equal) { status = "FAILED"; code = "UNAUTHORIZED_REPOSITORY_EFFECTS"; }
        } catch (Exception unavailable) {
            finalEffects = Json.object("observation", "UNKNOWN", "mutationAuthorityIssued", false);
            if (!status.equals("FAILED")) { status = "BLOCKED"; code = "FINAL_REPOSITORY_OBSERVATION_UNAVAILABLE"; }
        }
    }
    private static Map<String,Object> snapshotView(RepositoryFiles.Snapshot state) {
        return Json.object("rootFilesystemIdentity", state.rootFilesystemIdentity(), "entries", state.entries(),
                "protectedPaths", state.protectedPaths(), "excludedPaths", state.excludedPaths());
    }
    private List<Map<String,Object>> authorities() {
        List<Map<String,Object>> values = new ArrayList<>(); values.add(input.fingerprint());
        if (staticResolution != null) values.add(resolutionFingerprint());
        if (harness != null) for (Map<String,Object> item : harness.executionTrustedInputs().registry())
            values.add(map(item.get("artifactFingerprint")));
        values.addAll(artifacts.fingerprints());
        if (gates != null) values.addAll(gates.declarationFingerprints());
        return ExecutionGates.sorted(values);
    }
    private Map<String,Object> resolutionFingerprint() { return Json.fingerprint("CALLER_RESOLUTION", staticResolution); }
    private String sourceIdentity() { return harness.executionSourceBoundary().metadata().rootFilesystemIdentity(); }
    private String targetIdentity() { return harness.executionTargetMetadata().metadata().rootFilesystemIdentity(); }
    private void safeContent(String text) { evidence.safe(text); checkLive(); }
    private void checkLive() { if (terminal) throw new Halt("FAILED", "PIPELINE_TERMINATED"); }
    private static Map<String,Object> map(Object value) { return ExecutionPlan.map(value); }
    private static List<String> operations(TrustedInputs.Role role) {
        return switch (role) {
            case SOURCE_ANALYSIS -> List.of("READ_SOURCE_TEXT", "LIST_SOURCE_PATHS", "SEARCH_SOURCE_TEXT", "INSPECT_SOURCE_METADATA");
            case ANALYSIS -> List.of("READ_TARGET_TEXT", "LIST_TARGET_PATHS", "SEARCH_TARGET_TEXT", "INSPECT_TARGET_METADATA");
            case DOMAIN, PERSISTENCE, SERVICE, TESTS -> List.of("READ_TARGET_TEXT", "CREATE_TARGET_FILE", "WRITE_TARGET_TEXT");
            case VALIDATION -> List.of("READ_TARGET_TEXT");
            default -> List.of();
        };
    }
    private static Map<String,Object> toolShape() {
        return Json.object("operationId", "unique-current-session-id", "invocationId", "echo-current-invocation",
                "role", "echo-current-role", "operation", "one-listed-operation", "scope", "SOURCE or TARGET",
                "rootFilesystemIdentity", "echo-designated-scope", "path", "canonical-relative-path",
                "query", null, "expectedBeforeFingerprint", null, "content", null);
    }
    private static String safeCode(IllegalStateException failure) {
        String message = failure.getMessage();
        return message != null && message.matches("(?:BLOCKED:)?[A-Z][A-Z0-9_]{1,100}")
                ? message.replace("BLOCKED:", "") : "RUNTIME_CAPABILITY_UNAVAILABLE";
    }
    private static final class Halt extends RuntimeException {
        final String status, code;
        Halt(String status, String code) { super(code); this.status = status; this.code = code; }
    }
}
