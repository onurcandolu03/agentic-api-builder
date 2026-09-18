package dev.agentic.harness;

import java.nio.file.*;
import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;
import static dev.agentic.harness.OperationImplementationFixtures.*;

/** Offline host authority and actual broker/provider-boundary regressions. */
@SuppressWarnings("unchecked")
public final class OperationImplementationTest {
    private static int contracts, runtime;
    private static Path temp, repository;
    public static void main(String[] args) throws Exception {
        temp = Path.of(args[0]).toRealPath(); repository = Path.of(args[1]).toRealPath();
        String filter = args.length > 2 ? args[2] : "";
        contracts();
        for (String test : List.of("GET", "INSERT", "UPDATE", "DELETE", "reuse", "cross-03", "cross-04", "cross-05", "cross-06",
                "extra-file", "traversal", "absolute", "wrong-content", "wrong-result-lineage", "predecessor-failure", "master-reject", "drift",
                "hidden-write", "symlink", "process", "test-execution", "source-read", "stop-drift",
                "capability-RUN_PROCESS", "capability-RUN_SHELL", "capability-NETWORK_REQUEST", "capability-EXECUTE_SQL"))
            if (test.contains(filter)) scenario(test);
        System.out.println("PASS " + contracts + " operation implementation contract checks and " + runtime + " runtime checks; mocked provider only");
    }
    private static void deny(Runnable r, String name) {
        try { r.run(); } catch (IllegalArgumentException | IllegalStateException expected) { contracts++; return; }
        throw new AssertionError("accepted unsafe operation authority: " + name);
    }
    private static void contracts() throws Exception {
        var input = OperationPlanningTest.input(Path.of("/target"), "INSERT", true);
        var a = accepted(input, false, false); byte[] policy = policy(input, a, false);
        var implementation = OperationImplementation.prepare(input, a, policy); contracts++;
        check(implementation.stages().stream().map(s -> s.role()).toList().equals(OperationImplementation.ROLES), "fixed stages"); contracts++;
        for (String key : List.of("callerRequestFingerprint", "operationRequirementFingerprint", "targetAnalysisFingerprint", "operationPlanFingerprint", "workflow")) {
            var p = Json.parse(MigrationInput.utf8(policy)); map(p.get("lineage")).put(key, "tampered");
            deny(() -> OperationImplementation.prepare(input, a, Json.bytes(p)), key);
        }
        deny(() -> OperationImplementation.prepare(input, a, null), "missing host authority");
        var missing = new LinkedHashMap<>(a); missing.remove("OPERATION_PLAN"); deny(() -> OperationImplementation.prepare(input, missing, policy), "missing plan");
        var source = new LinkedHashMap<>(a); source.put("SOURCE_ANALYSIS", Json.bytes(Map.of())); deny(() -> OperationImplementation.prepare(input, source, policy), "source lineage");
        for (String key : List.of("shell", "sourceAnalysisFingerprint", "validationPolicy")) {
            var p = Json.parse(MigrationInput.utf8(policy)); p.put(key, true); deny(() -> OperationImplementation.prepare(input, a, Json.bytes(p)), key);
        }
        for (String path : List.of("../Escape.java", "/tmp/Escape.java", "src/../Escape.java", "other/Escape.java")) {
            var p = Json.parse(MigrationInput.utf8(policy)); row(p, "files", 1).put("path", path);
            deny(() -> OperationImplementation.prepare(input, a, Json.bytes(p)), path);
        }
        for (String fault : List.of("missing-rule", "wrong-obligations", "extra-rule")) {
            var p = Json.parse(MigrationInput.utf8(policy));
            if (fault.equals("missing-rule")) ((List<?>)p.get("files")).removeLast();
            if (fault.equals("wrong-obligations")) row(p, "files", 0).put("obligationsFingerprint", Map.of());
            if (fault.equals("extra-rule")) ((List<Object>)p.get("files")).add(replace(row(p, "files", 0), "componentDecisionId", "CD-999"));
            deny(() -> OperationImplementation.prepare(input, a, Json.bytes(p)), fault);
        }
        for (String fault : List.of("missing-create-fact", "directory-create-fact", "unsupported-modify", "outside-create-directory", "different-create-name", "reverse-stage-order")) {
            var changed = new LinkedHashMap<>(a); var t = Json.parse(MigrationInput.utf8(a.get("TARGET_ANALYSIS")));
            var p = Json.parse(MigrationInput.utf8(a.get("OPERATION_PLAN")));
            if (fault.endsWith("create-fact")) for (Object item : (List<?>)map(t.get("architecture")).get("findings")) {
                var f = map(item); if (!OperationPlanContract.FACT_TOPIC.equals(f.get("topic"))) continue;
                var fact = Json.parse((String)f.get("statement"));
                if (fact.get("property").equals("creationPath")) {
                    if (fault.startsWith("missing")) f.put("topic", "Ordinary prose");
                    else { f.put("evidenceIds", List.of("E-003")); ((List<Object>)row(t, "evidence", 2).get("supports")).add(f.get("id")); }
                }
            }
            if (fault.equals("unsupported-modify")) row(p, "componentDecisions", 0).put("targetPath", "src/Unobserved.java");
            if (fault.equals("outside-create-directory")) row(p, "componentDecisions", 3).put("targetPath", "other/OperationRepository.java");
            if (fault.equals("different-create-name")) row(p, "componentDecisions", 3).put("targetPath", "src/Arbitrary.java");
            if (fault.equals("reverse-stage-order")) row(p, "implementationOrder", 0).put("specialistRole", "05-service-api-implementation");
            changed.put("TARGET_ANALYSIS", exact(t)); p.put("targetAnalysisFingerprint", Json.fingerprint("TARGET_ANALYSIS", changed.get("TARGET_ANALYSIS")));
            changed.put("OPERATION_PLAN", exact(p));
            byte[] rebound = policy(input, changed, false);
            deny(() -> OperationImplementation.prepare(input, changed, rebound), fault);
        }
        for (int stage = 0; stage < 4; stage++) {
            var impl = OperationImplementation.prepare(input, a, policy);
            for (int other = 0; other < 4; other++) if (other != stage) {
                int s = stage, o = other;
                deny(() -> impl.checkWrite(OperationImplementation.ROLES.get(s), PATHS.get(o), text(input, false, o)), "cross-owner");
            }
        }
        deny(() -> implementation.checkWrite(OperationImplementation.ROLES.getFirst(), PATHS.getFirst(), "append at end"), "invented placement content");
        var shift = accepted(input, false, true); var shiftImpl = OperationImplementation.prepare(input, shift, policy(input, shift, true));
        for (var stage : shiftImpl.stages()) {
            var duties = (List<?>)stage.assignment().get("obligations");
            check(!duties.isEmpty(), "all stages carry explicit obligations");
            var acceptedPlacement = Json.parse(MigrationInput.utf8(shift.get("OPERATION_PLAN"))).get("placement");
            for (Object item : duties) check(map(item).get("placement").equals(acceptedPlacement), "exact shift and transaction obligations retained");
            contracts++;
        }
        var noPlacementInput = OperationPlanningTest.input(Path.of("/target"), "DELETE", false);
        var noPlacement = accepted(noPlacementInput, false, false);
        var noPlacementImpl = OperationImplementation.prepare(noPlacementInput, noPlacement, policy(noPlacementInput, noPlacement, false));
        for (var stage : noPlacementImpl.stages()) for (Object item : (List<?>)stage.assignment().get("obligations")) {
            var placement = map(map(item).get("placement"));
            check(placement.get("callerPlacement") == null && placement.get("coordinatedChanges") == null
                    && placement.get("transactionConsistency") == null && placement.get("resolvedValues").equals(List.of()), "no invented placement");
        }
        contracts++;
        var tests = (List<?>)implementation.stages().getLast().assignment().get("obligations");
        check(!((List<?>)map(tests.getFirst()).get("testObligations")).isEmpty(), "06 bound test obligations"); contracts++;
    }
    private static void scenario(String fault) throws Exception {
        Fixture base = fixture(temp); String operation = Set.of("GET", "INSERT", "UPDATE", "DELETE").contains(fault) ? fault : "INSERT";
        var input = OperationPlanningTest.input(base.target(), operation, true); boolean reuse = fault.equals("reuse");
        Files.writeString(base.target().resolve(AnalysisFixtures.TARGET_PATH), original(input, false));
        var fixture = new Fixture(base.trusted(), null, base.target(), input);
        var a = accepted(input, reuse, false); var policy = policy(input, a, false); Mock mock = new Mock(); boolean[] injected = {false};
        mock.answer = turn -> {
            String role = (String)map(turn.get("binding")).get("role"); int stage = OperationImplementation.ROLES.stream().map(r -> r.id).toList().indexOf(role);
            if (stage >= 0) verifyAssignment(turn, input);
            var answer = OperationImplementationFixtures.answer(turn, fixture, reuse, false);
            if (role.equals("MASTER")) {
                String action = (String)map(data(turn).get("proposedDecision")).get("action");
                if (fault.equals("master-reject") && action.equals("ACCEPT_IMPLEMENTATION_STEP")) {
                    injected[0] = true; return replace(answer, "decision", "BLOCKED", "reasons", List.of("Host acceptance refused"));
                }
                if (fault.equals("stop-drift") && action.equals("STOP_BLOCKED")) { injected[0] = true; write(base.target().resolve("hidden.txt"), "drift"); }
            }
            if (stage >= 0 && !injected[0]) {
                if (fault.equals("drift") || fault.equals("symlink")) {
                    injected[0] = true;
                    if (fault.equals("symlink")) {
                        try { Files.createSymbolicLink(base.target().resolve(PATHS.get(1)), Path.of("/tmp")); } catch (Exception e) { throw new AssertionError(e); }
                    } else write(base.target().resolve("hidden.txt"), "drift");
                }
                if (answer.get("kind").equals("TOOL_REQUEST")) {
                    var tool = copy(map(answer.get("toolRequest")));
                    if (fault.equals("cross-0" + (stage + 3))) { tool.put("path", PATHS.get((stage + 1) % 4)); injected[0] = true; }
                    if (stage == 0 && List.of("extra-file", "traversal", "absolute", "wrong-content").contains(fault)) {
                        injected[0] = true;
                        if (fault.equals("wrong-content")) tool.put("content", "invented ordering implementation");
                        else tool.put("path", fault.equals("traversal") ? "../Escape.java" : fault.equals("absolute") ? "/tmp/Escape.java" : "src/Extra.java");
                    }
                    if (fault.startsWith("capability-") || fault.equals("process") || fault.equals("source-read") || fault.equals("test-execution") && stage == 3) {
                        injected[0] = true; tool.remove("grantId"); tool.put("content", null); tool.put("expectedBeforeFingerprint", null);
                        tool.put("operation", fault.startsWith("capability-") ? fault.substring("capability-".length()) : fault.equals("source-read") ? "READ_SOURCE_TEXT" : "RUN_MAVEN");
                        if (fault.equals("source-read")) { tool.put("scope", "SOURCE"); tool.put("rootFilesystemIdentity", "POSIX:0:0"); }
                    }
                    if (injected[0] && !fault.equals("drift") && !fault.equals("symlink")) return replace(answer, "toolRequest", tool);
                } else if (answer.get("kind").equals("ARTIFACT") && stage == 0) {
                    var result = Json.parse((String)answer.get("artifactText"));
                    if (fault.equals("wrong-result-lineage")) { map(result.get("lineage")).put("operationPlanFingerprint", Map.of()); injected[0] = true; }
                    if (fault.equals("predecessor-failure")) { result.put("status", "FAILED"); injected[0] = true; }
                    if (fault.equals("hidden-write")) { write(base.target().resolve("hidden.txt"), "undeclared"); injected[0] = true; }
                    return artifact(turn, (String)data(turn).get("artifactRole"), result);
                }
            }
            return answer;
        };
        var result = new ControlledPipeline(base.trusted(), input, new ControlledHarness.Config("mock-model", 16384), mock, policy).run();
        boolean success = Set.of("GET", "INSERT", "UPDATE", "DELETE", "reuse").contains(fault);
        check(!result.status().equals("SUCCESS"), "no overall success before validation");
        if (success) {
            check(result.code().equals("NEW_OPERATION_VALIDATION_AUTHORITY_REQUIRED"), fault + ":" + result.code() + " " + mock.roles());
            check(((List<?>)result.report().get("acceptedArtifacts")).size() == 7, "three inputs and four stage results accepted");
            check(mock.roles().stream().filter(r -> !r.equals("MASTER")).distinct().toList().equals(List.of("00r-requirement-analysis", "01-target-analysis", "02r-operation-planning",
                    "03-domain-contract-implementation", "04-persistence-mapping-implementation", "05-service-api-implementation", "06-test-implementation")), "exact route");
            for (int i = 0; i < 4; i++) check(Files.readString(base.target().resolve(PATHS.get(i))).equals(reuse && i == 0 ? original(input, false) : text(input, false, i)), "exact effects");
            check(result.report().get("runAuthorityBundleFingerprint") != null, "authority retained");
            check(result.status().equals("BLOCKED"), "controlled stop is BLOCKED");
            check(((List<?>)result.report().get("implementationLedger")).stream().map(RuntimeTest::map)
                    .filter(e -> "NEW_OPERATION_STAGE_ACCEPTED".equals(e.get("eventType"))).count() == 4, "four host accepted stages");
            check(Boolean.TRUE.equals(map(result.report().get("finalRepositoryEffects")).get("targetMatchesAuthorizedEffects")), "host effects equal grants");
            for (Object item : (List<?>)result.report().get("acceptedArtifacts")) {
                var artifact = map(item);
                check(artifact.get("fingerprint").equals(Json.fingerprint((String)artifact.get("role"), ((String)artifact.get("exactText")).getBytes(java.nio.charset.StandardCharsets.UTF_8))), "exact artifact bytes retained");
            }
            check(((List<?>)map(result.report().get("finalRepositoryEffects")).get("authorizedMutations")).size() == (reuse ? 3 : 4), "only assigned mutations");
        } else {
            check(injected[0], "fault reached: " + fault + ":" + result.code());
            check(!result.code().equals("NEW_OPERATION_VALIDATION_AUTHORITY_REQUIRED"), "attack blocked " + fault);
            if (!fault.equals("stop-drift")) check(((List<?>)result.report().get("acceptedArtifacts")).size() < 7, "later acceptance blocked");
            if (Set.of("predecessor-failure", "master-reject", "hidden-write", "wrong-result-lineage").contains(fault))
                check(!mock.roles().contains("04-persistence-mapping-implementation"), "rejected predecessor prevents dispatch");
            if (fault.startsWith("capability-") || Set.of("process", "test-execution", "source-read").contains(fault))
                check(result.code().equals("ROLE_OPERATION_DENIED"), "host denies capability before broker execution: " + result.code());
        }
        check(!mock.roles().contains("07-validation") && !mock.roles().contains("00-source-analysis"), "no source or validation roles");
        var gates = map(result.report().get("gateArtifacts")); check(gates.get("sourceDeclaration") == null && gates.get("sourceCheck") == null, "no source gates");
        check(Boolean.FALSE.equals(result.report().get("validationExecuted")), "no host validation");
        check(map(result.report().get("finalRepositoryEffects")).get("sourceObservation").equals("NOT_APPLICABLE"), "no source broker");
        runtime++; System.out.println("PASS operation implementation " + fault);
    }
    private static void verifyAssignment(Map<String,Object> turn, MigrationInput input) {
        var d = data(turn); var binding = map(turn.get("binding")); var bundle = map(d.get("authorityBundle"));
        check(Json.fingerprint("NEW_OPERATION_IMPLEMENTATION_AUTHORITY_V1", Json.bytes(bundle)).equals(binding.get("runAuthorityBundleFingerprint")), "complete exact bundle supplied");
        check(bundle.get("workflow").equals("NEW_OPERATION") && bundle.get("validationExecution").equals("NOT_PERMITTED"), "operation-only authority");
        check(bundle.get("executionCapabilities").equals(List.of("BOUNDED_TARGET_READ", "EXACT_TARGET_CREATE", "EXACT_TARGET_MODIFY")), "no execution capabilities");
        var target = map(map(bundle.get("targetIdentity")).get("targetScope"));
        check(target.get("resolvedRoot").equals(input.targetRoot().toString()) && target.get("rootFilesystemIdentity").equals(d.get("targetScopeIdentity")), "exact target identity");
        var lineage = map(bundle.get("lineage"));
        check(lineage.keySet().equals(Set.of("workflow", "callerRequestFingerprint", "operationRequirementFingerprint", "targetAnalysisFingerprint", "operationPlanFingerprint")), "no source/migration lineage fields");
        Map<String,Map<String,Object>> accepted = new LinkedHashMap<>();
        for (Object item : (List<?>)d.get("acceptedArtifacts")) { var a = map(item); accepted.put((String)a.get("role"), a); }
        for (var pair : Map.of("OPERATION_REQUIREMENT", "operationRequirementFingerprint", "TARGET_ANALYSIS", "targetAnalysisFingerprint", "OPERATION_PLAN", "operationPlanFingerprint").entrySet())
            check(accepted.get(pair.getKey()).get("fingerprint").equals(lineage.get(pair.getValue())), "exact accepted lineage");
        check(!accepted.containsKey("SOURCE_ANALYSIS") && !accepted.containsKey("MIGRATION_PLAN"), "no migration artifacts");
        var plan = Json.parse((String)accepted.get("OPERATION_PLAN").get("exactText")); var assignment = map(d.get("assignment"));
        check(assignment.get("stage").equals(binding.get("role")), "stage binding");
        for (Object item : (List<?>)assignment.get("obligations")) {
            var obligation = map(item);
            for (String key : List.of("requestedOperation", "targetConventions", "databaseMapping", "fieldMappings", "placement", "validationObligations"))
                check(obligation.get(key).equals(plan.get(key)), "unchanged accepted " + key);
            String component = (String)map(obligation.get("componentDecision")).get("component");
            check(OperationPlanContract.owner(component).equals(binding.get("role")), "stage ownership");
            if (component.equals("TESTS")) check(obligation.get("testObligations").equals(plan.get("testObligations")), "06 exact test obligations");
        }
        for (Object item : (List<?>)d.get("mutationGrants")) {
            var grant = map(item);
            check(grant.get("role").equals(binding.get("role")) && grant.get("invocationId").equals(binding.get("invocationId")), "exact grant recipient");
            check(((List<?>)assignment.get("allowedWrites")).stream().map(RuntimeTest::map).anyMatch(w -> w.get("pathKey").equals(grant.get("pathKey")) && w.get("action").equals(grant.get("action"))), "exact assigned path/action");
        }
    }
    private static void write(Path p, String text) { try { Files.writeString(p, text); } catch (Exception e) { throw new AssertionError(e); } }
}
