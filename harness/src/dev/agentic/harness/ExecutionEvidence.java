package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static dev.agentic.harness.ExecutionPlan.*;

/** Canonical current-session orchestration evidence for the bounded, non-Git static profile. */
final class ExecutionEvidence {
    record Dispatch(ExecutionPlan.Step step, Map<String,Object> event, Map<String,Object> fingerprint,
                    Map<String,Object> before, Map<String,Object> projection, Map<String,Object> proof) {}
    record Candidate(Dispatch dispatch, Map<String,Object> event, Map<String,Object> fingerprint,
                     Map<String,Object> after) {}
    record Validation(Map<String,Object> event, Map<String,Object> fingerprint,
                      Map<String,Object> before, Map<String,Object> proof) {}

    private final ExecutionPlan plan;
    private final String runId;
    private final Map<String,Object> bundle, bundleFingerprint, targetIdentity, policy, policyFingerprint;
    private final Evidence retention;
    private final List<Map<String,Object>> ledger = new ArrayList<>();
    private final Map<String,Map<String,Object>> accepted = new LinkedHashMap<>();
    private final Map<String,byte[]> artifacts = new HashMap<>();
    private final Set<String> attempts = new HashSet<>(), terminals = new HashSet<>();
    private final Map<String,Map<String,Object>> wrappers = new HashMap<>();
    private final Map<String,Map<String,Object>> rawFingerprints = new HashMap<>();
    private int nextRecord;
    private Dispatch active;
    private Validation validation;
    private Candidate pending;
    private String rootIdentity;

    ExecutionEvidence(ExecutionPlan plan, String runId, Map<String,Object> bundle,
                      Map<String,Object> targetIdentity, Map<String,Object> policy, Evidence retention) {
        Json.identifier(runId);
        this.plan = plan; this.runId = runId; this.bundle = freeze(bundle); this.targetIdentity = freeze(targetIdentity);
        this.policy = policy == null ? null : freeze(policy); this.retention = Objects.requireNonNull(retention);
        fields(this.targetIdentity, Set.of("declaredRoot", "resolvedRoot", "repositoryKind", "headCommit", "semanticIndexStateFingerprint"));
        if (!"NON_GIT".equals(this.targetIdentity.get("repositoryKind")) || this.targetIdentity.get("headCommit") != null
                || this.targetIdentity.get("semanticIndexStateFingerprint") != null) blocked("NON_GIT_STATIC_PROFILE_ONLY");
        bundleFingerprint = retain("RUN_AUTHORITY_BUNDLE_V1", this.bundle);
        if (policy == null) {
            policyFingerprint = null;
            return; // Implementation-only runtime: no validation execution capability is asserted.
        }
        if (!list(policy.get("commands")).isEmpty() || !list(policy.get("effectScopes")).isEmpty()
                || list(policy.get("expectationRules")).isEmpty()) blocked("STATIC_VALIDATION_POLICY_REQUIRED");
        policyFingerprint = retain("RUNTIME_EXECUTION_POLICY", this.policy);
        boolean bound = list(bundle.get("runtimeCapabilities")).stream().map(ExecutionPlan::map)
                .anyMatch(c -> policyFingerprint.equals(c.get("policyFingerprint")));
        if (!bound) invalid("VALIDATION_POLICY_NOT_BUNDLE_BOUND");
    }

    Map<String,Object> manifest(RepositoryFiles.Snapshot snapshot) {
        if (rootIdentity == null) rootIdentity = snapshot.rootFilesystemIdentity();
        if (!rootIdentity.equals(snapshot.rootFilesystemIdentity())) invalid("TARGET_ROOT_CHANGED");
        List<Map<String,Object>> entries = snapshot.entries();
        List<String> keys = entries.stream().map(e -> string(e.get("pathKey"))).toList();
        if (new HashSet<>(keys).size() != keys.size() || !keys.equals(keys.stream().sorted().toList()))
            invalid("STATE_PATH_ORDER");
        Map<String,Object> scope = Json.object("includeTargetRoot", true, "includedPathKeys", List.of(),
                "protectedPathKeys", snapshot.protectedPaths(), "excludedPathKeys", snapshot.excludedPaths(),
                "observationComplete", true);
        Map<String,Object> result = state("FULL_RELEVANT_STATE_MANIFEST", scope, null, entries, List.of());
        retain("FULL_RELEVANT_STATE_MANIFEST", result);
        return result;
    }

    void preflight(Map<String,Object> before) {
        validateManifest(before);
        for (Step step : plan.steps()) {
            Map<String,Object> path = path(before, step.path());
            if (step.action().equals("CREATE") && !"ABSENT".equals(path.get("existence"))) blocked("CREATE_DESTINATION_OCCUPIED");
            if (step.action().equals("MODIFY") && !"REGULAR_FILE".equals(path.get("fileType"))) blocked("MODIFY_REGULAR_FILE_REQUIRED");
            for (Obligation duty : step.obligations()) if (conforms(duty, before)) blocked("IMPLEMENTATION_ALREADY_SATISFIED");
        }
    }

    Dispatch begin(Step step, String attemptId, Map<String,Object> before) {
        if (active != null || validation != null) invalid("INVOCATION_ALREADY_ACTIVE");
        if (accepted.size() >= plan.steps().size() || plan.steps().get(accepted.size()) != step)
            invalid("PREDECESSOR_OR_ORDER_MISMATCH");
        validateManifest(before);
        Map<String,Object> beforePath = path(before, step.path());
        if (step.action().equals("CREATE") && !"ABSENT".equals(beforePath.get("existence"))) blocked("CREATE_DESTINATION_OCCUPIED");
        if (step.action().equals("MODIFY") && !"REGULAR_FILE".equals(beforePath.get("fileType"))) blocked("MODIFY_REGULAR_FILE_REQUIRED");
        for (Obligation obligation : step.obligations()) if (conforms(obligation, before)) blocked("IMPLEMENTATION_ALREADY_SATISFIED");
        Map<String,Object> projection = projection(step, before);
        Map<String,Object> proof = proof(step.id(), step.decisionIds(), step.prerequisites(), before);
        newAttempt(attemptId);
        Map<String,Object> spec = specification(step.role());
        Map<String,Object> event = Json.object("recordVersion", 1, "eventType", "SPECIALIST_DISPATCH",
                "recordIdentity", identity(attemptId, "SPECIALIST_DISPATCH"), "provenanceClass", "MASTER_SESSION_OBSERVED",
                "runAuthorityBundleFingerprint", bundleFingerprint, "specialistSpecificationFingerprint", spec,
                "target", Json.object("identity", targetIdentity,
                        "beforeFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", before),
                        "beforeStepObligationProjectionFingerprint", fingerprint("STEP_OBLIGATION_STATE_PROJECTION", projection)),
                "assignment", step.assignment(), "authorizedWrites", step.writes(), "applicableImplementationConstraints", List.of(),
                "prerequisiteProof", proof, "prerequisiteProofFingerprint", fingerprint("PREREQUISITE_PROOF", proof),
                "restrictions", List.of("ONLY_EXACT_ASSIGNED_FILE", "NO_PROCESS_EXECUTION", "NO_DIRECTORY_CREATION"),
                "expectedResponseBindings", Json.object("requiredFields", List.of("runId", "attemptId", "runAuthorityBundleFingerprint",
                        "specialistSpecificationFingerprint", "dispatchFingerprint", "prerequisiteProofFingerprint",
                        "beforeFullStateManifestFingerprint", "beforeStepObligationProjectionFingerprint"),
                        "dispatchFingerprintSource", "THIS_CANONICAL_SPECIALIST_DISPATCH"), "limitations", List.of());
        Map<String,Object> fp = append("SPECIALIST_DISPATCH", event);
        active = new Dispatch(step, event, fp, before, projection, proof);
        return active;
    }

    Map<String,Object> expectedBinding(Dispatch dispatch) {
        requireActive(dispatch);
        return Json.object("mode", "MASTER_CONTROLLED", "runId", runId, "attemptId", attempt(dispatch.event),
                "runAuthorityBundleFingerprint", bundleFingerprint,
                "specialistSpecificationFingerprint", dispatch.event.get("specialistSpecificationFingerprint"),
                "dispatchFingerprint", dispatch.fingerprint, "prerequisiteProofFingerprint", fingerprint("PREREQUISITE_PROOF", dispatch.proof),
                "beforeFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", dispatch.before),
                "beforeStepObligationProjectionFingerprint", fingerprint("STEP_OBLIGATION_STATE_PROJECTION", dispatch.projection));
    }

    Candidate prepareAcceptance(Dispatch dispatch, byte[] rawResponse, Map<String,Object> after) {
        requireActive(dispatch);
        if (pending != null) invalid("DUPLICATE_ACCEPTANCE_CANDIDATE");
        Map<String,Object> response;
        try {
            response = strictResponse(rawResponse);
            ImplementationHandoff.validate(dispatch.step, map(response.get("handoff")), expectedBinding(dispatch), plan.plan());
        } catch (RuntimeException invalidResponse) {
            observe(dispatch.event, dispatch.fingerprint, rawResponse, "MALFORMED_RESPONSE");
            throw invalidResponse;
        }
        String status = string(map(response.get("handoff")).get("status"));
        observe(dispatch.event, dispatch.fingerprint, rawResponse, "REPORTED_" + status);
        if (!"SUCCESS".equals(status)) blocked("SPECIALIST_" + status);
        validateManifest(after);
        Step step = dispatch.step;
        List<Map<String,Object>> differences = differences(dispatch.before, after, step);
        List<Map<String,Object>> fileChanges = differences.stream().filter(d -> !createParentEffect(d, step)).toList();
        if (fileChanges.size() != 1 || !authorized(fileChanges.getFirst(), step)) invalid("UNAUTHORIZED_OR_ZERO_PROGRESS_MUTATION");
        for (Map<String,Object> difference : differences) if (createParentEffect(difference, step))
            retain("IMPLEMENTATION_PARENT_METADATA_EFFECT", Json.object("dispatchFingerprint", dispatch.fingerprint, "effect", difference));
        Map<String,Object> delta = fileChanges.getFirst();
        if (step.action().equals("MODIFY") && !Objects.equals(map(delta.get("beforeState")).get("filesystemIdentity"),
                map(delta.get("afterState")).get("filesystemIdentity"))) invalid("MODIFY_IDENTITY_REPLACED");
        for (Obligation duty : step.obligations())
            if (conforms(duty, dispatch.before) || !conforms(duty, after)) invalid("INDEPENDENT_IMPLEMENTATION_PROGRESS_FAILED");
        Map<String,Object> projection = projection(step, after);
        Map<String,Object> audit = audit(step, attempt(dispatch.event), step.id(), "STEP_ACCEPTANCE", after, projection, null);
        if (!"PASS".equals(audit.get("result"))) invalid("OBLIGATION_AUDIT_FAILED");
        Map<String,Object> revalidation = revalidation(dispatch.fingerprint, dispatch.proof, after);
        if (!"PASS".equals(revalidation.get("result"))) blocked("PREREQUISITE_REVALIDATION_FAILED");
        String attemptId = attempt(dispatch.event);
        Map<String,Object> raw = rawFingerprints.get(attemptId), wrapper = wrappers.get(attemptId);
        Map<String,Object> mutation = Json.object("pathKey", step.path(), "action", delta.get("transition"),
                "beforePathState", delta.get("beforeState"), "afterPathState", delta.get("afterState"),
                "componentDecisionIds", step.decisionIds(), "requirementIds", step.requirementIds());
        Map<String,Object> reportMutation = Json.object("pathKey", step.path(), "action", delta.get("transition"),
                "componentDecisionIds", step.decisionIds(), "requirementIds", step.requirementIds());
        Map<String,Object> event = Json.object("recordVersion", 1, "eventType", "STEP_ACCEPTED",
                "recordIdentity", identity(attemptId, "STEP_ACCEPTED"),
                "provenance", Json.object("class", "MASTER_SESSION_OBSERVED", "limitations", List.of(
                        "NO_AUTHENTICATED_SPECIALIST_IDENTITY", "NO_COMPLETE_EXECUTION_HISTORY", "NO_TRANSIENT_WRITE_DETECTION",
                        "NO_EXCLUSIVE_WRITER_PROOF", "NO_AUTOMATIC_CROSS_SESSION_ELIGIBILITY")),
                "attemptResolution", resolution("REPORTED_SUCCESS", "STEP_ACCEPTED", "ACCEPTED", "NONE"),
                "runAuthorityBundleFingerprint", bundleFingerprint, "specialistSpecificationFingerprint", specification(step.role()),
                "inputBindings", Json.object("dispatchFingerprint", dispatch.fingerprint,
                        "prerequisiteProofFingerprint", fingerprint("PREREQUISITE_PROOF", dispatch.proof),
                        "rawSpecialistResponseFingerprint", raw, "specialistResponseWrapperFingerprint", wrapper),
                "stateBindings", Json.object("targetIdentity", targetIdentity,
                        "beforeFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", dispatch.before),
                        "afterFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", after),
                        "beforeStepObligationProjectionFingerprint", fingerprint("STEP_OBLIGATION_STATE_PROJECTION", dispatch.projection)),
                "assignment", step.assignment(), "authorizedWrites", step.writes(),
                "mutationAccounting", Json.object("observedPersistentMutations", List.of(mutation),
                        "specialistReportedMutations", List.of(reportMutation), "setEquality", "PASS",
                        "everyAssignedDecisionHasMutation", "PASS", "unauthorizedPersistentMutations", List.of()),
                "obligationState", Json.object("projection", projection,
                        "projectionFingerprint", fingerprint("STEP_OBLIGATION_STATE_PROJECTION", projection)),
                "currentObligationAudit", audit, "currentObligationAuditFingerprint", fingerprint("CURRENT_OBLIGATION_AUDIT_V1", audit),
                "prerequisiteRevalidation", revalidation,
                "prerequisiteRevalidationFingerprint", fingerprint("PREREQUISITE_REVALIDATION_V1", revalidation),
                "implementationConstraintTraceability", List.of(),
                "contractTraceability", List.of(Json.object("componentDecisionId", step.decisionId(),
                        "responsibilities", step.obligations().stream().map(Obligation::value).toList(), "callableContracts", List.of(),
                        "declarationContracts", List.of(), "preservationObligations", List.of())),
                "specialistSelfReport", Json.object("source", "DIRECT_CURRENT_SESSION_RESPONSE", "reportedAgent", step.role().id,
                        "reportedStatus", "SUCCESS", "runId", runId, "attemptId", attemptId,
                        "runAuthorityBundleFingerprint", bundleFingerprint, "specialistSpecificationFingerprint", specification(step.role()),
                        "dispatchFingerprint", dispatch.fingerprint, "prerequisiteProofFingerprint", fingerprint("PREREQUISITE_PROOF", dispatch.proof),
                        "rawResponseFingerprint", raw),
                "masterVerification", Json.object("structure", "PASS", "assignment", "PASS", "prerequisites", "PASS",
                        "pathScope", "PASS", "changeAccounting", "PASS", "nonemptyMutationPerDecision", "PASS",
                        "responsibilityConformance", "PASS", "contractConformance", "PASS", "implementationConstraintConformance", "PASS",
                        "preservation", "PASS", "currentState", "PASS", "observableDrift", "PASS"));
        // A candidate is not retained in the ledger or eligible as a prerequisite until MASTER accepts it.
        pending = new Candidate(dispatch, event, fingerprint("STEP_ACCEPTED", event), after);
        return pending;
    }

    void commit(Candidate candidate) {
        if (candidate != pending) invalid("STALE_ACCEPTANCE_CANDIDATE");
        requireActive(candidate.dispatch);
        terminal(attempt(candidate.dispatch.event));
        append("STEP_ACCEPTED", candidate.event);
        accepted.put(candidate.dispatch.step.id(), candidate.event);
        pending = null; active = null;
    }

    Map<String,Object> terminate(Dispatch dispatch, byte[] raw, Map<String,Object> after,
                                 String normalizedOutcome, String disposition, String reason) {
        requireActive(dispatch); Json.identifier(reason);
        if (!Set.of("REPORTED_SUCCESS", "REPORTED_NO_ACTION", "REPORTED_PARTIAL", "REPORTED_BLOCKED", "REPORTED_FAILED",
                "MALFORMED_RESPONSE", "INTERRUPTED_OR_NO_RESPONSE").contains(normalizedOutcome)) invalid("TERMINAL_OUTCOME");
        if (!Set.of("BLOCKED", "FAILED", "PARTIAL", "UNKNOWN").contains(disposition)) invalid("TERMINAL_DISPOSITION");
        observe(dispatch.event, dispatch.fingerprint, raw, normalizedOutcome);
        List<Map<String,Object>> changes = after == null ? List.of() : differences(dispatch.before, after, dispatch.step);
        List<Map<String,Object>> unauthorized = changes.stream().filter(d -> !authorized(d, dispatch.step) && !createParentEffect(d, dispatch.step)).toList();
        String mutation = after == null ? "UNKNOWN" : changes.isEmpty() ? "NONE" : "UNACCEPTED";
        if (!unauthorized.isEmpty() || normalizedOutcome.equals("MALFORMED_RESPONSE")) disposition = "FAILED";
        String recovery = !"NONE".equals(mutation) ? "RECONCILIATION_REQUIRED"
                : disposition.equals("FAILED") ? "ABORT" : "CALLER_RESOLUTION_REQUIRED";
        String attemptId = attempt(dispatch.event);
        retainReason(attemptId, reason);
        Map<String,Object> event = Json.object("recordVersion", 1, "eventType", "ATTEMPT_TERMINATED",
                "recordIdentity", identity(attemptId, "ATTEMPT_TERMINATED"), "provenanceClass", "MASTER_SESSION_OBSERVED",
                "runAuthorityBundleFingerprint", bundleFingerprint,
                "inputBindings", Json.object("dispatchFingerprint", dispatch.fingerprint,
                        "prerequisiteProofFingerprint", fingerprint("PREREQUISITE_PROOF", dispatch.proof),
                        "rawSpecialistResponseFingerprint", rawFingerprints.get(attemptId),
                        "specialistResponseWrapperFingerprint", wrappers.get(attemptId)),
                "stateBindings", Json.object("targetIdentity", targetIdentity,
                        "beforeFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", dispatch.before),
                        "beforeStepObligationProjectionFingerprint", fingerprint("STEP_OBLIGATION_STATE_PROJECTION", dispatch.projection),
                        "afterState", after == null ? "UNKNOWN" : "OBSERVED",
                        "afterFullStateManifestFingerprint", after == null ? null : fingerprint("FULL_RELEVANT_STATE_MANIFEST", after),
                        "afterStepObligationProjectionFingerprint", after == null ? null : fingerprint("STEP_OBLIGATION_STATE_PROJECTION", projection(dispatch.step, after))),
                "mutationAccounting", Json.object("persistentMutationState", mutation, "accountingComplete", after != null,
                        "observedPersistentDifferences", changes, "unauthorizedPersistentDifferences", unauthorized),
                "attemptResolution", resolution(normalizedOutcome, disposition, mutation, recovery),
                "reasonReferences", reasonReferences(attemptId, reason), "limitations", List.of());
        terminal(attemptId); append("ATTEMPT_TERMINATED", event); active = null; pending = null;
        return event;
    }

    Validation beginValidation(String attemptId, Map<String,Object> before) {
        if (policy == null) blocked("VALIDATION_EXECUTION_RUNTIME_REQUIRED");
        if (active != null || validation != null || accepted.size() != plan.steps().size()) invalid("VALIDATION_PREDECESSORS_INELIGIBLE");
        validateManifest(before);
        Set<String> depended = new HashSet<>(); plan.steps().forEach(s -> depended.addAll(s.prerequisites()));
        List<String> sinks = plan.steps().stream().filter(s -> !depended.contains(s.id())).map(Step::id).toList();
        List<String> decisionIds = plan.steps().stream().map(Step::decisionId).sorted().toList();
        Map<String,Object> proof = proof("FINAL_VALIDATION", decisionIds, sinks, before);
        newAttempt(attemptId);
        Map<String,Object> event = Json.object("recordVersion", 1, "eventType", "VALIDATION_INVOCATION",
                "recordIdentity", identity(attemptId, "VALIDATION_INVOCATION"), "provenanceClass", "MASTER_SESSION_OBSERVED",
                "runAuthorityBundleFingerprint", bundleFingerprint, "specialistSpecificationFingerprint", specification(TrustedInputs.Role.VALIDATION),
                "targetIdentity", targetIdentity, "beforeFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", before),
                "validationExecutionPolicyFingerprint", policyFingerprint, "prerequisiteProof", proof,
                "prerequisiteProofFingerprint", fingerprint("PREREQUISITE_PROOF", proof), "limitations", List.of());
        validation = new Validation(event, append("VALIDATION_INVOCATION", event), before, proof);
        return validation;
    }

    /** Independent host execution of the exact declared non-command predicates, never target code. */
    Map<String,Object> validationObservation(Validation invocation, Map<String,Object> after) {
        requireValidation(invocation); validateManifest(after);
        List<Map<String,Object>> changes = differences(invocation.before, after, null);
        String attemptId = attempt(invocation.event);
        List<Map<String,Object>> results = new ArrayList<>();
        for (Object entry : list(policy.get("expectationRules"))) {
            Map<String,Object> rule = map(entry);
            List<String> refs = strings(rule.get("additionalEvidenceReferences"));
            List<String> evidence = new ArrayList<>(); boolean passes = true;
            for (String reference : refs) {
                Obligation duty = plan.steps().stream().flatMap(s -> s.obligations().stream())
                        .filter(o -> o.pointer().equals(reference)).findFirst().orElseThrow();
                boolean pass = conforms(duty, after); passes &= pass;
                String evidenceRef = attemptId + ":static:" + reference;
                Map<String,Object> observed = Json.object("reference", evidenceRef, "runId", runId, "attemptId", attemptId,
                        "runAuthorityBundleFingerprint", bundleFingerprint, "invocationFingerprint", invocation.fingerprint,
                        "targetIdentity", targetIdentity, "fullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", after),
                        "mechanismReference", reference, "expectedContentFingerprint", duty.rule().declaration().get("expectedContentFingerprint"),
                        "observedPathState", path(after, duty.rule().path()), "result", pass ? "PASS" : "FAIL");
                retain("RUNTIME_DISCOVERY_EVIDENCE", observed); evidence.add(evidenceRef);
            }
            List<Map<String,Object>> reasons = passes ? List.of() : reasonReferences(attemptId, "STATIC_PREDICATE_FAILED");
            if (!passes) retainReason(attemptId, "STATIC_PREDICATE_FAILED");
            results.add(Json.object("validationExpectationId", rule.get("validationExpectationId"),
                    "validationOutcome", passes ? "PASS" : "FAIL", "evidenceReferences", evidence.stream().sorted().toList(),
                    "reasonReferences", reasons));
        }
        Map<String,Object> comparison = Json.object("comparisonReference", attemptId + ":static-final", "commandId", null,
                "beforeFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", invocation.before),
                "afterState", "OBSERVED", "afterFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", after),
                "accountingComplete", true, "observedPersistentDifferences", changes, "authorizedValidationEffects", List.of(),
                "unauthorizedPersistentDifferences", changes, "reasonReferences", List.of());
        return Json.object("expectationResults", results, "stateComparisons", List.of(comparison),
                "finalFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", after),
                "validationOutcome", results.stream().allMatch(r -> "PASS".equals(r.get("validationOutcome"))) ? "PASS" : "FAIL");
    }

    Map<String,Object> finishValidation(Validation invocation, byte[] raw, Map<String,Object> after,
                                        Map<String,Object> observed, boolean masterAccepted) {
        requireValidation(invocation);
        Map<String,Object> response = strictResponse(raw);
        fields(response, Set.of("validationVersion", "agent", "runId", "attemptId", "runAuthorityBundleFingerprint",
                "specialistSpecificationFingerprint", "invocationFingerprint", "validationExecutionPolicyFingerprint",
                "prerequisiteProofFingerprint", "beforeFullStateManifestFingerprint", "commandResults", "expectationResults",
                "stateComparisons", "finalFullStateManifestFingerprint", "validationOutcome", "reasonReferences", "limitations"));
        if (!Integer.valueOf(1).equals(response.get("validationVersion")) || !"07-validation".equals(response.get("agent"))
                || !runId.equals(response.get("runId")) || !attempt(invocation.event).equals(response.get("attemptId"))) invalid("VALIDATION_IDENTITY_MISMATCH");
        for (String key : List.of("runAuthorityBundleFingerprint", "specialistSpecificationFingerprint", "validationExecutionPolicyFingerprint",
                "prerequisiteProofFingerprint", "beforeFullStateManifestFingerprint"))
            if (!Objects.equals(response.get(key), invocation.event.get(key))) invalid("VALIDATION_BINDING_MISMATCH");
        if (!invocation.fingerprint.equals(response.get("invocationFingerprint")) || !list(response.get("commandResults")).isEmpty())
            invalid("VALIDATION_AUTHORITY_MISMATCH");
        for (String key : List.of("expectationResults", "stateComparisons", "finalFullStateManifestFingerprint", "validationOutcome"))
            if (!Objects.equals(response.get(key), observed.get(key))) invalid("VALIDATION_OBSERVATION_MISMATCH");
        if (!list(response.get("limitations")).isEmpty() || !list(response.get("reasonReferences")).isEmpty())
            invalid("UNSUPPORTED_VALIDATION_DIAGNOSTICS");
        observe(invocation.event, invocation.fingerprint, raw, "REPORTED_VALIDATION_RESULT");
        Map<String,Object> revalidation = revalidation(invocation.fingerprint, invocation.proof, after);
        List<Map<String,Object>> changes = differences(invocation.before, after, null);
        String protocol = !changes.isEmpty() ? "FAILED" : !masterAccepted || !"PASS".equals(revalidation.get("result")) ? "BLOCKED" : "VALID";
        String outcome = string(observed.get("validationOutcome"));
        String recovery = !changes.isEmpty() ? "RECONCILIATION_REQUIRED" : protocol.equals("VALID") && outcome.equals("PASS") ? "NONE" : "CALLER_RESOLUTION_REQUIRED";
        return validationTerminal(invocation, after, observed, revalidation, protocol, outcome,
                changes.isEmpty() ? "NONE" : "UNAUTHORIZED", recovery, List.of());
    }

    Map<String,Object> terminateValidation(Validation invocation, byte[] raw, Map<String,Object> after, String reason) {
        requireValidation(invocation); Json.identifier(reason);
        String attemptId = attempt(invocation.event);
        observe(invocation.event, invocation.fingerprint, raw, raw == null ? "INTERRUPTED_OR_NO_RESPONSE" : "MALFORMED_RESPONSE");
        retainReason(attemptId, reason);
        List<Map<String,Object>> results = new ArrayList<>();
        for (Object item : list(policy.get("expectationRules"))) results.add(Json.object("validationExpectationId", map(item).get("validationExpectationId"),
                "validationOutcome", "AMBIGUOUS", "evidenceReferences", List.of(), "reasonReferences", reasonReferences(attemptId, reason)));
        List<Map<String,Object>> changes = after == null ? List.of() : differences(invocation.before, after, null);
        Map<String,Object> comparison = Json.object("comparisonReference", attemptId + ":terminated", "commandId", null,
                "beforeFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", invocation.before),
                "afterState", after == null ? "UNKNOWN" : "OBSERVED",
                "afterFullStateManifestFingerprint", after == null ? null : fingerprint("FULL_RELEVANT_STATE_MANIFEST", after),
                "accountingComplete", after != null, "observedPersistentDifferences", changes,
                "authorizedValidationEffects", List.of(), "unauthorizedPersistentDifferences", changes,
                "reasonReferences", reasonReferences(attemptId, reason));
        Map<String,Object> observed = Json.object("expectationResults", results, "stateComparisons", List.of(comparison));
        Map<String,Object> revalidation = after == null ? null : revalidation(invocation.fingerprint, invocation.proof, after);
        String effect = after == null ? "UNKNOWN" : changes.isEmpty() ? "NONE" : "UNAUTHORIZED";
        String protocol = raw != null || !changes.isEmpty() ? "FAILED" : "UNKNOWN";
        return validationTerminal(invocation, after, observed, revalidation, protocol, "AMBIGUOUS", effect,
                !effect.equals("NONE") ? "RECONCILIATION_REQUIRED" : protocol.equals("FAILED") ? "ABORT" : "CALLER_RESOLUTION_REQUIRED",
                reasonReferences(attemptId, reason));
    }

    private Map<String,Object> validationTerminal(Validation invocation, Map<String,Object> after, Map<String,Object> observed,
                                                 Map<String,Object> revalidation, String protocol, String outcome, String effect,
                                                 String recovery, List<Map<String,Object>> reasons) {
        String attemptId = attempt(invocation.event);
        Map<String,Object> event = Json.object("recordVersion", 1, "eventType", "VALIDATION_ATTEMPT_TERMINATED",
                "recordIdentity", identity(attemptId, "VALIDATION_ATTEMPT_TERMINATED"), "provenanceClass", "MASTER_SESSION_OBSERVED",
                "runAuthorityBundleFingerprint", bundleFingerprint, "invocationFingerprint", invocation.fingerprint,
                "rawSpecialistResponseFingerprint", rawFingerprints.get(attemptId), "specialistResponseWrapperFingerprint", wrappers.get(attemptId),
                "commandResults", List.of(), "expectationResults", observed.get("expectationResults"),
                "stateComparisons", observed.get("stateComparisons"),
                "finalFullStateManifestFingerprint", after == null ? null : fingerprint("FULL_RELEVANT_STATE_MANIFEST", after),
                "prerequisiteRevalidation", revalidation,
                "prerequisiteRevalidationFingerprint", revalidation == null ? null : fingerprint("PREREQUISITE_REVALIDATION_V1", revalidation),
                "protocolDisposition", protocol, "validationOutcome", outcome, "effectState", effect,
                "requiredRecovery", recovery, "reasonReferences", reasons, "limitations", List.of());
        terminal(attemptId); append("VALIDATION_ATTEMPT_TERMINATED", event); validation = null;
        return event;
    }

    private Map<String,Object> proof(String consumer, List<String> decisions, List<String> directIds, Map<String,Object> current) {
        Set<String> direct = new LinkedHashSet<>(directIds), closure = new LinkedHashSet<>();
        for (String id : directIds) ancestors(id, closure);
        closure.removeAll(direct);
        List<Map<String,Object>> directEntries = entries(direct, consumer, current);
        List<Map<String,Object>> transitiveEntries = entries(closure, consumer, current);
        Map<String,Object> composition = compose(directEntries, transitiveEntries, current);
        Map<String,Object> proof = Json.object("proofVersion", 1, "runAuthorityBundleFingerprint", bundleFingerprint,
                "targetIdentity", targetIdentity, "consumerRole", consumer.equals("FINAL_VALIDATION") ? "FINAL_VALIDATION" : "SPECIALIST",
                "consumerStepId", consumer, "consumerComponentDecisionIds", decisions,
                "directMutablePrerequisites", directEntries, "transitiveMutablePrerequisites", transitiveEntries,
                "reuseWitnesses", List.of(), "currentPrerequisiteReuseProjection", composition,
                "currentPrerequisiteReuseProjectionFingerprint", fingerprint("PREREQUISITE_REUSE_STATE_PROJECTION", composition),
                "eligibility", allPass(directEntries, transitiveEntries) ? "ELIGIBLE" : "INELIGIBLE", "limitations", List.of());
        retain("PREREQUISITE_PROOF", proof);
        if (!"ELIGIBLE".equals(proof.get("eligibility"))) blocked("STALE_PREREQUISITES");
        return proof;
    }

    private List<Map<String,Object>> entries(Set<String> ids, String consumer, Map<String,Object> current) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (Step step : plan.steps()) if (ids.contains(step.id())) {
            Map<String,Object> event = accepted.get(step.id());
            if (event == null) blocked("PREREQUISITE_NOT_MASTER_ACCEPTED");
            Map<String,Object> recorded = map(map(event.get("obligationState")).get("projection"));
            Map<String,Object> projection = projection(step, current);
            Map<String,Object> audit = audit(step, attempt(event), consumer, "PREREQUISITE", current, projection, event);
            boolean fresh = map(recorded.get("targetIdentity")).equals(map(projection.get("targetIdentity")))
                    && recorded.get("pathStates").equals(projection.get("pathStates")) && "PASS".equals(audit.get("result"));
            result.add(Json.object("prerequisiteStepId", step.id(), "componentDecisionIds", step.decisionIds(),
                    "eventType", "STEP_ACCEPTED", "recordIdentity", event.get("recordIdentity"),
                    "recordFingerprint", fingerprint("STEP_ACCEPTED", event), "recordRunAuthorityBundleFingerprint", bundleFingerprint,
                    "recordedObligationProjectionFingerprint", fingerprint("STEP_OBLIGATION_STATE_PROJECTION", recorded),
                    "currentObligationProjection", projection, "currentObligationProjectionFingerprint", fingerprint("STEP_OBLIGATION_STATE_PROJECTION", projection),
                    "projectionComparison", fresh ? "PASS" : "FAIL", "currentObligationAudit", audit,
                    "currentObligationAuditFingerprint", fingerprint("CURRENT_OBLIGATION_AUDIT_V1", audit), "reconciliationRelation", null));
        }
        if (result.size() != ids.size()) invalid("UNKNOWN_PREREQUISITE");
        return result;
    }

    private Map<String,Object> revalidation(Map<String,Object> dispatchFingerprint, Map<String,Object> original, Map<String,Object> after) {
        Set<String> directIds = new LinkedHashSet<>(), transitiveIds = new LinkedHashSet<>();
        list(original.get("directMutablePrerequisites")).forEach(e -> directIds.add(string(map(e).get("prerequisiteStepId"))));
        list(original.get("transitiveMutablePrerequisites")).forEach(e -> transitiveIds.add(string(map(e).get("prerequisiteStepId"))));
        String consumer = string(original.get("consumerStepId"));
        List<Map<String,Object>> direct = entries(directIds, consumer, after), transitive = entries(transitiveIds, consumer, after);
        Map<String,Object> composed = compose(direct, transitive, after);
        Map<String,Object> result = Json.object("revalidationVersion", 1, "runAuthorityBundleFingerprint", bundleFingerprint,
                "dispatchFingerprint", dispatchFingerprint, "prerequisiteProofFingerprint", fingerprint("PREREQUISITE_PROOF", original),
                "currentFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", after),
                "directMutablePrerequisites", direct, "transitiveMutablePrerequisites", transitive, "reuseWitnesses", List.of(),
                "currentPrerequisiteReuseProjection", composed,
                "currentPrerequisiteReuseProjectionFingerprint", fingerprint("PREREQUISITE_REUSE_STATE_PROJECTION", composed),
                "result", allPass(direct, transitive) ? "PASS" : "FAIL");
        retain("PREREQUISITE_REVALIDATION_V1", result); return result;
    }

    private Map<String,Object> audit(Step step, String subjectAttempt, String consumer, String evaluation,
                                      Map<String,Object> current, Map<String,Object> projection, Map<String,Object> completion) {
        List<Map<String,Object>> rows = new ArrayList<>();
        for (Obligation duty : step.obligations()) {
            String expected = Json.write(duty.value());
            rows.add(Json.object("obligationKind", "ASSIGNED_RESPONSIBILITY", "obligationReference", duty.pointer(),
                    "componentDecisionIds", step.decisionIds(), "requirementIds", step.requirementIds(),
                    "analysisFindingIds", List.of(), "analysisEvidenceIds", List.of(), "relevantPathKeys", List.of(step.path()),
                    "expectedSemantics", Json.object("sourceKind", "PLAN_JSON_POINTER", "sourceReference", duty.pointer(),
                            "canonicalValueJson", expected, "valueFingerprint", retainBytes("OBLIGATION_EXPECTED_SEMANTICS", expected.getBytes(StandardCharsets.UTF_8))),
                    "currentConformance", conforms(duty, current) ? "PASS" : "FAIL",
                    "currentEvidenceReferences", List.of(Json.object("evidenceKind", "PATH_STATE", "reference", step.path()))));
        }
        rows.sort(Comparator.comparing(r -> string(r.get("obligationReference"))));
        Map<String,Object> audit = Json.object("auditVersion", 1, "auditKind", "CURRENT_OBLIGATION_AUDIT_V1", "evaluationRole", evaluation,
                "subjectAttemptId", subjectAttempt, "runAuthorityBundleFingerprint", bundleFingerprint, "targetIdentity", targetIdentity,
                "currentFullStateManifestFingerprint", fingerprint("FULL_RELEVANT_STATE_MANIFEST", current),
                "currentObligationProjectionFingerprint", fingerprint("STEP_OBLIGATION_STATE_PROJECTION", projection),
                "consumerStepId", consumer, "prerequisiteStepId", step.id(),
                "sourceCompletionRecordIdentity", completion == null ? null : completion.get("recordIdentity"),
                "sourceCompletionRecordFingerprint", completion == null ? null : fingerprint("STEP_ACCEPTED", completion),
                "entries", rows, "result", rows.stream().allMatch(r -> "PASS".equals(r.get("currentConformance"))) ? "PASS" : "FAIL", "limitations", List.of());
        retain("CURRENT_OBLIGATION_AUDIT_V1", audit); return audit;
    }

    private Map<String,Object> projection(Step step, Map<String,Object> full) {
        Map<String,Object> result = state("STEP_OBLIGATION_STATE_PROJECTION", map(full.get("scope")),
                fingerprint("FULL_RELEVANT_STATE_MANIFEST", full), List.of(path(full, step.path())), List.of());
        retain("STEP_OBLIGATION_STATE_PROJECTION", result); return result;
    }

    private Map<String,Object> compose(List<Map<String,Object>> direct, List<Map<String,Object>> transitive, Map<String,Object> full) {
        Map<String,Map<String,Object>> paths = new TreeMap<>();
        Map<String,Map<String,Object>> byStep = new HashMap<>();
        for (List<Map<String,Object>> group : List.of(direct, transitive)) for (Map<String,Object> entry : group) {
            byStep.put(string(entry.get("prerequisiteStepId")), map(entry.get("recordIdentity")));
            for (Object state : list(map(entry.get("currentObligationProjection")).get("pathStates"))) {
                Map<String,Object> value = map(state); String key = string(value.get("pathKey"));
                Map<String,Object> old = paths.putIfAbsent(key, value);
                if (old != null && !old.equals(value)) invalid("CONFLICTING_PREREQUISITE_PATH_STATES");
            }
        }
        List<Map<String,Object>> sourceIds = plan.steps().stream().map(Step::id).filter(byStep::containsKey).map(byStep::get).toList();
        Map<String,Object> result = state("PREREQUISITE_REUSE_STATE_PROJECTION", map(full.get("scope")),
                fingerprint("FULL_RELEVANT_STATE_MANIFEST", full), new ArrayList<>(paths.values()), sourceIds);
        retain("PREREQUISITE_REUSE_STATE_PROJECTION", result); return result;
    }

    private Map<String,Object> state(String kind, Map<String,Object> scope, Map<String,Object> source,
                                     List<Map<String,Object>> entries, List<Map<String,Object>> sourceIds) {
        List<String> keys = entries.stream().map(e -> string(e.get("pathKey"))).toList();
        Map<String,Object> pathSet = retain("STATE_PATH_SET:" + kind, keys);
        return Json.object("stateVersion", 1, "stateKind", kind, "targetIdentity", targetIdentity, "scope", scope,
                "sourceFullManifestFingerprint", source, "pathSetFingerprint", pathSet, "pathStates", entries,
                "sourceEventIdentities", sourceIds, "limitations", List.of());
    }

    static List<Map<String,Object>> differences(Map<String,Object> before, Map<String,Object> after, Step step) {
        List<Map<String,Object>> differences = new ArrayList<>();
        for (String identity : List.of("targetIdentity", "scope")) if (!Objects.equals(before.get(identity), after.get(identity)))
            differences.add(Json.object("subjectKind", identity.equals("scope") ? "OBSERVATION_SCOPE" : "TARGET_IDENTITY", "pathKey", null,
                    "transition", identity.equals("scope") ? "OBSERVATION_SCOPE_CHANGED" : "TARGET_IDENTITY_CHANGED",
                    "beforeState", before.get(identity), "afterState", after.get(identity), "componentDecisionIds", List.of(), "requirementIds", List.of()));
        Map<String,Map<String,Object>> left = index(before), right = index(after);
        Set<String> keys = new TreeSet<>(left.keySet()); keys.addAll(right.keySet());
        for (String key : keys) {
            Map<String,Object> a = left.getOrDefault(key, absent(key)), b = right.getOrDefault(key, absent(key));
            if (a.equals(b)) continue;
            String transition = "ABSENT".equals(a.get("existence")) ? "CREATED" : "ABSENT".equals(b.get("existence")) ? "DELETED"
                    : !a.get("fileType").equals(b.get("fileType")) ? "TYPE_CHANGED"
                    : "REGULAR_FILE".equals(a.get("fileType")) && !Objects.equals(a.get("contentFingerprint"), b.get("contentFingerprint"))
                    ? "MODIFIED" : "STATE_CHANGED";
            boolean owned = step != null && step.path().equals(key);
            differences.add(Json.object("subjectKind", "PATH_STATE", "pathKey", key, "transition", transition,
                    "beforeState", a, "afterState", b, "componentDecisionIds", owned ? step.decisionIds() : List.of(),
                    "requirementIds", owned ? step.requirementIds() : List.of()));
        }
        return List.copyOf(differences);
    }

    static Map<String,Object> absent(String path) {
        return Json.object("pathKey", path, "existence", "ABSENT", "fileType", "ABSENT", "contentFingerprint", null,
                "symlinkTargetBase64", null, "filesystemIdentity", null, "linkCount", null, "gitTracking", "NOT_APPLICABLE",
                "indexStatus", "NOT_APPLICABLE", "worktreeStatus", "NOT_APPLICABLE", "gitRelatedPathKey", null);
    }
    private static boolean createParentEffect(Map<String,Object> delta, Step step) {
        return "CREATE".equals(step.action()) && "PATH_STATE".equals(delta.get("subjectKind"))
                && "STATE_CHANGED".equals(delta.get("transition"))
                && RepositoryFiles.createParentTransition(step.path(), map(delta.get("beforeState")), map(delta.get("afterState")));
    }
    private boolean authorized(Map<String,Object> delta, Step step) {
        if (!"PATH_STATE".equals(delta.get("subjectKind")) || !step.path().equals(delta.get("pathKey"))
                || !(step.action().equals("CREATE") ? "CREATED" : "MODIFIED").equals(delta.get("transition"))) return false;
        Map<String,Object> after = map(delta.get("afterState"));
        return "REGULAR_FILE".equals(after.get("fileType")) && Objects.equals(after.get("linkCount"), 1L)
                && after.get("filesystemIdentity") != null && after.get("symlinkTargetBase64") == null
                && "NOT_APPLICABLE".equals(after.get("gitTracking")) && "NOT_APPLICABLE".equals(after.get("indexStatus"))
                && "NOT_APPLICABLE".equals(after.get("worktreeStatus"));
    }
    private boolean conforms(Obligation duty, Map<String,Object> full) {
        return duty.rule().declaration().get("expectedContentFingerprint").equals(path(full, duty.rule().path()).get("contentFingerprint"));
    }
    private void validateManifest(Map<String,Object> value) {
        if (!"FULL_RELEVANT_STATE_MANIFEST".equals(value.get("stateKind")) || !targetIdentity.equals(value.get("targetIdentity"))
                || !Boolean.TRUE.equals(map(value.get("scope")).get("observationComplete"))) invalid("INCOMPLETE_OR_MISMATCHED_STATE");
        byte[] retained = artifacts.get(key(fingerprint("FULL_RELEVANT_STATE_MANIFEST", value)));
        if (retained == null || !Arrays.equals(retained, Json.bytes(value))) invalid("UNOBSERVED_STATE_SUBSTITUTION");
    }
    private void observe(Map<String,Object> dispatch, Map<String,Object> dispatchFingerprint, byte[] raw, String normalized) {
        String attemptId = attempt(dispatch);
        if (raw == null) return;
        Map<String,Object> rawFingerprint = fingerprintBytes("SPECIALIST_RAW_RESPONSE", raw);
        if (rawFingerprints.containsKey(attemptId)) {
            if (!rawFingerprints.get(attemptId).equals(rawFingerprint)) invalid("RESPONSE_SUBSTITUTION");
            return;
        }
        retainBytes("SPECIALIST_RAW_RESPONSE", raw);
        Map<String,Object> wrapper = Json.object("recordVersion", 1, "eventType", "SPECIALIST_RESPONSE_OBSERVED",
                "recordIdentity", identity(attemptId, "SPECIALIST_RESPONSE_OBSERVED"), "provenanceClass", "MASTER_SESSION_OBSERVED",
                "runAuthorityBundleFingerprint", bundleFingerprint, "specialistSpecificationFingerprint", dispatch.get("specialistSpecificationFingerprint"),
                "dispatchFingerprint", dispatchFingerprint, "rawResponseFingerprint", rawFingerprint,
                "normalizedAttemptOutcome", normalized, "limitations", List.of());
        rawFingerprints.put(attemptId, rawFingerprint); wrappers.put(attemptId, append("SPECIALIST_RESPONSE_OBSERVED", wrapper));
    }
    private Map<String,Object> strictResponse(byte[] bytes) {
        if (bytes == null) invalid("NO_RESPONSE");
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, raw.getBytes(StandardCharsets.UTF_8))) invalid("RESPONSE_UTF8");
        retention.safe(raw); return Json.parse(raw);
    }
    private void ancestors(String id, Set<String> result) {
        for (String prerequisite : plan.step(id).prerequisites()) if (result.add(prerequisite)) ancestors(prerequisite, result);
    }
    private boolean allPass(List<Map<String,Object>> first, List<Map<String,Object>> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).allMatch(e -> "PASS".equals(e.get("projectionComparison")));
    }
    private Map<String,Object> specification(TrustedInputs.Role role) {
        List<Map<String,Object>> found = list(map(bundle.get("capabilityRegistry")).get("specialists")).stream().map(ExecutionPlan::map)
                .filter(e -> role.id.equals(e.get("specialistRole"))).toList();
        if (found.size() != 1) invalid("SPECIALIST_CAPABILITY_BINDING");
        return map(found.get(0).get("specificationFingerprint"));
    }
    private static Map<String,Map<String,Object>> index(Map<String,Object> state) {
        Map<String,Map<String,Object>> result = new TreeMap<>();
        for (Object entry : list(state.get("pathStates"))) {
            Map<String,Object> value = map(entry); String key = string(value.get("pathKey"));
            if (result.put(key, value) != null) invalid("DUPLICATE_STATE_PATH");
        }
        return result;
    }
    static Map<String,Object> path(Map<String,Object> state, String key) { return index(state).getOrDefault(key, absent(key)); }
    private void requireActive(Dispatch dispatch) { if (dispatch != active || terminals.contains(attempt(dispatch.event))) invalid("STALE_INVOCATION"); }
    private void requireValidation(Validation invocation) { if (invocation != validation || terminals.contains(attempt(invocation.event))) invalid("STALE_VALIDATION_INVOCATION"); }
    private void newAttempt(String attemptId) { Json.identifier(attemptId); if (!attempts.add(attemptId)) invalid("REPLAYED_ATTEMPT"); }
    private void terminal(String attemptId) { if (!terminals.add(attemptId)) invalid("DUPLICATE_TERMINAL"); }
    private Map<String,Object> identity(String attempt, String type) {
        return Json.object("runId", runId, "attemptId", attempt, "eventType", type, "recordId", "record-" + ++nextRecord);
    }
    private static String attempt(Map<String,Object> event) { return string(map(event.get("recordIdentity")).get("attemptId")); }
    private static Map<String,Object> resolution(String normalized, String disposition, String mutation, String recovery) {
        return Json.object("normalizedAttemptOutcome", normalized, "primaryDisposition", disposition,
                "persistentMutationState", mutation, "requiredRecovery", recovery);
    }
    private List<Map<String,Object>> reasonReferences(String attempt, String reason) {
        return List.of(Json.object("kind", "MASTER_GATE", "reference", attempt + ":" + reason));
    }
    private void retainReason(String attempt, String reason) {
        retain("RUNTIME_DISCOVERY_EVIDENCE", Json.object("reference", attempt + ":" + reason,
                "runId", runId, "attemptId", attempt, "reason", reason));
    }
    private Map<String,Object> append(String role, Map<String,Object> object) {
        Map<String,Object> result = retain(role, object); ledger.add(object); return result;
    }
    private Map<String,Object> retain(String role, Object object) { return retainBytes(role, Json.bytes(object)); }
    private Map<String,Object> retainBytes(String role, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8); retention.safe(text);
        Map<String,Object> fp = fingerprintBytes(role, bytes);
        byte[] prior = artifacts.get(key(fp));
        if (prior != null && !Arrays.equals(prior, bytes)) invalid("ARTIFACT_SUBSTITUTION");
        if (prior == null) {
            retention.append("EXECUTION_ARTIFACT_RETAINED", Json.object("artifactFingerprint", fp, "exactUtf8", text));
            artifacts.put(key(fp), bytes.clone());
        }
        return fp;
    }
    private static String key(Map<String,Object> fingerprint) { return fingerprint.get("artifactRole") + ":" + fingerprint.get("digest"); }
    static Map<String,Object> fingerprint(String role, Object object) { return fingerprintBytes(role, Json.bytes(object)); }
    private static Map<String,Object> fingerprintBytes(String role, byte[] bytes) { return Json.fingerprint(role, bytes); }
    private static Map<String,Object> freeze(Map<String,Object> value) { return map(Json.object("value", value).get("value")); }
    List<Map<String,Object>> ledger() { return List.copyOf(ledger); }
    Map<String,Object> bundleFingerprint() { return bundleFingerprint; }
    Map<String,Object> policyFingerprint() { return policyFingerprint; }
    Map<String,Object> policy() { return policy; }
    byte[] artifact(Map<String,Object> fingerprint) {
        byte[] value = artifacts.get(key(fingerprint));
        if (value == null || !fingerprint.equals(fingerprintBytes(string(fingerprint.get("artifactRole")), value))) invalid("UNRESOLVED_ARTIFACT");
        return value.clone();
    }
}
