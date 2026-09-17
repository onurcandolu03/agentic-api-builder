package dev.agentic.harness;

import java.nio.file.*;
import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;
import static dev.agentic.harness.ImplementationFixtures.*;

/** Actual coordinator, transport readback, gates, brokers and artifacts; no target code or API is executed. */
public final class ImplementationRuntimeTest {
    private static Path temp, repository;
    private static int passed;
    private static String filter = "";
    public static void main(String[] args) throws Exception {
        if (args.length > 2) filter = args[2];
        temp = Path.of(args[0]).toRealPath(); repository = Path.of(args[1]).toRealPath();
        run("small fixture success route", () -> success(false));
        run("actual ten-document 00 through 06 route stops at 07", () -> success(true));
        for (String fault : List.of("ungranted-path", "reuse-03-grant", "stale-before", "delete", "rename", "source-read", "source-write",
                "planner-write", "00-target-write", "01-write", "fabricated-effect", "omitted-effect", "omitted-file", "forged-after-state",
                "target-drift", "extra-target-change", "unrelated-directory-drift", "source-drift", "stale-invocation", "wrong-role", "wrong-action", "grant-replay",
                "wrong-predecessor", "wrong-plan", "wrong-dispatch", "master-reject", "master-drift", "malformed-result", "wrong-content",
                "root-replacement", "symlink-substitution", "hardlink-substitution", "reentrant", "secret-write"))
            run(fault, () -> attack(fault));
        for (String fault : List.of("no-static-authority", "plan-rejected", "preflight-rejected", "preflight-drift", "missing-late-parent",
                "wrong-owner", "wrong-order", "occupied-create", "missing-static-rule", "changed-plan-bytes"))
            run(fault, () -> preflight(fault));
        run("plan and grant authority are immutable", ImplementationRuntimeTest::immutable);
        run("runtime item inspection preserves exact identity evidence", ImplementationRuntimeTest::itemObservation);
        for (String field : List.of("evidence", "implementationLedger"))
            run("final journal screening " + field, () -> journalScreening(field));
        for (String fault : List.of("malformed-report", "master-report", "master-journal", "master-journal-report",
                "unexpected-report", "observation"))
            run("finalization " + fault, () -> finalization(fault));
        System.out.println("PASS " + passed + " implementation runtime checks; mocked provider only, no live E2E");
    }
    @FunctionalInterface interface Check { void run() throws Exception; }
    private static void run(String name, Check task) throws Exception {
        if (!name.contains(filter)) return;
        try { task.run(); passed++; System.out.println("PASS implementation " + name); }
        catch (Throwable error) { throw new AssertionError("Implementation runtime: " + name, error); }
    }
    private static ControlledPipeline.Result execute(Fixture fixture, Mock mock, byte[] authority, boolean actual) {
        return new ControlledPipeline(actual ? repository : fixture.trusted(), fixture.input(),
                new ControlledHarness.Config("mock-model", 16384), mock, authority).run();
    }
    private static void success(boolean actual) throws Exception {
        Fixture fixture = fixture(temp); Mock mock = new Mock(); mock.answer = turn -> answer(turn, fixture);
        var result = execute(fixture, mock, resolution(), actual);
        check(result.status().equals("BLOCKED") && result.code().equals("VALIDATION_EXECUTION_RUNTIME_REQUIRED"),
                "precise 07 boundary: " + result.status() + ":" + result.code() + " roles=" + mock.roles());
        var accepted = (List<?>)result.report().get("acceptedArtifacts"); check(accepted.size() == 7, "all seven artifacts accepted");
        for (int i = 0; i < 4; i++) check(Files.readString(fixture.target().resolve(PATHS.get(i))).equals(TEXTS.get(i)), "exact target bytes " + i);
        check(Files.readString(fixture.source().resolve(AnalysisFixtures.SOURCE_PATH)).equals(AnalysisFixtures.SOURCE_TEXT), "source bytes unchanged");
        var effects = map(result.report().get("finalRepositoryEffects"));
        check(Boolean.TRUE.equals(effects.get("sourceUnchanged")) && Boolean.FALSE.equals(effects.get("targetUnchanged")), "phase-aware changes");
        check("EXPECTED_EFFECT".equals(effects.get("effectClassification")) && Boolean.TRUE.equals(effects.get("targetMatchesAuthorizedEffects")), "authorized effects exactly match");
        check(((List<?>)effects.get("authorizedMutations")).size() == 4, "four actual broker writes");
        var ledger = ((List<?>)result.report().get("implementationLedger")).stream().map(RuntimeTest::map).toList();
        check(ledger.stream().filter(e -> "STEP_ACCEPTED".equals(e.get("eventType"))).count() == 4, "canonical accepted step ledger");
        check(ledger.stream().noneMatch(e -> e.get("eventType").toString().startsWith("VALIDATION")), "no 07 execution records");
        check(mock.roles().stream().noneMatch(r -> r.startsWith("07")), "07 never dispatched");
        List<String> distinct = new ArrayList<>();
        for (String role : mock.roles()) if (distinct.isEmpty() || !distinct.getLast().equals(role)) distinct.add(role);
        List<String> expected = new ArrayList<>(List.of("MASTER", TrustedInputs.Role.SOURCE_ANALYSIS.id, "MASTER", TrustedInputs.Role.ANALYSIS.id, "MASTER", TrustedInputs.Role.PLANNING.id, "MASTER"));
        for (var role : ROLES) { expected.add(role.id); expected.add("MASTER"); }
        check(distinct.equals(expected), "strict sequential MASTER acceptances");
        for (Object value : accepted) {
            var a = map(value); String role = (String)a.get("role"), exact = (String)a.get("exactText");
            check(exact.startsWith(" \n") && exact.endsWith("\n"), "exact artifact whitespace retained");
            check(Json.fingerprint(role, exact.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(a.get("fingerprint")), "exact artifact digest");
            if (role.startsWith("IMPLEMENTATION_RESULT:")) {
                var parsed = Json.parse(exact); var effect = map(((List<?>)parsed.get("grantEffects")).getFirst());
                check(map(a.get("invocationBinding")).get("invocationId").equals(effect.get("invocationId")), "effect invocation binding");
                check(map(a.get("invocationBinding")).get("predecessorInvocationId").equals(map(parsed.get("predecessorAcceptance")).get("acceptanceInvocationId"))
                        || role.endsWith("STEP-001"), "accepted predecessor lineage");
            }
        }
        check(Boolean.FALSE.equals(result.report().get("realProviderE2EProven")), "no live E2E claim");
    }
    private static void attack(String fault) throws Exception {
        Fixture fixture = fixture(temp); Mock mock = new Mock();
        if (fault.equals("unrelated-directory-drift")) Files.createDirectory(fixture.target().resolve("unrelated"));
        java.util.concurrent.atomic.AtomicReference<Map<String,Object>> firstGrant = new java.util.concurrent.atomic.AtomicReference<>();
        int index = switch (fault) {
            case "reuse-03-grant" -> 1;
            case "stale-before", "target-drift", "root-replacement", "symlink-substitution", "hardlink-substitution" -> 2;
            case "delete", "rename" -> 3;
            default -> 0;
        };
        String attackedRole = fault.equals("planner-write") ? TrustedInputs.Role.PLANNING.id
                : fault.equals("00-target-write") ? TrustedInputs.Role.SOURCE_ANALYSIS.id
                : fault.equals("01-write") ? TrustedInputs.Role.ANALYSIS.id : ROLES.get(index).id;
        boolean[] injected = {false}; ControlledPipeline[] pipeline = new ControlledPipeline[1];
        mock.answer = turn -> {
            String role = (String)map(turn.get("binding")).get("role"); var data = data(turn);
            if (role.equals(ROLES.getFirst().id)) firstGrant.set(map(((List<?>)data.get("mutationGrants")).getFirst()));
            if (role.equals("MASTER") && Set.of("master-reject", "master-drift").contains(fault)
                    && "ACCEPT_IMPLEMENTATION_STEP".equals(map(data.get("proposedDecision")).get("action"))) {
                injected[0] = true;
                if (fault.equals("master-drift")) writeExternal(fixture.target().resolve("extra.txt"), "external");
                else return envelope(turn, "DECISION", null, null, null, "BLOCKED", List.of("Step rejected by MASTER."));
            }
            if (!role.equals(attackedRole) || injected[0]) return answer(turn, fixture);
            if (fault.equals("planner-write") || fault.equals("00-target-write") || fault.equals("01-write")) {
                injected[0] = true;
                return envelope(turn, "TOOL_REQUEST", null, null, replace(tool(turn, "TARGET", "CREATE_TARGET_FILE", "src/Unowned.java"),
                        "grantId", "invented-grant", "content", "unowned"), null, List.of());
            }
            boolean after = !((List<?>)data.get("toolResults")).isEmpty();
            if (Set.of("omitted-effect", "omitted-file", "forged-after-state", "wrong-predecessor", "wrong-plan", "wrong-dispatch", "malformed-result", "grant-replay", "unrelated-directory-drift").contains(fault) && !after)
                return answer(turn, fixture);
            if (Set.of("master-reject", "master-drift").contains(fault)) return answer(turn, fixture);
            injected[0] = true;
            if (fault.equals("unrelated-directory-drift")) {
                try { Files.createDirectory(fixture.target().resolve("unrelated/external")); }
                catch (java.io.IOException failure) { throw new AssertionError(failure); }
                return answer(turn, fixture);
            }
            if (fault.equals("reentrant")) { try { pipeline[0].run(); } catch (IllegalStateException expected) {} return answer(turn, fixture); }
            if (Set.of("fabricated-effect", "omitted-effect", "omitted-file", "forged-after-state", "wrong-predecessor", "wrong-plan", "wrong-dispatch", "malformed-result").contains(fault)) {
                var output = Json.parse(Json.write(result(turn)));
                switch (fault) {
                    case "omitted-effect" -> output.put("grantEffects", List.of());
                    case "omitted-file" -> map(output.get("handoff")).put("createdFiles", List.of());
                    case "forged-after-state" -> map(map(((List<?>)output.get("grantEffects")).getFirst()).get("afterState")).put("contentFingerprint", null);
                    case "wrong-predecessor" -> map(output.get("predecessorAcceptance")).put("acceptanceInvocationId", "old-acceptance");
                    case "wrong-plan" -> output.put("migrationPlanFingerprint", Json.fingerprint("MIGRATION_PLAN", new byte[]{1}));
                    case "wrong-dispatch" -> map(map(output.get("handoff")).get("orchestrationBinding")).put("dispatchFingerprint", Json.fingerprint("SPECIALIST_DISPATCH", new byte[]{1}));
                    case "malformed-result" -> output.put("modelGrants", List.of("arbitrary-authority"));
                    default -> {}
                }
                return artifact(turn, (String)data.get("artifactRole"), output);
            }
            var response = write(turn, index); var request = new LinkedHashMap<>(map(response.get("toolRequest")));
            switch (fault) {
                case "ungranted-path" -> request.put("path", "src/Unowned.java");
                case "reuse-03-grant" -> request.put("grantId", firstGrant.get().get("grantId"));
                case "stale-before" -> request.put("expectedBeforeFingerprint", Json.fingerprint("PATH_CONTENT:" + PATHS.get(index), new byte[]{1}));
                case "delete", "rename" -> { request.put("operation", fault.toUpperCase(Locale.ROOT)); request.remove("grantId"); }
                case "source-read" -> { request.put("operation", "READ_TARGET_TEXT"); request.put("content", null); request.remove("grantId"); request.put("scope", "SOURCE"); request.put("rootFilesystemIdentity", data.get("sourceScopeIdentity")); request.put("path", AnalysisFixtures.SOURCE_PATH); }
                case "source-write" -> { request.put("scope", "SOURCE"); request.put("rootFilesystemIdentity", data.get("sourceScopeIdentity")); request.put("path", AnalysisFixtures.SOURCE_PATH); }
                case "target-drift" -> writeExternal(fixture.target().resolve(PATHS.get(index)), "external change");
                case "extra-target-change" -> writeExternal(fixture.target().resolve("extra.txt"), "external");
                case "source-drift" -> writeExternal(fixture.source().resolve(AnalysisFixtures.SOURCE_PATH), "external source change");
                case "stale-invocation" -> request.put("invocationId", "stale-invocation");
                case "wrong-role" -> request.put("role", TrustedInputs.Role.PLANNING.id);
                case "wrong-action" -> request.put("operation", "WRITE_TARGET_TEXT");
                case "wrong-content" -> request.put("content", "wrong implementation");
                case "secret-write" -> request.put("content", "password=fixture-secret");
                case "root-replacement", "symlink-substitution", "hardlink-substitution" -> {
                    try {
                        if (fault.equals("root-replacement")) { Files.move(fixture.target(), fixture.target().resolveSibling("old-target")); Files.createDirectory(fixture.target()); }
                        else {
                            Files.delete(fixture.target().resolve(PATHS.get(index)));
                            if (fault.equals("symlink-substitution")) Files.createSymbolicLink(fixture.target().resolve(PATHS.get(index)), fixture.source().resolve(AnalysisFixtures.SOURCE_PATH));
                            else Files.createLink(fixture.target().resolve(PATHS.get(index)), fixture.source().resolve(AnalysisFixtures.SOURCE_PATH));
                        }
                    } catch (Exception e) { throw new AssertionError(e); }
                }
                default -> {}
            }
            return replace(response, "toolRequest", request);
        };
        pipeline[0] = new ControlledPipeline(fixture.trusted(), fixture.input(), new ControlledHarness.Config("mock-model", 16384), mock, resolution());
        var result = pipeline[0].run();
        check(injected[0], "attack reached: " + fault + ":" + result.code());
        check(!result.code().equals("VALIDATION_EXECUTION_RUNTIME_REQUIRED") && !result.status().equals("SUCCESS"), "attack stops execution");
        if (fault.equals("planner-write") || fault.equals("00-target-write") || fault.equals("01-write"))
            check(!mock.roles().contains(ROLES.getFirst().id), "pre-plan mutation never grants implementation");
        else if (index < 3) check(!mock.roles().contains(ROLES.get(index + 1).id), "next role withheld: " + fault);
        var accepted = (List<?>)result.report().getOrDefault("acceptedArtifacts", List.of());
        check(accepted.size() <= 3 + index, "failed step has no accepted artifact");
        if (!fault.equals("source-drift")) check(Files.readString(fixture.source().resolve(AnalysisFixtures.SOURCE_PATH)).equals(AnalysisFixtures.SOURCE_TEXT), "source content stays read-only");
        if (Set.of("master-reject", "omitted-effect", "omitted-file", "malformed-result", "wrong-predecessor", "unrelated-directory-drift").contains(fault)) {
            check(Files.readString(fixture.target().resolve(PATHS.getFirst())).equals(TEXTS.getFirst()), "unaccepted write remains present");
            check(!((List<?>)result.report().get("nonacceptedOutputs")).isEmpty(), "nonaccepted exact output retained");
            check(Json.write(result.report().get("implementationLedger")).contains("UNACCEPTED"), "truthful unaccepted mutation ledger");
        }
        if (Set.of("target-drift", "extra-target-change", "source-drift", "master-drift", "unrelated-directory-drift").contains(fault))
            check("UNEXPECTED_EFFECT".equals(map(result.report().get("finalRepositoryEffects")).get("effectClassification")), "external drift classified");
        if (fault.equals("unrelated-directory-drift")) {
            check(result.status().equals("FAILED") && result.code().equals("UNAUTHORIZED_REPOSITORY_EFFECTS"), "unrelated directory change fails closed");
            check(Files.isDirectory(fixture.target().resolve("unrelated/external")), "actual external directory remains truthfully present");
        }
        if (fault.equals("master-reject")) {
            check(result.code().equals("MASTER_REJECTED_ACCEPT_IMPLEMENTATION_STEP"), "rejection remains primary reason");
            check("EXPECTED_EFFECT".equals(map(result.report().get("finalRepositoryEffects")).get("effectClassification")), "authorized but unaccepted is not rollback");
        }
        if (fault.equals("malformed-result") || fault.equals("fabricated-effect") || fault.equals("omitted-effect"))
            check(mock.requests.stream().map(ImplementationRuntimeTest::turn).map(RuntimeTest::data)
                    .noneMatch(d -> d.get("proposedDecision") instanceof Map<?,?> decision && "ACCEPT_IMPLEMENTATION_STEP".equals(decision.get("action"))), "no MASTER acceptance for invalid result");
        check(!Json.write(result.report()).contains("fixture-secret"), "secret not retained");
    }
    private static Map<String,Object> turn(Map<String,Object> request) {
        var message = map(((List<?>)request.get("input")).getFirst());
        return Json.parse((String)map(((List<?>)message.get("content")).getFirst()).get("text"));
    }
    private static void writeExternal(Path path, String content) { try { Files.writeString(path, content); } catch (Exception e) { throw new AssertionError(e); } }
    private static void preflight(String fault) throws Exception {
        Fixture fixture = fixture(temp); Mock mock = new Mock(); boolean[] injected = {fault.equals("no-static-authority") || fault.equals("missing-static-rule")};
        byte[] authority = fault.equals("no-static-authority") ? null : resolution();
        if (fault.equals("missing-static-rule")) authority = Json.bytes(ExecutionPlan.callerResolution(rules().subList(0, 3), List.of()));
        if (fault.equals("missing-late-parent")) {
            var rules = new ArrayList<>(rules()); rules.set(3, new ExecutionPlan.StaticRule(pointer(3), "missing/Test.java", TEXTS.get(3), DUTIES.get(3)));
            authority = Json.bytes(ExecutionPlan.callerResolution(rules, List.of()));
        }
        mock.answer = turn -> {
            var data = data(turn); String role = (String)map(turn.get("binding")).get("role");
            var response = answer(turn, fixture);
            if (role.equals("MASTER")) {
                String action = (String)map(data.get("proposedDecision")).get("action");
                boolean planAcceptance = action.equals("ACCEPT_ARTIFACT") && TrustedInputs.Role.PLANNING.id.equals(map(data.get("details")).get("subjectRole"));
                if (fault.equals("plan-rejected") && planAcceptance || fault.equals("preflight-rejected") && action.equals("ACCEPT_IMPLEMENTATION_PREFLIGHT")) {
                    injected[0] = true; return replace(response, "decision", "BLOCKED", "reasons", List.of("Rejected authority."));
                }
                if (action.equals("ACCEPT_IMPLEMENTATION_PREFLIGHT") && fault.equals("preflight-drift")) { injected[0] = true; writeExternal(fixture.target().resolve("extra.txt"), "drift"); }
                if (planAcceptance && fault.equals("occupied-create")) { injected[0] = true; writeExternal(fixture.target().resolve(PATHS.getFirst()), "occupied"); }
            }
            if (role.equals(TrustedInputs.Role.PLANNING.id) && Set.of("missing-late-parent", "wrong-owner", "wrong-order", "changed-plan-bytes").contains(fault)) {
                injected[0] = true; var plan = Json.parse((String)response.get("artifactText"));
                if (fault.equals("changed-plan-bytes")) return replace(response, "artifactText", response.get("artifactText") + " ");
                if (fault.equals("wrong-owner")) map(((List<?>)plan.get("implementationOrder")).getFirst()).put("specialistRole", TrustedInputs.Role.PERSISTENCE.id);
                if (fault.equals("wrong-order")) {
                    // Keep ownership valid but reorder role stages; dependencies are removed so this is structurally valid planning.
                    var steps = (List<?>)plan.get("implementationOrder"); var decisions = (List<?>)plan.get("componentDecisions");
                    for (Object d : decisions) map(d).put("dependencies", List.of());
                    for (Object st : steps) map(st).put("prerequisiteStepIds", List.of());
                    var reordered = List.of(steps.get(1), steps.get(0), steps.get(2), steps.get(3));
                    for (int i = 0; i < 4; i++) map(reordered.get(i)).put("sequence", i + 1);
                    plan.put("implementationOrder", reordered);
                }
                if (fault.equals("missing-late-parent")) {
                    var d = map(((List<?>)plan.get("componentDecisions")).get(3)); d.put("expectedLocation", "missing/Test.java");
                    map(d.get("expectedChangeScope")).put("expectedPaths", List.of("missing/Test.java"));
                    map(((List<?>)plan.get("implementationOrder")).get(3)).put("expectedPaths", List.of("missing/Test.java"));
                    map(((List<?>)map(plan.get("validationPlan")).get("plannedTestChanges")).getFirst()).put("expectedPath", "missing/Test.java");
                    var paths = new ArrayList<>(PATHS); paths.set(3, "missing/Test.java"); map(plan.get("coverage")).put("expectedTouchedFiles", paths.stream().sorted().toList());
                }
                return artifact(turn, "MIGRATION_PLAN", plan);
            }
            return response;
        };
        var result = execute(fixture, mock, authority, false);
        check(injected[0], "preflight attack reached " + fault);
        check(!mock.roles().contains(ROLES.getFirst().id), "no 03 invocation " + result.code());
        var effects = map(result.report().get("finalRepositoryEffects"));
        check(Boolean.FALSE.equals(effects.get("mutationAuthorityIssued")), "no write authority before complete preflight");
        check(!result.code().equals("VALIDATION_EXECUTION_RUNTIME_REQUIRED"), "preflight blocks earlier");
    }
    private static void itemObservation() throws Exception {
        Fixture fixture = fixture(temp); Mock mock = new Mock();
        mock.answer = turn -> envelope(turn, "DECISION", null, null, null, data(turn).get("proposedDecision"), List.of());
        var harness = new ControlledHarness(fixture.trusted(), fixture.source(), fixture.target(),
                new ControlledHarness.Config("mock-model", 8192), mock, true);
        try {
            harness.freezeTrustedInputs(); harness.registerTargetMetadata();
            var roles = new RoleExecutor(harness, new Evidence(mock::rejectCredentialMaterial));
            var invocation = roles.begin(TrustedInputs.Role.MASTER, null, List.of(), null);
            roles.turn(invocation, Json.object("proposedDecision", Json.object("action", "REGISTER_INPUTS")), null, ignored -> {});
            roles.finish(invocation);
            var items = (List<?>)harness.inspect().get("providerItemBindings");
            check(items.size() == 2, "input and output identities present");
            for (Object entry : items) {
                var item = map(entry);
                check(item.keySet().equals(Set.of("itemId", "kind", "origin", "itemFingerprint")), "bounded execution inspection");
                Map<String,Object> exact;
                // Independently reconstruct the provider-neutral item view from mock wire facts.
                // Inspection hashes the full view, including its complete native comparison evidence.
                if (item.get("kind").equals("OUTPUT")) {
                    var nativeItem = map(((List<?>)Json.parse(mock.responses.get("resp_1")).get("output")).getFirst());
                    exact = Json.object("id", nativeItem.get("id"), "kind", "OUTPUT", "text", null,
                            "nativeEvidence", nativeItem);
                    check(item.get("origin").equals("resp_1"), "output origin retained");
                } else {
                    check(item.get("kind").equals("USER_INPUT"), "expected user input kind");
                    var message = map(((List<?>)mock.requests.getFirst().get("input")).getFirst());
                    exact = Json.object("id", "input_1", "kind", "USER_INPUT", "text", null,
                            "nativeEvidence", Json.object("type", message.get("type"), "role", message.get("role"),
                                    "content", message.get("content")));
                }
                check(exact.get("id").equals(item.get("itemId")), "exact provider item identity");
                check(Json.evidenceFingerprint(exact).equals(item.get("itemFingerprint")), "exact provider item fingerprint");
            }
        } finally { harness.close(); }
    }
    private static void journalScreening(String field) throws Exception {
        Fixture fixture = fixture(temp); Mock mock = new Mock(); boolean[] inspected = {false};
        mock.answer = turn -> answer(turn, fixture);
        mock.credentialFault = text -> {
            if (text.startsWith("{\"" + field + "\":[")) {
                inspected[0] = true; throw new IllegalStateException("FINAL_JOURNAL_REJECTION");
            }
        };
        var result = execute(fixture, mock, resolution(), false);
        check(inspected[0], "every final journal uses configured credential screening");
        check(result.status().equals("BLOCKED") && result.code().equals("VALIDATION_EXECUTION_RUNTIME_REQUIRED"), "export failure preserves primary boundary");
        check(((List<?>)result.report().get("acceptedArtifacts")).size() == 7, "accepted result references survive export failure");
        check(((List<?>)result.report().get("nonacceptedOutputs")).isEmpty(), "accepted effects never become nonaccepted");
        preservedEffects(result, fixture, mock, 4);
        diagnostic(result, "FINAL_REPORT", "FINAL_EVIDENCE_UNAVAILABLE");
        check(!Json.write(result.report()).contains("FINAL_JOURNAL_REJECTION"), "exception material omitted");
    }
    private static void finalization(String fault) throws Exception {
        Fixture fixture = fixture(temp); Mock mock = new Mock();
        ControlledPipeline[] pipeline = new ControlledPipeline[1];
        boolean[] injected = {false}, reportRejected = {false}, journalRejected = {false};
        mock.answer = turn -> {
            var response = answer(turn, fixture); var data = data(turn);
            String role = (String)map(turn.get("binding")).get("role");
            if (fault.startsWith("master-") && role.equals("MASTER")
                    && "ACCEPT_IMPLEMENTATION_STEP".equals(map(data.get("proposedDecision")).get("action"))) {
                injected[0] = true;
                return replace(response, "decision", "BLOCKED", "reasons", List.of("PROVIDER_FINALIZATION_MARKER"));
            }
            if (fault.equals("malformed-report") && role.equals(ROLES.get(1).id) && response.get("kind").equals("ARTIFACT")) {
                injected[0] = true;
                var output = Json.parse((String)response.get("artifactText"));
                output.put("unrecognized", "PROVIDER_FINALIZATION_MARKER");
                return artifact(turn, (String)data.get("artifactRole"), output);
            }
            if (fault.equals("unexpected-report") && role.equals(ROLES.getFirst().id) && response.get("kind").equals("ARTIFACT")) {
                injected[0] = true; writeExternal(fixture.target().resolve("extra.txt"), "external");
            }
            return response;
        };
        mock.credentialFault = text -> {
            if (fault.contains("report") && text.startsWith("{\"status\":") && text.contains("\"terminalSummary\"")) {
                reportRejected[0] = true; throw new IllegalStateException("password=finalization-exception-secret");
            }
            if (fault.contains("journal") && text.equals("TERMINAL")) {
                journalRejected[0] = true; throw new IllegalStateException("password=finalization-exception-secret");
            }
            if (fault.equals("observation") && text.equals(AnalysisFixtures.SOURCE_PATH)) {
                try {
                    var code = ControlledPipeline.class.getDeclaredField("code"); code.setAccessible(true);
                    if ("VALIDATION_EXECUTION_RUNTIME_REQUIRED".equals(code.get(pipeline[0]))) {
                        injected[0] = true; throw new IllegalStateException("password=finalization-exception-secret");
                    }
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
        };
        pipeline[0] = new ControlledPipeline(fixture.trusted(), fixture.input(), new ControlledHarness.Config("mock-model", 16384), mock, resolution());
        var result = pipeline[0].run();
        check(injected[0], "primary failure injected: " + fault);
        String expectedCode = fault.startsWith("master-") ? "MASTER_REJECTED_ACCEPT_IMPLEMENTATION_STEP"
                : fault.equals("malformed-report") ? "API_CREATE_OR_CORRELATION_FAILED"
                : fault.equals("observation") ? "FINAL_REPOSITORY_OBSERVATION_UNAVAILABLE" : "UNAUTHORIZED_REPOSITORY_EFFECTS";
        check(result.code().equals(expectedCode), "primary code survives finalization: " + result.code());
        check(result.status().equals(fault.equals("malformed-report") || fault.equals("unexpected-report") ? "FAILED" : "BLOCKED"), "primary status survives");
        int count = fault.equals("malformed-report") ? 2 : fault.equals("observation") ? 4 : 1;
        if (fault.equals("observation")) {
            var effects = map(result.report().get("finalRepositoryEffects"));
            check(effects.get("observation").equals("UNKNOWN") && effects.get("sourceUnchanged") == null, "actual failed source observation is unknown");
            check(effects.get("sourceObservation").equals("UNKNOWN") && effects.get("targetObservation").equals("COMPLETE_WITHIN_MODELED_SCOPE"), "target observation independently retained");
            check(Boolean.TRUE.equals(effects.get("targetMatchesAuthorizedEffects")), "known target effects preserved despite failed source snapshot");
            check(((List<?>)effects.get("authorizedMutations")).size() == 4, "all writes retained even with unknown source");
        } else {
            if (!fault.equals("unexpected-report")) preservedEffects(result, fixture, mock, count);
            else {
                var effects = map(result.report().get("finalRepositoryEffects"));
                check(effects.get("effectClassification").equals("UNEXPECTED_EFFECT") && Boolean.FALSE.equals(effects.get("targetMatchesAuthorizedEffects")), "unexpected target effect survives failed report");
                check(((List<?>)effects.get("authorizedMutations")).size() == 1, "known authorized write also retained");
            }
            check(!mock.roles().contains(ROLES.get(count).id), "next role withheld");
            check(((List<?>)result.report().get("acceptedArtifacts")).size() == count + 2, "only predecessors accepted");
            check(!((List<?>)result.report().get("nonacceptedOutputs")).isEmpty(), "applied result stays nonaccepted");
        }
        if (fault.contains("report")) {
            check(reportRejected[0], "full report screening attempted");
            diagnostic(result, "FINAL_REPORT", "FINAL_EVIDENCE_UNAVAILABLE");
            String bounded = Json.write(result.report());
            check(!bounded.contains("PROVIDER_FINALIZATION_MARKER") && !bounded.contains("exactText") && !bounded.contains("exactAfterText"), "fallback excludes provider payload and full mutation text");
        }
        if (fault.contains("journal")) {
            check(journalRejected[0], "terminal journal retention attempted");
            diagnostic(result, "TERMINAL_JOURNAL", "TERMINAL_OBSERVATION_RETENTION_UNAVAILABLE");
            check(!Json.write(result.report()).contains("FINAL_REPOSITORY_OBSERVATION_UNAVAILABLE"), "retention failure is not observation failure");
            if (!fault.contains("report")) {
                var effects = map(result.report().get("finalRepositoryEffects"));
                check(effects.get("actualSource") instanceof Map && effects.get("actualTarget") instanceof Map, "completed snapshots retained");
                check(Json.write(result.report().get("implementationLedger")).contains("UNACCEPTED"), "termination uses observed manifest");
            }
        }
        check(!Json.write(result.report()).contains("finalization-exception-secret"), "no exception payload retained");
    }
    private static void diagnostic(ControlledPipeline.Result result, String stage, String code) {
        check(((List<?>)result.report().get("orchestrationFailures")).stream().map(RuntimeTest::map)
                .anyMatch(d -> d.equals(Json.object("stage", stage, "code", code))), "fixed secondary diagnostic " + stage);
    }
    private static void preservedEffects(ControlledPipeline.Result result, Fixture fixture, Mock mock, int count) throws Exception {
        var effects = map(result.report().get("finalRepositoryEffects"));
        check(effects.get("observation").equals("COMPLETE_WITHIN_MODELED_SCOPE") && effects.get("effectClassification").equals("EXPECTED_EFFECT"), "known classification survives retention/export failure");
        check(Boolean.TRUE.equals(effects.get("sourceUnchanged")) && Boolean.TRUE.equals(effects.get("targetMatchesAuthorizedEffects")), "known repository facts preserved");
        check(((List<?>)effects.get("authorizedMutations")).size() == count, "every applied write visible");
        for (Object entry : (List<?>)effects.get("authorizedMutations")) {
            var mutation = map(entry);
            if (!mutation.containsKey("afterStateFingerprint")) continue; // Full report carries exact states instead.
            var observed = mock.requests.stream().map(ImplementationRuntimeTest::turn).map(RuntimeTest::data)
                    .filter(d -> d.get("observedGrantEffects") instanceof List<?>).flatMap(d -> ((List<?>)d.get("observedGrantEffects")).stream())
                    .map(RuntimeTest::map).filter(e -> mutation.get("grantId").equals(e.get("grantId"))).findFirst().orElseThrow();
            check(mutation.get("beforeStateFingerprint").equals(Json.evidenceFingerprint(observed.get("beforeState")))
                    && mutation.get("afterStateFingerprint").equals(Json.evidenceFingerprint(observed.get("afterState"))), "exact observed mutation fingerprints survive fallback");
            var reference = map(mutation.get("resultReference"));
            boolean accepted = ((List<?>)result.report().get("acceptedArtifacts")).stream().map(RuntimeTest::map)
                    .anyMatch(a -> mutation.get("invocationId").equals(a.get("invocationId")));
            check(reference.get("acceptanceStatus").equals(accepted ? "ACCEPTED" : "NOT_ACCEPTED"), "applied effect retains exact acceptance distinction");
        }
        for (int i = 0; i < count; i++) check(Files.readString(fixture.target().resolve(PATHS.get(i))).equals(TEXTS.get(i)), "write still present");
        check(Files.readString(fixture.source().resolve(AnalysisFixtures.SOURCE_PATH)).equals(AnalysisFixtures.SOURCE_TEXT), "source unchanged");
        check(result.report().get("runId").equals(map(turn(mock.requests.getFirst()).get("binding")).get("runId")), "host run identity retained");
        check(Boolean.FALSE.equals(result.report().get("validationExecuted")) && mock.roles().stream().noneMatch(r -> r.startsWith("07")), "07 never executed");
    }
    private static void immutable() throws Exception {
        var before = new LinkedHashMap<>(RepositoryFiles.absent("New.java")); var nested = new LinkedHashMap<>(Json.object("runId", "run"));
        var grant = new TargetContentBroker.WriteGrant("grant", "invocation", ROLES.getFirst().id, "POSIX:1:2", "New.java", "CREATE", before, nested);
        before.put("existence", "PRESENT"); nested.put("runId", "changed");
        check("ABSENT".equals(grant.expectedBeforeState().get("existence")) && "run".equals(grant.authority().get("runId")), "defensive copy");
        try { grant.authority().put("runId", "expanded"); throw new AssertionError("mutable authority"); } catch (UnsupportedOperationException expected) {}
        Fixture fixture = fixture(temp);
        var inputPlan = plan(fixture.input(), Map.of("SOURCE_ANALYSIS", Json.bytes(AnalysisFixtures.source(fixture.input())),
                "TARGET_ANALYSIS", Json.bytes(AnalysisFixtures.target(fixture.input()))));
        var parsed = ExecutionPlan.parse(inputPlan, rules());
        String exact = Json.write(parsed.plan());
        map(((List<?>)inputPlan.get("componentDecisions")).getFirst()).put("expectedLocation", "Expanded.java");
        check(Json.write(parsed.plan()).equals(exact) && parsed.steps().getFirst().path().equals(PATHS.getFirst()), "parsed plan defensively copies authority");
        try { parsed.steps().getFirst().decision().put("expectedLocation", "Expanded.java"); throw new AssertionError("mutable parsed decision"); } catch (UnsupportedOperationException expected) {}
        try { parsed.plan().put("implementationOrder", List.of()); throw new AssertionError("mutable parsed plan"); } catch (UnsupportedOperationException expected) {}
    }
}
