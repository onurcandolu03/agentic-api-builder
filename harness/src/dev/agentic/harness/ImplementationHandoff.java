package dev.agentic.harness;

import java.util.*;

/** Validates the existing 03–06 SUCCESS handoff profile for the supported one-path executable subset. */
final class ImplementationHandoff {
    static final List<String> PHASES = List.of("INPUT_VALIDATION", "PLAN_ELIGIBILITY_AND_ASSIGNMENT", "READINESS_AND_PREREQUISITES",
            "WORKTREE_AND_DRIFT_PREFLIGHT", "AUTHORIZED_IMPLEMENTATION", "STATIC_POST_CHANGE_VERIFICATION", "OUTPUT_VALIDATION_AND_HANDOFF");
    private static final String TOP = "implementationVersion agent status targetProject planReference orchestrationBinding assignedComponentDecisionIds appliedComponentDecisions skippedComponentDecisions modifiedFiles createdFiles deletedFiles preExistingChanges staticVerification deviations blockingIssues coverage";
    private ImplementationHandoff() {}

    static void validate(ExecutionPlan.Step step, Map<String,Object> response,
                         Map<String,Object> expectedBinding, Map<String,Object> plan) {
        fields(response, TOP);
        equal(response.get("implementationVersion"), 1); equal(response.get("agent"), step.role().id);
        equal(response.get("status"), "SUCCESS");
        var target = map(response.get("targetProject")); fields(target, "name root");
        var targetPlan = map(plan.get("targetProject"));
        equal(target.get("name"), targetPlan.get("name")); equal(target.get("root"), targetPlan.get("root"));
        var reference = map(response.get("planReference"));
        fields(reference, "planningVersion planStatus targetAnalysisVersion targetAnalysisStatus assignedImplementationStepIds completedPrerequisiteStepIds");
        equal(reference.get("planningVersion"), plan.get("planningVersion")); equal(reference.get("planStatus"), plan.get("status"));
        equal(reference.get("targetAnalysisVersion"), targetPlan.get("analysisVersion"));
        equal(reference.get("targetAnalysisStatus"), targetPlan.get("analysisStatus"));
        equal(reference.get("assignedImplementationStepIds"), List.of(step.id()));
        equal(reference.get("completedPrerequisiteStepIds"), step.prerequisites());
        var binding = map(response.get("orchestrationBinding"));
        fields(binding, "mode runId attemptId runAuthorityBundleFingerprint specialistSpecificationFingerprint dispatchFingerprint prerequisiteProofFingerprint beforeFullStateManifestFingerprint beforeStepObligationProjectionFingerprint");
        var expected = new LinkedHashMap<>(expectedBinding); expected.put("mode", "MASTER_CONTROLLED");
        equal(binding, expected);
        if (binding.values().stream().anyMatch(Objects::isNull)) fail();
        equal(response.get("assignedComponentDecisionIds"), step.decisionIds());
        empty(response.get("skippedComponentDecisions")); empty(response.get("deletedFiles"));
        empty(response.get("deviations")); empty(response.get("blockingIssues"));
        var applied = only(response.get("appliedComponentDecisions"));
        boolean typed = step.role() != TrustedInputs.Role.DOMAIN;
        fields(applied, "componentDecisionId requirementIds decision " + (typed ? "responsibilityKind " : "")
                + "implementationStepIds mutations implementationConstraintsApplied");
        equal(applied.get("componentDecisionId"), step.decisionId()); equal(applied.get("requirementIds"), step.requirementIds());
        equal(applied.get("decision"), step.decision().get("decision")); equal(applied.get("implementationStepIds"), List.of(step.id()));
        empty(applied.get("implementationConstraintsApplied"));
        if (typed) equal(applied.get("responsibilityKind"), kind(step));
        var mutation = only(applied.get("mutations"));
        fields(mutation, "componentDecisionId requirementIds path action " + (typed ? "responsibilityKind " : "") + "responsibilitiesApplied");
        equal(mutation.get("componentDecisionId"), step.decisionId()); equal(mutation.get("requirementIds"), step.requirementIds());
        equal(mutation.get("path"), step.path()); equal(mutation.get("action"), step.action().equals("CREATE") ? "CREATED" : "MODIFIED");
        List<String> duties = step.obligations().stream().map(ExecutionPlan.Obligation::pointer).sorted().toList();
        equal(mutation.get("responsibilitiesApplied"), duties);
        if (typed) equal(mutation.get("responsibilityKind"), kind(step));
        String activeFiles = step.action().equals("CREATE") ? "createdFiles" : "modifiedFiles";
        empty(response.get(step.action().equals("CREATE") ? "modifiedFiles" : "createdFiles"));
        var file = only(response.get(activeFiles));
        fields(file, "path componentDecisionIds requirementIds " + (typed ? "responsibilityKinds " : "") + "responsibilitiesApplied");
        equal(file.get("path"), step.path()); equal(file.get("componentDecisionIds"), step.decisionIds());
        equal(file.get("requirementIds"), step.requirementIds()); equal(file.get("responsibilitiesApplied"), duties);
        if (typed) equal(file.get("responsibilityKinds"), List.of(kind(step)));
        for (Object item : list(response.get("preExistingChanges"))) {
            var change = map(item); fields(change, "path worktreeStatus authorizedTargetPath disposition notes");
            ExecutionPlan.canonicalPath(text(change.get("path"))); text(change.get("worktreeStatus"));
            equal(change.get("authorizedTargetPath"), false); equal(change.get("disposition"), "UNTOUCHED"); text(change.get("notes"));
            if (step.path().equals(change.get("path"))) fail();
        }
        staticChecks(step, map(response.get("staticVerification")), plan);
        coverage(step, map(response.get("coverage")), plan);
    }

    static List<String> checks(TrustedInputs.Role role) {
        return switch (role) {
            case DOMAIN -> words("INPUT_AND_PLAN_INTEGRITY ASSIGNMENT_AND_OWNERSHIP AUTHORIZED_PATH_SCOPE DECISION_AND_REQUIREMENT_TRACEABILITY RESPONSIBILITY_COVERAGE UNRELATED_AND_PRE_EXISTING_WORK_PRESERVATION REPOSITORY_CHANGE_ACCOUNTING FORBIDDEN_ACTION_ABSENCE");
            case PERSISTENCE -> words("INPUT_AND_PLAN_INTEGRITY ASSIGNMENT_AND_OWNERSHIP AUTHORIZED_PATH_SCOPE DECISION_AND_REQUIREMENT_TRACEABILITY MAPPER_RESPONSIBILITY_SCOPE PERSISTENCE_RESPONSIBILITY_SCOPE FRAMEWORK_AND_SEMANTIC_AUTHORITY UNRELATED_AND_PRE_EXISTING_WORK_PRESERVATION REPOSITORY_CHANGE_ACCOUNTING FORBIDDEN_ACTION_ABSENCE");
            case SERVICE -> words("INPUT_AND_PLAN_INTEGRITY ASSIGNMENT_AND_OWNERSHIP AUTHORIZED_PATH_SCOPE DECISION_AND_REQUIREMENT_TRACEABILITY SERVICE_INTERFACE_RESPONSIBILITY_SCOPE SERVICE_IMPLEMENTATION_RESPONSIBILITY_SCOPE CONTROLLER_API_RESPONSIBILITY_SCOPE API_ROUTING_CONSTANT_RESPONSIBILITY_SCOPE SERVICE_API_SEMANTIC_AUTHORITY FORBIDDEN_V1_OWNERSHIP_ABSENCE UNRELATED_AND_PRE_EXISTING_WORK_PRESERVATION REPOSITORY_CHANGE_ACCOUNTING FORBIDDEN_ACTION_ABSENCE SERVICE_API_SUPPORT_CONSTANT_RESPONSIBILITY_SCOPE");
            case TESTS -> words("INPUT_AND_PLAN_INTEGRITY ASSIGNMENT_AND_OWNERSHIP AUTHORIZED_PATH_SCOPE PREREQUISITE_PROOF_BINDINGS TEST_REQUIREMENT_AND_PRODUCTION_TRACEABILITY TEST_CONVENTION_EVIDENCE IMPLEMENTATION_PROGRESS PRESERVATION_CONFORMANCE CALLABLE_DECLARATION_AND_CONSTRAINT_CONFORMANCE EXISTING_TEST_PRESERVATION NEW_TEST_DESTINATION_AND_STRUCTURE FORBIDDEN_OWNERSHIP_ABSENCE UNRELATED_AND_PRE_EXISTING_WORK_PRESERVATION REPOSITORY_CHANGE_ACCOUNTING FORBIDDEN_ACTION_ABSENCE");
            default -> throw new IllegalArgumentException("IMPLEMENTATION_ROLE_REQUIRED");
        };
    }

    static boolean applicableCheck(ExecutionPlan.Step step, String name) {
        return switch (name) {
            case "MAPPER_RESPONSIBILITY_SCOPE" -> kind(step).equals("MAPPER");
            case "PERSISTENCE_RESPONSIBILITY_SCOPE" -> kind(step).equals("PERSISTENCE_REPOSITORY");
            case "SERVICE_INTERFACE_RESPONSIBILITY_SCOPE" -> kind(step).equals("SERVICE_INTERFACE");
            case "SERVICE_IMPLEMENTATION_RESPONSIBILITY_SCOPE" -> kind(step).equals("SERVICE_IMPLEMENTATION");
            case "CONTROLLER_API_RESPONSIBILITY_SCOPE" -> kind(step).equals("CONTROLLER_API");
            case "API_ROUTING_CONSTANT_RESPONSIBILITY_SCOPE" -> kind(step).equals("API_ROUTING_CONSTANT");
            case "SERVICE_API_SUPPORT_CONSTANT_RESPONSIBILITY_SCOPE" -> kind(step).equals("SERVICE_API_SUPPORT_CONSTANT");
            case "EXISTING_TEST_PRESERVATION" -> step.action().equals("MODIFY");
            case "NEW_TEST_DESTINATION_AND_STRUCTURE" -> step.action().equals("CREATE");
            default -> true;
        };
    }

    static String kind(ExecutionPlan.Step step) {
        String component = text(step.decision().get("targetComponent"));
        return component.equals("CONTROLLER") ? "CONTROLLER_API" : component;
    }

    private static void staticChecks(ExecutionPlan.Step step, Map<String,Object> verification, Map<String,Object> plan) {
        fields(verification, "result checks"); equal(verification.get("result"), "PASS");
        var checks = list(verification.get("checks")); var names = checks(step.role());
        if (checks.size() != names.size()) fail();
        for (int i = 0; i < checks.size(); i++) {
            var check = map(checks.get(i)); fields(check, "id check result evidence");
            equal(check.get("id"), "SV-%03d".formatted(i + 1)); equal(check.get("check"), names.get(i));
            boolean applicable = applicableCheck(step, names.get(i));
            equal(check.get("result"), applicable ? "PASS" : "NOT_PERFORMED");
            var rows = list(check.get("evidence")); if (rows.isEmpty()) fail();
            if (step.role() == TrustedInputs.Role.TESTS) {
                for (Object item : rows) testEvidence(step, map(item), plan, applicable);
                if (names.get(i).equals("IMPLEMENTATION_PROGRESS")) {
                    if (rows.size() != step.obligations().size()) fail();
                    Set<String> observed = new HashSet<>();
                    for (Object item : rows) {
                        var row = map(item); nonempty(row.get("beforeObservation")); nonempty(row.get("afterObservation"));
                        int matches = 0;
                        for (var obligation : step.obligations()) if (list(row.get("planReferences")).contains(obligation.pointer())) {
                            if (!observed.add(obligation.pointer())) fail(); matches++;
                        }
                        if (matches != 1) fail();
                    }
                }
                if (names.get(i).equals("TEST_CONVENTION_EVIDENCE") && rows.stream().noneMatch(item ->
                        !list(map(item).get("targetFindingIds")).isEmpty() && !list(map(item).get("targetEvidenceIds")).isEmpty())) fail();
                if (names.get(i).equals("TEST_REQUIREMENT_AND_PRODUCTION_TRACEABILITY")) {
                    Set<String> references = new HashSet<>();
                    for (Object item : rows) for (Object ref : list(map(item).get("planReferences"))) references.add(text(ref));
                    if (references.stream().noneMatch(ref -> ref.startsWith("/validationPlan/plannedTestChanges/"))
                            || references.stream().noneMatch(ref -> ref.startsWith("/validationPlan/validationExpectations/"))) fail();
                    for (var obligation : step.obligations()) if (!references.contains(obligation.pointer())) fail();
                }
            } else {
                // 03–05 use evidence references/observations, not the 06-only structured evidence schema.
                for (Object row : rows) nonempty(row);
            }
        }
    }

    private static void testEvidence(ExecutionPlan.Step step, Map<String,Object> row, Map<String,Object> plan, boolean applicable) {
        fields(row, "componentDecisionIds requirementIds planReferences targetFindingIds targetEvidenceIds paths symbols beforeObservation afterObservation conformance notes");
        subset(row.get("componentDecisionIds"), step.decisionIds()); subset(row.get("requirementIds"), step.requirementIds());
        subset(row.get("targetFindingIds"), list(step.decision().get("targetFindingIds")));
        subset(row.get("targetEvidenceIds"), list(step.decision().get("targetEvidenceIds")));
        subset(row.get("paths"), List.of(step.path())); strings(row.get("symbols")); strings(row.get("planReferences"));
        for (Object ref : list(row.get("planReferences"))) pointer(plan, text(ref));
        text(row.get("beforeObservation")); text(row.get("afterObservation")); nonempty(row.get("notes"));
        equal(row.get("conformance"), applicable ? "PASS" : "UNVERIFIABLE");
    }

    private static void coverage(ExecutionPlan.Step step, Map<String,Object> coverage, Map<String,Object> plan) {
        fields(coverage, "assignment requirements paths phases notes");
        var assignment = map(coverage.get("assignment"));
        String specialized = switch (step.role()) {
            case PERSISTENCE -> "ownedMapperMutable ownedPersistenceMutable ";
            case SERVICE -> "ownedServiceInterfaceMutable ownedServiceImplementationMutable ownedControllerApiMutable ownedApiRoutingConstantMutable ownedServiceApiSupportConstantMutable ";
            default -> "";
        };
        fields(assignment, "planComponentDecisions assigned ownedMutable " + specialized + "ownedReuse ownedManualReview readyMutable notAssignedComponentDecisionIds");
        var decisions = list(plan.get("componentDecisions"));
        equal(assignment.get("planComponentDecisions"), decisions.size()); equal(assignment.get("assigned"), 1);
        equal(assignment.get("ownedMutable"), 1); equal(assignment.get("readyMutable"), 1);
        equal(assignment.get("ownedReuse"), 0); equal(assignment.get("ownedManualReview"), 0);
        List<String> unassigned = decisions.stream().map(value -> text(map(value).get("id"))).filter(id -> !id.equals(step.decisionId())).sorted().toList();
        equal(assignment.get("notAssignedComponentDecisionIds"), unassigned);
        if (!specialized.isEmpty()) for (String key : words(specialized.strip())) {
            String owner = switch (key) {
                case "ownedMapperMutable" -> "MAPPER"; case "ownedPersistenceMutable" -> "PERSISTENCE_REPOSITORY";
                case "ownedServiceInterfaceMutable" -> "SERVICE_INTERFACE"; case "ownedServiceImplementationMutable" -> "SERVICE_IMPLEMENTATION";
                case "ownedControllerApiMutable" -> "CONTROLLER_API"; case "ownedApiRoutingConstantMutable" -> "API_ROUTING_CONSTANT";
                default -> "SERVICE_API_SUPPORT_CONSTANT";
            };
            equal(assignment.get(key), kind(step).equals(owner) ? 1 : 0);
        }
        var requirements = map(coverage.get("requirements")); fields(requirements, "assignedRequirementIds appliedRequirementIds incompleteRequirementIds");
        equal(requirements.get("assignedRequirementIds"), step.requirementIds()); equal(requirements.get("appliedRequirementIds"), step.requirementIds());
        empty(requirements.get("incompleteRequirementIds"));
        var paths = map(coverage.get("paths")); fields(paths, "authorizedMutationPaths agentChangedPaths unauthorizedAgentChangedPaths");
        equal(paths.get("authorizedMutationPaths"), List.of(step.path())); equal(paths.get("agentChangedPaths"), List.of(step.path()));
        empty(paths.get("unauthorizedAgentChangedPaths"));
        var phases = list(coverage.get("phases")); if (phases.size() != PHASES.size()) fail();
        for (int i = 0; i < phases.size(); i++) {
            var phase = map(phases.get(i)); fields(phase, "phase result notes");
            equal(phase.get("phase"), PHASES.get(i)); equal(phase.get("result"), "COMPLETE"); text(phase.get("notes"));
        }
        strings(coverage.get("notes"));
    }

    private static void pointer(Object current, String pointer) {
        if (!pointer.startsWith("/")) fail();
        for (String escaped : pointer.substring(1).split("/", -1)) {
            if (escaped.matches(".*~(?:[^01]|$).*")) fail();
            String key = escaped.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?,?> map && map.containsKey(key)) current = map.get(key);
            else if (current instanceof List<?> list && key.matches("0|[1-9][0-9]*")) {
                try { current = list.get(Integer.parseInt(key)); } catch (RuntimeException invalid) { fail(); }
            } else fail();
        }
    }
    private static void subset(Object value, List<?> allowed) { strings(value); if (!allowed.containsAll(list(value))) fail(); }
    private static void strings(Object value) { Set<String> set = new HashSet<>(); for (Object item : list(value)) if (!set.add(text(item))) fail(); }
    private static List<String> words(String value) { return List.of(value.split(" ")); }
    private static Map<String,Object> only(Object value) { if (list(value).size() != 1) fail(); return map(list(value).getFirst()); }
    private static Map<String,Object> map(Object value) { return ArtifactContracts.object(value); }
    private static List<Object> list(Object value) { return ArtifactContracts.list(value); }
    private static void fields(Map<String,Object> value, String keys) { ArtifactContracts.fields(value, keys); }
    private static String text(Object value) { return ArtifactContracts.string(value); }
    private static String nonempty(Object value) { return ArtifactContracts.nonempty(value); }
    private static void equal(Object actual, Object expected) { if (!Objects.equals(actual, expected)) fail(); }
    private static void empty(Object value) { if (!list(value).isEmpty()) fail(); }
    private static void fail() { throw new IllegalArgumentException("IMPLEMENTATION_HANDOFF_MISMATCH"); }
}
