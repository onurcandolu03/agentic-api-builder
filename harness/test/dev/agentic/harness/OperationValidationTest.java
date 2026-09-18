package dev.agentic.harness;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;
import static dev.agentic.harness.OperationImplementationFixtures.*;

/** Synthetic Java targets, fake host Maven bootstrap, real isolated child, mocked providers only. */
public final class OperationValidationTest {
    private static int passed;
    private static Path temp;
    private static final Map<String,List<String>> TESTS = Map.of("TEST-001", List.of("NewOperationTest#followsRepositoryResult"));
    public static void main(String[] args) throws Exception {
        temp = Path.of(args[0]).toRealPath(); String filter = args.length > 2 ? args[2] : "";
        if (filter.isEmpty() || filter.equals("contracts")) { contracts(); if (filter.equals("contracts")) return; }
        for (String mode : List.of("GET", "INSERT", "UPDATE", "DELETE", "omitted-placement", "shift", "master-reject", "final-drift",
                "write", "source-read", "source-write", "shell", "process", "network", "sql", "maven",
                "wrong-plan", "wrong-implementation", "forged-maven", "false-failure", "false-success", "missing-stage",
                "missing-report", "pre-drift", "missing-authority", "wrong-policy", "nonzero", "timeout", "mutate",
                "skipped", "report-fail", "xxe", "report-drift")) {
            if (!filter.isEmpty() && !Set.of(filter.split(",")).contains(mode)) continue;
            scenario(mode); passed++; System.out.println("PASS operation validation " + mode);
        }
        check(passed > 0, "selected case executed");
        System.out.println("PASS " + passed + " operation validation checks; mocked provider, synthetic Maven bootstrap, offline");
    }
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void denied(Attempt attempt, String code) throws Exception {
        try { attempt.run(); } catch (IllegalArgumentException | IllegalStateException rejected) {
            check(code.equals(rejected.getMessage()), "expected " + code + " got " + rejected.getMessage()); return;
        }
        throw new AssertionError("accepted " + code);
    }
    private static void contracts() throws Exception {
        var base = fixture(temp); var input = OperationPlanningTest.input(base.target(), "GET", false);
        Files.writeString(base.target().resolve(AnalysisFixtures.TARGET_PATH), original(input, false));
        var bytes = accepted(input, false, false); var store = new ArtifactStore(new Evidence(t -> {}));
        for (var entry : bytes.entrySet()) store.accept(entry.getKey(), MigrationInput.utf8(entry.getValue()),
                Json.object("runId", "run", "role", "fixture", "invocationId", entry.getKey()), "response", "accepted", "master");
        var impl = OperationImplementation.prepare(input, bytes, policy(input, bytes, false));
        var attrs = Files.readAttributes(base.target(), "unix:dev,ino");
        var observer = new RepositoryFiles(base.target(), "POSIX:" + attrs.get("dev") + ":" + attrs.get("ino"),
                RepositoryFiles.Limits.defaults(), Set.of(), Set.of(), t -> {});
        var state = observer.snapshot(Set.of());
        Path home = MavenValidationTest.installation(temp, "operation-success", base.target());
        Path cache = Files.createTempDirectory(temp, "operation-contract-cache-");
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("r-x------"));
        try {
            var fp = Json.fingerprint("OPERATION_PLAN", bytes.get("OPERATION_PLAN"));
            var profile = ValidationProfile.mavenOperationTest(home, cache, fp, TESTS);
            var spec = Json.fingerprint("AGENT_07_SPECIFICATION", new byte[]{1});
            denied(() -> OperationValidation.bind(input, store, impl, state, null, "run", spec, List.of()), "NEW_OPERATION_VALIDATION_AUTHORITY_REQUIRED");
            var migration = ValidationProfile.mavenTest(home, cache);
            denied(() -> OperationValidation.bind(input, store, impl, state, migration, "run", spec, List.of()), "NEW_OPERATION_VALIDATION_AUTHORITY_REQUIRED");
            denied(() -> OperationValidation.bind(input, store, impl, state, profile, "run", spec, List.of()), "OPERATION_VALIDATION_PREDECESSORS_REQUIRED");
            var wrong = ValidationProfile.mavenOperationTest(home, cache, Json.fingerprint("OPERATION_PLAN", new byte[]{1}), TESTS);
            denied(() -> OperationValidation.bind(input, store, impl, state, wrong, "run", spec, List.of()), "OPERATION_VALIDATION_POLICY_PLAN");
            denied(() -> ValidationProfile.mavenOperationTest(home, cache, fp, Map.of()), "OPERATION_VALIDATION_TESTS_REQUIRED");
            denied(() -> ValidationProfile.mavenOperationTest(home, cache, fp, Map.of("TEST-001", List.of("Bad#test", "Bad#test"))), "OPERATION_VALIDATION_TEST_IDENTITY");
            denied(() -> ValidationProfile.mavenOperationTest(home, cache, fp, Map.of("TEST-001", List.of("../bad"))), "OPERATION_VALIDATION_TEST_IDENTITY");
            check(profile.command(base.target()).equals(migration.command(base.target())), "same exact hardened argv for both workflows");
            System.out.println("PASS 8 operation validation authority contract checks; no process/provider execution");
        } finally { Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------")); }
    }
    private static void scenario(String mode) throws Exception {
        var base = fixture(temp); String operation = Set.of("GET", "INSERT", "UPDATE", "DELETE").contains(mode) ? mode : "INSERT";
        boolean shift = mode.equals("shift"), omitted = mode.equals("omitted-placement");
        var input = OperationPlanningTest.input(base.target(), operation, !omitted);
        Files.writeString(base.target().resolve(AnalysisFixtures.TARGET_PATH), original(input, shift));
        var f = new Fixture(base.trusted(), null, base.target(), input); var accepted = accepted(input, false, shift);
        String runnerMode = switch (mode) {
            case "false-success", "nonzero" -> "nonzero";
            case "timeout", "mutate", "skipped", "report-fail", "xxe" -> mode;
            case "missing-report" -> "missing";
            default -> "success";
        };
        Path home = MavenValidationTest.installation(temp, "operation-" + runnerMode, f.target());
        Path cache = Files.createTempDirectory(temp, "operation-offline-cache-");
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("r-x------"));
        try {
            var fp = Json.fingerprint("OPERATION_PLAN", accepted.get("OPERATION_PLAN"));
            var profile = mode.equals("missing-authority") ? ValidationProfile.mavenTest(home, cache)
                    : ValidationProfile.mavenOperationTest(home, cache, mode.equals("wrong-policy") ? Json.fingerprint("OPERATION_PLAN", new byte[]{1}) : fp, TESTS);
            Mock mock = new Mock(); int[] calls = {0};
            mock.answer = turn -> {
                var d = data(turn); String role = (String)map(turn.get("binding")).get("role");
                if (role.equals("MASTER")) {
                    String action = (String)map(d.get("proposedDecision")).get("action");
                    if (mode.equals("pre-drift") && action.equals("ACCEPT_IMPLEMENTATION_STEP")
                            && "06-test-implementation".equals(map(d.get("details")).get("subjectRole")))
                        writeFile(f.target().resolve("src/NewOperation.java"), "drift");
                    if (action.equals("ACCEPT_VALIDATION")) {
                        if (mode.equals("final-drift")) writeFile(f.target().resolve("src/NewOperation.java"), "drift");
                        if (mode.equals("master-reject")) return envelope(turn, "DECISION", null, null, null, "FAILED", List.of("Reject validation"));
                    }
                }
                if (!role.equals("07-validation")) return answer(turn, f, false, shift);
                calls[0]++;
                var authority = map(d.get("validationAuthority")); var context = map(authority.get("operationContext"));
                check(authority.get("workingRoot").equals(f.target().toString()), "target cwd");
                check(context.get("workflow").equals("NEW_OPERATION") && ((List<?>)context.get("implementationResults")).size() == 4, "operation stages");
                var plan = Json.parse(MigrationInput.utf8(accepted.get("OPERATION_PLAN"))); var obligations = map(context.get("obligations"));
                for (String key : List.of("requestedOperation", "databaseMapping", "fieldMappings", "placement", "testObligations", "validationObligations"))
                    check(obligations.get(key).equals(plan.get(key)), "exact " + key);
                check(map(context.get("lineage")).get("operationPlanFingerprint").equals(fp), "exact plan");
                check(!Json.write(authority).contains("SOURCE_ANALYSIS") && !Json.write(authority).contains("MIGRATION_PLAN"), "no fabricated lineage");
                check(((List<?>)d.get("availableOperations")).isEmpty() && d.get("toolProtocol") == null, "07 has no tools");
                check(Boolean.TRUE.equals(map(map(map(d.get("hostObservedRepositoryEffects")).get("validation")).get("execution")).get("started")), "host runs before 07");
                String forbidden = switch (mode) {
                    case "write" -> "WRITE_TARGET_TEXT"; case "source-read" -> "READ_SOURCE_TEXT"; case "source-write" -> "WRITE_SOURCE_TEXT";
                    case "shell" -> "RUN_SHELL"; case "process" -> "RUN_PROCESS"; case "network" -> "NETWORK_REQUEST"; case "sql" -> "EXECUTE_SQL";
                    case "maven" -> "RUN_VALIDATION"; default -> null;
                };
                if (forbidden != null) {
                    var request = new LinkedHashMap<>(Json.object("operationId", "attack", "operation", forbidden,
                            "invocationId", map(turn.get("binding")).get("invocationId"), "role", role));
                    if (forbidden.equals("RUN_VALIDATION")) request.put("authorityId", authority.get("authorityId"));
                    else {
                        request.putAll(Json.object("scope", mode.startsWith("source-") ? "SOURCE" : "TARGET",
                                "rootFilesystemIdentity", authority.get("targetRootIdentity"), "path", "src/NewOperation.java",
                                "query", null, "content", mode.equals("write") ? "attack" : null, "expectedBeforeFingerprint", null));
                        if (forbidden.equals("WRITE_TARGET_TEXT")) request.put("grantId", "invented");
                    }
                    return envelope(turn, "TOOL_REQUEST", null, null, request, null, List.of());
                }
                var result = map(d.get("resultContract"));
                switch (mode) {
                    case "wrong-plan" -> map(result.get("lineage")).put("operationPlanFingerprint", Map.of());
                    case "wrong-implementation" -> result.put("implementationAuthorityFingerprint", Map.of());
                    case "forged-maven" -> result.put("hostExecutionEvidenceReference", Map.of());
                    case "false-failure" -> result.put("validationStatus", "FAILED");
                    case "false-success" -> { result.put("validationStatus", "SUCCESS"); result.put("failures", List.of()); }
                    case "missing-stage" -> result.put("implementationResults", List.of());
                    case "report-drift" -> writeFile(f.target().resolve("target/surefire-reports/TEST-NewOperationTest.xml"), "changed");
                    default -> { }
                }
                return artifact(turn, "VALIDATION_RESULT", result);
            };
            var result = new ControlledPipeline(f.trusted(), input, new ControlledHarness.Config("mock-model", 16384), mock, policy(input, accepted, shift), profile).run();
            boolean success = Set.of("GET", "INSERT", "UPDATE", "DELETE", "omitted-placement", "shift").contains(mode);
            check(result.status().equals("SUCCESS") == success, mode + ": " + result.status() + " " + result.code());
            boolean launched = !Set.of("pre-drift", "missing-authority", "wrong-policy").contains(mode);
            check(Boolean.valueOf(launched).equals(result.report().get("validationExecuted")), "launch truth " + result.code());
            check(calls[0] == (launched ? 1 : 0), "07 invoked once only after host execution: " + calls[0] + " " + result.code());
            check(!mock.roles().contains("00-source-analysis") && !mock.roles().contains("02-migration-planning"), "no migration roles");
            check(map(result.report().get("gateArtifacts")).get("sourceCheck") == null, "no source gate");
            check(map(result.report().get("finalRepositoryEffects")).get("sourceObservation").equals("NOT_APPLICABLE"), "no source observer");
            if (success) {
                check(result.code().equals("VALIDATION_MASTER_ACCEPTED"), "final acceptance");
                check(mock.roles().stream().filter(r -> !r.equals("MASTER")).distinct().toList().equals(input.workflow().route()), "complete route");
                check(((List<?>)result.report().get("acceptedArtifacts")).size() == 8, "all artifacts accepted");
                var facts = map(result.report().get("validationEvidence"));
                check(map(facts.get("testEvidence")).get("status").equals("SUCCESS"), "required tests observed");
                check(Boolean.TRUE.equals(map(facts.get("effects")).get("targetImplementationUnchanged")), "only build effects");
                check(Files.readString(f.target().resolve("target/proof")).equals("ARGV_CWD_ENV_OK"), "actual child checks argv cwd environment");
            }
            if (mode.equals("false-success") || mode.equals("nonzero")) check(result.code().equals("VALIDATION_TESTS_FAILED"), "latched host failure");
            if (Set.of("write", "source-read", "source-write", "shell", "process", "network", "sql", "maven").contains(mode))
                check(result.code().equals("ROLE_OPERATION_DENIED"), "well-formed capability request denied: " + result.code());
            if (mode.equals("timeout")) check(result.code().equals("VALIDATION_TIMEOUT"), "timeout cannot pass");
            if (Set.of("missing-report", "skipped").contains(mode)) check(result.status().equals("BLOCKED") && result.code().equals("VALIDATION_REQUIRED_TEST_EVIDENCE_MISSING"), "exit zero needs tests");
            if (Set.of("mutate", "final-drift").contains(mode)) check(result.code().equals("UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS"), "effect rejection");
            if (mode.equals("report-drift")) check(result.code().equals("UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS"), "07 cannot alter validation reports");
        } finally { Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------")); }
    }
    private static void writeFile(Path path, String text) { try { Files.writeString(path, text); } catch (Exception e) { throw new IllegalStateException(e); } }
}
