package dev.agentic.harness;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static dev.agentic.harness.RuntimeTest.*;
import static dev.agentic.harness.ImplementationFixtures.*;

/** Offline process-boundary tests: fake host Classworlds jar, real Java child, mocked role transport. */
public final class MavenValidationTest {
    private static Path temp;
    private static int passed;
    public static void main(String[] args) throws Exception {
        if (args.length == 2 || (args.length == 3 && !args[2].equals("child"))) {
            // Supply dangerous inherited variables deliberately, without using a shell or printing them.
            var driver = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), MavenValidationTest.class.getName(), args[0], args[1], "child"));
            if (args.length == 3) driver.add(args[2]);
            var process = new ProcessBuilder(driver);
            process.environment().clear();
            for (String key : List.of("OPENAI_API_KEY", "GITHUB_TOKEN", "AWS_SECRET_ACCESS_KEY", "MAVEN_OPTS",
                    "MAVEN_ARGS", "CLASSPATH", "BASH_ENV", "ENV", "CUSTOM_INJECTION", "HOME"))
                process.environment().put(key, "host-sentinel-value");
            process.environment().put("JAVA_TOOL_OPTIONS", "-Dhost.environment.canary=true");
            process.environment().put("JDK_JAVA_OPTIONS", "-Dhost.jdk.canary=true");
            process.environment().put("_JAVA_OPTIONS", "-Dhost.legacy.canary=true");
            process.inheritIO();
            check(process.start().waitFor() == 0, "isolated Maven boundary group");
            return;
        }
        temp = Path.of(args[0]).toRealPath();
        for (String mode : List.of("success", "nonzero", "logs", "master-reject", "authority-copy", "json", "yaml", "yml",
                "executable", "arguments", "profileId", "environment", "cwd", "timeoutSeconds", "classpath", "goals",
                "properties", "profiles", "jvmOptions", "stdoutLimit", "ephemeralScopes", "artifact-profile",
                "mvn-config", "mvn-existing", "mvn-jvm", "mvn-extensions", "wrapper", "target-symlink", "source-cwd", "host-drift", "background-success", "background-nonzero", "timeout-replacement", "timeout")) {
            if (args.length == 4 && !Set.of(args[3].split(",")).contains(mode)) continue;
            scenario(mode); passed++; System.out.println("PASS Maven boundary " + mode);
        }
        if (args.length != 4) { paths(); passed++; }
        check(passed > 0, "a selected Maven case must execute");
        System.out.println("PASS " + passed + " Maven boundary checks; fake Maven, offline, no provider calls");
    }
    private static void scenario(String mode) throws Exception {
        Fixture base = fixture(temp);
        Fixture f = base;
        if (Set.of("json", "yaml", "yml", "master-reject").contains(mode)) {
            var data = Json.parse(base.input().text());
            data.put("executable", "/bin/false"); data.put("arguments", List.of("-Pattack", "-Dattack=true"));
            data.put("profileId", "ATTACK"); data.put("environment", Map.of("INJECTED", "yes"));
            data.put("timeoutSeconds", 9999); data.put("cwd", base.source().toString());
            data.put("callerInformation", Map.of("executable", "/bin/false", "profileId", "ATTACK", "args", List.of("attack"), "timeoutSeconds", 9999));
            Path config = base.target().getParent().resolve("migration." + (mode.equals("master-reject") ? "json" : mode));
            // JSON is a YAML subset, but use block YAML to exercise the YAML-specific loading path too.
            String text = Set.of("json", "master-reject").contains(mode) ? Json.write(data) : "migration:\n  settings:\n    sourceProjectPath: "
                    + Json.write(base.source().toString()) + "\n    targetProjectPath: " + Json.write(base.target().toString())
                    + "\n    sourceOperationName: readItem\nexecutable: /bin/false\narguments: [attack]\nprofileId: ATTACK\n"
                    + "callerInformation:\n  executable: /bin/false\n  args: [attack]\n  profileId: ATTACK\n  timeoutSeconds: 9999\nenvironment: {INJECTED: yes}\ntimeoutSeconds: 9999\ncwd: " + Json.write(base.source().toString()) + "\n";
            Files.writeString(config, text);
            f = new Fixture(base.trusted(), base.source(), base.target(), MigrationConfigLoader.load(config));
        }
        Path installation = installation(mode, f.target());
        Path cache = Files.createTempDirectory(temp, "offline-cache-");
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("r-x------"));
        try {
            ValidationProfile profile = ValidationProfile.mavenTest(installation, cache);
            var command = profile.command(f.target()); var identity = profile.view();
            check(command.getFirst().equals(Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString()), "host JDK executable");
            check(command.getLast().equals("test") && command.contains("--offline") && !command.contains("-c") && command.get(13).equals("org.codehaus.plexus.classworlds.launcher.Launcher"), "fixed goal and no shell");
            try { command.clear(); throw new AssertionError("mutable argv"); } catch (UnsupportedOperationException expected) { }
            Mock mock = new Mock(); Fixture active = f;
            boolean[] pipelineValidation = {false};
            mock.answer = turn -> {
                var data = data(turn); String role = (String)map(turn.get("binding")).get("role");
                if (mode.equals("master-reject") && role.equals("MASTER")
                        && "ACCEPT_VALIDATION".equals(map(data.get("proposedDecision")).get("action")))
                    return envelope(turn, "DECISION", null, null, null, "FAILED", List.of("Host build evidence is not acceptance."));
                if (!role.equals(TrustedInputs.Role.VALIDATION.id)) return answer(turn, active);
                pipelineValidation[0] = true;
                if (((List<?>)data.get("toolResults")).isEmpty()) {
                    try {
                        if (mode.equals("mvn-config")) {
                            Files.createDirectory(active.target().resolve(".mvn"));
                            Files.writeString(active.target().resolve(".mvn/maven.config"), "-Pattack");
                        }
                        if (mode.equals("target-symlink")) Files.createSymbolicLink(active.target().resolve("escape"), active.source());
                        if (mode.equals("host-drift")) Files.writeString(installation.resolve("changed"), "drift");
                        if (mode.equals("source-cwd")) {
                            var observer = new RepositoryFiles(active.target(), identity(active.target()), RepositoryFiles.Limits.defaults(), Set.of(), Set.of(), t -> {});
                            try { observer.requireRoot(active.source()); throw new AssertionError("source cwd accepted"); }
                            catch (java.io.IOException expected) { }
                        }
                    } catch (Exception e) { throw new IllegalStateException(e); }
                    var binding = map(turn.get("binding"));
                    var authority = map(data.get("validationAuthority"));
                    var request = Json.object("operationId", "maven-once", "operation", "RUN_VALIDATION",
                            "invocationId", binding.get("invocationId"), "role", role, "authorityId", authority.get("authorityId"));
                    if (Set.of("executable", "arguments", "profileId", "environment", "cwd", "timeoutSeconds", "classpath",
                            "goals", "properties", "profiles", "jvmOptions", "stdoutLimit", "ephemeralScopes").contains(mode))
                        request = replace(request, mode, "ATTACK");
                    if (Set.of("authority-copy", "master-reject").contains(mode)) {
                        authority.put("profileId", "ATTACK"); authority.put("workingRoot", active.source().toString());
                        map(authority.get("trustedProfile")).put("command", List.of("/bin/false"));
                    }
                    return envelope(turn, "TOOL_REQUEST", null, null, request, null, List.of());
                }
                var result = map(data.get("resultContract"));
                if (mode.equals("artifact-profile")) result.put("profileId", "ATTACK");
                return artifact(turn, "VALIDATION_RESULT", result);
            };
            long start = System.nanoTime();
            boolean throughPipeline = Set.of("json", "yaml", "yml", "master-reject").contains(mode);
            var result = throughPipeline
                    ? new ControlledPipeline(f.trusted(), f.input(), new ControlledHarness.Config("mock-model", 16384), mock, resolution(), profile).run()
                    : direct(f, profile, mode, installation);
            if (throughPipeline) check(pipelineValidation[0], "loaded migration input reached pipeline validation");
            check(identity.equals(profile.view()) && command.equals(profile.command(f.target())), "immutable host profile");
            boolean success = Set.of("success", "logs", "authority-copy", "json", "yaml", "yml", "source-cwd", "wrapper", "background-success").contains(mode);
            check(result.status().equals("SUCCESS") == success, mode + ": " + result.status() + " " + result.code());
            boolean launched = success || Set.of("nonzero", "master-reject", "artifact-profile", "timeout", "timeout-replacement", "background-nonzero").contains(mode);
            check(Boolean.valueOf(launched).equals(result.report().get("validationExecuted")), "truthful process launch " + mode);
            if (Set.of("mvn-existing", "mvn-jvm", "mvn-extensions", "host-drift").contains(mode))
                check(result.code().equals("VALIDATION_TRUSTED_PROFILE_UNAVAILABLE"), "profile-specific precheck rejection");
            if (launched) {
                var execution = map(map(result.report().get("validationEvidence")).get("execution"));
                check(execution.get("profileId").equals("HOST_MAVEN_TEST"), "host execution profile identity");
                check(Files.readString(f.target().resolve("target/proof")).equals("ARGV_CWD_ENV_OK"), "child boundary proof");
                if (success || mode.equals("master-reject")) check(((Number)execution.get("exitCode")).intValue() == 0, "exit success independent of MASTER");
                if (Set.of("nonzero", "background-nonzero").contains(mode)) check(result.status().equals("FAILED") && result.code().equals("VALIDATION_TESTS_FAILED"), "deterministic nonzero");
                if (Set.of("timeout", "timeout-replacement").contains(mode)) {
                    long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
                    check(elapsed >= 60 && elapsed < 80 && result.code().equals("VALIDATION_TIMEOUT")
                            && Boolean.TRUE.equals(execution.get("completed")), "host timeout and parent termination");
                }
                var authority = map(map(result.report().get("validationEvidence")).get("authority"));
                check(authority.get("launchFingerprint").equals(Json.evidenceFingerprint(command)), "exact host executable/argv binding");
                check(map(authority.get("trustedProfile")).get("timeoutSeconds").equals(60), "host timeout unchanged");
                check(authority.get("workingRoot").equals(f.target().toString()), "authorized target cwd");
                if (Set.of("background-success", "background-nonzero", "timeout", "timeout-replacement").contains(mode)) {
                    var tree = map(execution.get("processTree"));
                    check(((Number)tree.get("observedDescendants")).intValue() >= 1, "retained descendant observations");
                    check(!Boolean.TRUE.equals(execution.get("cleanupUnavailable")), "cleanup available");
                    for (String file : mode.equals("timeout-replacement") ? List.of("child-pid", "replacement-pid") : List.of("child-pid")) {
                        long pid = Long.parseLong(Files.readString(f.target().resolve("target/" + file)));
                        check(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "observed descendant terminated: " + file);
                    }
                    if (mode.startsWith("background")) check((System.nanoTime() - start) < java.util.concurrent.TimeUnit.SECONDS.toNanos(15), "normal parent exit and pipe cleanup bounded");
                }
                if (mode.equals("logs")) for (String stream : List.of("stdout", "stderr")) {
                    var capture = map(execution.get(stream));
                    check(((Number)capture.get("retainedBytes")).intValue() == 8192 && Boolean.TRUE.equals(capture.get("truncated")), "bounded stream");
                    check(capture.get("classification").equals("UNTRUSTED_VALIDATION_OUTPUT") && ((List<?>)capture.get("excerpts")).isEmpty(), "untrusted hash-only evidence");
                }
            }
            String report = Json.write(result.report());
            check(!report.contains("UNTRUSTED_LOG_SENTINEL") && !report.contains("host-sentinel-value"), "sanitized diagnostics");
        } finally {
            // A failed lifecycle assertion must not leave a fake child behind in the test host.
            for (String name : List.of("child-pid", "replacement-pid")) {
                Path pidFile = f.target().resolve("target/" + name);
                if (Files.isRegularFile(pidFile)) try {
                    ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                            .ifPresent(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
                } catch (Exception ignored) { /* Assertion failure remains primary. */ }
            }
            Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------"));
        }
    }
    private static ControlledPipeline.Result direct(Fixture f, ValidationProfile profile, String mode, Path installation) throws Exception {
        if (Set.of("mvn-existing", "mvn-jvm", "mvn-extensions").contains(mode)) {
            Files.createDirectory(f.target().resolve(".mvn"));
            Files.writeString(f.target().resolve(".mvn/" + (mode.equals("mvn-jvm") ? "jvm.config" : mode.equals("mvn-extensions") ? "extensions.xml" : "maven.config")), "ATTACK");
        }
        if (mode.equals("wrapper")) Files.writeString(f.target().resolve("mvnw"), "ATTACK: must never be executed");
        var source = new RepositoryFiles(f.source(), identity(f.source()), RepositoryFiles.Limits.defaults(), Set.of(), Set.of(), t -> {});
        var target = new RepositoryFiles(f.target(), identity(f.target()), RepositoryFiles.Limits.defaults(), Set.of(), Set.of(), t -> {}).validationOutputs();
        var s = source.snapshot(Set.of()); var t = target.snapshot(Set.of());
        var store = new ArtifactStore(new Evidence(text -> {}));
        store.accept("MIGRATION_PLAN", "{}", Json.object("runId", "run", "role", TrustedInputs.Role.PLANNING.id), "plan", "accepted-plan", "master-plan");
        for (int i = 1; i <= 4; i++) store.accept("IMPLEMENTATION_RESULT:STEP-00" + i,
                "{\"executable\":\"/bin/false\",\"arguments\":[\"attack\"],\"profileId\":\"ATTACK\"}",
                Json.object("runId", "run", "role", ROLES.get(i - 1).id), "result-" + i, "accepted-" + i, "master-" + i);
        var invocation = new RoleExecutor.Invocation("validation", TrustedInputs.Role.VALIDATION, "accepted-4", Map.of(), List.of(), Map.of());
        var runtime = new ValidationRuntime(profile, f.target(), s, t, "run", invocation, store, source, target, source.validationModes(s), target.validationModes(t));
        var request = Json.object("operationId", "use", "operation", "RUN_VALIDATION", "invocationId", "validation",
                "role", TrustedInputs.Role.VALIDATION.id, "authorityId", runtime.authority().get("authorityId"));
        if (Set.of("executable", "arguments", "profileId", "environment", "cwd", "timeoutSeconds", "classpath",
                "goals", "properties", "profiles", "jvmOptions", "stdoutLimit", "ephemeralScopes").contains(mode)) request = replace(request, mode, "ATTACK");
        if (mode.equals("mvn-config")) { Files.createDirectory(f.target().resolve(".mvn")); Files.writeString(f.target().resolve(".mvn/maven.config"), "ATTACK"); }
        if (mode.equals("target-symlink")) Files.createSymbolicLink(f.target().resolve("escape"), f.source());
        if (mode.equals("host-drift")) Files.writeString(installation.resolve("changed"), "drift");
        if (mode.equals("source-cwd")) rejects(() -> new ValidationRuntime(profile, f.source(), s, t, "run", invocation, store, source, target, source.validationModes(s), target.validationModes(t)));
        if (mode.equals("authority-copy")) {
            var copy = Json.parse(Json.write(runtime.authority())); copy.put("profileId", "ATTACK"); copy.put("workingRoot", f.source().toString());
            map(copy.get("trustedProfile")).put("command", List.of("/bin/false"));
        }
        String status, code;
        try {
            runtime.execute(invocation, request, store);
            var contract = Json.parse(Json.write(runtime.resultContract()));
            if (mode.equals("artifact-profile")) contract.put("profileId", "ATTACK");
            runtime.validateResult(contract);
            status = runtime.status(); code = runtime.code();
            check(store.views().size() == 5, "process evidence does not accept VALIDATION_RESULT or MASTER success");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            status = runtime.started() ? "FAILED" : "BLOCKED"; code = runtime.code();
        }
        return new ControlledPipeline.Result(status, code, Json.object("validationExecuted", runtime.started(), "validationEvidence", runtime.facts()));
    }
    private static String identity(Path path) throws Exception {
        var attrs = Files.readAttributes(path, "unix:dev,ino");
        return "POSIX:" + attrs.get("dev") + ":" + attrs.get("ino");
    }
    private static Path installation(String mode, Path target) throws Exception {
        Path home = Files.createTempDirectory(temp, "host maven-$()-");
        Files.createDirectory(home.resolve("boot")); Files.createDirectory(home.resolve("lib"));
        Path build = Files.createTempDirectory(temp, "fake-bootstrap-");
        String source = """
                package org.codehaus.plexus.classworlds.launcher;
                import java.nio.file.*;
                import java.util.*;
                public class Launcher {
                    public static void main(String[] args) throws Exception {
                        if (args.length == 1 && args[0].equals("child")) { Thread.sleep(120000); return; }
                        Path root = Path.of("").toRealPath();
                        if (!Path.of(ProcessHandle.current().info().command().orElseThrow()).toRealPath().toString().equals(EXPECTED_JAVA)
                                || !System.getProperty("java.class.path").equals(EXPECTED_BOOT)
                                || !root.toString().equals(EXPECTED_ROOT) || !new java.util.HashSet<>(System.getenv().keySet()).stream().allMatch(k -> k.equals("__CF_USER_TEXT_ENCODING"))
                                || System.getProperty("host.environment.canary") != null || System.getProperty("host.jdk.canary") != null || System.getProperty("host.legacy.canary") != null)
                            throw new AssertionError("boundary");
                        Path work = root.resolve("target/.harness-maven");
                        if (!System.getProperty("user.home").equals(work.toString())
                                || !System.getProperty("java.io.tmpdir").equals(work.resolve("tmp").toString())
                                || !System.getProperty("maven.multiModuleProjectDirectory").equals(root.toString())) throw new AssertionError("properties");
                        List<String> expected = List.of("--batch-mode", "--offline", "--no-transfer-progress", "--strict-checksums",
                                "--settings", work.resolve("settings.xml").toString(), "--global-settings", work.resolve("settings.xml").toString());
                        if (args.length != 11 || !Arrays.asList(args).subList(0, 8).equals(expected)
                                || !args[8].startsWith("-Dmaven.repo.local=") || !args[9].equals("-Dstyle.color=never") || !args[10].equals("test"))
                            throw new AssertionError("argv");
                        Files.writeString(root.resolve("target/proof"), "ARGV_CWD_ENV_OK");
                        if (MODE.equals("nonzero")) { System.err.println("UNTRUSTED_LOG_SENTINEL /private/host/secret"); System.exit(17); }
                        if (MODE.equals("logs")) {
                            for (int i = 0; i < 4096; i++) { System.out.println("UNTRUSTED_LOG_SENTINEL"); System.err.println("UNTRUSTED_LOG_SENTINEL"); }
                        }
                        if (Set.of("timeout", "timeout-replacement", "background-success", "background-nonzero").contains(MODE)) {
                            var child = child();
                            Files.writeString(root.resolve("target/child-pid"), Long.toString(child.pid()));
                            if (MODE.startsWith("background")) {
                                Thread.sleep(500); // Multiple host discovery polls before deliberate parent exit.
                                System.exit(MODE.equals("background-nonzero") ? 17 : 0);
                            }
                            if (MODE.equals("timeout-replacement")) {
                                // Replace the child before timeout; the deterministic lifecycle test
                                // covers replacement specifically during cleanup.
                                child.destroyForcibly();
                                child.waitFor();
                                var replacement = child();
                                Files.writeString(root.resolve("target/replacement-pid"), Long.toString(replacement.pid()));
                            }
                            Thread.sleep(120000);
                        }
                    }
                    private static Process child() throws Exception {
                        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp", System.getProperty("java.class.path"), Launcher.class.getName(), "child").inheritIO().start();
                    }
                }
                """.replace("EXPECTED_ROOT", Json.write(target.toString()))
                .replace("EXPECTED_JAVA", Json.write(Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString()))
                .replace("EXPECTED_BOOT", Json.write(home.resolve("boot/plexus-classworlds-fixture.jar").toString()))
                .replace("MODE", Json.write(mode));
        Path java = build.resolve("Launcher.java"); Files.writeString(java, source);
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", build.toString(), java.toString()) == 0, "compile fake host bootstrap");
        String entry = "org/codehaus/plexus/classworlds/launcher/Launcher.class";
        try (var jar = new JarOutputStream(Files.newOutputStream(home.resolve("boot/plexus-classworlds-fixture.jar")))) {
            jar.putNextEntry(new JarEntry(entry)); jar.write(Files.readAllBytes(build.resolve(entry))); jar.closeEntry();
        }
        return home;
    }
    private static void paths() throws Exception {
        Fixture f = fixture(temp); Path home = installation("success", f.target());
        Path cache = Files.createTempDirectory(temp, "unsafe-cache-");
        rejects(() -> ValidationProfile.mavenTest(home, cache)); // Writable cache cannot gain write authority.
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("r-x------"));
        try {
            Path alias = temp.resolve("maven-alias"); Files.createSymbolicLink(alias, home);
            rejects(() -> ValidationProfile.mavenTest(alias, cache));
            rejects(() -> ValidationProfile.mavenTest(home.resolve("../" + home.getFileName()), cache));
            rejects(() -> ValidationProfile.mavenTest(temp.resolve("absent"), cache));
            var profile = ValidationProfile.mavenTest(home, cache);
            rejects(() -> profile.verifyRoots(home, f.target()));
            Path targetAlias = temp.resolve("target-alias"); Files.createSymbolicLink(targetAlias, f.target());
            rejects(() -> profile.verifyRoots(f.source(), targetAlias));
            rejects(() -> profile.verifyRoots(f.source(), f.target().resolve("pom.xml")));
            Files.createLink(home.resolve("boot/linked.jar"), home.resolve("boot/plexus-classworlds-fixture.jar"));
            rejects(() -> ValidationProfile.mavenTest(home, cache));
        } finally { Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------")); }
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("unsafe host path accepted"); }
        catch (IllegalStateException | java.io.IOException expected) { }
    }
}
