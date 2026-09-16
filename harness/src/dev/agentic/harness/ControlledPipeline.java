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
    private final ValidationProfile validationProfile;
    private ValidationRuntime validation;
    private RepositoryFiles validationSourceObserver, validationTargetObserver;
    private Map<String,Object> validationSourceModes, validationTargetModes;
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
    private RepositoryFiles.Snapshot sourceBaseline, targetBaseline, targetCurrent;
    private ExecutionEvidence implementation;
    private ExecutionEvidence.Dispatch activeDispatch;
    private String activeImplementationText;
    private String activeImplementationOutcome = "INTERRUPTED_OR_NO_RESPONSE";
    private boolean mutationAuthorityIssued;
    private Map<String,Object> finalEffects;
    private Map<String,Object> bundleFingerprint;
    private boolean started, terminal;
    private String status = "BLOCKED", code = "NOT_STARTED";

    public ControlledPipeline(Path trustedRoot, MigrationInput input, ControlledHarness.Config config,
                              ResponsesClient client) {
        this(trustedRoot, input, config, client, null);
    }
    /** Optional exact caller resolution supplies independent exact-file implementation predicates. */
    public ControlledPipeline(Path trustedRoot, MigrationInput input, ControlledHarness.Config config,
                              ResponsesClient client, byte[] staticResolution) {
        this(trustedRoot, input, config, client, staticResolution, null);
    }
    /** Validation opt-in is a trusted host object, never model/caller JSON configuration. */
    public ControlledPipeline(Path trustedRoot, MigrationInput input, ControlledHarness.Config config,
                              ResponsesClient client, byte[] staticResolution, ValidationProfile validationProfile) {
        this.validationProfile = validationProfile;
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
            targetBaseline = targetCurrent;
            if (validationProfile != null) {
                validationSourceObserver = new RepositoryFiles(input.sourceRoot(), sourceIdentity(), RepositoryFiles.Limits.defaults(),
                        Set.of(), Set.of(), this::safeContent);
                validationTargetObserver = new RepositoryFiles(input.targetRoot(), targetIdentity(), RepositoryFiles.Limits.defaults(),
                        Set.of(), Set.of(), this::safeContent).validationOutputs();
                validationSourceModes = validationSourceObserver.validationModes(sourceBaseline);
                validationTargetModes = validationTargetObserver.validationModes(targetBaseline);
            }
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
        Map<String,Object> terminalSummary = terminalSummary();
        Map<String,Object> report;
        try {
            report = Json.object("status", status, "code", code, "migrationMode", "SOURCE_TO_TARGET",
                    "runId", roles == null ? null : roles.runId(), "runAuthorityBundleFingerprint", bundleFingerprint,
                    "phases", phases, "roleTurns", roles == null ? List.of() : roles.history(),
                    "acceptedArtifacts", artifacts.views(), "nonacceptedOutputs", nonacceptedOutputs,
                    "orchestrationFailures", orchestrationFailures,
                    "finalRepositoryEffects", finalEffects,
                    "terminalSummary", terminalSummary, "validationExecuted", validation != null && validation.started(),
                    "validationEvidence", validation == null ? null : validation.facts(),
                    "implementationLedger", implementation == null ? List.of() : implementation.ledger(),
                    "gateArtifacts", gates == null ? null : gates.delivery(),
                    "evidence", evidence.snapshot(), "realProviderE2EProven", false,
                    "limitations", List.of("CURRENT_SESSION_IN_MEMORY", "POINT_IN_TIME_FILESYSTEM_CHECKS",
                            "NO_GENERIC_PROCESS_OR_NETWORK_TOOLS", "NO_GIT_STATE_IMPLEMENTATION", "NO_LIVE_PROVIDER_VERIFICATION"));
            screenFinalReport(report);
        } catch (RuntimeException rejected) {
            List<Map<String,Object>> fallbackFailures = new ArrayList<>(orchestrationFailures);
            // Existing entries were screened before retention (or are fixed host diagnostics).
            // Preserve those without copying any of the rejected report or exception payload.
            fallbackFailures.add(Json.object("stage", "FINAL_REPORT", "code", "FINAL_EVIDENCE_UNAVAILABLE"));
            Map<String,Object> fallback = new LinkedHashMap<>(terminalSummary);
            fallback.put("orchestrationFailures", fallbackFailures);
            report = map(Json.object("summary", fallback).get("summary"));
        }
        terminal = true;
        return new Result(status, code, report);
    }

    /** Only host facts and previously screened identifiers; no report, journal or model text. */
    private Map<String,Object> terminalSummary() {
        List<Map<String,Object>> accepted = artifacts.terminalReferences();
        List<Map<String,Object>> mutations = new ArrayList<>(), nonaccepted = new ArrayList<>();
        if (target != null) for (var mutation : target.mutationEvidence()) {
            var grant = map(mutation.get("grant"));
            Object invocation = grant.get("invocationId");
            var acceptedResult = accepted.stream().filter(a -> invocation.equals(a.get("invocationId"))).findFirst();
            var candidate = nonacceptedOutputs.stream().filter(a -> invocation.equals(a.get("invocationId"))).findFirst();
            Map<String,Object> reference = Json.object("role", grant.get("role"), "invocationId", invocation,
                    "acceptanceStatus", acceptedResult.isPresent() ? "ACCEPTED" : "NOT_ACCEPTED",
                    "fingerprint", acceptedResult.map(a -> a.get("fingerprint"))
                            .orElseGet(() -> candidate.map(a -> a.get("fingerprint")).orElse(null)));
            if (acceptedResult.isEmpty()) nonaccepted.add(reference);
            mutations.add(Json.object("grantId", grant.get("grantId"), "role", grant.get("role"),
                    "invocationId", invocation, "pathKey", grant.get("pathKey"), "action", grant.get("action"),
                    "grantFingerprint", Json.evidenceFingerprint(grant),
                    "beforeStateFingerprint", Json.evidenceFingerprint(mutation.get("beforeState")),
                    "afterStateFingerprint", Json.evidenceFingerprint(mutation.get("afterState")),
                    "parentMetadataEffectFingerprint", mutation.get("parentMetadataEffect") == null ? null
                            : Json.evidenceFingerprint(mutation.get("parentMetadataEffect")),
                    "resultReference", reference));
        }
        Map<String,Object> effects = new LinkedHashMap<>();
        for (String field : List.of("observation", "sourceUnchanged", "targetUnchanged", "mutationAuthorityIssued",
                "effectClassification", "targetMatchesAuthorizedEffects", "sourceObservation", "targetObservation"))
            effects.put(field, finalEffects.get(field));
        effects.put("authorizedMutations", mutations);
        for (String field : List.of("actualSource", "actualTarget"))
            effects.put(field + "Fingerprint", finalEffects.get(field) == null ? null : Json.evidenceFingerprint(finalEffects.get(field)));
        effects.put("rollback", "NOT_PERFORMED");
        return Json.object("runId", roles == null ? null : roles.runId(), "status", status, "code", code,
                "finalRepositoryEffects", effects, "acceptedArtifacts", accepted, "nonacceptedOutputs", nonaccepted,
                "validationExecuted", validation != null && validation.started(),
                "validationEvidence", validation == null ? null : validation.facts(), "realProviderE2EProven", false,
                "orchestrationFailures", orchestrationFailures);
    }

    private void screenFinalReport(Map<String,Object> report) {
        if (implementation == null) { evidence.safe(Json.write(report)); return; }
        // These two host-owned journals can together exceed one inspection budget.
        // Inspect every immutable record, preserving its outer field context. No model
        // artifact is split, and each record remains subject to all original scanner limits.
        Map<String,Object> header = new LinkedHashMap<>(report);
        for (String field : List.of("evidence", "implementationLedger")) {
            for (Object record : ExecutionPlan.list(report.get(field)))
                evidence.safe(Json.write(Json.object(field, List.of(record))));
            header.put(field, List.of());
        }
        evidence.safe(Json.write(header));
        evidence.verify(); checkLive();
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
                action.equals("ACCEPT_RUNTIME_GATES") ? "INPUT_REGISTRATION" : (action.equals("ACCEPT_ARTIFACT") || action.startsWith("ACCEPT_IMPLEMENTATION") || action.equals("ACCEPT_VALIDATION"))
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
        if (implementation != null) unchanged();
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
                    (String)request.get("content"), (String)request.get("grantId"));
        }
        if (implementation != null) {
            targetCurrent = expectedTarget();
            if (validationProfile != null && operation.equals("CREATE_TARGET_FILE")) {
                var actualModes = map(validationTargetObserver.validationModes(targetCurrent).get("modes"));
                var expectedModes = new LinkedHashMap<>(map(validationTargetModes.get("modes")));
                expectedModes.put(path, actualModes.get(path));
                validationTargetModes = Json.object("modes", expectedModes);
            }
            unchanged();
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
        // Revalidate the exact accepted artifacts; neither a provider grant nor a fresh plan is an input.
        ArtifactStore.Artifact acceptedPlan = artifacts.required("MIGRATION_PLAN");
        Map<String,Object> planData = ArtifactContracts.validate(TrustedInputs.Role.PLANNING, acceptedPlan.bytes(), input, artifacts.bytes());
        ExecutionPlan plan = ExecutionPlan.parse(planData, authority.rules());
        List<TrustedInputs.Role> sequence = List.of(TrustedInputs.Role.DOMAIN, TrustedInputs.Role.PERSISTENCE,
                TrustedInputs.Role.SERVICE, TrustedInputs.Role.TESTS);
        if (!plan.steps().stream().map(ExecutionPlan.Step::role).toList().equals(sequence))
            blockedPreflight("EXACT_03_06_SEQUENCE_REQUIRED");
        unchanged();
        // Observing every proposed path now also checks safe existing parents, aliases and links.
        try { target.hostSnapshot(new LinkedHashSet<>(plan.steps().stream().map(ExecutionPlan.Step::path).toList())); }
        catch (java.io.IOException unavailable) { throw new Halt("BLOCKED", "IMPLEMENTATION_PREFLIGHT_TARGET_UNAVAILABLE"); }
        plan.validateImplementationTraceability();
        Map<String,Object> bundle = implementationBundle();
        var metadata = harness.executionTargetMetadata().metadata();
        implementation = new ExecutionEvidence(plan, roles.runId(), bundle,
                Json.object("declaredRoot", metadata.declaredRoot(), "resolvedRoot", metadata.resolvedRoot(),
                        "repositoryKind", "NON_GIT", "headCommit", null, "semanticIndexStateFingerprint", null), null, evidence);
        bundleFingerprint = implementation.bundleFingerprint();
        implementation.preflight(implementation.manifest(targetCurrent));
        Map<String,Object> preflight = Json.object("runId", roles.runId(), "runAuthorityBundle", bundle,
                "runAuthorityBundleFingerprint", bundleFingerprint, "sourceBaseline", snapshotView(sourceBaseline),
                "targetBaseline", snapshotView(targetBaseline), "steps", plan.steps().stream().map(ExecutionPlan.Step::assignment).toList(),
                "mutationAuthorityIssued", false, "validationExecution", validationProfile == null ? "UNAVAILABLE" : validationProfile.view(),
                "terminalBoundary", validationProfile == null ? "VALIDATION_EXECUTION_RUNTIME_REQUIRED" : "MASTER_VALIDATION_ACCEPTANCE");
        evidence.append("IMPLEMENTATION_PREFLIGHT", preflight);
        master("ACCEPT_IMPLEMENTATION_PREFLIGHT", preflight);
        unchanged();
        phases.add(Json.object("phase", "WHOLE_PLAN_PREFLIGHT", "status", "PASS"));
        String predecessor = "MIGRATION_PLAN";
        for (ExecutionPlan.Step step : plan.steps()) {
            implement(step, plan, artifacts.required(predecessor));
            predecessor = "IMPLEMENTATION_RESULT:" + step.id();
        }
        if (validationProfile != null) { validate(); return; }
        unchanged();
        phases.add(Json.object("phase", "VALIDATION_BOUNDARY", "status", "BLOCKED", "reason", "VALIDATION_EXECUTION_RUNTIME_REQUIRED"));
        master("STOP_BLOCKED", Json.object("reason", "VALIDATION_EXECUTION_RUNTIME_REQUIRED",
                "implementation", "03_06_MASTER_ACCEPTED", "validation", "NOT_PERFORMED"));
        throw new Halt("BLOCKED", "VALIDATION_EXECUTION_RUNTIME_REQUIRED");
    }

    private void validate() throws Exception {
        RoleExecutor.Invocation invocation = roles.begin(TrustedInputs.Role.VALIDATION, bundleFingerprint, authorities(), null);
        validation = new ValidationRuntime(validationProfile, input.targetRoot(), sourceBaseline,
                expectedTarget(), roles.runId(), invocation, artifacts, validationSourceObserver, validationTargetObserver,
                validationSourceModes, validationTargetModes);
        validation.precheck();
        gates.check("BEFORE_INVOCATION", invocation, authorities(), bundleFingerprint);
        evidence.append("VALIDATION_AUTHORITY_ISSUED", validation.authority());
        List<Map<String,Object>> receipts = new ArrayList<>();
        RoleExecutor.Reply reply;
        while (true) {
            try {
                reply = roles.turn(invocation, Json.object("task", "VALIDATE_CONTROLLED_TARGET", "artifactRole", "VALIDATION_RESULT",
                    "acceptedArtifacts", artifacts.views(), "validationAuthority", validation.authority(),
                    "hostObservedRepositoryEffects", Json.object("acceptedImplementationEffects", target.mutationEvidence(),
                            "preValidationSourceUnchanged", true, "preValidationTargetMatchesAcceptedImplementation", true,
                            "validation", validation.facts()), "runtimeGates", gates.delivery(),
                    "resultContract", validation.resultContract(), "toolResults", receipts,
                    "availableOperations", List.of("RUN_VALIDATION"), "toolProtocol", Json.object("operationId", "unique-operation",
                            "operation", "RUN_VALIDATION", "role", invocation.role().id, "invocationId", invocation.id(),
                            "authorityId", validation.authority().get("authorityId"))), "VALIDATION_RESULT",
                    exact -> validation.validateResult(Json.parse(exact)));
            } catch (RuntimeException rejected) {
                // Transport boundary checks may notice root drift first. Retain the precise
                // validation precondition failure without launching or reopening any capability.
                if (!validation.started()) validation.precheck();
                throw rejected;
            }
            checkLive();
            gates.check("CONTEXT_ENTRY", invocation, authorities(), bundleFingerprint);
            if (!reply.kind().equals("TOOL_REQUEST")) break;
            if (!"RUN_VALIDATION".equals(reply.toolRequest().get("operation"))) throw new Halt("FAILED", "ROLE_OPERATION_DENIED");
            validation.execute(invocation, reply.toolRequest(), artifacts);
            // Latch failed execution before retention or any further provider interaction.
            if (validation.status().equals("FAILED")) specialistFailure = new Halt("FAILED", validation.code());
            evidence.append("VALIDATION_EXECUTION_OBSERVED", validation.facts());
            if (!validation.started()) throw new Halt(validation.status(), validation.code());
            receipts.add(validation.facts());
        }
        gates.check("AFTER_INVOCATION", invocation, authorities(), bundleFingerprint); roles.finish(invocation);
        validation.validateResult(Json.parse(reply.artifactText()));
        validation.observe();
        if (!validation.status().equals("SUCCESS")) {
            if (validation.status().equals("FAILED")) specialistFailure = new Halt("FAILED", validation.code());
            master(validation.status().equals("FAILED") ? "STOP_FAILED" : "STOP_BLOCKED", Json.object("subjectInvocationId", invocation.id(), "candidateArtifactText", reply.artifactText(),
                    "candidateFingerprint", reply.artifactFingerprint(), "hostValidationEvidence", validation.facts()));
            throw new Halt(validation.status(), validation.code());
        }
        // Independently recheck all host facts after the candidate and again after MASTER.
        validation.validateResult(Json.parse(reply.artifactText()));
        RoleExecutor.Reply acceptance = master("ACCEPT_VALIDATION", Json.object("subjectInvocationId", invocation.id(),
                "candidateArtifactText", reply.artifactText(), "candidateFingerprint", reply.artifactFingerprint(),
                "validationAuthority", validation.authority(), "hostValidationEvidence", validation.facts(),
                "acceptedPredecessors", artifacts.views()));
        validation.observe();
        if (!validation.status().equals("SUCCESS")) throw new Halt(validation.status(), validation.code());
        validation.validateResult(Json.parse(reply.artifactText()));
        artifacts.accept("VALIDATION_RESULT", reply.artifactText(), reply.binding(), reply.providerResponseId(),
                (String)acceptance.binding().get("invocationId"), acceptance.providerResponseId());
        phases.add(Json.object("role", invocation.role().id, "invocationId", invocation.id(), "status", "SUCCESS", "acceptanceStatus", "ACCEPTED"));
        status = "SUCCESS"; code = "VALIDATION_MASTER_ACCEPTED";
    }

    private Map<String,Object> implementationBundle() {
        Object contract = harness.executionTrustedInputs().registry().stream().map(v -> map(v.get("artifactFingerprint")))
                .filter(fp -> "ORCHESTRATION_CONTRACT".equals(fp.get("artifactRole"))).findFirst().orElseThrow();
        return Json.object("bundleVersion", 1, "bundleKind", "RUN_AUTHORITY_BUNDLE_V1", "migrationMode", "SOURCE_TO_TARGET",
                "callerMigrationRequestFingerprint", input.fingerprint(), "callerResolutions", List.of(Json.object(
                        "resolutionReference", "host-static-authority", "resolutionFingerprint", resolutionFingerprint())),
                "agent00SpecificationFingerprint", roles.specification(TrustedInputs.Role.SOURCE_ANALYSIS),
                "sourceAnalysisFingerprint", artifacts.required("SOURCE_ANALYSIS").fingerprint(),
                "agent01SpecificationFingerprint", roles.specification(TrustedInputs.Role.ANALYSIS),
                "targetAnalysisFingerprint", artifacts.required("TARGET_ANALYSIS").fingerprint(),
                "agent02SpecificationFingerprint", roles.specification(TrustedInputs.Role.PLANNING),
                "migrationPlanFingerprint", artifacts.required("MIGRATION_PLAN").fingerprint(),
                "orchestrationContractFingerprint", contract, "masterSpecificationFingerprint", roles.specification(TrustedInputs.Role.MASTER),
                "capabilityRegistry", Json.object("registryVersion", 1, "specialists", Arrays.stream(TrustedInputs.Role.values())
                        .filter(r -> r != TrustedInputs.Role.MASTER).sorted(Comparator.comparing(r -> r.id))
                        .map(r -> Json.object("specialistRole", r.id, "specificationFingerprint", roles.specification(r))).toList()),
                "runtimeCapabilities", gates.capabilities().stream().sorted(Comparator.comparing(c -> (String)c.get("capabilityId"))).toList());
    }

    private void implement(ExecutionPlan.Step step, ExecutionPlan plan, ArtifactStore.Artifact predecessor) throws Exception {
        unchanged();
        String invocationId = roles.nextInvocationId();
        activeDispatch = implementation.begin(step, invocationId, implementation.manifest(targetCurrent));
        activeImplementationText = null; activeImplementationOutcome = "INTERRUPTED_OR_NO_RESPONSE";
        Map<String,Object> predecessorAcceptance = Json.object("artifactFingerprint", predecessor.fingerprint(),
                "acceptanceInvocationId", predecessor.acceptanceInvocationId(),
                "acceptanceProviderResponseId", predecessor.acceptanceProviderResponseId());
        Map<String,Object> authority = Json.object("runId", roles.runId(), "stepId", step.id(),
                "sourceAnalysisFingerprint", artifacts.required("SOURCE_ANALYSIS").fingerprint(),
                "targetAnalysisFingerprint", artifacts.required("TARGET_ANALYSIS").fingerprint(),
                "migrationPlanFingerprint", artifacts.required("MIGRATION_PLAN").fingerprint(),
                "runAuthorityBundleFingerprint", bundleFingerprint, "dispatchFingerprint", activeDispatch.fingerprint(),
                "predecessorAcceptance", predecessorAcceptance);
        TargetContentBroker.WriteGrant grant = new TargetContentBroker.WriteGrant(roles.runId() + "-grant-" + step.id(),
                invocationId, step.role().id, targetIdentity(), step.path(), step.action(),
                ExecutionEvidence.path(activeDispatch.before(), step.path()), authority);
        RoleExecutor.Invocation invocation = roles.begin(step.role(), bundleFingerprint, authorities(), activeDispatch.fingerprint());
        if (!invocation.id().equals(invocationId)) throw new Halt("FAILED", "DISPATCH_INVOCATION_MISMATCH");
        gates.check("BEFORE_INVOCATION", invocation, authorities(), bundleFingerprint);
        evidence.append("IMPLEMENTATION_GRANT_ISSUED", grant.view()); mutationAuthorityIssued = true;
        target.activate(invocationId, step.role().id, Set.of(), List.of(grant));
        List<Map<String,Object>> receipts = new ArrayList<>();
        String artifactRole = "IMPLEMENTATION_RESULT:" + step.id();
        RoleExecutor.Reply reply;
        while (true) {
            reply = roles.turn(invocation, Json.object("task", "IMPLEMENT_ASSIGNED_STEP", "artifactRole", artifactRole,
                    "acceptedArtifacts", artifacts.views(), "runtimeGates", gates.delivery(),
                    "sourceScopeIdentity", sourceIdentity(), "targetScopeIdentity", targetIdentity(),
                    "dispatch", activeDispatch.event(), "dispatchFingerprint", activeDispatch.fingerprint(),
                    "orchestrationBinding", implementation.expectedBinding(activeDispatch),
                    "mutationGrants", List.of(grant.view()), "predecessorAcceptance", predecessorAcceptance,
                    "resultContract", "IMPLEMENTATION_STEP_RESULT_V1: resultVersion=1, migrationPlanFingerprint, predecessorAcceptance, grantEffects, handoff. "
                            + "handoff is the strict trusted implementation SUCCESS contract. grantEffects exactly echo host observed grantId, invocationId, role, pathKey, action, beforeState, afterState.",
                    "availableOperations", operations(step.role()), "toolProtocol", toolShape(), "toolResults", receipts,
                    "observedGrantEffects", grantEffects(invocationId)), artifactRole, exact -> {
                activeImplementationText = exact; activeImplementationOutcome = "MALFORMED_RESPONSE";
                Map<String,Object> retained = Json.object("acceptanceStatus", "NOT_ACCEPTED", "role", step.role().id,
                        "invocationId", invocationId, "exactText", exact,
                        "fingerprint", Json.fingerprint(artifactRole, exact.getBytes(StandardCharsets.UTF_8)));
                evidence.append("IMPLEMENTATION_CANDIDATE_RECEIVED", retained); nonacceptedOutputs.add(retained);
                validateImplementationResult(Json.parse(exact), step, plan, predecessorAcceptance, invocationId);
                activeImplementationOutcome = "REPORTED_SUCCESS";
            });
            checkLive(); gates.check("CONTEXT_ENTRY", invocation, authorities(), bundleFingerprint);
            if (!reply.kind().equals("TOOL_REQUEST")) break;
            receipts.add(executeTool(invocation, reply.toolRequest(), new HashMap<>()));
        }
        target.endInvocation(); unchanged();
        gates.check("AFTER_INVOCATION", invocation, authorities(), bundleFingerprint); roles.finish(invocation);
        ExecutionEvidence.Candidate candidate = implementation.prepareAcceptance(activeDispatch,
                reply.artifactText().getBytes(StandardCharsets.UTF_8), implementation.manifest(targetCurrent));
        RoleExecutor.Reply acceptance = master("ACCEPT_IMPLEMENTATION_STEP", Json.object("subjectInvocationId", invocationId,
                "subjectRole", step.role().id, "candidateArtifactText", reply.artifactText(), "candidateFingerprint", reply.artifactFingerprint(),
                "candidateStepRecord", candidate.event(), "hostMutationEvidence", target.mutationEvidence().stream()
                        .filter(m -> invocationId.equals(map(m.get("grant")).get("invocationId"))).toList(), "sourceUnchanged", true));
        unchanged();
        artifacts.accept(artifactRole, reply.artifactText(), reply.binding(), reply.providerResponseId(),
                (String)acceptance.binding().get("invocationId"), acceptance.providerResponseId());
        implementation.commit(candidate);
        nonacceptedOutputs.removeIf(value -> invocationId.equals(value.get("invocationId")));
        phases.add(Json.object("role", step.role().id, "invocationId", invocationId, "status", "SUCCESS", "acceptanceStatus", "ACCEPTED"));
        activeDispatch = null; activeImplementationText = null;
    }

    private void validateImplementationResult(Map<String,Object> result, ExecutionPlan.Step step, ExecutionPlan plan,
                                               Map<String,Object> predecessor, String invocationId) {
        ExecutionPlan.fields(result, Set.of("resultVersion", "migrationPlanFingerprint", "predecessorAcceptance", "grantEffects", "handoff"));
        if (!Objects.equals(result.get("resultVersion"), 1)
                || !artifacts.required("MIGRATION_PLAN").fingerprint().equals(result.get("migrationPlanFingerprint"))
                || !predecessor.equals(result.get("predecessorAcceptance"))
                || grantEffects(invocationId).size() != 1 || !Json.parseValue(Json.write(grantEffects(invocationId))).equals(result.get("grantEffects")))
            throw new IllegalArgumentException("IMPLEMENTATION_RESULT_EFFECT_OR_LINEAGE_MISMATCH");
        ImplementationHandoff.validate(step, map(result.get("handoff")), implementation.expectedBinding(activeDispatch), plan.plan());
    }

    private List<Map<String,Object>> grantEffects(String invocationId) {
        return target.mutationEvidence().stream().filter(m -> invocationId.equals(map(m.get("grant")).get("invocationId")))
                .map(m -> {
                    var grant = map(m.get("grant"));
                    return Json.object("grantId", grant.get("grantId"), "invocationId", invocationId, "role", grant.get("role"),
                            "pathKey", grant.get("pathKey"), "action", grant.get("action"),
                            "beforeState", m.get("beforeState"), "afterState", m.get("afterState"));
                }).toList();
    }

    /** Project only broker-observed writes; external changes never become a new baseline. */
    private RepositoryFiles.Snapshot expectedTarget() {
        Map<String,Map<String,Object>> states = new TreeMap<>();
        targetBaseline.entries().forEach(e -> states.put((String)e.get("pathKey"), e));
        for (Map<String,Object> mutation : target.mutationEvidence()) {
            var grant = map(mutation.get("grant")); String path = (String)grant.get("pathKey");
            if (!Objects.equals(states.getOrDefault(path, RepositoryFiles.absent(path)), mutation.get("beforeState")))
                throw new IllegalStateException("MUTATION_EVIDENCE_BEFORE_STATE_MISMATCH");
            states.put(path, map(mutation.get("afterState")));
            if (mutation.get("parentMetadataEffect") != null) {
                var parent = map(mutation.get("parentMetadataEffect"));
                var before = map(parent.get("beforeState")); var after = map(parent.get("afterState"));
                String key = (String)before.get("pathKey");
                if (!"CREATE".equals(grant.get("action")) || !RepositoryFiles.createParentTransition(path, before, after))
                    throw new IllegalStateException("MUTATION_PARENT_EFFECT_MISMATCH");
                if (!key.isEmpty()) {
                    if (!before.equals(states.get(key))) throw new IllegalStateException("MUTATION_PARENT_BEFORE_STATE_MISMATCH");
                    states.put(key, after);
                }
            }
        }
        return new RepositoryFiles.Snapshot(targetBaseline.rootFilesystemIdentity(), new ArrayList<>(states.values()),
                targetBaseline.protectedPaths(), targetBaseline.excludedPaths());
    }
    private void blockedPreflight(String blocker) {
        phases.add(Json.object("phase", "WHOLE_PLAN_PREFLIGHT", "status", "BLOCKED", "reason", blocker));
        master("STOP_BLOCKED", Json.object("reason", blocker, "planFingerprint", artifacts.required("MIGRATION_PLAN").fingerprint(),
                "implementation", "NOT_PERFORMED", "validation", "NOT_PERFORMED"));
        throw new Halt("BLOCKED", blocker);
    }
    private void recordObservation(String stage, RepositoryFiles.Snapshot sourceState, RepositoryFiles.Snapshot targetState) {
        evidence.append(implementation == null ? "READ_ONLY_REPOSITORY_OBSERVATION" : "IMPLEMENTATION_REPOSITORY_OBSERVATION", Json.object("stage", stage,
                "source", snapshotView(sourceState), "target", snapshotView(targetState),
                "limits", "POINT_IN_TIME_MODELED_STATE_ONLY"));
    }
    private void observeFinalEffects() {
        if (sourceBaseline == null || targetCurrent == null) {
            finalEffects = Json.object("observation", "NOT_REACHED", "mutationAuthorityIssued", mutationAuthorityIssued); return;
        }
        if (validation != null && validation.started()) {
            validation.observe();
            if (validation.status().equals("FAILED")) specialistFailure = new Halt("FAILED", validation.code());
            else if (status.equals("SUCCESS") && !validation.status().equals("SUCCESS")) {
                status = validation.status(); code = validation.code();
            }
        }
        RepositoryFiles.Snapshot observedSource = null, observedTarget = null;
        try { observedSource = source.observeTerminatedSnapshot(Set.of()); }
        catch (Exception unavailable) { /* Only this observation is unknown. */ }
        try { observedTarget = validation != null && validation.started() ? validation.targetSnapshot() : target.observeTerminatedSnapshot(Set.of()); }
        catch (Exception unavailable) { /* Keep an independently obtained source observation. */ }
        Boolean sourceEqual = observedSource == null ? null : sourceBaseline.equals(observedSource);
        if (validation != null && validation.started()) {
            var validationEffects = map(validation.facts().get("effects"));
            if (!Boolean.TRUE.equals(validationEffects.get("sourceUnchanged"))) sourceEqual = (Boolean)validationEffects.get("sourceUnchanged");
        }
        Boolean targetEqual = null;
        try { targetCurrent = expectedTarget(); targetEqual = observedTarget == null ? null : validation != null && validation.started()
                ? validation.terminalTargetMatches(observedTarget) : Boolean.valueOf(targetCurrent.equals(observedTarget)); }
        catch (RuntimeException unavailable) {
            orchestrationFailures.add(Json.object("stage", "TERMINAL_EFFECT_ACCOUNTING", "code", "EFFECT_ACCOUNTING_UNAVAILABLE"));
        }
        boolean unexpected = Boolean.FALSE.equals(sourceEqual) || Boolean.FALSE.equals(targetEqual);
        boolean complete = observedSource != null && observedTarget != null;
        finalEffects = Json.object("observation", complete ? "COMPLETE_WITHIN_MODELED_SCOPE" : "UNKNOWN",
                "sourceObservation", observedSource == null ? "UNKNOWN" : "COMPLETE_WITHIN_MODELED_SCOPE",
                "targetObservation", observedTarget == null ? "UNKNOWN" : "COMPLETE_WITHIN_MODELED_SCOPE",
                "sourceUnchanged", sourceEqual, "targetUnchanged", observedTarget == null ? null : targetBaseline.equals(observedTarget),
                "mutationAuthorityIssued", mutationAuthorityIssued,
                "effectClassification", unexpected ? "UNEXPECTED_EFFECT" : sourceEqual == null || targetEqual == null ? "UNKNOWN" : "EXPECTED_EFFECT",
                "targetMatchesAuthorizedEffects", targetEqual, "authorizedMutations", target.mutationEvidence(),
                "actualSource", observedSource == null ? null : snapshotView(observedSource),
                "actualTarget", observedTarget == null ? null : snapshotView(observedTarget), "rollback", "NOT_PERFORMED");
        if (unexpected) terminalObservationFailure("FAILED", "UNAUTHORIZED_REPOSITORY_EFFECTS");
        else if (!complete) terminalObservationFailure("BLOCKED", "FINAL_REPOSITORY_OBSERVATION_UNAVAILABLE");
        else if (targetEqual == null) terminalObservationFailure("BLOCKED", "EFFECT_ACCOUNTING_UNAVAILABLE");
        // Classification is retained before any fallible journal operation. Retention is secondary.
        if (complete) try { recordObservation("TERMINAL", observedSource, observedTarget); }
        catch (RuntimeException unavailable) {
            orchestrationFailures.add(Json.object("stage", "TERMINAL_JOURNAL", "code", "TERMINAL_OBSERVATION_RETENTION_UNAVAILABLE"));
        }
        terminateImplementation(observedTarget);
    }
    private void terminalObservationFailure(String failureStatus, String failureCode) {
        if (status.equals("SUCCESS") || code.equals("NOT_STARTED") || code.equals("VALIDATION_EXECUTION_RUNTIME_REQUIRED")) {
            status = failureStatus; code = failureCode;
        } else if (!code.equals(failureCode)) {
            orchestrationFailures.add(Json.object("stage", "TERMINAL_REPOSITORY_OBSERVATION", "code", failureCode));
        }
    }
    private void terminateImplementation(RepositoryFiles.Snapshot observed) {
        if (activeDispatch == null) return;
        try {
            implementation.terminate(activeDispatch,
                    activeImplementationText == null ? null : activeImplementationText.getBytes(StandardCharsets.UTF_8),
                    observed == null ? null : implementation.manifest(observed), activeImplementationOutcome,
                    observed == null ? "UNKNOWN" : status.equals("FAILED") ? "FAILED" : "BLOCKED", code);
        } catch (RuntimeException unavailable) {
            // A ledger retention failure cannot erase known filesystem effects or reopen a terminated run.
            orchestrationFailures.add(Json.object("stage", "IMPLEMENTATION_TERMINATION", "code", "TERMINATION_EVIDENCE_UNAVAILABLE"));
        } finally { activeDispatch = null; }
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
            case VALIDATION -> List.of();
            default -> List.of();
        };
    }
    private static Map<String,Object> toolShape() {
        return Json.object("operationId", "unique-current-session-id", "invocationId", "echo-current-invocation",
                "role", "echo-current-role", "operation", "one-listed-operation", "scope", "SOURCE or TARGET",
                "rootFilesystemIdentity", "echo-designated-scope", "path", "canonical-relative-path",
                "query", null, "expectedBeforeFingerprint", null, "content", null, "writeGrantField", "CREATE/MODIFY require grantId; omit for reads");
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
