package dev.agentic.harness;

import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;

/** Exact host predicates and mock outputs are deliberately separate from pipeline authority creation. */
final class ImplementationFixtures {
    static final List<TrustedInputs.Role> ROLES = List.of(TrustedInputs.Role.DOMAIN, TrustedInputs.Role.PERSISTENCE,
            TrustedInputs.Role.SERVICE, TrustedInputs.Role.TESTS);
    static final List<String> PATHS = List.of("src/Value.java", "src/ValueMapper.java", AnalysisFixtures.TARGET_PATH, "src/ReadItemContractTest.java");
    static final List<String> TEXTS = List.of("record Value(String value) {}\n",
            "final class ValueMapper { Value map(String value) { return new Value(value); } }\n",
            AnalysisFixtures.TARGET_TEXT + "final class ReadItemService { String readItem(String value) { return new ValueMapper().map(value).value(); } }\n",
            "final class ReadItemContractTest { static void verifiesIdentity() { if (!new ReadItemService().readItem(\"example\").equals(\"example\")) throw new AssertionError(); } }\n");
    static final List<String> DUTIES = List.of("Declare the String payload Value record.", "Map String input into Value without changing it.",
            "Provide readItem returning the mapped String unchanged; retain TargetConventions.", "Add static assertion source for the service String identity contract.");
    static List<ExecutionPlan.StaticRule> rules() {
        List<ExecutionPlan.StaticRule> rules = new ArrayList<>();
        for (int i = 0; i < 4; i++) rules.add(new ExecutionPlan.StaticRule(pointer(i), PATHS.get(i), TEXTS.get(i), DUTIES.get(i)));
        return rules;
    }
    static String pointer(int i) { return "/componentDecisions/" + i + "/expectedChangeScope/responsibilities/0"; }
    static byte[] resolution() { return Json.bytes(ExecutionPlan.callerResolution(rules(), List.of())); }
    static Map<String,Object> plan(MigrationInput input, Map<String,byte[]> accepted) {
        var plan = Json.parse(Json.write(AnalysisFixtures.plan(input, accepted.get("SOURCE_ANALYSIS"), accepted.get("TARGET_ANALYSIS"))));
        List<String> components = List.of("DOMAIN_MODEL", "MAPPER", "SERVICE_IMPLEMENTATION", "AUTOMATED_TEST_SOURCE");
        List<String> layers = List.of("DOMAIN_MODEL", "PERSISTENCE_MAPPING", "SERVICE_API", "TEST_SOURCE");
        List<Map<String,Object>> decisions = new ArrayList<>(), steps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = "CD-00" + (i + 1), stepId = "STEP-00" + (i + 1);
            var decision = AnalysisFixtures.decision(id, components.get(i), layers.get(i), i == 2 ? "EXTEND_EXISTING" : "CREATE_NEW",
                    PATHS.get(i), List.of("F-00" + (i + 2)), i == 0 ? List.of() : List.of("CD-00" + i),
                    "Apply the observed target convention for this responsibility.", "IMPLEMENTATION", DUTIES.get(i));
            decision.put("expectedChangeScope", Json.object("changeType", i == 2 ? "MODIFY" : "CREATE",
                    "responsibilities", List.of(Json.object("responsibilityClass", "IMPLEMENTATION", "description", DUTIES.get(i))),
                    "expectedPaths", List.of(PATHS.get(i))));
            decisions.add(decision);
            steps.add(AnalysisFixtures.step(stepId, i + 1, ROLES.get(i), id, PATHS.get(i), i == 0 ? List.of() : List.of("STEP-00" + i), List.of()));
        }
        plan.put("componentDecisions", decisions); plan.put("implementationOrder", steps);
        var mapping = map(((List<?>)plan.get("targetMappings")).getFirst());
        mapping.put("componentDecisionIds", List.of("CD-001", "CD-002", "CD-003", "CD-004"));
        mapping.put("targetLayers", layers); mapping.put("targetComponents", components);
        var validation = map(plan.get("validationPlan")); var tests = map(((List<?>)validation.get("plannedTestChanges")).getFirst());
        tests.put("componentDecisionId", "CD-004");
        var coverage = map(plan.get("coverage")); coverage.put("expectedTouchedFiles", PATHS.stream().sorted().toList());
        coverage.put("decisions", Json.object("total", 4, "reuseExisting", 0, "extendExisting", 1, "createNew", 3, "manualReviewRequired", 0));
        return plan;
    }
    static Map<String,Object> answer(Map<String,Object> turn, Fixture fixture) {
        String role = (String)map(turn.get("binding")).get("role"); var data = data(turn);
        if (role.equals(TrustedInputs.Role.PLANNING.id)) {
            Map<String,byte[]> accepted = new HashMap<>();
            for (Object a : (List<?>)data.get("acceptedArtifacts")) {
                var value = map(a); accepted.put((String)value.get("role"), ((String)value.get("exactText")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return artifact(turn, "MIGRATION_PLAN", plan(fixture.input(), accepted));
        }
        int index = -1;
        for (int i = 0; i < ROLES.size(); i++) if (ROLES.get(i).id.equals(role)) index = i;
        if (index < 0) return pipelineAnswer(turn, fixture);
        if (((List<?>)data.get("toolResults")).isEmpty()) return write(turn, index);
        return artifact(turn, (String)data.get("artifactRole"), result(turn));
    }
    static Map<String,Object> write(Map<String,Object> turn, int index) {
        var grant = map(((List<?>)data(turn).get("mutationGrants")).getFirst());
        var request = replace(tool(turn, "TARGET", index == 2 ? "WRITE_TARGET_TEXT" : "CREATE_TARGET_FILE", PATHS.get(index)),
                "grantId", grant.get("grantId"), "expectedBeforeFingerprint", map(grant.get("expectedBeforeState")).get("contentFingerprint"), "content", TEXTS.get(index));
        return envelope(turn, "TOOL_REQUEST", null, null, request, null, List.of());
    }
    static Map<String,Object> result(Map<String,Object> turn) {
        var data = data(turn); var grant = map(((List<?>)data.get("mutationGrants")).getFirst());
        String exactPlan = ((List<?>)data.get("acceptedArtifacts")).stream().map(RuntimeTest::map)
                .filter(a -> "MIGRATION_PLAN".equals(a.get("role"))).map(a -> (String)a.get("exactText")).findFirst().orElseThrow();
        var plan = ExecutionPlan.parse(Json.parse(exactPlan), rules());
        var step = plan.step((String)map(grant.get("authority")).get("stepId"));
        return Json.object("resultVersion", 1, "migrationPlanFingerprint", map(grant.get("authority")).get("migrationPlanFingerprint"),
                "predecessorAcceptance", data.get("predecessorAcceptance"), "grantEffects", data.get("observedGrantEffects"),
                "handoff", handoff(step, plan.plan(), map(data.get("orchestrationBinding"))));
    }
    static Map<String,Object> handoff(ExecutionPlan.Step step, Map<String,Object> plan, Map<String,Object> binding) {
        boolean typed = step.role() != TrustedInputs.Role.DOMAIN;
        var duties = step.obligations().stream().map(ExecutionPlan.Obligation::pointer).sorted().toList();
        var mutation = new LinkedHashMap<>(Json.object("componentDecisionId", step.decisionId(), "requirementIds", step.requirementIds(),
                "path", step.path(), "action", step.action().equals("CREATE") ? "CREATED" : "MODIFIED", "responsibilitiesApplied", duties));
        var applied = new LinkedHashMap<>(Json.object("componentDecisionId", step.decisionId(), "requirementIds", step.requirementIds(),
                "decision", step.decision().get("decision"), "implementationStepIds", List.of(step.id()), "implementationConstraintsApplied", List.of()));
        var file = new LinkedHashMap<>(Json.object("path", step.path(), "componentDecisionIds", step.decisionIds(),
                "requirementIds", step.requirementIds(), "responsibilitiesApplied", duties));
        if (typed) {
            mutation.put("responsibilityKind", ImplementationHandoff.kind(step)); applied.put("responsibilityKind", ImplementationHandoff.kind(step));
            file.put("responsibilityKinds", List.of(ImplementationHandoff.kind(step)));
        }
        applied.put("mutations", List.of(mutation));
        var assignment = new LinkedHashMap<>(Json.object("planComponentDecisions", 4, "assigned", 1, "ownedMutable", 1,
                "ownedReuse", 0, "ownedManualReview", 0, "readyMutable", 1, "notAssignedComponentDecisionIds",
                ((List<?>)plan.get("componentDecisions")).stream().map(RuntimeTest::map).map(d -> (String)d.get("id")).filter(id -> !id.equals(step.decisionId())).sorted().toList()));
        if (step.role() == TrustedInputs.Role.PERSISTENCE) { assignment.put("ownedMapperMutable", 1); assignment.put("ownedPersistenceMutable", 0); }
        if (step.role() == TrustedInputs.Role.SERVICE) {
            for (String name : List.of("ownedServiceInterfaceMutable", "ownedControllerApiMutable", "ownedApiRoutingConstantMutable", "ownedServiceApiSupportConstantMutable")) assignment.put(name, 0);
            assignment.put("ownedServiceImplementationMutable", 1);
        }
        List<Map<String,Object>> checks = new ArrayList<>();
        for (String name : ImplementationHandoff.checks(step.role())) {
            boolean applicable = ImplementationHandoff.applicableCheck(step, name);
            Object row = "Host must independently verify this claimed responsibility.";
            if (step.role() == TrustedInputs.Role.TESTS) row = Json.object("componentDecisionIds", step.decisionIds(), "requirementIds", step.requirementIds(),
                    "planReferences", List.of(duties.getFirst(), "/validationPlan/plannedTestChanges/0", "/validationPlan/validationExpectations/0"),
                    "targetFindingIds", step.decision().get("targetFindingIds"), "targetEvidenceIds", step.decision().get("targetEvidenceIds"),
                    "paths", List.of(step.path()), "symbols", List.of(), "beforeObservation", "Assigned test source was absent.",
                    "afterObservation", "Assigned test assertion source exists; no test execution claimed.", "conformance", applicable ? "PASS" : "UNVERIFIABLE", "notes", "Static source handoff only.");
            checks.add(Json.object("id", "SV-%03d".formatted(checks.size() + 1), "check", name, "result", applicable ? "PASS" : "NOT_PERFORMED", "evidence", List.of(row)));
        }
        var target = map(plan.get("targetProject"));
        return Json.object("implementationVersion", 1, "agent", step.role().id, "status", "SUCCESS",
                "targetProject", Json.object("name", target.get("name"), "root", target.get("root")),
                "planReference", Json.object("planningVersion", plan.get("planningVersion"), "planStatus", plan.get("status"),
                        "targetAnalysisVersion", target.get("analysisVersion"), "targetAnalysisStatus", target.get("analysisStatus"),
                        "assignedImplementationStepIds", List.of(step.id()), "completedPrerequisiteStepIds", step.prerequisites()),
                "orchestrationBinding", binding, "assignedComponentDecisionIds", step.decisionIds(), "appliedComponentDecisions", List.of(applied),
                "skippedComponentDecisions", List.of(), "modifiedFiles", step.action().equals("MODIFY") ? List.of(file) : List.of(),
                "createdFiles", step.action().equals("CREATE") ? List.of(file) : List.of(), "deletedFiles", List.of(), "preExistingChanges", List.of(),
                "staticVerification", Json.object("result", "PASS", "checks", checks), "deviations", List.of(), "blockingIssues", List.of(),
                "coverage", Json.object("assignment", assignment, "requirements", Json.object("assignedRequirementIds", step.requirementIds(),
                        "appliedRequirementIds", step.requirementIds(), "incompleteRequirementIds", List.of()),
                        "paths", Json.object("authorizedMutationPaths", List.of(step.path()), "agentChangedPaths", List.of(step.path()), "unauthorizedAgentChangedPaths", List.of()),
                        "phases", ImplementationHandoff.PHASES.stream().map(p -> Json.object("phase", p, "result", "COMPLETE", "notes", "Static implementation only.")).toList(),
                        "notes", List.of("07 validation remains unavailable.")));
    }
}
