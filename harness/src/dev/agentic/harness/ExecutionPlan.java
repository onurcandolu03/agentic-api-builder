package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

/** A deliberately bounded executable subset of the existing planning contract. */
final class ExecutionPlan {
    /** Host-selected current static mechanism. Bytes are caller/runtime authority, never a model assertion. */
    record StaticRule(String reference, String path, String expectedText, String expectedDescription) {
        StaticRule {
            Json.identifier(reference); canonicalPath(path); Json.identifier(expectedDescription);
            Objects.requireNonNull(expectedText);
            Evidence.rejectObviousSecrets(expectedText);
        }
        boolean passes(byte[] current) {
            return current != null && Arrays.equals(current, expectedText.getBytes(StandardCharsets.UTF_8));
        }
        Map<String,Object> declaration() {
            return Json.object("reference", reference, "mechanism", "EXACT_UTF8_FILE_V1", "pathKey", path,
                    "expectedDescription", expectedDescription,
                    "expectedContentFingerprint", Json.fingerprint("PATH_CONTENT:" + path,
                            expectedText.getBytes(StandardCharsets.UTF_8)));
        }
    }

    record Obligation(String pointer, Map<String,Object> value, StaticRule rule) {}
    record ValidationRule(String id, String acceptanceIntent, String observableOutcome,
                          List<String> requirementIds, List<String> obligationReferences) {
        ValidationRule {
            Json.identifier(id); Json.identifier(acceptanceIntent); Json.identifier(observableOutcome);
            requirementIds = List.copyOf(requirementIds); obligationReferences = List.copyOf(obligationReferences);
            requirementIds.forEach(Json::identifier); obligationReferences.forEach(Json::identifier);
        }
        Map<String,Object> declaration() {
            return Json.object("validationExpectationId", id, "acceptanceIntent", acceptanceIntent,
                    "observableOutcome", observableOutcome, "requirementIds", requirementIds,
                    "obligationReferences", obligationReferences);
        }
    }
    record Step(String id, int sequence, TrustedInputs.Role role, String path, String action,
                String decisionId, List<String> requirementIds, List<String> prerequisites,
                Map<String,Object> decision, List<Obligation> obligations) {
        List<String> decisionIds() { return List.of(decisionId); }
        Map<String,Object> assignment() {
            return Json.object("stepId", id, "componentDecisionIds", decisionIds(),
                    "requirementIds", requirementIds, "specialistRole", role.id);
        }
        List<Map<String,Object>> writes() {
            return List.of(Json.object("pathKey", path, "action", action,
                    "componentDecisionIds", decisionIds(), "requirementIds", requirementIds));
        }
    }

    private static final Set<String> DECISION_FIELDS = Set.of("id", "requirementIds", "targetComponent",
            "targetLayer", "targetScope", "existingPath", "expectedLocation", "expectedLocationKind", "decision",
            "rationale", "targetFindingIds", "targetEvidenceIds", "targetUncertaintyIds", "targetConflictIds",
            "targetBlockingQuestionIds", "targetCoverageReferences", "implementationConstraints", "callableContracts",
            "declarationContracts", "dependencies", "expectedChangeScope", "confidence");
    private static final Set<String> STEP_FIELDS = Set.of("id", "sequence", "specialistRole", "requirementIds",
            "componentDecisionIds", "objective", "prerequisiteStepIds", "reusedComponentDecisionIds", "expectedPaths",
            "implementationConstraints", "completionEvidenceExpected");

    private final Map<String,Object> plan;
    private final List<Step> steps;
    private final List<StaticRule> rules;

    private ExecutionPlan(Map<String,Object> plan, List<Step> steps, List<StaticRule> rules) {
        this.plan = Json.object("plan", plan).get("plan") instanceof Map<?,?> value ? map(value) : null;
        this.steps = List.copyOf(steps); this.rules = List.copyOf(rules);
    }

    static ExecutionPlan parse(Map<String,Object> plan, List<StaticRule> suppliedRules) {
        plan = map(Json.object("plan", plan).get("plan"));
        if (!"SUCCESS".equals(plan.get("status"))) blocked("EXECUTABLE_PLAN_REQUIRED");
        Map<String,StaticRule> rules = new HashMap<>();
        for (StaticRule rule : suppliedRules) if (rules.put(rule.reference(), rule) != null) invalid("DUPLICATE_STATIC_RULE");
        Map<String,Map<String,Object>> decisions = new LinkedHashMap<>();
        Map<String,Integer> decisionOffsets = new HashMap<>();
        Set<String> requirements = new HashSet<>();
        for (Object entry : list(plan.get("requirements"))) {
            String id = string(map(entry).get("id"));
            if (!requirements.add(id)) invalid("DUPLICATE_REQUIREMENT");
        }
        if (requirements.isEmpty()) blocked("NO_EXECUTABLE_REQUIREMENTS");
        int offset = 0;
        for (Object entry : list(plan.get("componentDecisions"))) {
            Map<String,Object> decision = map(entry); fields(decision, DECISION_FIELDS);
            String id = string(decision.get("id"));
            if (decisions.put(id, decision) != null) invalid("DUPLICATE_DECISION");
            decisionOffsets.put(id, offset++);
            requireSubset(strings(decision.get("requirementIds")), requirements, true, "DECISION_REQUIREMENTS");
            for (String field : List.of("targetComponent", "targetLayer", "rationale", "confidence")) string(decision.get(field));
            if (strings(decision.get("targetScope")).isEmpty()) blocked("OWNERSHIP_SCOPE_REQUIRED");
            for (String field : List.of("targetUncertaintyIds", "targetConflictIds", "targetBlockingQuestionIds"))
                if (!list(decision.get(field)).isEmpty()) blocked("UNRESOLVED_DECISION_AUTHORITY");
            for (String field : List.of("implementationConstraints", "callableContracts", "declarationContracts"))
                if (!list(decision.get(field)).isEmpty()) blocked("COMPLEX_SEMANTIC_CONTRACT_NOT_SUPPORTED");
            if (!Set.of("CREATE_NEW", "EXTEND_EXISTING").contains(decision.get("decision")))
                blocked("REUSE_REVIEW_RECONCILIATION_NOT_SUPPORTED");
        }
        List<Step> steps = new ArrayList<>();
        Set<String> usedDecisions = new HashSet<>(), paths = new HashSet<>(), ids = new HashSet<>();
        Set<String> usedRules = new HashSet<>();
        Map<String,String> owningSteps = new HashMap<>();
        int lastSequence = 0;
        for (Object entry : list(plan.get("implementationOrder"))) {
            Map<String,Object> step = map(entry); fields(step, STEP_FIELDS);
            String id = string(step.get("id"));
            if (!ids.add(id)) invalid("DUPLICATE_STEP");
            int sequence = integer(step.get("sequence"));
            if (sequence <= lastSequence) invalid("PLAN_SEQUENCE_INVALID");
            lastSequence = sequence;
            List<String> componentIds = strings(step.get("componentDecisionIds"));
            if (componentIds.size() != 1) blocked("GROUPED_DECISION_NOT_SUPPORTED");
            String componentId = componentIds.get(0);
            Map<String,Object> decision = decisions.get(componentId);
            if (decision == null || !usedDecisions.add(componentId)) invalid("STEP_DECISION_BINDING");
            TrustedInputs.Role role = TrustedInputs.Role.find(string(step.get("specialistRole")));
            if (!Set.of(TrustedInputs.Role.DOMAIN, TrustedInputs.Role.PERSISTENCE, TrustedInputs.Role.SERVICE,
                    TrustedInputs.Role.TESTS).contains(role)) invalid("IMPLEMENTATION_ROLE_REQUIRED");
            verifyOwnership(role, decision);
            List<String> requirementIds = strings(step.get("requirementIds"));
            if (!requirementIds.equals(strings(decision.get("requirementIds")))) invalid("STEP_REQUIREMENT_BINDING");
            Map<String,Object> scope = map(decision.get("expectedChangeScope"));
            fields(scope, Set.of("changeType", "responsibilities", "expectedPaths"));
            List<String> expectedPaths = strings(scope.get("expectedPaths"));
            if (expectedPaths.size() != 1 || !expectedPaths.equals(strings(step.get("expectedPaths"))))
                invalid("ONE_EXACT_PATH_REQUIRED");
            String path = expectedPaths.get(0); canonicalPath(path);
            if (!paths.add(path.toLowerCase(Locale.ROOT))) blocked("WRITE_PATH_NOT_COMPOSABLE");
            String action = string(scope.get("changeType"));
            if ("CREATE_NEW".equals(decision.get("decision"))) {
                if (!"CREATE".equals(action) || decision.get("existingPath") != null
                        || !"EXACT_PATH".equals(decision.get("expectedLocationKind"))
                        || !path.equals(decision.get("expectedLocation"))) invalid("CREATE_AUTHORITY_MISMATCH");
            } else if (!"MODIFY".equals(action) || !path.equals(decision.get("existingPath"))
                    || !"NOT_APPLICABLE".equals(decision.get("expectedLocationKind"))) invalid("MODIFY_AUTHORITY_MISMATCH");
            for (String field : List.of("reusedComponentDecisionIds", "implementationConstraints"))
                if (!list(step.get(field)).isEmpty()) blocked("COMPLEX_PREREQUISITES_NOT_SUPPORTED");
            string(step.get("objective"));
            if (strings(step.get("completionEvidenceExpected")).isEmpty()) invalid("COMPLETION_EVIDENCE_REQUIRED");
            List<String> prerequisites = strings(step.get("prerequisiteStepIds"));
            Set<String> projected = new LinkedHashSet<>();
            for (String dependency : strings(decision.get("dependencies"))) {
                String owner = owningSteps.get(dependency);
                if (owner == null) invalid("UNSATISFIED_OR_FORWARD_DEPENDENCY");
                projected.add(owner);
            }
            if (!new HashSet<>(prerequisites).equals(projected)) invalid("PREDECESSOR_PROJECTION_MISMATCH");
            List<Obligation> obligations = new ArrayList<>(); int responsibilityOffset = 0;
            for (Object responsibility : list(scope.get("responsibilities"))) {
                Map<String,Object> duty = map(responsibility);
                fields(duty, Set.of("responsibilityClass", "description"));
                if (!"IMPLEMENTATION".equals(duty.get("responsibilityClass"))) blocked("PRESERVATION_AUDIT_NOT_SUPPORTED");
                String description = string(duty.get("description"));
                String pointer = "/componentDecisions/" + decisionOffsets.get(componentId)
                        + "/expectedChangeScope/responsibilities/" + responsibilityOffset++;
                StaticRule rule = rules.get(pointer);
                if (rule == null || !rule.path().equals(path) || !rule.expectedDescription().equals(description))
                    blocked("INDEPENDENT_SEMANTIC_MECHANISM_REQUIRED");
                if (!usedRules.add(pointer)) invalid("DUPLICATE_RULE_ASSIGNMENT");
                obligations.add(new Obligation(pointer, Json.object("responsibilityClass", "IMPLEMENTATION",
                        "description", description), rule));
            }
            if (obligations.isEmpty()) invalid("IMPLEMENTATION_RESPONSIBILITY_REQUIRED");
            // Whole-file semantics must be mutually satisfiable for grouped duties.
            if (obligations.stream().map(o -> o.rule.expectedText()).distinct().count() != 1)
                blocked("CONFLICTING_STATIC_SEMANTICS");
            Step parsed = new Step(id, sequence, role, path, action, componentId, requirementIds,
                    prerequisites, decision, List.copyOf(obligations));
            steps.add(parsed); owningSteps.put(componentId, id);
        }
        if (steps.isEmpty() || !usedDecisions.equals(decisions.keySet())) blocked("INCOMPLETE_EXECUTABLE_PLAN");
        if (!usedRules.equals(rules.keySet())) invalid("EXCESS_STATIC_RULES");
        return new ExecutionPlan(plan, steps, suppliedRules);
    }

    /** Supported ownership uses explicit semantic categories in complete decision metadata, never paths. */
    private static void verifyOwnership(TrustedInputs.Role role, Map<String,Object> decision) {
        String layer = string(decision.get("targetLayer"));
        String component = string(decision.get("targetComponent"));
        List<String> scope = strings(decision.get("targetScope"));
        boolean eligible = switch (role) {
            case DOMAIN -> Set.of("DOMAIN_MODEL", "API_DATA_CONTRACT").contains(layer)
                    && Set.of("DOMAIN_MODEL", "API_REQUEST_MODEL", "API_RESPONSE_MODEL").contains(component);
            case PERSISTENCE -> "PERSISTENCE_MAPPING".equals(layer)
                    && Set.of("MAPPER", "PERSISTENCE_REPOSITORY").contains(component);
            case SERVICE -> "SERVICE_API".equals(layer)
                    && Set.of("SERVICE_INTERFACE", "SERVICE_IMPLEMENTATION", "CONTROLLER").contains(component);
            case TESTS -> "TEST_SOURCE".equals(layer) && "AUTOMATED_TEST_SOURCE".equals(component);
            default -> false;
        };
        if (!eligible || !scope.equals(List.of(component))) blocked("OWNERSHIP_NOT_SUPPORTED_OR_AMBIGUOUS");
    }

    List<Step> steps() { return steps; }
    Step step(String id) { return steps.stream().filter(s -> s.id.equals(id)).findFirst().orElseThrow(); }
    List<StaticRule> rules() { return rules; }
    Map<String,Object> plan() { return plan; }

    Map<String,Object> policy(String capabilityId, List<ValidationRule> validationRules) {
        Json.identifier(capabilityId);
        Map<String,ValidationRule> trusted = new TreeMap<>();
        for (ValidationRule rule : validationRules)
            if (trusted.put(rule.id, rule) != null) invalid("DUPLICATE_VALIDATION_RULE");
        Map<String,StaticRule> staticRules = new HashMap<>();
        for (StaticRule rule : rules) staticRules.put(rule.reference, rule);
        List<Map<String,Object>> expected = new ArrayList<>();
        Set<String> seen = new HashSet<>(), covered = new HashSet<>();
        Map<String,Object> validation = map(plan.get("validationPlan"));
        for (Object entry : list(validation.get("validationExpectations"))) {
            Map<String,Object> value = map(entry);
            fields(value, Set.of("id", "requirementIds", "acceptanceIntent", "verificationType", "observableOutcome",
                    "testChangeIds", "targetEvidenceIds"));
            String id = string(value.get("id"));
            if (!seen.add(id)) invalid("DUPLICATE_VALIDATION_EXPECTATION");
            if (!"STATIC_INSPECTION".equals(value.get("verificationType"))) blocked("PROCESS_VALIDATION_NOT_AVAILABLE");
            ValidationRule rule = trusted.get(id);
            if (rule == null || !rule.acceptanceIntent.equals(value.get("acceptanceIntent"))
                    || !rule.observableOutcome.equals(value.get("observableOutcome"))
                    || !rule.requirementIds.equals(strings(value.get("requirementIds"))))
                blocked("VALIDATION_AUTHORITY_INCOMPLETE");
            if (rule.obligationReferences.isEmpty()
                    || new HashSet<>(rule.obligationReferences).size() != rule.obligationReferences.size())
                invalid("VALIDATION_STATIC_MEMBERSHIP");
            for (String ref : rule.obligationReferences) {
                if (!staticRules.containsKey(ref)) invalid("UNRESOLVED_VALIDATION_MECHANISM");
                Step owner = steps.stream().filter(s -> s.obligations.stream().anyMatch(o -> o.pointer.equals(ref)))
                        .findFirst().orElseThrow();
                if (!rule.requirementIds.containsAll(owner.requirementIds)) invalid("VALIDATION_REQUIREMENT_COVERAGE");
                covered.add(ref);
            }
            expected.add(Json.object("validationExpectationId", id, "requiredCommandIds", List.of(),
                    "additionalEvidenceReferences", rule.obligationReferences.stream().sorted().toList(),
                    "passCriteria", "ALL_BOUND_EXACT_UTF8_FILE_V1_PREDICATES_PASS",
                    "failCriteria", "ANY_BOUND_EXACT_UTF8_FILE_V1_PREDICATE_FAILS"));
        }
        if (expected.isEmpty() || !seen.equals(trusted.keySet()) || !covered.equals(staticRules.keySet()))
            blocked("COMPLETE_STATIC_VALIDATION_REQUIRED");
        validateTestTraceability(validation);
        expected.sort(Comparator.comparing(v -> string(v.get("validationExpectationId"))));
        return Json.object("policyVersion", 1, "policyKind", "VALIDATION_EXECUTION_POLICY_V1",
                "policyId", "controlled-exact-file-static-v1", "executionMode", "SEQUENTIAL",
                "runtimeCapabilityIds", List.of(capabilityId), "cleanup", "PROHIBITED", "commands", List.of(),
                "effectScopes", List.of(), "expectationRules", expected);
    }

    void validateImplementationTraceability() { validateTestTraceability(map(plan.get("validationPlan"))); }

    private void validateTestTraceability(Map<String,Object> validation) {
        Set<String> testDecisions = new HashSet<>();
        for (Step step : steps) if (step.role == TrustedInputs.Role.TESTS) testDecisions.add(step.decisionId);
        Set<String> seen = new HashSet<>(), testIds = new HashSet<>();
        for (Object entry : list(validation.get("plannedTestChanges"))) {
            Map<String,Object> value = map(entry);
            fields(value, Set.of("id", "requirementIds", "componentDecisionId", "action", "expectedPath", "testScope",
                    "rationale", "targetFindingIds", "targetEvidenceIds"));
            String id = string(value.get("id")), decision = string(value.get("componentDecisionId"));
            if (!testIds.add(id) || !seen.add(decision) || !testDecisions.contains(decision)) invalid("TEST_PLAN_BINDING");
            Step step = steps.stream().filter(s -> s.decisionId.equals(decision)).findFirst().orElseThrow();
            if (!step.path.equals(value.get("expectedPath")) || !step.decision.get("decision").equals(value.get("action"))
                    || !step.requirementIds.equals(strings(value.get("requirementIds")))) invalid("TEST_PLAN_BINDING");
            string(value.get("testScope")); string(value.get("rationale"));
        }
        if (!seen.equals(testDecisions)) blocked("TEST_PLAN_TRACEABILITY_REQUIRED");
        for (Object entry : list(validation.get("validationExpectations")))
            if (!testIds.containsAll(strings(map(entry).get("testChangeIds")))) invalid("UNKNOWN_TEST_CHANGE");
        for (Object entry : list(validation.get("existingRelevantTests"))) {
            Map<String,Object> value = map(entry);
            fields(value, Set.of("path", "symbol", "observedCoverage", "targetEvidenceIds", "componentDecisionId", "plannedUse"));
            String decision = string(value.get("componentDecisionId"));
            Step step = steps.stream().filter(s -> s.decisionId.equals(decision)).findFirst().orElse(null);
            if (step == null || step.role != TrustedInputs.Role.TESTS || !"MODIFY".equals(step.action)
                    || !step.path.equals(value.get("path")) || !"EXTEND_EXISTING".equals(value.get("plannedUse")))
                invalid("EXISTING_TEST_BINDING");
        }
    }

    static Map<String,Object> callerResolution(List<StaticRule> rules, List<ValidationRule> validationRules) {
        return Json.object("resolutionKind", "HOST_SELECTED_EXACT_FILE_STATIC_AUTHORITY_V1",
                "staticRules", rules.stream().sorted(Comparator.comparing(StaticRule::reference)).map(rule ->
                        Json.object("reference", rule.reference, "pathKey", rule.path,
                                "expectedText", rule.expectedText, "expectedDescription", rule.expectedDescription)).toList(),
                "validationRules", validationRules.stream().sorted(Comparator.comparing(ValidationRule::id)).map(ValidationRule::declaration).toList());
    }

    record StaticAuthority(List<StaticRule> rules, List<ValidationRule> validationRules) {}
    static StaticAuthority readStaticAuthority(byte[] exactBytes) {
        if (exactBytes == null) blocked("STATIC_VALIDATION_AUTHORITY_REQUIRED");
        String text = new String(exactBytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), exactBytes)) invalid("INVALID_UTF8_STATIC_AUTHORITY");
        Evidence.rejectObviousSecrets(text);
        Map<String,Object> value = Json.parse(text);
        fields(value, Set.of("resolutionKind", "staticRules", "validationRules"));
        if (!"HOST_SELECTED_EXACT_FILE_STATIC_AUTHORITY_V1".equals(value.get("resolutionKind")))
            invalid("STATIC_AUTHORITY_KIND");
        List<StaticRule> rules = new ArrayList<>();
        for (Object entry : list(value.get("staticRules"))) {
            Map<String,Object> rule = map(entry);
            fields(rule, Set.of("reference", "pathKey", "expectedText", "expectedDescription"));
            if (!(rule.get("expectedText") instanceof String expected)) invalid("EXPECTED_TEXT_REQUIRED");
            rules.add(new StaticRule(string(rule.get("reference")), string(rule.get("pathKey")),
                    (String)rule.get("expectedText"), string(rule.get("expectedDescription"))));
        }
        List<ValidationRule> validationRules = new ArrayList<>();
        for (Object entry : list(value.get("validationRules"))) {
            Map<String,Object> rule = map(entry);
            fields(rule, Set.of("validationExpectationId", "acceptanceIntent", "observableOutcome", "requirementIds", "obligationReferences"));
            validationRules.add(new ValidationRule(string(rule.get("validationExpectationId")), string(rule.get("acceptanceIntent")),
                    string(rule.get("observableOutcome")), strings(rule.get("requirementIds")), strings(rule.get("obligationReferences"))));
        }
        return new StaticAuthority(List.copyOf(rules), List.copyOf(validationRules));
    }

    static String canonicalPath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")
                || value.contains(":") || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) invalid("NONCANONICAL_PATH");
        for (String part : value.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")
                    || part.codePoints().anyMatch(Character::isISOControl)) invalid("NONCANONICAL_PATH");
        if (!Path.of(value).normalize().toString().equals(value)) invalid("NONCANONICAL_PATH");
        return value;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) {
        if (!(value instanceof Map<?,?>)) invalid("OBJECT_REQUIRED"); return (Map<String,Object>) value;
    }
    static List<?> list(Object value) { if (!(value instanceof List<?>)) invalid("ARRAY_REQUIRED"); return (List<?>) value; }
    static String string(Object value) { if (!(value instanceof String)) invalid("STRING_REQUIRED"); Json.identifier((String)value); return (String)value; }
    static int integer(Object value) {
        if (!(value instanceof Number n) || n.longValue() < 1 || n.longValue() > Integer.MAX_VALUE
                || !(value instanceof Integer || value instanceof Long)) invalid("POSITIVE_INTEGER_REQUIRED");
        return ((Number)value).intValue();
    }
    static List<String> strings(Object value) {
        List<String> result = list(value).stream().map(ExecutionPlan::string).toList();
        if (new HashSet<>(result).size() != result.size()) invalid("DUPLICATE_ARRAY_MEMBER");
        return result;
    }
    static void fields(Map<String,Object> value, Set<String> names) { if (!value.keySet().equals(names)) invalid("SCHEMA_FIELDS"); }
    private static void requireSubset(List<String> entries, Set<String> allowed, boolean nonempty, String code) {
        if (nonempty && entries.isEmpty() || !allowed.containsAll(entries)) invalid(code);
    }
    static void invalid(String code) { throw new IllegalArgumentException("FAILED:" + code); }
    static void blocked(String code) { throw new IllegalStateException("BLOCKED:" + code); }
}
