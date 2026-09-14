package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fast negative contract checks; no files, provider, network, or process execution. */
public final class ArtifactContractTest {
    private static int passed;
    private static final MigrationInput INPUT = MigrationInput.fromJson("""
            {"migration":{"settings":{"sourceProjectPath":"/source-fixture","targetProjectPath":"/target-fixture","sourceOperationName":"readItem"}},
             "futureCallerField":{"relationship":[true,7,1.25]}}
            """);
    private static void check(boolean value) { if (!value) throw new AssertionError("Contract assertion failed"); }
    private static void run(Runnable check) { check.run(); passed++; }
    private static void denied(Runnable check) {
        try { check.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Contract accepted invalid input");
    }
    private static Map<String,Object> source() { return mutable(AnalysisFixtures.source(INPUT)); }
    private static Map<String,Object> target() { return mutable(AnalysisFixtures.target(INPUT)); }
    private static Map<String,byte[]> accepted() { return Map.of("SOURCE_ANALYSIS", Json.bytes(source()), "TARGET_ANALYSIS", Json.bytes(target())); }
    private static Map<String,Object> plan() {
        var accepted = accepted();
        return mutable(AnalysisFixtures.plan(INPUT, accepted.get("SOURCE_ANALYSIS"), accepted.get("TARGET_ANALYSIS")));
    }
    private static Map<String,Object> mutable(Map<String,Object> value) { return Json.parse(Json.write(value)); }
    private static void validate(TrustedInputs.Role role, Map<String,Object> value) {
        ArtifactContracts.validate(role, Json.bytes(value), INPUT, Map.of());
    }
    private static void validatePlan(Map<String,Object> value) {
        ArtifactContracts.validate(TrustedInputs.Role.PLANNING, Json.bytes(value), INPUT, accepted());
    }
    public static void main(String[] args) {
        run(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, source()));
        run(() -> validate(TrustedInputs.Role.ANALYSIS, target()));
        for (String invalid : List.of("false", "null", "string", "empty", "missing-id", "invalid-id", "duplicate-id", "required-field"))
            run(() -> malformedTargetFinding(invalid));
        for (String path : targetFindingArrays())
            run(() -> {
                var value = target(); var container = value;
                String[] keys = path.split("\\.");
                for (int i = 0; i < keys.length - 1; i++) container = object(container.get(keys[i]));
                var entries = new ArrayList<>(ArtifactContracts.list(container.get(keys[keys.length - 1])));
                entries.add(false); container.put(keys[keys.length - 1], entries);
                denied(() -> validate(TrustedInputs.Role.ANALYSIS, value));
            });
        run(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, AnalysisFixtures.blockedSource(INPUT)));
        run(() -> check(INPUT.text().contains("1.25") && INPUT.callerInformation().containsKey("futureCallerField")));
        run(() -> { byte[] bytes = INPUT.bytes(); bytes[0] = 'x'; check(INPUT.bytes()[0] == '{'); });
        run(() -> check(INPUT.fingerprint().equals(Json.fingerprint("CALLER_MIGRATION_REQUEST", INPUT.bytes()))));
        run(() -> {
            try { INPUT.callerInformation().put("role", "MASTER"); throw new AssertionError("Mutable caller input"); }
            catch (UnsupportedOperationException expected) { /* immutable */ }
        });
        run(() -> denied(() -> MigrationInput.fromJson("{\"sourceProjectPath\":\"/s\",\"sourceProjectPath\":\"/other\"}")));
        run(() -> denied(() -> MigrationInput.fromJson("{} {}")));
        run(() -> denied(() -> MigrationInput.fromJson("{}")));
        run(() -> denied(() -> MigrationInput.fromJson(new byte[]{(byte) 0xc3, 0x28})));
        run(() -> denied(() -> MigrationInput.fromJson(INPUT.text().replace("/source-fixture", "/target-fixture/source"))));
        run(() -> denied(() -> MigrationInput.fromJson(INPUT.text().replace("/source-fixture", "/source-fixture/../outside"))));
        run(() -> denied(() -> MigrationInput.fromJson(INPUT.text().replace("\"futureCallerField\"", "\"password\""))));
        run(() -> denied(() -> validate(TrustedInputs.Role.SERVICE, source())));
        run(() -> { var value = source(); value.remove("inputContract"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); value.put("status", "COMPLETE"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); value.put("activeRole", "MASTER"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); object(value.get("project")).put("root", "/target-fixture"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); object(value.get("analysisScope")).put("requestedOperation", "other"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); object(value.get("analysisScope")).put("resolvedEntryPointId", null); value.put("operationEntryPoint", null); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); first(value, "evidence").put("path", "../outside"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); first(value, "findings").put("evidenceIds", List.of("E-999")); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); first(value, "evidence").put("supports", List.of()); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); first(value, "callerProvidedMigrationInfo").put("sourceArtifactFingerprint", Json.fingerprint("CALLER_MIGRATION_REQUEST", new byte[]{1})); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); first(value, "callerProvidedMigrationInfo").put("reference", "/missing"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); first(value, "callerProvidedMigrationInfo").put("value", "differentOperation"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); object(value.get("analysisCoverage")).put("areas", List.of()); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); value.put("status", "BLOCKED"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); value.put("status", "FAILED"); denied(() -> validate(TrustedInputs.Role.SOURCE_ANALYSIS, value)); });
        run(() -> { var value = source(); value.put("status", "FAILED"); object(value.get("analysisCoverage")).put("limitations", List.of("Output observation failed.")); validate(TrustedInputs.Role.SOURCE_ANALYSIS, value); });
        run(() -> { var value = target(); object(value.get("project")).put("root", "/source-fixture"); denied(() -> validate(TrustedInputs.Role.ANALYSIS, value)); });
        run(() -> { var value = target(); object(value.get("mapping")).put("mapperPresence", "MAYBE"); denied(() -> validate(TrustedInputs.Role.ANALYSIS, value)); });
        run(() -> { var value = target(); value.remove("testing"); denied(() -> validate(TrustedInputs.Role.ANALYSIS, value)); });
        run(() -> {
            String raw = Json.write(source()).replace("\"analysisVersion\":1", "\"analysisVersion\":1,\"analysisVersion\":1");
            denied(() -> ArtifactContracts.validate(TrustedInputs.Role.SOURCE_ANALYSIS, raw.getBytes(StandardCharsets.UTF_8), INPUT, Map.of()));
        });
        run(() -> {
            var source = source(); String raw = " \n" + Json.write(source) + "\n";
            var parsed = ArtifactContracts.validate(TrustedInputs.Role.SOURCE_ANALYSIS, raw.getBytes(StandardCharsets.UTF_8), INPUT, Map.of());
            check(parsed.equals(source));
            check(!Json.fingerprint("SOURCE_ANALYSIS", raw.getBytes(StandardCharsets.UTF_8)).equals(Json.fingerprint("SOURCE_ANALYSIS", Json.bytes(source))));
        });
        run(() -> validatePlan(plan()));
        run(() -> {
            var plan = plan(); var coverage = object(plan.get("coverage"));
            check(Boolean.FALSE.equals(coverage.get("noImplementationChangeRequired")));
            check(ArtifactContracts.list(coverage.get("expectedTouchedFiles")).size() == 2);
            check(ArtifactContracts.list(plan.get("implementationOrder")).size() == 2);
        });
        run(() -> denied(() -> ArtifactContracts.validate(TrustedInputs.Role.PLANNING, Json.bytes(plan()), INPUT, Map.of())));
        run(() -> {
            var supplied = new LinkedHashMap<>(accepted()); supplied.remove("TARGET_ANALYSIS");
            denied(() -> ArtifactContracts.validate(TrustedInputs.Role.PLANNING, Json.bytes(plan()), INPUT, supplied));
        });
        run(() -> {
            var supplied = new LinkedHashMap<>(accepted()); supplied.put("SOURCE_ANALYSIS", Json.bytes(AnalysisFixtures.blockedSource(INPUT)));
            denied(() -> ArtifactContracts.validate(TrustedInputs.Role.PLANNING, Json.bytes(plan()), INPUT, supplied));
        });
        run(() -> { var value = plan(); object(value.get("sourceProject")).put("root", "/target-fixture"); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); object(value.get("targetProject")).put("analysisStatus", "PARTIAL"); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); object(value.get("sourceProject")).put("analysisFingerprint", Json.fingerprint("TARGET_ANALYSIS", accepted().get("SOURCE_ANALYSIS"))); denied(() -> validatePlan(value)); });
        run(() -> {
            var value = plan(); byte[] withWhitespace = (" \n" + MigrationInput.utf8(accepted().get("SOURCE_ANALYSIS"))).getBytes(StandardCharsets.UTF_8);
            object(value.get("sourceProject")).put("analysisFingerprint", Json.fingerprint("SOURCE_ANALYSIS", withWhitespace));
            denied(() -> validatePlan(value));
            ArtifactContracts.validate(TrustedInputs.Role.PLANNING, Json.bytes(value), INPUT,
                    Map.of("SOURCE_ANALYSIS", withWhitespace, "TARGET_ANALYSIS", accepted().get("TARGET_ANALYSIS")));
        });
        run(() -> { var value = plan(); object(value.get("sourceProject")).put("resolvedEntryPointId", "EP-999"); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); first(value, "requirements").put("sourceFindingIds", List.of("F-999")); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); first(value, "componentDecisions").put("targetFindingIds", List.of("F-999")); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); first(value, "requirements").put("sourceEvidenceIds", List.of()); denied(() -> validatePlan(value)); });
        run(() -> {
            var value = plan(); var origin = object(first(value, "requirements").get("source"));
            first(origin, "references").put("sourceArtifactFingerprint", Json.fingerprint("CALLER_MIGRATION_REQUEST", new byte[]{1}));
            denied(() -> validatePlan(value));
        });
        run(() -> { var value = plan(); object(object(value.get("coverage")).get("requirements")).put("total", 2); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); object(object(value.get("coverage")).get("decisions")).put("total", 2); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); object(value.get("coverage")).put("expectedTouchedFiles", List.of("../outside")); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); value.put("activeRole", "MASTER"); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); value.put("status", "BLOCKED"); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); value.put("status", "FAILED"); denied(() -> validatePlan(value)); });
        run(() -> { var value = plan(); value.remove("validationPlan"); denied(() -> validatePlan(value)); });
        run(() -> denied(() -> ArtifactContracts.validate(TrustedInputs.Role.PLANNING,
                (Json.write(plan()) + " {}").getBytes(StandardCharsets.UTF_8), INPUT, accepted())));
        System.out.println("PASS " + passed + " artifact contract checks; exact input bytes and analyst/planner schema gates, no provider calls");
    }
    private static void malformedTargetFinding(String invalid) {
        var value = target(); var architecture = object(value.get("architecture"));
        var entries = new ArrayList<>(ArtifactContracts.list(architecture.get("findings")));
        var finding = new LinkedHashMap<>(object(entries.getFirst()));
        finding.put("id", "F-099");
        Object malformed = switch (invalid) {
            case "false" -> false;
            case "null" -> null;
            case "string" -> "not a finding";
            case "empty" -> Map.of();
            case "missing-id" -> { finding.remove("id"); yield finding; }
            case "invalid-id" -> { finding.put("id", "wrong-099"); yield finding; }
            case "duplicate-id" -> entries.getFirst();
            case "required-field" -> { finding.remove("topic"); yield finding; }
            default -> throw new AssertionError(invalid);
        };
        entries.add(malformed); architecture.put("findings", entries);
        denied(() -> validate(TrustedInputs.Role.ANALYSIS, value));
    }
    private static List<String> targetFindingArrays() {
        List<String> paths = new ArrayList<>(List.of("project.frameworks", "project.importantDirectories"));
        var fixture = target();
        for (String section : List.of("architecture", "database", "domainAndDto", "mapping", "service", "api",
                "supportingConventions", "testing", "codingConventions"))
            arrayPaths(section, fixture.get(section), paths);
        return paths;
    }
    private static void arrayPaths(String path, Object value, List<String> paths) {
        if (value instanceof List<?>) paths.add(path);
        else if (value instanceof Map<?,?> map) map.forEach((key, item) -> arrayPaths(path + "." + key, item, paths));
    }
    private static Map<String,Object> object(Object value) { return ArtifactContracts.object(value); }
    private static Map<String,Object> first(Map<String,Object> value, String key) { return object(ArtifactContracts.list(value.get(key)).getFirst()); }
}
