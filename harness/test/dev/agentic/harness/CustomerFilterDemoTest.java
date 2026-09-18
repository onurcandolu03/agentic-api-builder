package dev.agentic.harness;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;

/** One realistic offline pipeline run: real Spring/JUnit compilation, mocked provider only. */
public final class CustomerFilterDemoTest {
    public static void main(String[] args) throws Exception {
        Path temp = Path.of(args[0]).toRealPath(), repository = Path.of(args[1]).toRealPath();
        var fixture = new CustomerFilterDemoFixture(temp, repository);
        var accepted = fixture.accepted();
        for (var role : List.of(TrustedInputs.Role.REQUIREMENT_ANALYSIS, TrustedInputs.Role.ANALYSIS, TrustedInputs.Role.OPERATION_PLANNING)) {
            String artifact = switch (role) { case REQUIREMENT_ANALYSIS -> "OPERATION_REQUIREMENT"; case ANALYSIS -> "TARGET_ANALYSIS"; default -> "OPERATION_PLAN"; };
            ArtifactContracts.validate(role, accepted.get(artifact), fixture.input, accepted);
        }
        Path home = Path.of(System.getenv().getOrDefault("HARNESS_DEMO_MAVEN_HOME",
                "/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3"));
        Path sourceCache = Path.of(System.getenv().getOrDefault("HARNESS_DEMO_MAVEN_CACHE", System.getProperty("user.home") + "/.m2/repository"));
        Path cache = Files.createDirectory(temp.resolve("demo-offline-cache"));
        Thread cleanup = new Thread(() -> { try { writableCache(cache); } catch (Exception ignored) { } }, "demo-cache-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        Path reportPath = Files.createTempFile(temp.getParent(), "customer-filter-demo-result-", ".json");
        try {
            copyCache(sourceCache, cache);
            var required = new ArrayList<>(CustomerFilterDemoFixture.TEST_NAMES.stream().map(n -> "demo.customer.AddCustomerFilterTest#" + n).toList());
            required.add("demo.customer.ExistingCustomerFilterTest#existingHierarchyIsOrdered");
            var profile = ValidationProfile.mavenOperationTest(home, cache, Json.fingerprint("OPERATION_PLAN", accepted.get("OPERATION_PLAN")), Map.of("TEST-001", required));
            Mock mock = new Mock(); var validationInput = new LinkedHashMap<String,Object>();
            String[] lastRole = {""};
            mock.answer = turn -> {
                String role = (String)map(turn.get("binding")).get("role");
                if (!role.equals(lastRole[0])) { System.out.println("Demo role: " + role); lastRole[0] = role; }
                if (role.equals("07-validation")) validationInput.putAll(data(turn));
                return fixture.answer(turn);
            };
            System.out.println("Running customer-filter demo through the controlled pipeline; real offline Maven, mocked provider.");
            var result = new ControlledPipeline(repository, fixture.input, new ControlledHarness.Config("mock-model", 16384), mock, fixture.policy(accepted), profile).run();
            Files.write(reportPath, Json.bytes(result.report()));
            if (result.code().equals("VALIDATION_REPORT_EVIDENCE_UNAVAILABLE")) {
                // Diagnose only this synthetic fixture's report gate, without changing pipeline status or evidence.
                var effects = map(map(result.report().get("validationEvidence")).get("effects"));
                var entries = ((List<?>)effects.get("targetChanges")).stream().map(RuntimeTest::map)
                        .map(change -> map(change.get("after"))).toList();
                try { OperationTestEvidence.read(fixture.target, new RepositoryFiles.Snapshot("demo", entries, List.of(), List.of()), profile.operationTests()); }
                catch (Exception rejected) { rejected.printStackTrace(System.err); }
            }
            check(result.status().equals("SUCCESS"), result.status() + " " + result.code() + "; report=" + reportPath + "; roles=" + mock.roles().stream().distinct().toList());
            check(result.code().equals("VALIDATION_MASTER_ACCEPTED"), "MASTER final acceptance");
            check(result.report().get("workflow").equals("NEW_OPERATION"), "explicit workflow");
            check(mock.roles().stream().filter(r -> !r.equals("MASTER")).distinct().toList().equals(fixture.input.workflow().route()), "exact full route");
            check(mock.roles().stream().filter("07-validation"::equals).count() == 1, "07 exactly once");
            check(mock.roles().getLast().equals("MASTER"), "MASTER final role");
            check(((List<?>)result.report().get("acceptedArtifacts")).size() == 8, "all eight artifacts retained");
            check(result.report().get("runAuthorityBundleFingerprint") != null, "implementation authority retained");
            check(((List<?>)result.report().get("implementationLedger")).size() >= 4, "stage effects retained");
            check(map(result.report().get("gateArtifacts")).get("sourceCheck") == null, "no source gate");
            var authority = map(validationInput.get("validationAuthority"));
            check(!Json.write(authority).contains("SOURCE_ANALYSIS") && !Json.write(authority).contains("MIGRATION_PLAN"), "no fabricated source lineage");
            var context = map(authority.get("operationContext"));
            check(map(context.get("lineage")).equals(OperationImplementation.lineage(fixture.input, accepted)), "exact operation lineage");
            check(((List<?>)context.get("implementationResults")).size() == 4, "four accepted predecessors");
            var plan = Json.parse(MigrationInput.utf8(accepted.get("OPERATION_PLAN")));
            check(map(context.get("obligations")).get("placement").equals(plan.get("placement")), "exact placement/consistency obligations");
            check(((List<?>)validationInput.get("availableOperations")).isEmpty(), "07 no tools");
            var changed = new TreeSet<String>();
            for (Object item : (List<?>)map(result.report().get("finalRepositoryEffects")).get("authorizedMutations")) {
                var mutation = map(item); changed.add((String)map(mutation.get("grant")).get("pathKey"));
            }
            check(changed.equals(fixture.approved.keySet()), "exact host-observed changed files: " + changed);
            for (var entry : fixture.approved.entrySet()) check(Files.readString(fixture.target.resolve(entry.getKey())).equals(entry.getValue()), "approved output " + entry.getKey());
            for (var entry : fixture.original.entrySet()) if (!fixture.approved.containsKey(entry.getKey()))
                check(Files.readString(fixture.target.resolve(entry.getKey())).equals(entry.getValue()), "REUSE/other files unchanged " + entry.getKey());
            var facts = map(result.report().get("validationEvidence"));
            var execution = map(facts.get("execution"));
            check(Boolean.TRUE.equals(execution.get("completed")) && Integer.valueOf(0).equals(execution.get("exitCode"))
                    && Boolean.FALSE.equals(execution.get("timedOut")), "host Maven completed with exit 0 and no timeout");
            var tests = map(facts.get("testEvidence"));
            check(tests.get("status").equals("SUCCESS"), "Surefire evidence");
            var observed = map(tests.get("observedTests"));
            check(observed.size() == 5 && observed.values().stream().allMatch("PASSED"::equals), "four new plus one existing JUnit tests pass");
            check(Boolean.TRUE.equals(map(facts.get("effects")).get("targetImplementationUnchanged")), "validation only build effects");
            String order = Files.readString(fixture.target.resolve("target/customer-filter-order.txt"));
            check(order.equals("1. Customer Segment\n2. Customer Title\n3. New Filter\n4. Customer Credit Score\n5. Customer Age\n"), "executed insertion order");
            var summary = Json.object("workflow", "NEW_OPERATION", "operation", "INSERT", "status", result.status(), "code", result.code(),
                    "roles", mock.roles().stream().filter(r -> !r.equals("MASTER")).distinct().toList(), "lineage", context.get("lineage"),
                    "implementationAuthority", result.report().get("runAuthorityBundleFingerprint"), "componentDecisions", CustomerFilterDemoFixture.DECISIONS,
                    "changedFiles", new ArrayList<>(changed), "placement", plan.get("placement"), "observedOrder", order, "observedTests", observed,
                    "maven", execution,
                    "agent07", "SUCCESS", "master", "VALIDATION_MASTER_ACCEPTED", "fullEvidenceReport", reportPath.toString());
            System.out.println(Json.write(summary));
            System.out.println("PASS customer-filter-demo: 1 E2E scenario, 5 real JUnit tests passed, 0 failures; offline mocked provider");
        } finally {
            // Only the disposable copy is made writable for test.sh cleanup; never alter the user's cache.
            writableCache(cache);
            Runtime.getRuntime().removeShutdownHook(cleanup);
        }
    }
    private static void writableCache(Path cache) throws Exception {
        try (var paths = Files.walk(cache)) { for (Path path : paths.toList())
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------")); }
    }
    private static void copyCache(Path source, Path target) throws Exception {
        long bytes = 0; int count = 0;
        try (var paths = Files.walk(source)) {
            for (Path path : paths.limit(8193).sorted().toList()) {
                if (++count > 8192 || Files.isSymbolicLink(path)) throw new IllegalArgumentException("DEMO_CACHE_UNSAFE");
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    if (!Files.isRegularFile(path) || (bytes += Files.size(path)) > 1_073_741_824L) throw new IllegalArgumentException("DEMO_CACHE_LIMIT");
                    Files.copy(path, destination);
                }
            }
        }
        try (var paths = Files.walk(target)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "r-x------" : "r--------")); }
    }
}
