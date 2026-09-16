package dev.agentic.harness;

import java.nio.file.*;
import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;
import static dev.agentic.harness.ImplementationFixtures.*;

/** Mocked provider, actual production coordinator and fixed-profile local child process. */
public final class ValidationRuntimeTest {
    private static Path temp, repository;
    private static int passed;
    public static void main(String[] args) throws Exception {
        temp = Path.of(args[0]).toRealPath(); repository = Path.of(args[1]).toRealPath();
        for (String fault : List.of("timeout", "nonzero", "malformed", "fabricated-pass", "omitted-failure", "source-during",
                "target-during", "outside-output", "source-mode", "target-mode", "git-output", "symlink-before",
                "hardlink-before", "symlink-output", "hardlink-output", "root-during", "master-reject", "master-drift",
                "bounded-logs", "evidence-limit", "nonzero-evidence-limit", "output-limit", "report-failure", "journal-failure",
                "journal-report-failure", "interrupted", "master-mode-reject", "nonzero-report-failure", "success", "early-07",
                "06-master-reject", "profile-mismatch", "authority-copy", "executable", "arguments", "cwd",
                "environment", "timeout-override", "scope-override", "memory-override", "profile-override", "authority",
                "invocation", "role", "replay", "predecessor", "target-root", "source-before",
                "target-before", "source-mode-before-07", "target-mode-before-07", "actual-specifications")) {
            if (args.length > 2 && !fault.equals(args[2])) continue;
            try { scenario(fault); passed++; System.out.println("PASS validation " + fault); }
            catch (Throwable e) { throw new AssertionError("Validation runtime: " + fault, e); }
        }
        if (args.length <= 2) { immutable(); passed++; }
        System.out.println("PASS " + passed + " validation runtime checks; local controlled processes, mocked provider, no live E2E");
    }
    private static void scenario(String fault) throws Exception {
        Fixture f = fixture(temp); Mock mock = new Mock();
        String body = switch (fault) {
            case "timeout", "interrupted" -> "while (true) { Thread.sleep(100); }";
            case "nonzero", "fabricated-pass", "omitted-failure", "nonzero-report-failure" -> "throw new AssertionError();";
            case "source-during" -> "java.nio.file.Files.writeString(java.nio.file.Path.of(" + Json.write(f.source().resolve(AnalysisFixtures.SOURCE_PATH).toString()) + "), \"changed\");";
            case "source-mode" -> "java.nio.file.Files.setPosixFilePermissions(java.nio.file.Path.of(" + Json.write(f.source().resolve(AnalysisFixtures.SOURCE_PATH).toString()) + "), java.nio.file.attribute.PosixFilePermissions.fromString(\"rwx------\"));";
            case "target-mode" -> "java.nio.file.Files.setPosixFilePermissions(java.nio.file.Path.of(\"pom.xml\"), java.nio.file.attribute.PosixFilePermissions.fromString(\"rwx------\"));";
            case "target-during" -> "java.nio.file.Files.writeString(java.nio.file.Path.of(\"src/Value.java\"), \"changed\");";
            case "outside-output" -> "java.nio.file.Files.writeString(java.nio.file.Path.of(\"pom.xml\"), \"changed\");";
            case "git-output" -> "java.nio.file.Files.createDirectory(java.nio.file.Path.of(\"target/.git\"));";
            case "symlink-output" -> "java.nio.file.Files.createSymbolicLink(java.nio.file.Path.of(\"target/link\"), java.nio.file.Path.of(\"../src/Value.java\"));";
            case "hardlink-output" -> "java.nio.file.Files.createLink(java.nio.file.Path.of(\"target/link\"), java.nio.file.Path.of(\"src/Value.java\"));";
            case "root-during" -> "java.nio.file.Path r = java.nio.file.Path.of(\"\").toAbsolutePath(); java.nio.file.Files.move(r, r.resolveSibling(\"moved-target\")); java.nio.file.Files.createDirectory(r);";
            case "output-limit" -> "java.nio.file.Files.write(java.nio.file.Path.of(\"target/large\"), new byte[1048577]);";
            case "nonzero-evidence-limit" -> "for (int i = 0; i < 512; i++) java.nio.file.Files.writeString(java.nio.file.Path.of(\"target/output-\" + i), \"output\"); throw new AssertionError();";
            case "evidence-limit" -> "for (int i = 0; i < 512; i++) java.nio.file.Files.writeString(java.nio.file.Path.of(\"target/output-\" + i), \"output\");";
            case "bounded-logs" -> "byte[] bytes = new byte[32768]; java.util.Arrays.fill(bytes, (byte)65); System.out.write(bytes); new java.io.FileOutputStream(java.io.FileDescriptor.err).write(bytes);";
            default -> null;
        };
        String test = body == null ? TEXTS.get(3) : "final class ReadItemContractTest { static void verifiesIdentity() throws Exception { " + body + " } }\n";
        Map<String,String> approved = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) approved.put(PATHS.get(i), i == 3 ? test : TEXTS.get(i));
        Map<String,String> profileSources = new LinkedHashMap<>(approved);
        if (fault.equals("profile-mismatch")) profileSources.put(PATHS.get(3), "host reviewed different test");
        ValidationProfile profile = ValidationProfile.controlledJavaContractTest(profileSources);
        var rules = new ArrayList<>(rules());
        rules.set(3, new ExecutionPlan.StaticRule(pointer(3), PATHS.get(3), test, DUTIES.get(3)));
        byte[] resolution = Json.bytes(ExecutionPlan.callerResolution(rules, List.of()));
        boolean[] changed = {false};
        Thread owner = Thread.currentThread(); Thread[] interrupter = {null};
        mock.answer = turn -> {
            var data = data(turn); String role = (String)map(turn.get("binding")).get("role");
            if (fault.equals("early-07") && role.equals(TrustedInputs.Role.TESTS.id))
                return envelope(turn, "TOOL_REQUEST", null, null, request(turn, "invented"), null, List.of());
            if (role.equals(TrustedInputs.Role.TESTS.id) && ((List<?>)data.get("toolResults")).isEmpty()) {
                var reply = write(turn, 3);
                return replace(reply, "toolRequest", replace(map(reply.get("toolRequest")), "content", test));
            }
            if (Set.of("source-mode-before-07", "target-mode-before-07").contains(fault) && role.equals("MASTER")
                    && "ACCEPT_IMPLEMENTATION_STEP".equals(map(data.get("proposedDecision")).get("action"))
                    && TrustedInputs.Role.TESTS.id.equals(map(data.get("details")).get("subjectRole")))
                mutate(f, fault.equals("source-mode-before-07") ? "source-mode-before" : "target-mode-before");
            if (fault.equals("06-master-reject") && role.equals("MASTER")
                    && "ACCEPT_IMPLEMENTATION_STEP".equals(map(data.get("proposedDecision")).get("action"))
                    && TrustedInputs.Role.TESTS.id.equals(map(data.get("details")).get("subjectRole")))
                return envelope(turn, "DECISION", null, null, null, "FAILED", List.of("06 not accepted."));
            if (role.equals("MASTER") && "ACCEPT_VALIDATION".equals(map(data.get("proposedDecision")).get("action"))) {
                if (fault.equals("master-mode-reject")) {
                    mutate(f, "target-mode-before");
                    return envelope(turn, "DECISION", null, null, null, "FAILED", List.of("Rejected after drift."));
                }
                if (fault.equals("master-reject")) return envelope(turn, "DECISION", null, null, null, "FAILED", List.of("Rejected validation."));
                if (fault.equals("master-drift")) mutate(f, "target-before");
            }
            if (!role.equals(TrustedInputs.Role.VALIDATION.id)) return answer(turn, f);
            if (((List<?>)data.get("toolResults")).isEmpty()) {
                if (!changed[0]) { mutate(f, fault); changed[0] = true; }
                if (fault.equals("interrupted")) {
                    interrupter[0] = new Thread(() -> {
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                        try {
                            while (!Files.isDirectory(f.target().resolve("target")) && System.nanoTime() < deadline) Thread.sleep(10);
                            owner.interrupt();
                        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                    });
                    interrupter[0].setDaemon(true); interrupter[0].start();
                }
                if (fault.equals("authority-copy")) {
                    // A model-side copy can be edited; it cannot change the retained host authority.
                    map(map(data.get("validationAuthority")).get("trustedProfile")).put("command", List.of("bad"));
                    map(data.get("validationAuthority")).put("workingRoot", f.source().toString());
                }
                var request = request(turn, (String)map(data.get("validationAuthority")).get("authorityId"));
                request = switch (fault) {
                    case "executable" -> replace(request, "executable", "/bin/false");
                    case "arguments" -> replace(request, "arguments", List.of("anything"));
                    case "cwd" -> replace(request, "cwd", f.source().toString());
                    case "environment" -> replace(request, "environment", Map.of("ANY", "value"));
                    case "timeout-override" -> replace(request, "timeoutSeconds", 1000);
                    case "scope-override" -> replace(request, "ephemeralScopes", List.of("src/**"));
                    case "memory-override" -> replace(request, "memoryLimit", "unlimited");
                    case "profile-override" -> replace(request, "profileId", "MAVEN_TEST");
                    case "authority" -> replace(request, "authorityId", "wrong");
                    case "invocation" -> replace(request, "invocationId", "wrong");
                    case "role" -> replace(request, "role", TrustedInputs.Role.TESTS.id);
                    default -> request;
                };
                var reply = envelope(turn, "TOOL_REQUEST", null, null, request, null, List.of());
                if (fault.equals("predecessor")) reply = replace(reply, "binding", replace(map(reply.get("binding")), "predecessorInvocationId", "stale"));
                return reply;
            }
            if (fault.equals("replay")) return envelope(turn, "TOOL_REQUEST", null, null,
                    replace(request(turn, (String)map(data.get("validationAuthority")).get("authorityId")), "operationId", "second-use"), null, List.of());
            var result = map(data.get("resultContract"));
            if (fault.equals("malformed")) result = replace(result, "unexpected", true);
            if (fault.equals("fabricated-pass")) result = replace(result, "validationStatus", "SUCCESS", "exitCode", 0, "failures", List.of());
            if (fault.equals("omitted-failure")) result = replace(result, "failures", List.of());
            return artifact(turn, "VALIDATION_RESULT", result);
        };
        mock.credentialFault = text -> {
            if (Set.of("report-failure", "nonzero-report-failure", "journal-report-failure").contains(fault) && text.startsWith("{\"status\":") && text.contains("\"terminalSummary\""))
                throw new IllegalArgumentException("REPORT_REJECTED");
            if (Set.of("journal-failure", "journal-report-failure").contains(fault) && text.contains("\"kind\":\"VALIDATION_EXECUTION_OBSERVED\""))
                throw new IllegalArgumentException("JOURNAL_REJECTED");
        };
        var result = new ControlledPipeline(fault.equals("actual-specifications") ? repository : f.trusted(), f.input(),
                new ControlledHarness.Config("mock-model", 16384), mock, resolution, profile).run();
        if (fault.equals("interrupted")) {
            boolean interruptPreserved = Thread.interrupted();
            if (interrupter[0] != null) interrupter[0].join();
            check(interruptPreserved, "interrupted validation must restore caller interrupt status");
            check(result.status().equals("FAILED") && result.code().equals("VALIDATION_EXECUTION_INTERRUPTED"), "interruption primary failure: " + result.code());
        }
        boolean success = Set.of("success", "actual-specifications", "bounded-logs", "report-failure", "authority-copy").contains(fault);
        check(success == result.status().equals("SUCCESS"), "status=" + result.status() + " code=" + result.code());
        boolean before = Set.of("early-07", "06-master-reject", "profile-mismatch", "executable", "arguments", "cwd", "environment", "timeout-override", "scope-override", "memory-override", "profile-override", "authority", "invocation", "role", "predecessor",
                "target-root", "source-before", "target-before", "symlink-before", "hardlink-before", "source-mode-before-07", "target-mode-before-07").contains(fault);
        check(Boolean.valueOf(!before).equals(result.report().get("validationExecuted")), "truthful launch " + result.code());
        if (before) {
            if (Set.of("target-root", "source-before", "target-before", "symlink-before", "hardlink-before", "source-mode-before-07", "target-mode-before-07").contains(fault))
                check(result.status().equals("BLOCKED") && result.code().startsWith("PRE_VALIDATION_"), "precise preflight drift: " + result.code());
            if (fault.equals("profile-mismatch")) check(result.status().equals("BLOCKED") && result.code().equals("VALIDATION_TRUSTED_PROFILE_UNAVAILABLE"), "profile pinning");
            return;
        }
        var facts = map(result.report().get("validationEvidence"));
        var execution = map(facts.get("execution"));
        if (Set.of("journal-failure", "journal-report-failure").contains(fault)) check(!result.status().equals("SUCCESS") && Boolean.TRUE.equals(execution.get("completed")), "journal failure retains execution");
        if (Set.of("report-failure", "nonzero-report-failure", "journal-report-failure").contains(fault))
            check(Json.write(result.report().get("orchestrationFailures")).contains("FINAL_EVIDENCE_UNAVAILABLE"), "actual fallback path exercised");
        check(Boolean.TRUE.equals(execution.get("started")), "launch facts survive termination");
        if (fault.equals("output-limit")) check(result.status().equals("BLOCKED") && result.code().equals("VALIDATION_REPOSITORY_OBSERVATION_UNAVAILABLE"), "oversized output cannot pass: " + result.code());
        if (fault.equals("evidence-limit")) check(result.status().equals("BLOCKED") && result.code().equals("VALIDATION_EVIDENCE_LIMIT")
                && Json.bytes(facts).length <= ValidationProfile.FACT_BYTES, "bounded evidence cannot pass: " + result.code());
        if (Set.of("timeout", "interrupted").contains(fault)) check(Boolean.TRUE.equals(execution.get("completed")), "terminated worker must be observed exited");
        if (fault.equals("timeout")) check(result.code().equals("VALIDATION_TIMEOUT") && Boolean.TRUE.equals(execution.get("timedOut")), "timeout truth");
        if (Set.of("nonzero", "fabricated-pass", "omitted-failure", "nonzero-report-failure", "nonzero-evidence-limit").contains(fault))
            check(result.code().equals("VALIDATION_TESTS_FAILED") && ((Number)execution.get("exitCode")).intValue() != 0, "latched nonzero failure");
        if (Set.of("source-during", "target-during", "outside-output", "symlink-output", "hardlink-output", "root-during", "master-drift", "source-mode", "target-mode", "git-output", "master-mode-reject").contains(fault))
            check(result.code().equals("UNAUTHORIZED_VALIDATION_REPOSITORY_EFFECTS"), "exact unauthorized effects code: " + result.code());
        if (success) {
            check(((List<?>)result.report().get("acceptedArtifacts")).size() == 8, "all 00–07 artifacts accepted");
            check(result.code().equals("VALIDATION_MASTER_ACCEPTED"), "MASTER acceptance required");
            var effects = map(facts.get("effects"));
            check(Boolean.TRUE.equals(effects.get("sourceUnchanged")) && Boolean.TRUE.equals(effects.get("targetImplementationUnchanged")), "immutable source/config");
            check(Json.write(effects).contains("VALIDATION_EPHEMERAL_EFFECT"), "binary build output classified");
            for (int i = 0; i < 4; i++) check(Files.readString(f.target().resolve(PATHS.get(i))).equals(approved.get(PATHS.get(i))), "accepted implementation unchanged");
        }
        if (fault.equals("bounded-logs")) for (String stream : List.of("stdout", "stderr")) {
            var capture = map(execution.get(stream));
            check(((Number)capture.get("retainedBytes")).intValue() == 8192 && Boolean.TRUE.equals(capture.get("truncated")), "stream bounded");
            check(((List<?>)capture.get("excerpts")).isEmpty(), "logs never published");
        }
    }
    private static Map<String,Object> request(Map<String,Object> turn, String authority) {
        var binding = map(turn.get("binding"));
        return Json.object("operationId", "validation-use", "operation", "RUN_VALIDATION", "invocationId", binding.get("invocationId"),
                "role", binding.get("role"), "authorityId", authority);
    }
    private static void mutate(Fixture f, String fault) {
        try {
            switch (fault) {
                case "target-root" -> { Files.move(f.target(), f.target().resolveSibling("old-target")); Files.createDirectory(f.target()); }
                case "source-before" -> Files.writeString(f.source().resolve(AnalysisFixtures.SOURCE_PATH), "drift");
                case "source-mode-before" -> Files.setPosixFilePermissions(f.source().resolve(AnalysisFixtures.SOURCE_PATH), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                case "target-mode-before" -> Files.setPosixFilePermissions(f.target().resolve("pom.xml"), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                case "target-before" -> Files.writeString(f.target().resolve(PATHS.get(0)), "drift");
                case "symlink-before", "hardlink-before" -> {
                    Path file = f.target().resolve(PATHS.get(0)), old = f.target().resolve("held");
                    Files.move(file, old);
                    if (fault.equals("symlink-before")) Files.createSymbolicLink(file, old); else Files.createLink(file, old);
                }
                default -> { }
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void immutable() throws Exception {
        Map<String,String> inputs = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) inputs.put(PATHS.get(i), TEXTS.get(i));
        var p = ValidationProfile.controlledJavaContractTest(inputs); var before = p.view(); inputs.clear();
        check(before.equals(p.view()), "defensive copy of host profile");
        try { ((List<?>)p.view().get("command")).clear(); throw new AssertionError("mutable command"); }
        catch (UnsupportedOperationException expected) { }
    }
}
