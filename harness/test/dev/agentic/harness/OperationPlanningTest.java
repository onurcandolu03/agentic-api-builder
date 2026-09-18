package dev.agentic.harness;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static dev.agentic.harness.RuntimeTest.*;

/** Offline 02R acceptance, lineage, evidence and capability denial checks. */
@SuppressWarnings("unchecked")
public final class OperationPlanningTest {
    private static int contracts, runtime;
    private static Path temp, repository;
    public static void main(String[] args) throws Exception {
        temp = Path.of(args[0]).toRealPath(); repository = Path.of(args[1]).toRealPath();
        contractChecks();
        for (String fault : List.of("success", "missing-evidence", "master-reject", "drift", "acceptance-drift", "stop-drift",
                "READ_TARGET_TEXT", "WRITE_TARGET_TEXT", "CREATE_TARGET_FILE", "READ_SOURCE_TEXT", "RUN_PROCESS", "RUN_SHELL",
                "RUN_MAVEN", "NETWORK_REQUEST", "EXECUTE_SQL", "wrong-fingerprint", "forged-mapping", "forged-excerpt")) scenario(fault);
        System.out.println("PASS " + contracts + " operation-plan contract checks and " + runtime + " operation-planning runtime checks; mocked provider only");
    }
    static MigrationInput input(Path target, String operation, boolean placement) {
        var config = new LinkedHashMap<>(Json.object("workflow", "NEW_OPERATION", "targetProjectPath", target.toString(), "tableName", "items",
                "operationType", operation, "operationName", "itemOperation", "requestFields", List.of(Json.object("name", "id")),
                "responseFields", List.of(Json.object("name", "label"))));
        if (placement) config.put("placement", Json.object("parentKey", "groupId", "parentOrder", 3, "filterOrder", 1, "after", "secondItem"));
        return MigrationInput.fromJson(Json.bytes(config));
    }
    private static void validate(Map<String,Object> plan, MigrationInput input, Map<String,byte[]> accepted) {
        ArtifactContracts.validate(TrustedInputs.Role.OPERATION_PLANNING, Json.bytes(plan), input, accepted);
    }
    private static void deny(Runnable run, String label) {
        try { run.run(); } catch (IllegalArgumentException expected) { contracts++; return; }
        throw new AssertionError("02R accepted invalid contract: " + label);
    }
    private static void contractChecks() throws Exception {
        MigrationInput input = input(Path.of("/target"), "GET", true);
        var accepted = OperationPlanFixtures.accepted(input);
        var original = OperationPlanFixtures.plan(input, accepted);
        validate(original, input, accepted); contracts++;
        for (String field : original.keySet()) {
            var value = copy(original); value.remove(field); deny(() -> validate(value, input, accepted), "missing " + field);
        }
        Map<String,Consumer<Map<String,Object>>> mutations = new LinkedHashMap<>();
        mutations.put("unknown field", p -> p.put("sourceProject", Map.of()));
        mutations.put("unknown nested field", p -> map(p.get("databaseMapping")).put("sql", "SELECT forbidden"));
        mutations.put("workflow", p -> p.put("workflow", "MIGRATION"));
        mutations.put("version", p -> p.put("planningVersion", 2));
        mutations.put("unsupported status", p -> p.put("status", "PARTIAL"));
        for (String field : List.of("callerRequestFingerprint", "operationRequirementFingerprint", "targetAnalysisFingerprint"))
            mutations.put(field, p -> p.put(field, Json.fingerprint("OPERATION_REQUIREMENT", new byte[]{1})));
        for (String field : List.of("tableName", "operationName", "operationType", "requestFields", "responseFields"))
            mutations.put("caller " + field, p -> map(map(p.get("requestedOperation")).get(field)).put("value", "tampered"));
        mutations.put("project root", p -> map(p.get("targetProject")).put("root", "/different"));
        mutations.put("invented physical table", p -> map(map(p.get("databaseMapping")).get("physicalTable")).put("value", "invented"));
        mutations.put("invented DB column", p -> map(first(p, "fieldMappings").get("databaseColumn")).put("value", "invented"));
        mutations.put("invented Java type", p -> map(first(p, "fieldMappings").get("javaType")).put("value", "Long"));
        mutations.put("wrong fact property", p -> first(p, "fieldMappings").put("javaType", first(p, "fieldMappings").get("databaseColumn")));
        mutations.put("unrelated evidence", p -> map(first(p, "fieldMappings").get("databaseColumn")).put("targetEvidenceIds", List.of("E-001")));
        mutations.put("missing mapping", p -> ((List<?>)p.get("fieldMappings")).removeFirst());
        mutations.put("caller field", p -> map(first(p, "fieldMappings").get("callerField")).put("name", "other"));
        mutations.put("dropped component", p -> ((List<?>)p.get("componentDecisions")).removeFirst());
        mutations.put("unsupported component decision", p -> first(p, "componentDecisions").put("decision", "REPLACE"));
        mutations.put("duplicate component category", p -> row(p, "componentDecisions", 1).put("component", "DOMAIN_MODEL"));
        mutations.put("component extra authority", p -> first(p, "componentDecisions").put("writeGrant", true));
        mutations.put("unproved component path", p -> first(p, "componentDecisions").put("targetPath", "src/Missing.java"));
        mutations.put("create path escape", p -> row(p, "componentDecisions", 5).put("targetPath", "../Escape.java"));
        mutations.put("create outside evidenced directory", p -> row(p, "componentDecisions", 5).put("targetPath", "other/New.java"));
        mutations.put("dependency cycle", p -> first(p, "componentDecisions").put("dependencies", List.of("CD-008")));
        mutations.put("unknown dependency", p -> first(p, "componentDecisions").put("dependencies", List.of("CD-999")));
        mutations.put("duplicate assignment", p -> row(p, "implementationOrder", 1).put("componentDecisionIds", List.of("CD-006")));
        mutations.put("wrong owner", p -> first(p, "implementationOrder").put("specialistRole", "03-domain-contract-implementation"));
        mutations.put("missing predecessor", p -> row(p, "implementationOrder", 1).put("prerequisiteStepIds", List.of()));
        mutations.put("forward predecessor", p -> first(p, "implementationOrder").put("prerequisiteStepIds", List.of("STEP-002")));
        mutations.put("placement override", p -> map(p.get("placement")).put("callerPlacement", Map.of()));
        mutations.put("placement value override", p -> first(map(p.get("placement")), "resolvedValues").put("value", "different"));
        mutations.put("placement omitted", p -> map(p.get("placement")).put("resolvedValues", List.of()));
        mutations.put("invented append", p -> map(map(p.get("placement")).get("strategy")).put("value", "APPEND"));
        mutations.put("prose authority", p -> first(p, "requiredBehavioralChanges").put("callerReferences", List.of("/operationType", "/requirementText")));
        mutations.put("missing behavior", p -> p.put("requiredBehavioralChanges", List.of()));
        mutations.put("missing test", p -> p.put("testObligations", List.of()));
        mutations.put("missing validation", p -> p.put("validationObligations", List.of()));
        mutations.put("validation command", p -> first(p, "validationObligations").put("command", List.of("mvn", "test")));
        mutations.put("coverage count", p -> map(p.get("coverage")).put("componentCount", 0));
        mutations.put("incomplete SUCCESS", p -> first(p, "fieldMappings").put("databaseColumn", OperationPlanFixtures.unresolved()));
        mutations.put("SUCCESS with review", p -> p.put("manualReviewItems", List.of(issue("REVIEW-001", "CHECK", "/placement"))));
        for (var mutation : mutations.entrySet()) {
            var value = copy(original); mutation.getValue().accept(value);
            deny(() -> validate(value, input, accepted), mutation.getKey());
        }
        for (String predecessor : List.of("OPERATION_REQUIREMENT", "TARGET_ANALYSIS")) {
            var changed = new LinkedHashMap<>(accepted); changed.remove(predecessor);
            deny(() -> validate(original, input, changed), "missing predecessor");
            changed.put(predecessor, (" \n" + MigrationInput.utf8(accepted.get(predecessor))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            deny(() -> validate(original, input, changed), "exact predecessor bytes");
            var rebound = copy(original); rebound.put(predecessor.equals("TARGET_ANALYSIS") ? "targetAnalysisFingerprint" : "operationRequirementFingerprint",
                    Json.fingerprint(predecessor, changed.get(predecessor)));
            validate(rebound, input, changed); contracts++;
        }
        var source = new LinkedHashMap<>(accepted); source.put("SOURCE_ANALYSIS", Json.bytes(Map.of()));
        deny(() -> validate(original, input, source), "source lineage");
        for (String operation : List.of("GET", "INSERT", "UPDATE", "DELETE")) {
            MigrationInput other = input(Path.of("/target"), operation, false); var predecessors = OperationPlanFixtures.accepted(other);
            validate(OperationPlanFixtures.plan(other, predecessors), other, predecessors); contracts++;
        }
        for (String defect : List.of("missing-fact", "uncertain", "wrong-scope", "conflicting-fact", "documentation-only", "directory-only", "invented-value", "caller-name-only")) {
            var target = Json.parse(MigrationInput.utf8(accepted.get("TARGET_ANALYSIS")));
            var facts = (List<Object>)map(target.get("architecture")).get("findings");
            var firstFact = map(facts.get(1));
            if (defect.equals("missing-fact")) firstFact.put("topic", "Ordinary prose");
            if (defect.equals("uncertain")) firstFact.put("findingStatus", "UNCERTAIN");
            if (defect.equals("wrong-scope")) { var fact = Json.parse((String)firstFact.get("statement")); fact.put("operationType", "DELETE"); firstFact.put("statement", Json.write(fact)); }
            if (defect.equals("conflicting-fact")) {
                var duplicate = copy(firstFact); duplicate.put("id", "F-999");
                var fact = Json.parse((String)duplicate.get("statement")); fact.put("value", "conflicting technology"); duplicate.put("statement", Json.write(fact)); facts.add(duplicate);
                ((List<Object>)row(target, "evidence", 1).get("supports")).add("F-999");
            }
            if (defect.equals("documentation-only")) row(target, "evidence", 1).put("kind", "DOCUMENTATION");
            if (defect.equals("directory-only")) row(target, "evidence", 1).put("kind", "DIRECTORY");
            if (defect.equals("invented-value") || defect.equals("caller-name-only")) {
                // Even matching fact/plan values and valid reciprocal IDs cannot prove a column.
                changeFact(target, "/requestFields/0", "databaseColumn", defect.equals("caller-name-only") ? "id" : "invented_column");
                attachEvidence(target, "/requestFields/0", "databaseColumn", "public record Value(String value) {}");
            }
            var changed = new LinkedHashMap<>(accepted); changed.put("TARGET_ANALYSIS", Json.bytes(target));
            var value = copy(original); value.put("targetAnalysisFingerprint", Json.fingerprint("TARGET_ANALYSIS", changed.get("TARGET_ANALYSIS")));
            if (defect.equals("invented-value") || defect.equals("caller-name-only"))
                first(value, "fieldMappings").put("databaseColumn", OperationPlanFixtures.resolved(target, "/requestFields/0", "databaseColumn"));
            deny(() -> validate(value, input, changed), defect);
        }
        var blocked = copy(original); first(blocked, "fieldMappings").put("databaseColumn", OperationPlanFixtures.unresolved());
        block(blocked, "/fieldMappings/0/databaseColumn"); validate(blocked, input, accepted); contracts++;
        var unsupportedPlacement = copy(original); first(map(unsupportedPlacement.get("placement")), "resolvedValues").put("support", OperationPlanFixtures.unresolved());
        block(unsupportedPlacement, "/placement/resolvedValues/0/support"); validate(unsupportedPlacement, input, accepted); contracts++;
        shift(input, accepted);
        placementChecks();
        Path target = Files.createTempDirectory(temp, "02r-trusted-");
        var trusted = TrustedInputs.freeze(repository, target, true, Workflow.NEW_OPERATION);
        Evidence.rejectObviousSecrets(trusted.instructions());
        check(trusted.roles().contains(TrustedInputs.Role.OPERATION_PLANNING), "02R frozen role");
        var binding = trusted.roleBindings().stream().filter(r -> r.get("role").equals("02r-operation-planning")).findFirst().orElseThrow();
        check(binding.get("specificationFingerprint").equals(Json.fingerprint("AGENT_02R_SPECIFICATION", Files.readAllBytes(repository.resolve("agents/02r-operation-planning.md")))), "exact 02R specification bytes");
        check(!Workflow.MIGRATION.roles().contains(TrustedInputs.Role.OPERATION_PLANNING), "migration registry unchanged"); contracts++;
    }
    private static void changeFact(Map<String,Object> target, String subject, String property, Object value) {
        for (Object item : (List<?>)map(target.get("architecture")).get("findings")) {
            var finding = map(item);
            if (!OperationPlanContract.FACT_TOPIC.equals(finding.get("topic"))) continue;
            var fact = Json.parse((String)finding.get("statement"));
            if (subject.equals(fact.get("subject")) && property.equals(fact.get("property"))) {
                fact.put("value", value); finding.put("statement", Json.write(fact)); return;
            }
        }
        throw new AssertionError("Fixture fact not found");
    }
    private static void attachEvidence(Map<String,Object> target, String subject, String property, String excerpt) {
        String id = (String)OperationPlanFixtures.resolved(target, subject, property).get("targetFindingId");
        for (Object item : (List<?>)map(target.get("architecture")).get("findings"))
            if (id.equals(map(item).get("id"))) map(item).put("evidenceIds", List.of("E-004"));
        ((List<?>)row(target, "evidence", 1).get("supports")).remove(id);
        var evidence = copy(row(target, "evidence", 1)); evidence.put("id", "E-004");
        evidence.put("observation", excerpt); evidence.put("supports", List.of(id));
        ((List<Object>)target.get("evidence")).add(evidence);
    }
    private static void placementChecks() {
        MigrationInput initial = input(Path.of("/target"), "INSERT", true);
        for (String excerpt : List.of("order = 13;", "order = -3;", "order = 3.5;", "order = 3e2;", "order = 3;")) {
            var target = copy(OperationPlanFixtures.target(initial));
            attachEvidence(target, "placement", "parentOrder", excerpt);
            var accepted = new LinkedHashMap<>(OperationPlanFixtures.accepted(initial)); accepted.put("TARGET_ANALYSIS", Json.bytes(target));
            var plan = OperationPlanFixtures.plan(initial, accepted);
            if (excerpt.equals("order = 3;")) { validate(plan, initial, accepted); contracts++; }
            else deny(() -> validate(plan, initial, accepted), "order literal cannot be taken from a different number");
        }
        for (Object order : List.of(0, -1, 2147483648L, "3", 3.5)) {
            var caller = copy(initial.callerInformation());
            map(caller.get("placement")).put("parentOrder", order);
            if (order instanceof String || order instanceof Double) {
                deny(() -> MigrationInput.fromJson(Json.bytes(caller)), "integer-only caller order");
            } else {
                MigrationInput candidate = MigrationInput.fromJson(Json.bytes(caller));
                var accepted = OperationPlanFixtures.accepted(candidate);
                validate(OperationPlanFixtures.plan(candidate, accepted), candidate, accepted); contracts++;
            }
        }
        var integerPlan = OperationPlanFixtures.plan(initial, OperationPlanFixtures.accepted(initial));
        for (Object item : (List<?>)map(integerPlan.get("placement")).get("resolvedValues"))
            if ("parentOrder".equals(map(item).get("field"))) map(item).put("value", 3.5);
        deny(() -> OperationPlanContract.validate(integerPlan, initial, OperationPlanFixtures.accepted(initial)), "fractional plan order");
        for (String fault : List.of("unresolved-strategy", "conflicting-placement", "omitted-evidenced-order", "empty-derived", "string-derived-order")) {
            MigrationInput candidate = input(Path.of("/target"), "INSERT", fault.equals("conflicting-placement"));
            var target = copy(OperationPlanFixtures.target(candidate));
            if (fault.contains("derived") || fault.equals("omitted-evidenced-order")) {
                changeFact(target, "placement", "strategy", "DERIVED");
                row(target, "evidence", 1).put("observation", row(target, "evidence", 1).get("observation") + " DERIVED");
            }
            if (fault.endsWith("order") || fault.equals("conflicting-placement")) {
                var finding = copy(row(map(target.get("architecture")), "findings", 1));
                finding.put("id", "F-990");
                Object order = fault.equals("conflicting-placement") ? "firstItem" : fault.equals("string-derived-order") ? "3" : 3;
                finding.put("statement", Json.write(Json.object("operationName", "itemOperation", "operationType", "INSERT", "tableName", "items",
                        "subject", "placement", "property", fault.equals("conflicting-placement") ? "before" : "filterOrder", "value", order)));
                ((List<Object>)map(target.get("architecture")).get("findings")).add(finding);
                ((List<Object>)row(target, "evidence", 1).get("supports")).add("F-990");
                row(target, "evidence", 1).put("observation", row(target, "evidence", 1).get("observation") + " " + order);
            }
            var accepted = new LinkedHashMap<>(OperationPlanFixtures.accepted(candidate)); accepted.put("TARGET_ANALYSIS", Json.bytes(target));
            var plan = OperationPlanFixtures.plan(candidate, accepted);
            String pointer = "/placement/resolvedValues";
            if (fault.equals("unresolved-strategy")) {
                map(plan.get("placement")).put("strategy", OperationPlanFixtures.unresolved()); pointer = "/placement/strategy";
            }
            if (fault.contains("derived-order")) map(plan.get("placement")).put("resolvedValues", List.of(Json.object(
                    "field", "filterOrder", "origin", "TARGET_EVIDENCE", "value", "3",
                    "support", OperationPlanFixtures.resolved(target, "placement", "filterOrder"))));
            if (fault.equals("conflicting-placement")) ((List<Object>)map(plan.get("placement")).get("resolvedValues")).add(Json.object(
                    "field", "before", "origin", "TARGET_EVIDENCE", "value", "firstItem", "support", OperationPlanFixtures.resolved(target, "placement", "before")));
            MigrationInput boundInput = candidate;
            deny(() -> validate(plan, boundInput, accepted), fault);
            if (!fault.contains("derived-order")) {
                block(plan, pointer); validate(plan, boundInput, accepted); contracts++;
            }
        }
    }
    private static void shift(MigrationInput input, Map<String,byte[]> accepted) {
        var target = Json.parse(MigrationInput.utf8(accepted.get("TARGET_ANALYSIS")));
        var facts = (List<Object>)map(target.get("architecture")).get("findings");
        Map<String,Object> template = null;
        String behavior = (String)OperationPlanFixtures.resolved(target, "operation", "behavior").get("value");
        for (Object item : facts) {
            var f = map(item); if (!OperationPlanContract.FACT_TOPIC.equals(f.get("topic"))) continue;
            var fact = Json.parse((String)f.get("statement"));
            if (fact.get("subject").equals("placement") && fact.get("property").equals("strategy")) {
                fact.put("value", "SHIFT"); f.put("statement", Json.write(fact)); template = f;
            }
        }
        for (String property : List.of("coordinatedChanges", "transactionConsistency")) {
            var f = copy(template); String id = property.equals("coordinatedChanges") ? "F-980" : "F-981"; f.put("id", id);
            var fact = Json.parse((String)f.get("statement")); fact.put("property", property);
            fact.put("value", property.equals("coordinatedChanges") ? behavior : "Use the observed atomic ordering transaction with conflict detection");
            f.put("statement", Json.write(fact)); facts.add(f); ((List<Object>)row(target, "evidence", 1).get("supports")).add(id);
        }
        row(target, "evidence", 1).put("observation", row(target, "evidence", 1).get("observation") + " SHIFT Use the observed atomic ordering transaction with conflict detection");
        var bound = new LinkedHashMap<>(accepted); bound.put("TARGET_ANALYSIS", Json.bytes(target));
        var value = OperationPlanFixtures.plan(input, bound); var placement = map(value.get("placement"));
        placement.put("coordinatedChanges", OperationPlanFixtures.resolved(target, "placement", "coordinatedChanges"));
        placement.put("transactionConsistency", OperationPlanFixtures.resolved(target, "placement", "transactionConsistency"));
        value.put("risks", List.of(issue("RISK-001", "COORDINATED_ORDER_UPDATE", "/placement")));
        validate(value, input, bound); contracts++;
        for (String property : List.of("coordinatedChanges", "transactionConsistency")) {
            var changed = copy(value); map(changed.get("placement")).put(property, null);
            deny(() -> validate(changed, input, bound), "shift requires " + property);
        }
        var changed = copy(value); changed.put("risks", List.of()); deny(() -> validate(changed, input, bound), "shift risk");
    }
    private static void scenario(String fault) throws Exception {
        Fixture base = fixture(temp); MigrationInput input = input(base.target(), "INSERT", true);
        OperationPlanFixtures.prepareTarget(input);
        if (fault.equals("forged-excerpt")) Files.writeString(base.target().resolve(AnalysisFixtures.TARGET_PATH), AnalysisFixtures.TARGET_TEXT);
        Fixture fixture = new Fixture(base.trusted(), null, base.target(), input); Mock mock = new Mock();
        mock.answer = turn -> {
            String role = (String)map(turn.get("binding")).get("role");
            if (role.equals("00r-requirement-analysis")) return artifact(turn, "OPERATION_REQUIREMENT", OperationRequirement.normalize(input));
            if (role.equals("02r-operation-planning")) {
                check(data(turn).get("availableOperations").equals(List.of()), "02R has no tools");
                check(((List<?>)data(turn).get("acceptedArtifacts")).size() == 2, "only accepted predecessors supplied");
                check(data(turn).get("sourceScopeIdentity") == null, "no source identity");
                if (fault.matches("(READ|WRITE|CREATE|RUN|NETWORK|EXECUTE)_.*")) {
                    var tool = new LinkedHashMap<>(tool(turn, fault.equals("READ_SOURCE_TEXT") ? "SOURCE" : "TARGET", fault, "src/Forbidden.java"));
                    if (Set.of("WRITE_TARGET_TEXT", "CREATE_TARGET_FILE").contains(fault)) {
                        tool.put("grantId", "invented-grant"); tool.put("content", "unauthorized");
                    }
                    if (fault.equals("READ_SOURCE_TEXT")) tool.put("rootFilesystemIdentity", "POSIX:0:0");
                    return envelope(turn, "TOOL_REQUEST", null, null, tool, null, List.of());
                }
                if (fault.equals("drift")) drift(base.target());
            }
            var answer = OperationPlanFixtures.answer(turn, fixture);
            if (role.equals("02r-operation-planning") && answer.get("kind").equals("ARTIFACT")) {
                var plan = Json.parse((String)answer.get("artifactText"));
                if (fault.equals("wrong-fingerprint")) plan.put("operationRequirementFingerprint", Json.fingerprint("OPERATION_REQUIREMENT", new byte[]{1}));
                if (fault.equals("forged-mapping")) map(first(plan, "fieldMappings").get("databaseColumn")).put("value", "invented");
                if (fault.equals("missing-evidence")) { first(plan, "fieldMappings").put("databaseColumn", OperationPlanFixtures.unresolved()); block(plan, "/fieldMappings/0/databaseColumn"); }
                return artifact(turn, "OPERATION_PLAN", plan);
            }
            if (role.equals("MASTER")) {
                var d = data(turn); String action = (String)map(d.get("proposedDecision")).get("action");
                var details = map(d.get("details"));
                if (action.equals("ACCEPT_ARTIFACT") && "02r-operation-planning".equals(details.get("subjectRole"))) {
                    if (fault.equals("master-reject")) return envelope(turn, "DECISION", null, null, null, "BLOCKED", List.of("Plan rejected by MASTER"));
                    if (fault.equals("acceptance-drift")) drift(base.target());
                }
                if (action.equals("STOP_BLOCKED") && fault.equals("stop-drift")) drift(base.target());
            }
            return answer;
        };
        var result = new ControlledPipeline(base.trusted(), input, new ControlledHarness.Config("mock-model", 16384), mock).run();
        var artifacts = (List<?>)result.report().get("acceptedArtifacts");
        check(mock.roles().stream().filter(r -> !r.equals("MASTER")).distinct().toList().equals(fault.equals("forged-excerpt")
                ? List.of("00r-requirement-analysis", "01-target-analysis")
                : List.of("00r-requirement-analysis", "01-target-analysis", "02r-operation-planning")), "exact analysis/planning route " + fault);
        check(result.report().get("runAuthorityBundleFingerprint") == null && result.report().get("implementationLedger").equals(List.of()), "no implementation authority");
        check(Boolean.FALSE.equals(result.report().get("validationExecuted")), "no validation");
        var effects = map(result.report().get("finalRepositoryEffects"));
        check(Boolean.FALSE.equals(effects.get("mutationAuthorityIssued")), "no mutation grants");
        var gates = map(result.report().get("gateArtifacts"));
        check(gates.get("sourceDeclaration") == null && gates.get("sourceCheck") == null, "no source gate");
        if (fault.equals("success")) {
            check(result.status().equals("BLOCKED") && result.code().equals("NEW_OPERATION_IMPLEMENTATION_AUTHORITY_REQUIRED"), result.status() + ":" + result.code());
            check(artifacts.size() == 3, "three accepted artifacts");
            for (Object item : artifacts) {
                var a = map(item); check(a.get("fingerprint").equals(Json.fingerprint((String)a.get("role"), ((String)a.get("exactText")).getBytes(java.nio.charset.StandardCharsets.UTF_8))), "exact accepted bytes");
                check(a.get("acceptanceInvocationId") != null && a.get("acceptanceProviderResponseId") != null, "MASTER lineage");
            }
        } else {
            check(!result.status().equals("SUCCESS"), "unsafe planning cannot succeed");
            check(artifacts.size() == (fault.equals("stop-drift") ? 3 : fault.equals("forged-excerpt") ? 1 : 2), "rejected plan not accepted: " + fault + ":" + result.code());
            if (fault.contains("drift")) check(result.status().equals("FAILED") && result.code().equals("UNAUTHORIZED_REPOSITORY_EFFECTS"), result.code());
            if (fault.equals("missing-evidence")) check(result.code().equals("OPERATION_PLAN_BLOCKED"), result.code());
            if (fault.equals("master-reject")) check(result.code().equals("MASTER_REJECTED_ACCEPT_ARTIFACT"), result.code());
            if (fault.matches("(READ|WRITE|CREATE|RUN|NETWORK|EXECUTE)_.*"))
                check(result.code().equals("ROLE_OPERATION_DENIED"), "role denies execution before broker access: " + result.code());
            if (fault.equals("forged-excerpt")) check(result.code().equals("API_CREATE_OR_CORRELATION_FAILED"), result.code());
        }
        if (!fault.contains("drift")) check(Boolean.TRUE.equals(effects.get("targetUnchanged")), "target unchanged");
        runtime++; System.out.println("PASS 02R runtime " + fault);
    }
    private static void drift(Path target) {
        try { Files.writeString(target.resolve("unexpected.txt"), "external drift"); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static Map<String,Object> issue(String id, String code, String ref) {
        return Json.object("id", id, "code", code, "description", "Refresh evidence or obtain a caller resolution",
                "affectedReferences", List.of(ref), "resolutionRoute", "REFRESH_TARGET_ANALYSIS");
    }
    private static void block(Map<String,Object> p, String pointer) {
        p.put("status", "BLOCKED"); p.put("blockingIssues", List.of(issue("BLOCK-001", "MISSING_TARGET_EVIDENCE", pointer)));
        map(p.get("coverage")).put("unresolvedReferences", List.of(pointer)); map(p.get("coverage")).put("complete", false);
    }
    private static Map<String,Object> copy(Map<String,Object> value) { return Json.parse(Json.write(value)); }
    private static Map<String,Object> first(Map<String,Object> value, String key) { return row(value, key, 0); }
    private static Map<String,Object> row(Map<String,Object> value, String key, int index) { return map(((List<?>)value.get(key)).get(index)); }
}
