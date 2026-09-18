package dev.agentic.harness;

import java.util.*;
import static dev.agentic.harness.ArtifactContracts.*;

/** NEW_OPERATION planning data only. This class cannot construct dispatches, grants or authority bundles. */
final class OperationPlanContract {
    static final List<String> COMPONENTS = List.of("DOMAIN_MODEL", "REQUEST_DTO", "RESPONSE_DTO", "PERSISTENCE", "MAPPER", "SERVICE", "API", "TESTS");
    static final List<String> CONVENTIONS = List.of("persistence", "domainContracts", "mapping", "service", "api", "testing");
    static final List<String> PLACEMENT_FIELDS = List.of("parentKey", "parentOrder", "filterOrder", "before", "after", "reference");
    static final String FACT_TOPIC = "OPERATION_PLANNING_FACT_V1";
    private final MigrationInput input;
    private final Map<String,Object> target;
    private final Map<String,Map<String,Object>> findings = new LinkedHashMap<>(), evidence = new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> facts = new LinkedHashMap<>();
    private final Set<String> unresolved = new TreeSet<>();

    /** Narrow NEW_OPERATION gate over host-retained full-read receipts, never model-supplied tool results. */
    static void verifyObservedExcerpts(Map<String,Object> target, MigrationInput input, List<Map<String,Object>> receipts) {
        var contract = new OperationPlanContract(input, target);
        for (var factEntry : contract.facts.entrySet()) {
            var finding = contract.findings.get(factEntry.getKey());
            if (!"OBSERVED".equals(finding.get("findingStatus"))) continue;
            for (String id : strings(finding.get("evidenceIds"))) {
                var e = contract.evidence.get(id);
                if ("DIRECTORY".equals(e.get("kind"))) continue; // Already correlated to directory metadata by the pipeline.
                String path = input.targetRoot().resolve(string(e.get("path"))).toString();
                String excerpt = nonempty(e.get("observation"));
                boolean observed = receipts.stream().map(r -> object(r.get("result"))).anyMatch(r ->
                        "READ_TARGET_TEXT".equals(r.get("operation")) && path.equals(r.get("canonicalScope"))
                        && Boolean.TRUE.equals(object(r.get("result")).get("complete"))
                        && Boolean.FALSE.equals(object(r.get("result")).get("truncated"))
                        && string(object(r.get("result")).get("text")).contains(excerpt));
                if (!observed) fail("OPERATION_FACT_EXCERPT_NOT_OBSERVED");
            }
        }
    }

    private OperationPlanContract(MigrationInput input, Map<String,Object> target) {
        this.input = input; this.target = target;
        collectFindings(target, findings);
        for (Object entry : list(target.get("evidence"))) {
            var e = object(entry); evidence.put(nonempty(e.get("id")), e);
        }
        for (var f : findings.values()) if (FACT_TOPIC.equals(f.get("topic"))) {
            var fact = Json.parse(string(f.get("statement")));
            fields(fact, "operationName operationType tableName subject property value");
            for (String key : List.of("operationName", "operationType", "tableName", "subject", "property")) nonempty(fact.get(key));
            scalar(fact.get("value"));
            facts.put((String)f.get("id"), fact);
        }
    }

    /** Execution additionally requires an observed exact CREATE destination, not just a proposed filename. */
    static String creationPath(MigrationInput input, Map<String,Object> target, String component) {
        var contract = new OperationPlanContract(input, target);
        for (var entry : contract.facts.entrySet()) {
            var fact = entry.getValue();
            if (contract.scoped(fact) && component.equals(fact.get("subject")) && "creationPath".equals(fact.get("property"))) {
                var finding = contract.findings.get(entry.getKey());
                return (String)contract.resolution(Json.object("status", "RESOLVED", "value", fact.get("value"),
                        "targetFindingId", entry.getKey(), "targetEvidenceIds", finding.get("evidenceIds"), "reason", null),
                        component, "creationPath", "/creationPath", true);
            }
        }
        throw new IllegalStateException("NEW_OPERATION_EXACT_CREATION_PATH_REQUIRED");
    }

    static void validate(Map<String,Object> plan, MigrationInput input, Map<String,byte[]> accepted) {
        equal(input.workflow(), Workflow.NEW_OPERATION, "OPERATION_PLAN_WORKFLOW");
        if (accepted.containsKey("SOURCE_ANALYSIS") || accepted.containsKey("MIGRATION_PLAN")) fail("OPERATION_PLAN_SOURCE_LINEAGE");
        byte[] requirementBytes = accepted.get("OPERATION_REQUIREMENT"), targetBytes = accepted.get("TARGET_ANALYSIS");
        if (requirementBytes == null || targetBytes == null) fail("OPERATION_PLAN_PREDECESSORS_REQUIRED");
        var requirement = ArtifactContracts.validate(TrustedInputs.Role.REQUIREMENT_ANALYSIS, requirementBytes, input, Map.of());
        var target = ArtifactContracts.validate(TrustedInputs.Role.ANALYSIS, targetBytes, input, Map.of());
        equal(requirement.get("status"), "SUCCESS", "OPERATION_REQUIREMENT_NOT_ACCEPTABLE");
        equal(target.get("status"), "SUCCESS", "TARGET_ANALYSIS_NOT_ACCEPTABLE");
        fields(plan, "planningVersion workflow status callerRequestFingerprint operationRequirementFingerprint targetAnalysisFingerprint targetProject requestedOperation targetConventions databaseMapping fieldMappings componentDecisions placement requiredBehavioralChanges implementationOrder testObligations validationObligations risks manualReviewItems blockingIssues coverage");
        equal(plan.get("planningVersion"), 1, "OPERATION_PLAN_VERSION");
        equal(plan.get("workflow"), "NEW_OPERATION", "OPERATION_PLAN_WORKFLOW");
        equal(plan.get("callerRequestFingerprint"), input.fingerprint(), "OPERATION_PLAN_CALLER_BINDING");
        equal(plan.get("operationRequirementFingerprint"), Json.fingerprint("OPERATION_REQUIREMENT", requirementBytes), "OPERATION_PLAN_REQUIREMENT_BINDING");
        equal(plan.get("targetAnalysisFingerprint"), Json.fingerprint("TARGET_ANALYSIS", targetBytes), "OPERATION_PLAN_TARGET_BINDING");
        var project = object(plan.get("targetProject")); fields(project, "name root");
        for (String key : project.keySet()) equal(project.get(key), object(target.get("project")).get(key), "OPERATION_PLAN_PROJECT");
        equal(plan.get("requestedOperation"), requirement.get("explicitRequirements"), "OPERATION_PLAN_CALLER_SUBSTITUTION");
        new OperationPlanContract(input, target).details(plan);
    }

    private void details(Map<String,Object> plan) {
        String status = choice(plan.get("status"), "SUCCESS BLOCKED");
        var conventions = object(plan.get("targetConventions")); fields(conventions, String.join(" ", CONVENTIONS));
        for (String key : CONVENTIONS) resolution(conventions.get(key), "conventions", key, "/targetConventions/" + key, true);
        var database = object(plan.get("databaseMapping")); fields(database, "physicalTable endpointPath");
        resolution(database.get("physicalTable"), "operation", "physicalTable", "/databaseMapping/physicalTable", true);
        resolution(database.get("endpointPath"), "operation", "endpointPath", "/databaseMapping/endpointPath", true);
        List<Object> mappings = list(plan.get("fieldMappings"));
        int offset = 0;
        for (String direction : List.of("requestFields", "responseFields")) {
            var callerFields = list(input.callerInformation().get(direction));
            for (int i = 0; i < callerFields.size(); i++) {
                if (offset >= mappings.size()) fail("OPERATION_PLAN_FIELD_COVERAGE");
                var mapping = object(mappings.get(offset));
                fields(mapping, "callerReference callerField databaseColumn javaField javaType");
                String ref = "/" + direction + "/" + i;
                equal(mapping.get("callerReference"), ref, "OPERATION_PLAN_FIELD_REFERENCE");
                equal(mapping.get("callerField"), callerFields.get(i), "OPERATION_PLAN_FIELD_SUBSTITUTION");
                for (String property : List.of("databaseColumn", "javaField", "javaType"))
                    resolution(mapping.get(property), ref, property, "/fieldMappings/" + offset + "/" + property, true);
                offset++;
            }
        }
        equal(mappings.size(), offset, "OPERATION_PLAN_FIELD_COVERAGE");
        var behaviors = indexed(plan.get("requiredBehavioralChanges"), "BC");
        if (behaviors.isEmpty()) unresolved.add("/requiredBehavioralChanges");
        int index = 0;
        for (var behavior : behaviors.values()) {
            fields(behavior, "id callerReferences behavior");
            var refs = strings(behavior.get("callerReferences"));
            if (!refs.contains("/operationType")) fail("OPERATION_PLAN_BEHAVIOR_CALLER_SCOPE");
            for (String ref : refs) callerReference(ref);
            resolution(behavior.get("behavior"), "operation", "behavior", "/requiredBehavioralChanges/" + index++ + "/behavior", false);
        }
        var components = indexed(plan.get("componentDecisions"), "CD");
        if (components.size() != COMPONENTS.size()) fail("OPERATION_PLAN_COMPONENT_COVERAGE");
        Set<String> coveredBehaviors = new HashSet<>();
        Map<String,String> paths = new HashMap<>(); index = 0;
        for (var component : components.values()) {
            fields(component, "id component status decision targetPath basis behavioralChangeIds dependencies");
            String kind = COMPONENTS.get(index), pointer = "/componentDecisions/" + index++;
            equal(component.get("component"), kind, "OPERATION_PLAN_COMPONENT_ORDER");
            String state = choice(component.get("status"), "RESOLVED UNRESOLVED NOT_APPLICABLE");
            var duties = references(component.get("behavioralChangeIds"), behaviors);
            references(component.get("dependencies"), components);
            coveredBehaviors.addAll(duties);
            if (state.equals("UNRESOLVED")) {
                equal(component.get("decision"), null, "OPERATION_PLAN_UNRESOLVED_ACTION");
                equal(component.get("targetPath"), null, "OPERATION_PLAN_UNRESOLVED_PATH");
                equal(object(component.get("basis")).get("status"), "UNRESOLVED", "OPERATION_PLAN_UNRESOLVED_BASIS");
                resolution(component.get("basis"), kind, "existingPath", pointer + "/basis", true);
            } else if (state.equals("NOT_APPLICABLE")) {
                equal(component.get("decision"), null, "OPERATION_PLAN_INAPPLICABLE_ACTION");
                equal(component.get("targetPath"), null, "OPERATION_PLAN_INAPPLICABLE_PATH");
                equal(component.get("behavioralChangeIds"), List.of(), "OPERATION_PLAN_INAPPLICABLE_DUTIES");
                equal(component.get("dependencies"), List.of(), "OPERATION_PLAN_INAPPLICABLE_DEPENDENCIES");
                Object value = resolution(component.get("basis"), kind, "applicability", pointer + "/basis", true);
                if (value != null) equal(value, "NOT_APPLICABLE", "OPERATION_PLAN_APPLICABILITY");
            } else {
                String action = choice(component.get("decision"), "REUSE MODIFY CREATE");
                String path = nonempty(component.get("targetPath")); ExecutionPlan.canonicalPath(path);
                if (duties.isEmpty()) fail("OPERATION_PLAN_COMPONENT_RESPONSIBILITIES");
                Object basis = resolution(component.get("basis"), kind, action.equals("CREATE") ? "creationDirectory" : "existingPath", pointer + "/basis", true);
                if (basis != null) {
                    if (action.equals("CREATE")) {
                        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : ".";
                        equal(basis, parent, "OPERATION_PLAN_CREATE_LOCATION");
                        requireEvidencePath(component.get("basis"), parent.equals(".") ? "" : parent, true);
                        if (evidence.values().stream().anyMatch(e -> path.equals(e.get("path")))) fail("OPERATION_PLAN_CREATE_EXISTS");
                    } else {
                        equal(basis, path, "OPERATION_PLAN_EXISTING_PATH"); requireEvidencePath(component.get("basis"), path, false);
                    }
                }
                String prior = paths.putIfAbsent(path.toLowerCase(Locale.ROOT), action);
                if (prior != null) fail("OPERATION_PLAN_SHARED_PATH_UNSUPPORTED");
            }
        }
        if (!coveredBehaviors.equals(behaviors.keySet())) unresolved.add("/componentDecisions");
        for (String id : components.keySet()) closure(id, components, new HashSet<>());
        steps(plan.get("implementationOrder"), components);
        placement(object(plan.get("placement")), behaviors, plan);
        obligations(plan, behaviors, components);
        for (String key : List.of("uncertainties", "conflicts", "blockingQuestions"))
            if (!list(target.get(key)).isEmpty()) unresolved.add("/targetAnalysis");
        issues(plan.get("risks"), "RISK");
        issues(plan.get("manualReviewItems"), "REVIEW");
        Set<String> blockers = issues(plan.get("blockingIssues"), "BLOCK");
        if (!blockers.containsAll(unresolved)) fail("OPERATION_PLAN_UNREPORTED_GAPS");
        var coverage = object(plan.get("coverage"));
        fields(coverage, "componentCount fieldMappingCount behavioralChangeCount unresolvedReferences complete");
        equal(coverage.get("componentCount"), components.size(), "OPERATION_PLAN_COVERAGE");
        equal(coverage.get("fieldMappingCount"), mappings.size(), "OPERATION_PLAN_COVERAGE");
        equal(coverage.get("behavioralChangeCount"), behaviors.size(), "OPERATION_PLAN_COVERAGE");
        equal(coverage.get("unresolvedReferences"), new ArrayList<>(unresolved), "OPERATION_PLAN_GAP_COVERAGE");
        boolean complete = unresolved.isEmpty() && list(plan.get("blockingIssues")).isEmpty() && list(plan.get("manualReviewItems")).isEmpty();
        equal(coverage.get("complete"), complete, "OPERATION_PLAN_COMPLETENESS");
        equal(status, complete ? "SUCCESS" : "BLOCKED", "OPERATION_PLAN_STATUS");
    }

    private Object resolution(Object supplied, String subject, String property, String pointer, boolean unique) {
        var value = object(supplied); fields(value, "status value targetFindingId targetEvidenceIds reason");
        String status = choice(value.get("status"), "RESOLVED UNRESOLVED");
        if (status.equals("UNRESOLVED")) {
            equal(value.get("value"), null, "OPERATION_PLAN_UNRESOLVED_VALUE");
            equal(value.get("targetFindingId"), null, "OPERATION_PLAN_UNRESOLVED_FINDING");
            equal(value.get("targetEvidenceIds"), List.of(), "OPERATION_PLAN_UNRESOLVED_EVIDENCE");
            nonempty(value.get("reason")); unresolved.add(pointer); return null;
        }
        equal(value.get("reason"), null, "OPERATION_PLAN_RESOLVED_REASON"); scalar(value.get("value"));
        if (subject.equals("placement") && property.endsWith("Order")) {
            if (!(value.get("value") instanceof Integer || value.get("value") instanceof Long)) fail("OPERATION_PLAN_ORDER_TYPE");
        } else nonempty(value.get("value"));
        String id = nonempty(value.get("targetFindingId")); var f = findings.get(id); var fact = facts.get(id);
        if (f == null || fact == null) fail("OPERATION_PLAN_FACT_REQUIRED");
        equal(f.get("findingStatus"), "OBSERVED", "OPERATION_PLAN_UNOBSERVED_FACT");
        if (Set.of("CONFLICTING", "UNKNOWN").contains(f.get("prevalence"))) fail("OPERATION_PLAN_AMBIGUOUS_FACT");
        for (String key : List.of("operationName", "operationType", "tableName"))
            equal(fact.get(key), input.callerInformation().get(key), "OPERATION_PLAN_FACT_SCOPE");
        equal(fact.get("subject"), subject, "OPERATION_PLAN_FACT_SUBJECT");
        equal(fact.get("property"), property, "OPERATION_PLAN_FACT_PROPERTY");
        equal(fact.get("value"), value.get("value"), "OPERATION_PLAN_FACT_VALUE");
        Set<String> ids = strings(value.get("targetEvidenceIds"));
        if (ids.isEmpty() || !ids.equals(strings(f.get("evidenceIds")))) fail("OPERATION_PLAN_EVIDENCE_SUPPORT");
        boolean direct = false;
        for (String evidenceId : ids) {
            var e = evidence.get(evidenceId);
            if (e == null || !list(e.get("supports")).contains(id)) fail("OPERATION_PLAN_EVIDENCE_SUPPORT");
            if (Set.of("SOURCE", "CONFIGURATION", "TEST").contains(e.get("kind"))
                    || property.equals("creationDirectory") && "DIRECTORY".equals(e.get("kind"))) direct = true;
        }
        if (!direct) fail("OPERATION_PLAN_DIRECT_EVIDENCE_REQUIRED");
        // A matching ID alone is not evidence of the claimed value. Existing paths and
        // directories are correlated separately against evidence paths/metadata.
        if (!Set.of("existingPath", "creationDirectory").contains(property)
                && ids.stream().map(evidence::get).filter(e -> Set.of("SOURCE", "CONFIGURATION", "TEST").contains(e.get("kind")))
                .noneMatch(e -> containsValue(string(e.get("observation")), value.get("value"))))
            fail("OPERATION_PLAN_VALUE_NOT_IN_EVIDENCE");
        if (unique) for (var other : facts.entrySet()) {
            var candidate = other.getValue();
            if (List.of("operationName", "operationType", "tableName", "subject", "property").stream()
                    .allMatch(key -> Objects.equals(candidate.get(key), fact.get(key)))
                    && !Objects.equals(candidate.get("value"), fact.get("value"))) fail("OPERATION_PLAN_CONFLICTING_FACTS");
        }
        return value.get("value");
    }

    private void placement(Map<String,Object> p, Map<String,Map<String,Object>> behaviors, Map<String,Object> plan) {
        fields(p, "callerPlacement strategy resolvedValues coordinatedChanges transactionConsistency");
        Object caller = input.callerInformation().get("placement"); equal(p.get("callerPlacement"), caller, "OPERATION_PLAN_PLACEMENT_CALLER");
        Object strategy = resolution(p.get("strategy"), "placement", "strategy", "/placement/strategy", true);
        if (strategy != null) choice(strategy, "NOT_APPLICABLE PRESERVE EXPLICIT DERIVED SHIFT");
        Set<String> seen = new HashSet<>(); int index = 0;
        for (Object item : list(p.get("resolvedValues"))) {
            var row = object(item); fields(row, "field origin value support");
            String field = choice(row.get("field"), String.join(" ", PLACEMENT_FIELDS));
            if (!seen.add(field)) fail("OPERATION_PLAN_DUPLICATE_PLACEMENT");
            scalar(row.get("value"));
            if (field.endsWith("Order")) {
                if (!(row.get("value") instanceof Integer || row.get("value") instanceof Long)) fail("OPERATION_PLAN_ORDER_TYPE");
            } else nonempty(row.get("value"));
            boolean explicit = caller != null && object(caller).containsKey(field);
            equal(row.get("origin"), explicit ? "CALLER_EXPLICIT" : "TARGET_EVIDENCE", "OPERATION_PLAN_PLACEMENT_ORIGIN");
            if (explicit) equal(row.get("value"), object(caller).get(field), "OPERATION_PLAN_PLACEMENT_CHANGED");
            Object support = resolution(row.get("support"), "placement", field, "/placement/resolvedValues/" + index++ + "/support", true);
            if (support == null && !explicit) fail("OPERATION_PLAN_INVENTED_PLACEMENT");
            if (support != null) equal(support, row.get("value"), "OPERATION_PLAN_PLACEMENT_SUPPORT");
        }
        if (caller != null && !seen.containsAll(object(caller).keySet())) fail("OPERATION_PLAN_PLACEMENT_OMITTED");
        // A planner cannot hide material target-established order/parent values by omitting rows.
        for (var fact : facts.values()) if (scoped(fact) && "placement".equals(fact.get("subject"))
                && PLACEMENT_FIELDS.contains(fact.get("property")) && !seen.contains(fact.get("property")))
            unresolved.add("/placement/resolvedValues");
        if ("DERIVED".equals(strategy) && seen.isEmpty()) unresolved.add("/placement/resolvedValues");
        if (seen.contains("before") && seen.contains("after")) unresolved.add("/placement/resolvedValues");
        if ("NOT_APPLICABLE".equals(strategy) && !seen.isEmpty()) fail("OPERATION_PLAN_PLACEMENT_APPLICABILITY");
        if ("EXPLICIT".equals(strategy) && (caller == null || object(caller).isEmpty())) fail("OPERATION_PLAN_PLACEMENT_ORIGIN");
        if ("SHIFT".equals(strategy)) {
            Object changes = resolution(p.get("coordinatedChanges"), "placement", "coordinatedChanges", "/placement/coordinatedChanges", true);
            resolution(p.get("transactionConsistency"), "placement", "transactionConsistency", "/placement/transactionConsistency", true);
            if (list(plan.get("risks")).stream().map(ArtifactContracts::object).noneMatch(r -> "COORDINATED_ORDER_UPDATE".equals(r.get("code"))))
                fail("OPERATION_PLAN_ORDER_RISK_REQUIRED");
            if (changes != null && behaviors.values().stream().noneMatch(b -> changes.equals(object(b.get("behavior")).get("value"))))
                unresolved.add("/placement/coordinatedChanges");
        } else {
            equal(p.get("coordinatedChanges"), null, "OPERATION_PLAN_UNSUPPORTED_SHIFT");
            equal(p.get("transactionConsistency"), null, "OPERATION_PLAN_UNSUPPORTED_TRANSACTION");
        }
    }

    private void steps(Object supplied, Map<String,Map<String,Object>> components) {
        var steps = indexed(supplied, "STEP"); Map<String,String> owners = new HashMap<>(); Set<String> prior = new HashSet<>(); int sequence = 0;
        for (var step : steps.values()) {
            fields(step, "id sequence specialistRole componentDecisionIds prerequisiteStepIds");
            equal(step.get("sequence"), ++sequence, "OPERATION_PLAN_STEP_SEQUENCE");
            Set<String> ids = references(step.get("componentDecisionIds"), components);
            if (ids.isEmpty()) fail("OPERATION_PLAN_EMPTY_ASSIGNMENT");
            for (String id : ids) {
                var component = components.get(id);
                if (!mutable(component) || owners.putIfAbsent(id, (String)step.get("id")) != null) fail("OPERATION_PLAN_ASSIGNMENT");
                equal(step.get("specialistRole"), owner((String)component.get("component")), "OPERATION_PLAN_OWNER");
            }
            Set<String> prerequisites = strings(step.get("prerequisiteStepIds")), expected = new HashSet<>();
            if (!prior.containsAll(prerequisites)) fail("OPERATION_PLAN_FORWARD_DEPENDENCY");
            for (String id : ids) for (String dep : strings(components.get(id).get("dependencies"))) {
                if (ids.contains(dep)) continue;
                if (mutable(components.get(dep))) {
                    String predecessor = owners.get(dep);
                    if (predecessor == null || !prior.contains(predecessor)) fail("OPERATION_PLAN_DEPENDENCY_ORDER");
                    expected.add(predecessor);
                }
            }
            equal(prerequisites, expected, "OPERATION_PLAN_PREREQUISITES"); prior.add((String)step.get("id"));
        }
        for (var component : components.values()) {
            if (mutable(component) && !owners.containsKey(component.get("id"))) fail("OPERATION_PLAN_UNASSIGNED_COMPONENT");
            for (String dep : strings(component.get("dependencies"))) {
                var dependency = components.get(dep);
                if (!"RESOLVED".equals(dependency.get("status"))) unresolved.add("/implementationOrder");
                if ("REUSE".equals(component.get("decision")) && mutable(dependency)) fail("OPERATION_PLAN_REUSE_MUTABLE_DEPENDENCY");
            }
        }
    }

    private void obligations(Map<String,Object> plan, Map<String,Map<String,Object>> behaviors, Map<String,Map<String,Object>> components) {
        var tests = indexed(plan.get("testObligations"), "TEST"); var validations = indexed(plan.get("validationObligations"), "VAL");
        Set<String> tested = new HashSet<>(), validated = new HashSet<>(); int index = 0;
        for (var test : tests.values()) {
            fields(test, "id behavioralChangeIds componentDecisionIds expectedOutcome");
            Set<String> duties = references(test.get("behavioralChangeIds"), behaviors);
            var componentIds = references(test.get("componentDecisionIds"), components);
            if (duties.isEmpty() || componentIds.isEmpty()) fail("OPERATION_PLAN_TEST_TRACEABILITY");
            for (String id : componentIds) {
                var component = components.get(id);
                if (!"TESTS".equals(component.get("component")) || !strings(component.get("behavioralChangeIds")).containsAll(duties))
                    fail("OPERATION_PLAN_TEST_COMPONENT");
                if (!"RESOLVED".equals(component.get("status"))) unresolved.add("/testObligations");
            }
            tested.addAll(duties);
            resolution(test.get("expectedOutcome"), "operation", "testOutcome", "/testObligations/" + index++ + "/expectedOutcome", false);
        }
        index = 0;
        for (var validation : validations.values()) {
            fields(validation, "id testObligationIds method expectation");
            var refs = references(validation.get("testObligationIds"), tests);
            if (refs.isEmpty()) fail("OPERATION_PLAN_VALIDATION_TRACEABILITY");
            choice(validation.get("method"), "STATIC_REVIEW AUTOMATED_TEST"); validated.addAll(refs);
            resolution(validation.get("expectation"), "operation", "validationExpectation", "/validationObligations/" + index++ + "/expectation", false);
        }
        if (!tested.equals(behaviors.keySet()) || tests.isEmpty()) unresolved.add("/testObligations");
        if (!validated.equals(tests.keySet()) || validations.isEmpty()) unresolved.add("/validationObligations");
    }

    private void requireEvidencePath(Object value, String path, boolean directory) {
        if (strings(object(value).get("targetEvidenceIds")).stream().map(evidence::get)
                .noneMatch(e -> path.equals(e.get("path")) && (directory ? "DIRECTORY".equals(e.get("kind")) : !"DIRECTORY".equals(e.get("kind")))))
            fail("OPERATION_PLAN_PATH_EVIDENCE");
    }
    private void callerReference(String ref) {
        // Only normalized structural intent, never unknown caller fields or prose as authority.
        if (Set.of("/tableName", "/operationType", "/operationName", "/requestFields", "/responseFields").contains(ref)) return;
        if (ref.equals("/placement") && input.callerInformation().containsKey("placement")) return;
        if (ref.matches("/(requestFields|responseFields)/(0|[1-9][0-9]*)")) {
            String[] parts = ref.split("/");
            try { list(input.callerInformation().get(parts[1])).get(Integer.parseInt(parts[2])); return; }
            catch (RuntimeException invalid) { fail("OPERATION_PLAN_CALLER_REFERENCE"); }
        }
        fail("OPERATION_PLAN_CALLER_REFERENCE");
    }
    private Set<String> issues(Object value, String prefix) {
        Set<String> affected = new HashSet<>();
        for (var issue : indexed(value, prefix).values()) {
            fields(issue, "id code description affectedReferences resolutionRoute");
            nonempty(issue.get("code")); nonempty(issue.get("description"));
            choice(issue.get("resolutionRoute"), "REFRESH_TARGET_ANALYSIS HOST_INPUT_REGISTRATION");
            Set<String> refs = strings(issue.get("affectedReferences"));
            if (refs.isEmpty() || refs.stream().anyMatch(r -> !r.startsWith("/"))) fail("OPERATION_PLAN_ISSUE_SCOPE");
            affected.addAll(refs);
        }
        return affected;
    }
    private static void closure(String id, Map<String,Map<String,Object>> components, Set<String> active) {
        if (!active.add(id)) fail("OPERATION_PLAN_DEPENDENCY_CYCLE");
        for (String dep : strings(components.get(id).get("dependencies"))) closure(dep, components, active);
        active.remove(id);
    }
    static String owner(String component) {
        return switch (component) {
            case "DOMAIN_MODEL", "REQUEST_DTO", "RESPONSE_DTO" -> "03-domain-contract-implementation";
            case "PERSISTENCE", "MAPPER" -> "04-persistence-mapping-implementation";
            case "SERVICE", "API" -> "05-service-api-implementation";
            case "TESTS" -> "06-test-implementation";
            default -> throw new IllegalArgumentException("OPERATION_PLAN_COMPONENT");
        };
    }
    private static boolean mutable(Map<String,Object> c) { return "MODIFY".equals(c.get("decision")) || "CREATE".equals(c.get("decision")); }
    private static Map<String,Map<String,Object>> indexed(Object value, String prefix) {
        Map<String,Map<String,Object>> result = new LinkedHashMap<>();
        for (Object item : list(value)) {
            var row = object(item); String id = nonempty(row.get("id"));
            if (!id.matches(prefix + "-[0-9]{3,}") || result.putIfAbsent(id, row) != null) fail("OPERATION_PLAN_ID");
        }
        return result;
    }
    private static Set<String> references(Object value, Map<String,?> namespace) {
        Set<String> refs = strings(value); if (!namespace.keySet().containsAll(refs)) fail("OPERATION_PLAN_REFERENCE"); return refs;
    }
    private static Set<String> strings(Object value) {
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list(value)) if (!result.add(nonempty(item))) fail("OPERATION_PLAN_DUPLICATE_REFERENCE");
        return result;
    }
    private static String choice(Object value, String options) {
        String result = nonempty(value); if (!List.of(options.split(" ")).contains(result)) fail("OPERATION_PLAN_ENUM"); return result;
    }
    private static void scalar(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) nonempty(value);
    }
    private boolean scoped(Map<String,Object> fact) {
        return List.of("operationName", "operationType", "tableName").stream()
                .allMatch(key -> Objects.equals(fact.get(key), input.callerInformation().get(key)));
    }
    private static boolean containsValue(String excerpt, Object value) {
        String text = value.toString();
        if (value instanceof Integer || value instanceof Long)
            return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_$+.\\-])" + java.util.regex.Pattern.quote(text)
                    + "(?![\\p{L}\\p{N}_$.])").matcher(excerpt).find();
        // Avoid matching column id in other_id or order 3 inside 13. Encoded strings
        // are also allowed in real JSON/configuration excerpts.
        return excerpt.contains(Json.write(value)) && value instanceof String
                || java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_$])" + java.util.regex.Pattern.quote(text)
                        + "(?![\\p{L}\\p{N}_$])").matcher(excerpt).find();
    }
    private static void fail(String code) { throw new IllegalArgumentException(code); }
}
