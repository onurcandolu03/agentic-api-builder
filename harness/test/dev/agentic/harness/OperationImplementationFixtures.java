package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;

/** Synthetic source metadata and independently host-approved file predicates; never a live project. */
@SuppressWarnings("unchecked")
final class OperationImplementationFixtures {
    static final List<String> PATHS = List.of(AnalysisFixtures.TARGET_PATH, "src/OperationRepository.java", "src/NewOperation.java", "src/NewOperationTest.java");
    static final List<Integer> OFFSETS = List.of(0, 3, 5, 7);
    static Map<String,Object> copy(Map<String,Object> v) { return Json.parse(Json.write(v)); }
    static byte[] exact(Map<String,Object> v) { return (" \n" + Json.write(v) + "\n").getBytes(StandardCharsets.UTF_8); }
    static Map<String,Object> row(Map<String,Object> v, String k, int i) { return map(((List<?>)v.get(k)).get(i)); }
    static Map<String,Object> target(MigrationInput input, boolean shift) {
        var t = copy(OperationPlanFixtures.target(input));
        var findings = (List<Object>)map(t.get("architecture")).get("findings");
        var file = row(t, "evidence", 1); var directory = row(t, "evidence", 2);
        for (Object item : findings) {
            var finding = map(item); if (!OperationPlanContract.FACT_TOPIC.equals(finding.get("topic"))) continue;
            var fact = Json.parse((String)finding.get("statement"));
            if (fact.get("subject").equals("PERSISTENCE")) {
                fact.put("property", "creationDirectory"); fact.put("value", "src");
                finding.put("evidenceIds", List.of("E-003"));
                ((List<?>)file.get("supports")).remove(finding.get("id")); ((List<Object>)directory.get("supports")).add(finding.get("id"));
            }
            if (shift && fact.get("subject").equals("placement") && fact.get("property").equals("strategy")) fact.put("value", "SHIFT");
            finding.put("statement", Json.write(fact));
        }
        for (int i = 1; i < 4; i++) add(t, input, "F-90" + i, List.of("", "PERSISTENCE", "SERVICE", "TESTS").get(i), "creationPath", PATHS.get(i));
        if (shift) {
            String behavior = (String)OperationPlanFixtures.resolved(t, "operation", "behavior").get("value");
            add(t, input, "F-910", "placement", "coordinatedChanges", behavior);
            add(t, input, "F-911", "placement", "transactionConsistency", "Apply coordinated shifts within the observed atomic repository transaction");
        }
        file.put("observation", "Synthetic reviewed fixture rules: " + Json.write(findings.stream().map(RuntimeTest::map)
                .filter(f -> OperationPlanContract.FACT_TOPIC.equals(f.get("topic"))).map(f -> Json.parse((String)f.get("statement"))).toList()));
        return t;
    }
    private static void add(Map<String,Object> t, MigrationInput input, String id, String subject, String property, Object value) {
        var finding = copy(row(map(t.get("architecture")), "findings", 1)); finding.put("id", id);
        finding.put("statement", Json.write(Json.object("operationName", input.requestedOperation(), "operationType", input.callerInformation().get("operationType"),
                "tableName", input.callerInformation().get("tableName"), "subject", subject, "property", property, "value", value)));
        finding.put("evidenceIds", List.of("E-002")); ((List<Object>)map(t.get("architecture")).get("findings")).add(finding);
        ((List<Object>)row(t, "evidence", 1).get("supports")).add(id);
    }
    static String original(MigrationInput input, boolean shift) {
        return AnalysisFixtures.TARGET_TEXT + "\n/* " + row(target(input, shift), "evidence", 1).get("observation") + " */\n";
    }
    static String text(MigrationInput input, boolean shift, int index) {
        return switch (index) {
            case 0 -> original(input, shift) + "record OperationValue(String id) {}\n";
            case 1 -> "interface OperationRepository { String apply(String id); }\n";
            case 2 -> "final class NewOperation { private final OperationRepository repository; NewOperation(OperationRepository repository) { this.repository = repository; } String apply(String id) { return repository.apply(id); } }\n";
            default -> "final class NewOperationTest { static void followsRepositoryResult() { if (!new NewOperation(id -> id).apply(\"sample\").equals(\"sample\")) throw new AssertionError(); } }\n";
        };
    }
    static Map<String,Object> plan(MigrationInput input, Map<String,byte[]> accepted, boolean reuse, boolean shift) {
        var p = OperationPlanFixtures.plan(input, accepted); var t = Json.parse(MigrationInput.utf8(accepted.get("TARGET_ANALYSIS")));
        List<Map<String,Object>> steps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var c = row(p, "componentDecisions", OFFSETS.get(i));
            c.put("status", "RESOLVED"); c.put("decision", i == 0 ? reuse ? "REUSE" : "MODIFY" : "CREATE"); c.put("targetPath", PATHS.get(i));
            c.put("basis", OperationPlanFixtures.resolved(t, (String)c.get("component"), i == 0 ? "existingPath" : "creationDirectory"));
            c.put("behavioralChangeIds", List.of("BC-001"));
            c.put("dependencies", i == 0 ? List.of() : List.of(row(p, "componentDecisions", OFFSETS.get(i - 1)).get("id")));
            if (i == 0 && reuse) continue;
            String id = "STEP-%03d".formatted(steps.size() + 1);
            steps.add(Json.object("id", id, "sequence", steps.size() + 1, "specialistRole", OperationImplementation.ROLES.get(i).id,
                    "componentDecisionIds", List.of(c.get("id")), "prerequisiteStepIds", steps.isEmpty() ? List.of() : List.of(steps.getLast().get("id"))));
        }
        p.put("implementationOrder", steps);
        if (shift) {
            var placement = map(p.get("placement"));
            placement.put("coordinatedChanges", OperationPlanFixtures.resolved(t, "placement", "coordinatedChanges"));
            placement.put("transactionConsistency", OperationPlanFixtures.resolved(t, "placement", "transactionConsistency"));
            p.put("risks", List.of(Json.object("id", "RISK-001", "code", "COORDINATED_ORDER_UPDATE", "description", "Atomic coordinated changes required",
                    "affectedReferences", List.of("/placement"), "resolutionRoute", "REFRESH_TARGET_ANALYSIS")));
        }
        return p;
    }
    static Map<String,byte[]> accepted(MigrationInput input, boolean reuse, boolean shift) {
        Map<String,byte[]> a = new LinkedHashMap<>(); a.put("OPERATION_REQUIREMENT", exact(OperationRequirement.normalize(input)));
        a.put("TARGET_ANALYSIS", exact(target(input, shift))); a.put("OPERATION_PLAN", exact(plan(input, a, reuse, shift))); return a;
    }
    static byte[] policy(MigrationInput input, Map<String,byte[]> a, boolean shift) {
        var plan = Json.parse(MigrationInput.utf8(a.get("OPERATION_PLAN"))); List<Map<String,Object>> files = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var c = row(plan, "componentDecisions", OFFSETS.get(i)); if ("REUSE".equals(c.get("decision"))) continue;
            files.add(Json.object("componentDecisionId", c.get("id"), "path", c.get("targetPath"), "expectedText", text(input, shift, i),
                    "obligationsFingerprint", Json.fingerprint("OPERATION_COMPONENT_OBLIGATIONS", Json.bytes(OperationImplementation.obligations(plan, c)))));
        }
        return Json.bytes(Json.object("authorityVersion", 1, "authorityKind", "NEW_OPERATION_EXACT_FILES_V1", "lineage", OperationImplementation.lineage(input, a), "files", files));
    }
    static Map<String,Object> answer(Map<String,Object> turn, Fixture fixture, boolean reuse, boolean shift) {
        String role = (String)map(turn.get("binding")).get("role");
        if (role.equals("00r-requirement-analysis")) return artifact(turn, "OPERATION_REQUIREMENT", OperationRequirement.normalize(fixture.input()));
        if (role.equals("02r-operation-planning")) {
            Map<String,byte[]> a = new LinkedHashMap<>();
            for (Object item : (List<?>)data(turn).get("acceptedArtifacts")) { var v = map(item); a.put((String)v.get("role"), ((String)v.get("exactText")).getBytes(StandardCharsets.UTF_8)); }
            return artifact(turn, "OPERATION_PLAN", plan(fixture.input(), a, reuse, shift));
        }
        int index = OperationImplementation.ROLES.stream().map(r -> r.id).toList().indexOf(role);
        if (index >= 0) {
            var grants = (List<?>)data(turn).get("mutationGrants");
            if (!grants.isEmpty() && ((List<?>)data(turn).get("toolResults")).isEmpty()) {
                var grant = map(grants.getFirst());
                return envelope(turn, "TOOL_REQUEST", null, null, replace(tool(turn, "TARGET", index == 0 ? "WRITE_TARGET_TEXT" : "CREATE_TARGET_FILE", (String)grant.get("pathKey")),
                        "grantId", grant.get("grantId"), "expectedBeforeFingerprint", map(grant.get("expectedBeforeState")).get("contentFingerprint"), "content", text(fixture.input(), shift, index)), null, List.of());
            }
            var d = data(turn); var dispatch = map(d.get("dispatch")); var assignment = map(d.get("assignment"));
            return artifact(turn, (String)d.get("artifactRole"), Json.object("implementationVersion", 1, "workflow", "NEW_OPERATION", "agent", role, "status", "SUCCESS",
                    "lineage", d.get("lineage"), "runAuthorityBundleFingerprint", dispatch.get("runAuthorityBundleFingerprint"), "dispatchFingerprint", d.get("dispatchFingerprint"),
                    "predecessorAcceptance", d.get("predecessorAcceptance"), "assignmentFingerprint", Json.fingerprint("NEW_OPERATION_STAGE_ASSIGNMENT", Json.bytes(assignment)),
                    "completedComponentDecisionIds", ((List<?>)assignment.get("componentDecisions")).stream().map(RuntimeTest::map).map(c -> c.get("id")).toList(),
                    "changedFiles", grants.stream().map(RuntimeTest::map).map(g -> g.get("pathKey")).toList(), "grantEffects", d.get("observedGrantEffects")));
        }
        var answer = pipelineAnswer(turn, fixture);
        if (role.equals("01-target-analysis") && answer.get("kind").equals("ARTIFACT")) return artifact(turn, "TARGET_ANALYSIS", target(fixture.input(), shift));
        return answer;
    }
}
