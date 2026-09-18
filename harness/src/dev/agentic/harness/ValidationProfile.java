package dev.agentic.harness;

import java.nio.file.*;
import java.util.*;

/** Host-only opt-in. Never construct this from caller routing, artifacts or tool requests. */
public final class ValidationProfile {
    public static final String ID = "CONTROLLED_JAVA_CONTRACT_TEST";
    static final List<String> SOURCES = List.of("src/Value.java", "src/ValueMapper.java",
            "src/TargetConventions.java", "src/ReadItemContractTest.java");
    static final int TIMEOUT_SECONDS = 5, STREAM_BYTES = 8192, FACT_BYTES = 256 * 1024;
    private final Map<String,String> approvedSources;
    private final MavenValidation maven;
    private final List<String> command;
    private final Path executable, runner;
    private final Map<String,Object> executableFingerprint, runnerFingerprint;
    private Map<String,Object> operationPlanFingerprint;
    private Map<String,List<String>> operationTests;

    private ValidationProfile(Map<String,String> approvedSources) throws Exception {
        if (!approvedSources.keySet().equals(Set.copyOf(SOURCES)))
            throw new IllegalArgumentException("VALIDATION_PROFILE_SOURCE_SET_MISMATCH");
        this.approvedSources = Map.copyOf(approvedSources);
        maven = null;
        executable = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        Path classes = Path.of(ValidationWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        runner = classes.resolve("dev/agentic/harness/ValidationWorker.class");
        executableFingerprint = fingerprint(executable); runnerFingerprint = fingerprint(runner);
        command = List.of(executable.toString(), "-Xmx64m", "-XX:+UseSerialGC", "-XX:-UsePerfData",
                "-XX:+DisableAttachMechanism", "-cp", classes.toString(), ValidationWorker.class.getName());
    }
    /** The host must independently trust these complete source bytes as safe local fixture code.
     * Hash equality alone is not a security review. This profile never discovers project scripts. */
    public static ValidationProfile controlledJavaContractTest(Map<String,String> hostReviewedSources) throws Exception {
        return new ValidationProfile(hostReviewedSources);
    }
    private ValidationProfile(Path installation, Path readOnlyRepository) throws Exception {
        approvedSources = Map.of();
        maven = new MavenValidation(installation, readOnlyRepository);
        executable = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        executableFingerprint = fingerprint(executable);
        runner = null; runnerFingerprint = Map.of(); command = List.of();
    }
    /** Host-reviewed Maven 3 installation and a pre-provisioned, read-only offline cache.
     * Neither path may come from migration input, repository data or provider output. */
    public static ValidationProfile mavenTest(Path hostMavenHome, Path hostReadOnlyRepository) throws Exception {
        return new ValidationProfile(hostMavenHome, hostReadOnlyRepository);
    }
    /** Separate host authorization: exact accepted plan and independently reviewed test identities.
     * Keys are TEST obligation IDs; values are Surefire classname#name identities. Never caller/model data. */
    public static ValidationProfile mavenOperationTest(Path home, Path cache, Map<String,Object> planFingerprint,
                                                     Map<String,List<String>> requiredTests) throws Exception {
        SourceBroker.fingerprint(planFingerprint, "OPERATION_PLAN");
        if (requiredTests.isEmpty()) throw new IllegalArgumentException("OPERATION_VALIDATION_TESTS_REQUIRED");
        var tests = new TreeMap<String,List<String>>();
        requiredTests.forEach((id, names) -> {
            if (!id.matches("TEST-[0-9]{3}") || names.isEmpty() || new HashSet<>(names).size() != names.size()
                    || names.stream().anyMatch(n -> !n.matches("[A-Za-z_$][A-Za-z0-9_.$]*#[A-Za-z_$][A-Za-z0-9_$]*")))
                throw new IllegalArgumentException("OPERATION_VALIDATION_TEST_IDENTITY");
            tests.put(id, List.copyOf(names));
        });
        var profile = new ValidationProfile(home, cache);
        profile.operationPlanFingerprint = MigrationInput.immutable(planFingerprint);
        profile.operationTests = Collections.unmodifiableMap(tests);
        return profile;
    }
    Map<String,List<String>> operationTests() { return operationTests; }
    Map<String,Object> operationPolicy() {
        if (operationTests == null) throw new IllegalStateException("NEW_OPERATION_VALIDATION_AUTHORITY_REQUIRED");
        return Json.object("policyVersion", 1, "workflow", "NEW_OPERATION", "operationPlanFingerprint", operationPlanFingerprint,
                "requiredTests", operationTests, "reportPolicy", "FRESH_SUREFIRE_EXACT_TESTS_V1", "mavenProfile", view());
    }
    String id() { return maven == null ? ID : "HOST_MAVEN_TEST"; }
    int timeoutSeconds() { return maven == null ? TIMEOUT_SECONDS : 60; }
    List<String> command(Path root) { return maven == null ? command : maven.command(executable, root); }
    void verifyRoots(Path source, Path target) throws Exception {
        if (maven != null) maven.verifyRoots(source, target);
    }
    void verifyTargetRoot(Path target) throws Exception {
        if (maven == null || operationTests == null) throw new IllegalStateException("NEW_OPERATION_VALIDATION_AUTHORITY_REQUIRED");
        maven.verifyTargetRoot(target);
    }
    void prepare(Path root) throws Exception { if (maven != null) maven.prepare(root); }
    List<String> command() { return command; }
    Map<String,Object> view() {
        if (maven != null) return Json.object("profileId", id(), "launchPolicy", "DIRECT_JAVA_MAVEN_3_OFFLINE_TEST",
                "installationFingerprint", maven.identity(), "executableFingerprint", executableFingerprint,
                "environment", Map.of(), "timeoutSeconds", timeoutSeconds(), "stdoutLimit", STREAM_BYTES,
                "stderrLimit", STREAM_BYTES, "maxRetainedFactBytes", FACT_BYTES, "ephemeralScopes", List.of("target/**"));
        return Json.object("profileId", ID, "command", command, "environment", Map.of(),
                "executableFingerprint", executableFingerprint, "runnerFingerprint", runnerFingerprint,
                "approvedSourceFingerprints", SOURCES.stream().map(p -> Json.fingerprint("PATH_CONTENT:" + p,
                        approvedSources.get(p).getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList(),
                "timeoutSeconds", TIMEOUT_SECONDS, "stdoutLimit", STREAM_BYTES, "stderrLimit", STREAM_BYTES,
                "maxRetainedFactBytes", FACT_BYTES, "ephemeralScopes", List.of("target/**"));
    }
    void verify(RepositoryFiles.Snapshot state) throws Exception {
        if (!executableFingerprint.equals(fingerprint(executable)) || (maven == null && !runnerFingerprint.equals(fingerprint(runner))))
            throw new IllegalStateException("VALIDATION_EXECUTABLE_CHANGED");
        if (maven != null) { maven.verify(state); return; }
        for (String path : SOURCES) {
            var expected = Json.fingerprint("PATH_CONTENT:" + path, approvedSources.get(path).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (state.entries().stream().noneMatch(e -> path.equals(e.get("pathKey")) && expected.equals(e.get("contentFingerprint"))))
                throw new IllegalStateException("VALIDATION_PROFILE_SOURCE_MISMATCH");
        }
        // Never load pre-existing classes, compiler extensions or project-selected configuration.
        if (state.entries().stream().anyMatch(e -> ephemeral((String)e.get("pathKey"))))
            throw new IllegalStateException("VALIDATION_OUTPUT_SCOPE_NOT_EMPTY");
    }
    static boolean ephemeral(String path) { return path.equals("target") || path.startsWith("target/"); }
    private static Map<String,Object> fingerprint(Path path) throws Exception {
        var attrs = Files.readAttributes(path, "unix:mode,nlink,size,dev,ino", LinkOption.NOFOLLOW_LINKS);
        if ((((Number)attrs.get("mode")).intValue() & 0170000) != 0100000
                || ((Number)attrs.get("nlink")).intValue() != 1 || ((Number)attrs.get("size")).longValue() > 4 * 1024 * 1024)
            throw new IllegalStateException("VALIDATION_EXECUTABLE_UNSAFE");
        return Json.object("identity", Json.object("device", attrs.get("dev"), "inode", attrs.get("ino"), "mode", attrs.get("mode")), "bytes", Json.fingerprint("TRUSTED_VALIDATION_EXECUTABLE", Files.readAllBytes(path)));
    }
}
