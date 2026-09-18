package dev.agentic.harness;

import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;

/** Synthetic, explicitly reviewed schema/convention observations for offline planning tests. */
final class OperationPlanFixtures {
    private OperationPlanFixtures() {}
    static void prepareTarget(MigrationInput input) throws java.io.IOException {
        var analysis = target(input);
        String observations = (String)map(((List<?>)analysis.get("evidence")).get(1)).get("observation");
        java.nio.file.Files.writeString(input.targetRoot().resolve(AnalysisFixtures.TARGET_PATH),
                AnalysisFixtures.TARGET_TEXT + "\n/* Synthetic fixture schema/business-rule metadata:\n" + observations + "\n*/\n");
    }
    static Map<String,Object> target(MigrationInput input) {
        var target = Json.parse(Json.write(AnalysisFixtures.target(input)));
        List<Map<String,Object>> facts = new ArrayList<>();
        for (String key : OperationPlanContract.CONVENTIONS) add(facts, input, "conventions", key, switch (key) {
            case "persistence" -> "Existing repository port; preserve its technology";
            case "api" -> "Internal Java operation; no HTTP endpoint";
            default -> "Use the inspected " + key + " convention";
        });
        add(facts, input, "operation", "physicalTable", "physical_items");
        add(facts, input, "operation", "endpointPath", "NOT_APPLICABLE");
        for (String direction : List.of("requestFields", "responseFields")) {
            var fields = (List<?>)input.callerInformation().get(direction);
            if (fields == null) continue;
            for (int i = 0; i < fields.size(); i++) {
                String name = (String)map(fields.get(i)).get("name"); String subject = "/" + direction + "/" + i;
                add(facts, input, subject, "databaseColumn", "db_" + name);
                add(facts, input, subject, "javaField", name);
                add(facts, input, subject, "javaType", "String");
            }
        }
        for (String kind : OperationPlanContract.COMPONENTS) {
            if (kind.equals("DOMAIN_MODEL")) add(facts, input, kind, "existingPath", AnalysisFixtures.TARGET_PATH);
            else if (Set.of("SERVICE", "TESTS").contains(kind)) add(facts, input, kind, "creationDirectory", "src");
            else add(facts, input, kind, "applicability", "NOT_APPLICABLE");
        }
        Object placement = input.callerInformation().get("placement");
        add(facts, input, "placement", "strategy", placement == null || map(placement).isEmpty() ? "NOT_APPLICABLE" : "EXPLICIT");
        if (placement != null) map(placement).forEach((key, value) -> add(facts, input, "placement", key, value));
        add(facts, input, "operation", "behavior", "Apply the evidenced " + input.callerInformation().get("operationType") + " repository-port behavior to the requested item");
        add(facts, input, "operation", "testOutcome", "The operation follows the evidenced repository-port result and error behavior");
        add(facts, input, "operation", "validationExpectation", "Review field mappings and run the planned operation contract tests under a future host policy");
        var findings = new ArrayList<>(ArtifactContracts.list(map(target.get("architecture")).get("findings")));
        List<String> fileSupports = new ArrayList<>(List.of("F-002", "F-003", "F-004", "F-005")), directorySupports = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            String id = "F-" + (100 + i); var fact = facts.get(i); boolean directory = fact.get("property").equals("creationDirectory");
            (directory ? directorySupports : fileSupports).add(id);
            findings.add(Json.object("id", id, "topic", OperationPlanContract.FACT_TOPIC, "findingStatus", "OBSERVED",
                    "statement", Json.write(fact), "scope", List.of("src"), "prevalence", "CONSISTENT",
                    "sampleBasis", "Reviewed synthetic target metadata", "evidenceIds", List.of(directory ? "E-003" : "E-002"),
                    "implementationConstraint", "Preserve this observed target rule"));
        }
        map(target.get("architecture")).put("findings", findings);
        var evidence = new ArrayList<>(ArtifactContracts.list(target.get("evidence")));
        map(evidence.get(1)).put("supports", fileSupports);
        map(evidence.get(1)).put("observation", "Reviewed synthetic target metadata: " + Json.write(facts));
        evidence.add(Json.object("id", "E-003", "kind", "DIRECTORY", "path", "src", "symbol", "", "location", "",
                "observation", "The existing src directory contains target sources", "supports", directorySupports));
        target.put("evidence", evidence);
        return target;
    }
    private static void add(List<Map<String,Object>> facts, MigrationInput input, String subject, String property, Object value) {
        facts.add(Json.object("operationName", input.requestedOperation(), "operationType", input.callerInformation().get("operationType"),
                "tableName", input.callerInformation().get("tableName"), "subject", subject, "property", property, "value", value));
    }
    static Map<String,Object> resolved(Map<String,Object> target, String subject, String property) {
        Map<String,Map<String,Object>> findings = new LinkedHashMap<>(); ArtifactContracts.collectFindings(target, findings);
        for (var finding : findings.values()) if (OperationPlanContract.FACT_TOPIC.equals(finding.get("topic"))) {
            var fact = Json.parse((String)finding.get("statement"));
            if (subject.equals(fact.get("subject")) && property.equals(fact.get("property")))
                return Json.object("status", "RESOLVED", "value", fact.get("value"), "targetFindingId", finding.get("id"),
                        "targetEvidenceIds", finding.get("evidenceIds"), "reason", null);
        }
        return unresolved();
    }
    static Map<String,Object> unresolved() {
        return Json.object("status", "UNRESOLVED", "value", null, "targetFindingId", null,
                "targetEvidenceIds", List.of(), "reason", "Refresh target analysis for the missing scoped fact");
    }
    static Map<String,Object> plan(MigrationInput input, Map<String,byte[]> accepted) {
        var target = Json.parse(MigrationInput.utf8(accepted.get("TARGET_ANALYSIS")));
        var requirement = Json.parse(MigrationInput.utf8(accepted.get("OPERATION_REQUIREMENT")));
        var conventions = new LinkedHashMap<String,Object>();
        for (String key : OperationPlanContract.CONVENTIONS) conventions.put(key, resolved(target, "conventions", key));
        List<Map<String,Object>> mappings = new ArrayList<>();
        for (String direction : List.of("requestFields", "responseFields")) {
            var fields = (List<?>)input.callerInformation().get(direction);
            for (int i = 0; i < fields.size(); i++) {
                String ref = "/" + direction + "/" + i;
                mappings.add(Json.object("callerReference", ref, "callerField", fields.get(i), "databaseColumn", resolved(target, ref, "databaseColumn"),
                        "javaField", resolved(target, ref, "javaField"), "javaType", resolved(target, ref, "javaType")));
            }
        }
        List<Map<String,Object>> components = new ArrayList<>();
        for (int i = 0; i < OperationPlanContract.COMPONENTS.size(); i++) {
            String kind = OperationPlanContract.COMPONENTS.get(i); boolean reuse = kind.equals("DOMAIN_MODEL"), create = Set.of("SERVICE", "TESTS").contains(kind);
            components.add(Json.object("id", "CD-00" + (i + 1), "component", kind, "status", reuse || create ? "RESOLVED" : "NOT_APPLICABLE",
                    "decision", reuse ? "REUSE" : create ? "CREATE" : null,
                    "targetPath", reuse ? AnalysisFixtures.TARGET_PATH : create ? "src/" + (kind.equals("SERVICE") ? "NewOperation.java" : "NewOperationTest.java") : null,
                    "basis", resolved(target, kind, reuse ? "existingPath" : create ? "creationDirectory" : "applicability"),
                    "behavioralChangeIds", reuse || create ? List.of("BC-001") : List.of(),
                    "dependencies", kind.equals("SERVICE") ? List.of("CD-001") : kind.equals("TESTS") ? List.of("CD-006") : List.of()));
        }
        List<Map<String,Object>> placements = new ArrayList<>(); Object placement = input.callerInformation().get("placement");
        if (placement != null) map(placement).forEach((key, value) -> placements.add(Json.object("field", key, "origin", "CALLER_EXPLICIT",
                "value", value, "support", resolved(target, "placement", key))));
        var result = Json.object("planningVersion", 1, "workflow", "NEW_OPERATION", "status", "SUCCESS",
                "callerRequestFingerprint", input.fingerprint(), "operationRequirementFingerprint", Json.fingerprint("OPERATION_REQUIREMENT", accepted.get("OPERATION_REQUIREMENT")),
                "targetAnalysisFingerprint", Json.fingerprint("TARGET_ANALYSIS", accepted.get("TARGET_ANALYSIS")),
                "targetProject", Json.object("name", map(target.get("project")).get("name"), "root", input.targetRoot().toString()),
                "requestedOperation", requirement.get("explicitRequirements"), "targetConventions", conventions,
                "databaseMapping", Json.object("physicalTable", resolved(target, "operation", "physicalTable"), "endpointPath", resolved(target, "operation", "endpointPath")),
                "fieldMappings", mappings, "componentDecisions", components,
                "placement", Json.object("callerPlacement", placement, "strategy", resolved(target, "placement", "strategy"), "resolvedValues", placements,
                        "coordinatedChanges", null, "transactionConsistency", null),
                "requiredBehavioralChanges", List.of(Json.object("id", "BC-001", "callerReferences", List.of("/operationType", "/tableName"),
                        "behavior", resolved(target, "operation", "behavior"))),
                "implementationOrder", List.of(Json.object("id", "STEP-001", "sequence", 1, "specialistRole", "05-service-api-implementation",
                                "componentDecisionIds", List.of("CD-006"), "prerequisiteStepIds", List.of()),
                        Json.object("id", "STEP-002", "sequence", 2, "specialistRole", "06-test-implementation",
                                "componentDecisionIds", List.of("CD-008"), "prerequisiteStepIds", List.of("STEP-001"))),
                "testObligations", List.of(Json.object("id", "TEST-001", "behavioralChangeIds", List.of("BC-001"), "componentDecisionIds", List.of("CD-008"),
                        "expectedOutcome", resolved(target, "operation", "testOutcome"))),
                "validationObligations", List.of(Json.object("id", "VAL-001", "testObligationIds", List.of("TEST-001"), "method", "AUTOMATED_TEST",
                        "expectation", resolved(target, "operation", "validationExpectation"))),
                "risks", List.of(), "manualReviewItems", List.of(), "blockingIssues", List.of(), "coverage", Json.object("componentCount", 8,
                        "fieldMappingCount", mappings.size(), "behavioralChangeCount", 1, "unresolvedReferences", List.of(), "complete", true));
        return Json.parse(Json.write(result));
    }
    static Map<String,byte[]> accepted(MigrationInput input) {
        return Map.of("OPERATION_REQUIREMENT", Json.bytes(OperationRequirement.normalize(input)), "TARGET_ANALYSIS", Json.bytes(target(input)));
    }
    static Map<String,Object> answer(Map<String,Object> turn, Fixture fixture) {
        String role = (String)map(turn.get("binding")).get("role");
        if (role.equals("02r-operation-planning")) {
            Map<String,byte[]> accepted = new LinkedHashMap<>();
            for (Object entry : (List<?>)data(turn).get("acceptedArtifacts")) {
                var artifact = map(entry); accepted.put((String)artifact.get("role"), ((String)artifact.get("exactText")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return artifact(turn, "OPERATION_PLAN", plan(fixture.input(), accepted));
        }
        var reply = pipelineAnswer(turn, fixture);
        if (role.equals("01-target-analysis") && reply.get("kind").equals("ARTIFACT")) {
            var analysis = target(fixture.input());
            // Reuse the real broker-derived inventory in the existing analyst fixture response.
            var old = Json.parse((String)reply.get("artifactText"));
            analysis.put("analysisCoverage", old.get("analysisCoverage"));
            return artifact(turn, "TARGET_ANALYSIS", analysis);
        }
        return reply;
    }
}
