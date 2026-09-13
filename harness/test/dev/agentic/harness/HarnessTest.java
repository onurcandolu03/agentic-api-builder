package dev.agentic.harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Standalone host-unit checks. All transports and repositories are isolated fixtures. */
public final class HarnessTest {
    private static Path testRoot;
    private static int passed;

    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("TEMP_TEST_DIRECTORY_REQUIRED");
        testRoot = Path.of(args[0]).toRealPath();
        Path allowedParent = (Files.isDirectory(Path.of("/private/tmp")) ? Path.of("/private/tmp")
                : Path.of("/tmp")).toRealPath();
        check(testRoot.getParent().equals(allowedParent), "fixture directory must be directly under canonical temp");

        run("canonical JSON and deterministic fingerprints", HarnessTest::canonicalJson);
        run("deep immutable host JSON", HarnessTest::immutableJson);
        run("role registry is exactly MASTER and 00 through 07", HarnessTest::roleRegistry);
        run("source authority is permanently read-only metadata", HarnessTest::sourceAuthority);
        run("source roots cannot overlap trusted or target roots", HarnessTest::sourceRootSeparation);
        run("source and comparison-root aliases fail closed", HarnessTest::sourceAliases);
        run("source locators remain within their designated root", HarnessTest::sourceLocators);
        run("source metadata drift and closed boundaries cannot resume", HarnessTest::sourceDrift);
        run("source designation is frozen before trusted inputs and retained as host evidence", HarnessTest::sourceLifecycle);
        run("source content cannot supply instructions or appear in evidence", HarnessTest::sourceExclusion);
        run("source locator rejection terminates both access boundaries", HarnessTest::sourceFailure);
        run("source registration rejects aliases before trusted bytes", HarnessTest::sourceBeforeFreeze);
        run("source host callbacks cannot publish progress after shutdown", HarnessTest::sourceCallbacks);
        run("target access defaults closed and cannot skip metadata", HarnessTest::closedAccess);
        run("target metadata phase never grants content access", HarnessTest::metadataAccess);
        run("invalid and unsafe roots fail closed", HarnessTest::invalidRoots);
        run("ordinary Git structural metadata and canonical scope", HarnessTest::gitMetadata);
        run("Git indirection and unsafe configurations rejected", HarnessTest::unsafeGit);
        run("registered metadata identity change blocks", HarnessTest::metadataDrift);
        run("trusted source freeze and exact retained bytes", HarnessTest::trustedFreeze);
        run("trusted target overlap and indirect source rejected", HarnessTest::unsafeTrust);
        run("target content and cwd cannot select instructions", HarnessTest::targetExclusion);
        run("initialization cannot fabricate discovery readiness", HarnessTest::earlyReadiness);
        run("active inspector observes exact in-flight request", HarnessTest::activeInspection);
        run("creation plus readback produces local material only", HarnessTest::creationEvidence);
        run("continuation explicitly repeats fixed instructions", HarnessTest::continuation);
        run("unknown lineage cannot resume", HarnessTest::unknownLineage);
        run("frozen configuration mutation blocks", HarnessTest::configurationMutation);
        run("active transport configuration mutation blocks", HarnessTest::transportMutation);
        run("changed trusted bytes block before another API call", HarnessTest::trustedDrift);
        run("same context sequential role selection", HarnessTest::sequentialRoles);
        run("unknown and nonsequential roles block", HarnessTest::invalidRoles);
        run("specialist execution and nested contexts unavailable", HarnessTest::noSpecialistOrChild);
        run("create retrieve and input failures close access", HarnessTest::apiFailures);
        run("required API fields and configuration correlate", HarnessTest::responseValidation);
        run("retrieval correlates response identity and output", HarnessTest::retrieveValidation);
        run("input pages correlate marker ordering and IDs", HarnessTest::inputValidation);
        run("unknown and out-of-order input history rejected", HarnessTest::unexpectedInputHistory);
        run("retained input history can span ascending pages", HarnessTest::retainedInputHistory);
        run("terminal inspection does not expose rejected configuration", HarnessTest::unsafeInspection);
        run("assembly and evidence callback failures close the harness", HarnessTest::callbackFailures);
        run("target aliases are rejected before trusted bytes are loaded", HarnessTest::targetAliasBeforeFreeze);
        run("discovery evidence uses one validated configuration snapshot", HarnessTest::discoveryConfiguration);
        run("malformed response output blocks", HarnessTest::malformedOutput);
        run("in-flight shutdown cannot be overwritten by a response", HarnessTest::inFlightShutdown);
        run("readback preserves partial evidence after failure", HarnessTest::partialEvidence);
        run("HTTP mock receives exact supported REST requests", HarnessTest::httpBoundary);
        run("HTTP errors invalid IDs UTF8 and size fail safely", HarnessTest::httpFailures);
        run("raw escaped and Unicode credentials never retained", HarnessTest::credentialSafety);
        run("caller observations cannot replace host evidence", HarnessTest::evidenceIntegrity);
        run("response identity reuse cannot create a second lineage link", HarnessTest::responseIdReuse);
        run("invalid transitions and closed contexts cannot reopen", HarnessTest::reopenDenial);
        run("repeated freeze hardlinks and invalid UTF8 reject", HarnessTest::freezeRejection);
        run("local readiness never grants read mutation or validation", HarnessTest::readyAccessDenial);
        run("corrupted retained lineage and evidence block", HarnessTest::corruptedState);
        run("structured escaped and malformed secret payloads retain no evidence", HarnessTest::structuredSecretRejection);
        run("credential field names with empty structure remain inspectable", HarnessTest::emptyCredentialStructures);
        run("credential ancestry rejects values inside schema-like containers", HarnessTest::credentialStructureValues);
        run("schema-like secrets never enter response or item evidence", HarnessTest::credentialStructureRetention);
        run("candidate configuration rejected before fingerprint retention", HarnessTest::unsafeCandidateConfiguration);
        run("evidence callbacks cannot replace validated candidate bytes", HarnessTest::evidenceCallbackMutation);
        run("embedded JSON secrets reject through public harness before retention", HarnessTest::embeddedSecretRejection);
        run("ordinary braces and harmless embedded JSON remain usable", HarnessTest::harmlessEmbeddedJson);
        run("embedded JSON inspection bounds fail closed", HarnessTest::embeddedInspectionLimits);
        run("ambiguous quote boundaries cannot hide encoded credentials", HarnessTest::ambiguousQuoteCredentials);
        run("successful close callbacks cannot publish lifecycle progress", HarnessTest::successfulCloseCallbacks);
        run("swallowed reentrant lifecycle failures cannot dispatch", HarnessTest::successfulReentrantCallbacks);
        run("output IDs cannot be reused across fresh responses", HarnessTest::crossResponseOutputIdentity);
        run("input identities cannot reinterpret current or historical content", HarnessTest::crossResponseInputIdentity);
        run("duplicate output IDs reject while fresh omitted-history continuation works", HarnessTest::freshIdentityControl);
        System.out.println("PASS " + passed + " harness unit checks; mocked API only");
    }

    private static void run(String name, Check body) throws Exception {
        try { body.run(); passed++; }
        catch (Throwable failure) { throw new AssertionError("Harness check failed: " + name, failure); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T extends Throwable> T expect(Class<T> type, Check body) throws Exception {
        try { body.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError("Unexpected exception type", failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static final class Fixture {
        final Path root = Files.createTempDirectory(testRoot, "case-");
        final Path trusted = Files.createDirectory(root.resolve("trusted"));
        final Path target = Files.createDirectory(root.resolve("target"));
        final Path source = Files.createDirectory(root.resolve("source"));
        Fixture() throws IOException {
            write(trusted.resolve("agents/contracts/orchestration-contract.md"), "Trusted contract fixture.\n");
            for (var role : TrustedInputs.Role.values())
                write(trusted.resolve(role.location), "Trusted fixture specification for " + role.id + ".\n");
        }
        ControlledHarness harness(ResponsesClient client) {
            return new ControlledHarness(trusted, target, new ControlledHarness.Config("fixture-model-v1", 128), client);
        }
        ControlledHarness sourceHarness(ResponsesClient client) {
            return new ControlledHarness(trusted, source, target,
                    new ControlledHarness.Config("fixture-model-v1", 128), client);
        }
        ControlledHarness registered(Mock client) {
            var harness = harness(client);
            harness.freezeTrustedInputs();
            harness.registerTargetMetadata();
            return harness;
        }
        ControlledHarness created(Mock client) {
            var harness = registered(client);
            harness.createMasterContext();
            return harness;
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void git(Path target) throws IOException {
        Files.createDirectories(target.resolve(".git/objects"));
        Files.createDirectories(target.resolve(".git/refs"));
        write(target.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        write(target.resolve(".git/config"), "[core]\nrepositoryformatversion = 0\nbare = false\nfilemode = true\n");
    }

    private static final class Mock implements ResponsesClient {
        final List<String> requests = new ArrayList<>();
        int revision;
        String failureStage;
        Consumer<String> duringCreate = ignored -> {};
        UnaryOperator<Map<String, Object>> createdTransform = value -> value;
        UnaryOperator<Map<String, Object>> retrievedTransform = value -> value;
        UnaryOperator<Map<String, Object>> pageTransform = value -> value;
        Map<String, Object> lastRequest;
        Map<String, Object> lastResponse;
        final List<Map<String, Object>> responseHistory = new ArrayList<>();
        java.util.function.Supplier<Map<String, Object>> configurationSource;
        Consumer<String> safetyCheck = Evidence::rejectObviousSecrets;
        java.util.function.BiFunction<String, String, Map<String, Object>> pages;

        @Override public Map<String, Object> configuration() {
            if (configurationSource != null) return configurationSource.get();
            return Json.object("transportKind", "MOCK", "revision", revision);
        }
        @Override public void rejectCredentialMaterial(String text) { safetyCheck.accept(text); }
        @Override public String create(String body) throws Exception {
            requests.add(body);
            lastRequest = Json.parse(body);
            duringCreate.accept(body);
            fail("CREATE");
            var response = new LinkedHashMap<>(lastRequest);
            response.remove("input");
            response.remove("stream");
            response.putAll(Json.object("id", "resp_" + requests.size(), "object", "response",
                    "status", "completed", "created_at", 1, "error", null, "incomplete_details", null,
                    "output", List.of(Json.object("id", "out_" + requests.size(), "status", "completed",
                            "type", "message", "role", "assistant", "content",
                            List.of(Json.object("type", "output_text", "text", "Acknowledged."))))));
            lastResponse = createdTransform.apply(response);
            responseHistory.add(lastResponse);
            return Json.write(lastResponse);
        }
        @Override public String retrieve(String id) throws Exception {
            fail("RETRIEVE");
            check(id.equals(lastResponse.get("id")), "retrieve uses returned response ID");
            return Json.write(retrievedTransform.apply(new LinkedHashMap<>(lastResponse)));
        }
        @Override public String inputItems(String id, String after) throws Exception {
            fail("INPUT");
            check(id.equals(lastResponse.get("id")), "input listing uses returned response ID");
            if (pages != null) return Json.write(pages.apply(id, after));
            var message = map(((List<?>) lastRequest.get("input")).getFirst());
            var item = new LinkedHashMap<>(message);
            item.put("id", "msg_" + requests.size());
            return Json.write(pageTransform.apply(new LinkedHashMap<>(Json.object("object", "list",
                    "data", List.of(item), "first_id", item.get("id"), "last_id", item.get("id"),
                    "has_more", false))));
        }
        private void fail(String stage) throws IOException {
            if (stage.equals(failureStage)) throw new IOException("untrusted upstream diagnostic");
        }
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static void terminal(ControlledHarness harness, String expected) {
        var observation = harness.inspect();
        check(expected.equals(observation.get("state")), "terminal state");
        check("CLOSED".equals(observation.get("targetAccessPhase")), "terminal access is closed");
    }

    private static void canonicalJson() throws Exception {
        var value = Json.object("z", "\u001b", "a", "é🧪");
        check(Json.write(value).equals("{\"z\":\"\\u001b\",\"a\":\"é🧪\"}"), "ordered lowercase canonical JSON");
        check(Json.parse(Json.write(value)).equals(value), "Unicode roundtrip");
        var first = Json.evidenceFingerprint(value);
        check(first.equals(Json.evidenceFingerprint(value)), "deterministic fingerprint");
        check(first.get("byteLength").equals(Json.bytes(value).length), "UTF8 byte count");
        check(!first.equals(Json.evidenceFingerprint(Json.object("a", "é🧪", "z", "\u001b"))), "schema order matters");
        expect(RuntimeException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        expect(RuntimeException.class, () -> Json.parse("{} {}"));
        expect(RuntimeException.class, () -> Json.write("\ud800"));
        expect(RuntimeException.class, () -> Json.identifier("e\u0301"));
        expect(RuntimeException.class, () -> Json.write(1.5));
    }

    private static void immutableJson() throws Exception {
        var nested = new ArrayList<Object>();
        nested.add("initial");
        var object = Json.object("nested", nested);
        nested.add("external mutation");
        check(((List<?>) object.get("nested")).size() == 1, "nested value is retained immutably");
        expect(UnsupportedOperationException.class, () -> map(object).put("extra", true));
        expect(UnsupportedOperationException.class, () -> ((List<?>) object.get("nested")).clear());
    }

    private static void roleRegistry() throws Exception {
        List<String> expected = List.of("MASTER", "00-source-analysis", "01-target-analysis", "02-migration-planning",
                "03-domain-contract-implementation", "04-persistence-mapping-implementation",
                "05-service-api-implementation", "06-test-implementation", "07-validation");
        check(Arrays.stream(TrustedInputs.Role.values()).map(role -> role.id).toList()
                .equals(expected), "exact registry in deterministic assembly order");
        check(TrustedInputs.Role.values().length == 9, "no duplicate roles");
        var sourceRole = TrustedInputs.Role.find("00-source-analysis");
        check(sourceRole == TrustedInputs.Role.SOURCE_ANALYSIS, "first-class source enum role");
        check(sourceRole.location.equals("agents/00-source-analysis.md"), "exact source role path");
        check(sourceRole.artifactRole.equals("AGENT_00_SPECIFICATION"), "exact source artifact role");
        expect(IllegalArgumentException.class, () -> TrustedInputs.Role.find("unregistered"));
        expect(IllegalArgumentException.class, () -> TrustedInputs.Role.find("agents/00-source-analysis.md"));
    }

    private static SourceBoundary sourceBoundary(Fixture fixture) throws Exception {
        var boundary = new SourceBoundary();
        boundary.register(fixture.source, fixture.trusted, fixture.target);
        return boundary;
    }

    private static void sourceAuthority() throws Exception {
        check(Arrays.asList(SourceBoundary.Authority.values()).equals(List.of(SourceBoundary.Authority.READ_ONLY)),
                "source authority cannot represent mutation or target phases");
        check(Arrays.asList(SourceBoundary.State.values()).equals(List.of(SourceBoundary.State.CLOSED,
                SourceBoundary.State.METADATA_ONLY)), "no source content capability is implemented");
        var boundary = new SourceBoundary();
        check(boundary.state() == SourceBoundary.State.CLOSED && boundary.metadata() == null, "source starts closed");
        expect(IOException.class, () -> boundary.inspectLocator(Path.of("Example.java")));
        var fixture = new Fixture();
        var source = sourceBoundary(fixture);
        var target = new TargetBroker();
        target.inspect(fixture.target);
        check(source.authority() == SourceBoundary.Authority.READ_ONLY, "read-only designation");
        check(Boolean.FALSE.equals(source.metadata().view().get("contentAccessImplemented")), "no fabricated source reads");
        expect(IOException.class, () -> target.request(TargetBroker.Phase.MUTATION_ALLOWED));
        source.verify();
        check(source.state() == SourceBoundary.State.METADATA_ONLY, "target phase never alters source authority");
    }

    private static void sourceRootSeparation() throws Exception {
        var fixture = new Fixture();
        for (Path source : List.of(fixture.trusted, fixture.target, fixture.root,
                Files.createDirectory(fixture.trusted.resolve("nested")),
                Files.createDirectory(fixture.target.resolve("nested")))) {
            var boundary = new SourceBoundary();
            expect(IOException.class, () -> boundary.register(source, fixture.trusted, fixture.target));
            check(boundary.state() == SourceBoundary.State.CLOSED, "overlap closes source boundary");
        }
        for (boolean containsTarget : List.of(false, true)) {
            var next = new Fixture();
            Path nested = Files.createDirectory(next.source.resolve("nested"));
            var boundary = new SourceBoundary();
            expect(IOException.class, () -> boundary.register(next.source,
                    containsTarget ? next.trusted : nested, containsTarget ? nested : next.target));
        }
    }

    private static void sourceAliases() throws Exception {
        var fixture = new Fixture();
        Path file = fixture.root.resolve("file");
        write(file, "fixture");
        Path alias = Files.createSymbolicLink(fixture.root.resolve("source-alias"), fixture.source);
        for (Path source : List.of(Path.of("relative"), Path.of("/"), fixture.root.resolve("missing"),
                fixture.source.resolve(".."), alias, file)) {
            var boundary = new SourceBoundary();
            expect(IOException.class, () -> boundary.register(source, fixture.trusted, fixture.target));
        }
        for (boolean trustedAlias : List.of(false, true)) {
            Path other = Files.createSymbolicLink(fixture.root.resolve("alias-" + trustedAlias),
                    trustedAlias ? fixture.trusted : fixture.target);
            var boundary = new SourceBoundary();
            expect(IOException.class, () -> boundary.register(fixture.source,
                    trustedAlias ? other : fixture.trusted, trustedAlias ? fixture.target : other));
        }
        Path ancestorAlias = Files.createSymbolicLink(fixture.root.resolve("parent-alias"), fixture.root);
        expect(IOException.class, () -> new SourceBoundary().register(ancestorAlias.resolve("source"),
                fixture.trusted, fixture.target));
        Path caseAlias = fixture.root.resolve("SOURCE");
        if (Files.exists(caseAlias)) {
            check(Files.isSameFile(caseAlias, fixture.source), "conditional fixture case alias");
            expect(IOException.class, () -> new SourceBoundary().register(fixture.source, fixture.trusted, caseAlias));
            Path nested = Files.createDirectory(fixture.source.resolve("nested"));
            expect(IOException.class, () -> new SourceBoundary().register(caseAlias, fixture.trusted, nested));
            expect(IOException.class, () -> new SourceBoundary().register(nested, fixture.trusted, caseAlias));
        }
    }

    private static void sourceLocators() throws Exception {
        var fixture = new Fixture();
        write(fixture.source.resolve("src/Example.java"), "class Example {}\n");
        var boundary = sourceBoundary(fixture);
        var relative = boundary.inspectLocator(Path.of("src/Example.java"));
        check(relative.equals(boundary.inspectLocator(fixture.source.resolve("src/Example.java"))),
                "absolute and relative contained locators produce deterministic metadata");
        check(relative.get("sourceRelativePath").equals("src/Example.java")
                && relative.get("kind").equals("REGULAR_FILE"), "relative evidence identity");
        check(relative.get("contentRead").equals(false), "locator inspection performs no content read");
        check(boundary.inspectLocator(Path.of("src")).get("kind").equals("DIRECTORY"), "module locator supported");
        Files.createSymbolicLink(fixture.source.resolve("outside"), fixture.target);
        Files.createSymbolicLink(fixture.source.resolve("inside"), fixture.source.resolve("src"));
        Files.createLink(fixture.source.resolve("linked.java"), fixture.source.resolve("src/Example.java"));
        write(fixture.source.resolve("scheme:Example.java"), "fixture");
        write(fixture.source.resolve("back\\slash.java"), "fixture");
        for (Path locator : List.of(Path.of("../target"), Path.of("src/../src/Example.java"),
                fixture.target, Path.of("outside"), Path.of("inside/Example.java"),
                Path.of("linked.java"), Path.of("src/Example.java"), Path.of("missing"), Path.of("."), Path.of(""),
                Path.of("scheme:Example.java"), Path.of("back\\slash.java"))) {
            var next = sourceBoundary(fixture);
            expect(IOException.class, () -> next.inspectLocator(locator));
            check(next.state() == SourceBoundary.State.CLOSED, "unsafe locator closes boundary");
            expect(IOException.class, () -> next.register(fixture.source, fixture.trusted, fixture.target));
        }
    }

    private static void sourceDrift() throws Exception {
        var fixture = new Fixture();
        var boundary = sourceBoundary(fixture);
        Files.move(fixture.source, fixture.root.resolve("old-source"));
        Files.createDirectory(fixture.source);
        expect(IOException.class, boundary::verify);
        check(boundary.state() == SourceBoundary.State.CLOSED, "root replacement blocks");
        var closed = sourceBoundary(new Fixture());
        closed.close();
        expect(IOException.class, closed::verify);
    }

    private static void sourceLifecycle() throws Exception {
        var fixture = new Fixture();
        write(fixture.source.resolve("Entry.java"), "class Entry {}\n");
        var mock = new Mock();
        var harness = fixture.sourceHarness(mock);
        check(harness.inspect().get("sourceAccessState").equals("CLOSED"), "host source access initially closed");
        harness.freezeTrustedInputs();
        check(harness.inspect().get("sourceAccessState").equals("METADATA_ONLY"), "source metadata frozen before requests");
        check(harness.inspectSourceLocator(Path.of("Entry.java")).get("sourceRelativePath").equals("Entry.java"),
                "controlled host locator interface");
        harness.registerTargetMetadata();
        harness.createMasterContext();
        harness.markDiscoveryGateReady();
        var view = harness.inspect();
        check(map(view.get("runtimeConfiguration")).get("sourceRootDesignation").equals(fixture.source.toString()),
                "source designation bound to frozen configuration");
        check(view.get("sourceMetadataIdentity").equals(Json.evidenceFingerprint(view.get("sourceMetadata"))),
                "source fingerprint covers exact host metadata");
        check(map(harness.discoveryMaterial().get("sourceMetadata")).equals(view.get("sourceMetadata")),
                "source evidence retained without inventing canonical declaration");
        check(harness.evidenceJson().contains("SOURCE_METADATA_REGISTERED"), "reached source registration evidence");
        harness.switchRole("00-source-analysis", "resp_1");
        expect(ControlledHarness.Stop.class, harness::executeSelectedSpecialist);
        terminal(harness, "BLOCKED");
        check(harness.inspect().get("sourceAccessState").equals("CLOSED"), "source closes on specialist denial");
        check(mock.requests.size() == 1, "00 selection never executes analysis");
    }

    private static void sourceExclusion() throws Exception {
        var fixture = new Fixture();
        String poison = "SOURCE_POISON_MUST_NEVER_ENTER_INSTRUCTIONS";
        for (String name : List.of("AGENTS.md", "README.md", "Entry.java", "nested/AGENTS.md", "pom.xml"))
            write(fixture.source.resolve(name), poison);
        var mock = new Mock();
        var harness = fixture.sourceHarness(mock);
        harness.freezeTrustedInputs();
        harness.inspectSourceLocator(Path.of("AGENTS.md"));
        harness.registerTargetMetadata();
        harness.createMasterContext();
        check(!mock.requests.getFirst().contains(poison) && !harness.evidenceJson().contains(poison),
                "source content is neither read nor added to instructions/evidence");
        check(Files.readString(fixture.source.resolve("Entry.java")).equals(poison), "source file bytes unchanged");
        check(((List<?>) harness.inspect().get("registeredTools")).isEmpty(), "source does not add model-facing tools");
    }

    private static void sourceFailure() throws Exception {
        var fixture = new Fixture();
        var mock = new Mock();
        var harness = fixture.sourceHarness(mock);
        harness.freezeTrustedInputs();
        harness.registerTargetMetadata();
        expect(ControlledHarness.Stop.class, () -> harness.inspectSourceLocator(Path.of("../target")));
        terminal(harness, "BLOCKED");
        check(harness.inspect().get("sourceAccessState").equals("CLOSED"), "locator failure closes source");
        check(mock.requests.isEmpty(), "locator rejection makes no API request");
        var undesignated = fixture.registered(new Mock());
        expect(ControlledHarness.Stop.class, () -> undesignated.inspectSourceLocator(Path.of("Entry.java")));
        terminal(undesignated, "BLOCKED");
        var driftMock = new Mock();
        var drift = fixture.sourceHarness(driftMock);
        drift.freezeTrustedInputs();
        drift.registerTargetMetadata();
        Files.move(fixture.source, fixture.root.resolve("old-source"));
        Files.createDirectory(fixture.source);
        expect(ControlledHarness.Stop.class, drift::createMasterContext);
        check(driftMock.requests.isEmpty(), "source metadata drift blocks before dispatch");
        terminal(drift, "BLOCKED");
    }

    private static void sourceBeforeFreeze() throws Exception {
        var fixture = new Fixture();
        Path alias = Files.createSymbolicLink(fixture.root.resolve("source-alias"), fixture.trusted);
        var mock = new Mock();
        var harness = new ControlledHarness(fixture.trusted, alias, fixture.target,
                new ControlledHarness.Config("fixture-model-v1", 128), mock);
        expect(ControlledHarness.Stop.class, harness::freezeTrustedInputs);
        check(((List<?>) harness.inspect().get("trustedSourceRegistry")).isEmpty(), "unsafe source cannot load trusted chain");
        check(harness.inspect().get("instructionAssemblyIdentity") == null, "no trusted hash for rejected designation");
        check(mock.requests.isEmpty(), "source alias never reaches provider");
    }

    private static void sourceCallbacks() throws Exception {
        for (boolean duringRegistration : List.of(false, true)) {
            var fixture = new Fixture();
            write(fixture.source.resolve("Entry.java"), "class Entry {}\n");
            var mock = new Mock();
            var harness = fixture.sourceHarness(mock);
            if (!duringRegistration) harness.freezeTrustedInputs();
            mock.safetyCheck = text -> {
                if (text.contains(duringRegistration ? "contentAccessImplemented" : "sourceRelativePath"))
                    harness.close();
            };
            expect(ControlledHarness.Stop.class, () -> {
                if (duringRegistration) harness.freezeTrustedInputs();
                else harness.inspectSourceLocator(Path.of("Entry.java"));
            });
            terminal(harness, "CLOSED");
            check(harness.inspect().get("sourceAccessState").equals("CLOSED"), "successful callback cannot reopen source");
            check(mock.requests.isEmpty(), "source callback close never dispatches");
        }
    }

    private static void closedAccess() throws Exception {
        for (var phase : TargetBroker.Phase.values()) {
            var broker = new TargetBroker();
            check(broker.phase() == TargetBroker.Phase.CLOSED && broker.metadata() == null, "closed default");
            expect(IOException.class, () -> broker.request(phase));
            check(broker.phase() == TargetBroker.Phase.CLOSED, "illegal advance stays closed");
            expect(IOException.class, () -> broker.inspect(new Fixture().target));
        }
    }

    private static void metadataAccess() throws Exception {
        for (var denied : List.of(TargetBroker.Phase.CLOSED, TargetBroker.Phase.READ_ALLOWED,
                TargetBroker.Phase.MUTATION_ALLOWED, TargetBroker.Phase.VALIDATION_ALLOWED)) {
            var broker = new TargetBroker();
            var fixture = new Fixture();
            broker.inspect(fixture.target);
            broker.request(TargetBroker.Phase.METADATA_ONLY);
            expect(IOException.class, () -> broker.request(denied));
            check(broker.phase() == TargetBroker.Phase.CLOSED, "metadata cannot become content authority");
        }
        var broker = new TargetBroker();
        broker.close();
        expect(IOException.class, () -> broker.inspect(new Fixture().target));
    }

    private static void invalidRoots() throws Exception {
        var fixture = new Fixture();
        Path file = fixture.root.resolve("regular-file");
        write(file, "not a directory");
        Path alias = Files.createSymbolicLink(fixture.root.resolve("alias"), fixture.target);
        for (Path root : List.of(Path.of("relative"), fixture.root.resolve("missing"), file, alias,
                fixture.target.resolve(".."), Path.of("/"))) {
            var broker = new TargetBroker();
            expect(IOException.class, () -> broker.inspect(root));
            check(broker.phase() == TargetBroker.Phase.CLOSED, "unsafe root closes broker");
        }
    }

    private static void gitMetadata() throws Exception {
        var fixture = new Fixture();
        git(fixture.target);
        var broker = new TargetBroker();
        var metadata = broker.inspect(fixture.target);
        check(metadata.repositoryKind().equals("GIT"), "ordinary Git recognized");
        check(metadata.gitWorktreeRoot().equals(fixture.target.toString()), "exact worktree root");
        check(metadata.rootFilesystemIdentity().matches("POSIX:(0|[1-9][0-9]*):(0|[1-9][0-9]*)"), "contract POSIX encoding");
        check(new ArrayList<>(metadata.scope().keySet()).equals(List.of("declaredRoot", "resolvedRoot",
                "repositoryKind", "rootFilesystemIdentity")), "contract scope member order");
        broker.verify();
        check(broker.inspect(fixture.target).equals(metadata), "repeat metadata observation");
        var plain = new TargetBroker().inspect(new Fixture().target);
        check(plain.repositoryKind().equals("NON_GIT") && plain.gitWorktreeRoot() == null, "non-Git root");
    }

    private static void unsafeGit() throws Exception {
        for (String setting : List.of("[include]\npath = elsewhere", "[extensions]\nworktreeConfig = true",
                "[core]\nworktree = elsewhere", "[core]\nbare = true")) {
            var fixture = new Fixture();
            git(fixture.target);
            Files.writeString(fixture.target.resolve(".git/config"), setting);
            expect(IOException.class, () -> new TargetBroker().inspect(fixture.target));
        }
        var linked = new Fixture();
        write(linked.target.resolve(".git"), "gitdir: /unavailable/indirection\n");
        expect(IOException.class, () -> new TargetBroker().inspect(linked.target));
        var nested = new Fixture();
        git(nested.target);
        Path child = Files.createDirectory(nested.target.resolve("child"));
        expect(IOException.class, () -> new TargetBroker().inspect(child));
        var bare = new Fixture();
        for (String name : List.of("objects", "refs")) Files.createDirectory(bare.target.resolve(name));
        write(bare.target.resolve("HEAD"), "ref: refs/heads/main\n");
        write(bare.target.resolve("config"), "[core]\nbare = true\n");
        expect(IOException.class, () -> new TargetBroker().inspect(bare.target));
    }

    private static void metadataDrift() throws Exception {
        var fixture = new Fixture();
        var broker = new TargetBroker();
        broker.inspect(fixture.target);
        Files.move(fixture.target, fixture.root.resolve("original-target"));
        Files.createDirectory(fixture.target);
        expect(IOException.class, broker::verify);
        check(broker.phase() == TargetBroker.Phase.CLOSED, "identity replacement closes access");
        var gitFixture = new Fixture();
        git(gitFixture.target);
        var gitBroker = new TargetBroker();
        gitBroker.inspect(gitFixture.target);
        Files.writeString(gitFixture.target.resolve(".git/HEAD"), "ref: refs/heads/other\n");
        expect(IOException.class, gitBroker::verify);
    }

    private static void trustedFreeze() throws Exception {
        var fixture = new Fixture();
        var trusted = TrustedInputs.freeze(fixture.trusted, fixture.target);
        check(trusted.registry().size() == 10, "exact ten trusted sources");
        check(trusted.roleBindings().size() == 9, "nine profile bindings");
        var sortedRoles = Arrays.stream(TrustedInputs.Role.values()).map(r -> r.id).sorted().toList();
        check(trusted.roleBindings().stream().map(r -> r.get("role")).toList().equals(sortedRoles),
                "profile bindings preserve contract role sort including 00");
        var expectedLocations = new ArrayList<String>(List.of("agents/contracts/orchestration-contract.md"));
        expectedLocations.addAll(Arrays.stream(TrustedInputs.Role.values()).map(r -> r.location).toList());
        for (int i = 0; i < expectedLocations.size(); i++) {
            var actual = trusted.registry().get(i);
            Path exactPath = fixture.trusted.resolve(expectedLocations.get(i));
            String artifactRole = i == 0 ? "ORCHESTRATION_CONTRACT" : TrustedInputs.Role.values()[i - 1].artifactRole;
            check(actual.get("resolvedLocation").equals(exactPath.toString()), "fixed chain order and exact path");
            check(actual.get("artifactFingerprint").equals(Json.fingerprint(artifactRole, Files.readAllBytes(exactPath))),
                    "each fingerprint covers exact original UTF8 bytes and artifact role");
        }
        check(trusted.instructions().contains(Files.readString(fixture.trusted.resolve("MASTER.md"))), "exact trusted bytes");
        String frozen = trusted.instructions();
        Files.writeString(fixture.trusted.resolve("agents/00-source-analysis.md"), "Changed trusted source-analysis bytes.");
        check(trusted.instructions().equals(frozen), "frozen payload retained");
        expect(IOException.class, () -> trusted.verify(fixture.target));
    }

    private static void unsafeTrust() throws Exception {
        var fixture = new Fixture();
        expect(IOException.class, () -> TrustedInputs.freeze(fixture.trusted, fixture.root));
        expect(IOException.class, () -> TrustedInputs.freeze(fixture.trusted, fixture.trusted.resolve("nested")));
        Files.delete(fixture.trusted.resolve("MASTER.md"));
        Files.createSymbolicLink(fixture.trusted.resolve("MASTER.md"), fixture.target.resolve("AGENTS.md"));
        write(fixture.target.resolve("AGENTS.md"), "Untrusted target instructions.");
        expect(IOException.class, () -> TrustedInputs.freeze(fixture.trusted, fixture.target));
    }

    private static void targetExclusion() throws Exception {
        var fixture = new Fixture();
        String poison = "TARGET_POISON_MUST_NEVER_ENTER_INSTRUCTIONS";
        for (String name : List.of("AGENTS.md", "MASTER.md", "README.md", "nested/AGENTS.md"))
            write(fixture.target.resolve(name), poison);
        var mock = new Mock();
        var harness = fixture.registered(mock);
        String oldCwd = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", fixture.target.toString());
            harness.createMasterContext();
        } finally { System.setProperty("user.dir", oldCwd); }
        check(!mock.requests.getFirst().contains(poison), "no target content in request");
        check(!harness.evidenceJson().contains(poison), "no target content in host evidence");
    }

    private static void earlyReadiness() throws Exception {
        var fixture = new Fixture();
        for (int stage = 0; stage < 3; stage++) {
            var mock = new Mock();
            var harness = fixture.harness(mock);
            if (stage > 0) harness.freezeTrustedInputs();
            if (stage > 1) harness.registerTargetMetadata();
            expect(ControlledHarness.Stop.class, harness::markDiscoveryGateReady);
            terminal(harness, "BLOCKED");
            check(mock.requests.isEmpty(), "local setup must not make requests");
        }
        var harness = fixture.harness(new Mock());
        expect(ControlledHarness.Stop.class, () -> harness.requestTargetAccess("READ_ALLOWED"));
        terminal(harness, "BLOCKED");
    }

    private static void activeInspection() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().registered(mock);
        var before = harness.inspect();
        mock.duringCreate = body -> {
            var active = harness.inspect();
            check(body.equals(active.get("activeRequestBody")), "inspector reads executed request object");
            check(Boolean.TRUE.equals(active.get("requestInFlight")), "actual execution in-flight state");
            check(before.get("logicalContextId").equals(active.get("logicalContextId")), "same controlled context");
        };
        harness.createMasterContext();
        check(((List<?>) before.get("responseLineage")).isEmpty(), "prior snapshot cannot silently change");
        var active = harness.inspect();
        check(((List<?>) active.get("responseLineage")).size() == 1, "current inspector follows actual lineage");
        expect(UnsupportedOperationException.class, () -> active.put("activeRole", "forged"));
    }

    private static void creationEvidence() throws Exception {
        var harness = new Fixture().created(new Mock());
        check(harness.inspect().get("state").equals("CREATION_EVIDENCE_CAPTURED"), "readback captured");
        harness.markDiscoveryGateReady();
        var material = harness.discoveryMaterial();
        check(material.get("protocolAcceptance").equals("NOT_EVALUATED"), "raw material is not canonical acceptance");
        check(material.get("materialKind").equals("RAW_HOST_DISCOVERY_MATERIAL"), "raw provider material");
        check(harness.inspect().get("targetAccessPhase").equals("METADATA_ONLY"), "readiness grants no content access");
        String evidence = harness.evidenceJson();
        for (String kind : List.of("REQUEST_ASSEMBLED", "RESPONSE_CREATED_READOUT", "RESPONSE_RETRIEVED",
                "INPUT_ITEMS_RETRIEVED", "CREATION_EVIDENCE_CAPTURED", "PROVIDER_OBSERVATION"))
            check(evidence.contains("\"kind\":\"" + kind + "\""), "reached evidence kind retained");
        check(!evidence.contains("\"protocolAcceptance\":\"PASS\""), "no fake protocol PASS");
        check(!material.containsKey("DISCOVERY_CONTROL_CHECK_V1"), "no canonical check fabricated");
    }

    private static void continuation() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().created(mock);
        String context = (String) harness.inspect().get("logicalContextId");
        check(harness.continueMaster("resp_1").equals("resp_2"), "continuation returns next response");
        var first = Json.parse(mock.requests.get(0));
        var second = Json.parse(mock.requests.get(1));
        check(first.get("instructions").equals(second.get("instructions")), "fixed instructions explicitly reapplied");
        check(first.containsKey("instructions") && second.containsKey("instructions"), "instruction field always present");
        check(second.get("previous_response_id").equals("resp_1"), "known previous response carried");
        check(context.equals(harness.inspect().get("logicalContextId")), "logical context retained");
        check(((List<?>) harness.inspect().get("responseLineage")).size() == 2, "both lineage links retained");
    }

    private static void unknownLineage() throws Exception {
        for (String unknown : Arrays.asList(null, "resp_unknown")) {
            var mock = new Mock();
            var harness = new Fixture().created(mock);
            expect(ControlledHarness.Stop.class, () -> harness.continueMaster(unknown));
            terminal(harness, "BLOCKED");
            check(mock.requests.size() == 1, "unknown lineage never sent");
        }
    }

    private static void configurationMutation() throws Exception {
        var harness = new Fixture().harness(new Mock());
        expect(ControlledHarness.Stop.class, () -> harness.replaceConfiguration(new ControlledHarness.Config("other", 64)));
        terminal(harness, "BLOCKED");
        check(Boolean.TRUE.equals(harness.inspect().get("configurationChangedSinceInitialization")), "rejected config mutation visible");
    }

    private static void transportMutation() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().registered(mock);
        mock.revision++;
        expect(ControlledHarness.Stop.class, harness::inspect);
        terminal(harness, "BLOCKED");
        check(Boolean.TRUE.equals(harness.inspect().get("configurationChangedSinceInitialization")), "active client drift observed");
        check(mock.requests.isEmpty(), "changed active transport cannot execute");
    }

    private static void trustedDrift() throws Exception {
        var fixture = new Fixture();
        var mock = new Mock();
        var harness = fixture.created(mock);
        Files.writeString(fixture.trusted.resolve("MASTER.md"), "changed after context creation");
        expect(ControlledHarness.Stop.class, () -> harness.continueMaster("resp_1"));
        terminal(harness, "BLOCKED");
        check(mock.requests.size() == 1, "drift cannot cause another request");
    }

    private static void sequentialRoles() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().created(mock);
        var initial = harness.inspect();
        for (var role : TrustedInputs.Role.values()) {
            if (role == TrustedInputs.Role.MASTER) continue;
            harness.switchRole(role.id, "resp_1");
            check(harness.inspect().get("activeRole").equals(role.id), "actual role selected");
            check(harness.inspect().get("logicalContextId").equals(initial.get("logicalContextId")), "role keeps context");
            check(harness.inspect().get("instructionAssemblyIdentity").equals(initial.get("instructionAssemblyIdentity")), "role keeps assembly");
            harness.switchRole("MASTER", "resp_1");
        }
        check(mock.requests.size() == 1, "selection alone never executes specialist");
        harness.continueMaster("resp_1");
        check(mock.requests.size() == 2, "MASTER resumes same response chain");
    }

    private static void invalidRoles() throws Exception {
        var unknown = new Fixture().created(new Mock());
        expect(ControlledHarness.Stop.class, () -> unknown.switchRole("invented-role", "resp_1"));
        terminal(unknown, "BLOCKED");
        var nonsequential = new Fixture().created(new Mock());
        nonsequential.switchRole("01-target-analysis", "resp_1");
        expect(ControlledHarness.Stop.class, () -> nonsequential.switchRole("02-migration-planning", "resp_1"));
        terminal(nonsequential, "BLOCKED");
    }

    private static void noSpecialistOrChild() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().created(mock);
        harness.switchRole("01-target-analysis", "resp_1");
        expect(ControlledHarness.Stop.class, harness::executeSelectedSpecialist);
        terminal(harness, "BLOCKED");
        check(mock.requests.size() == 1, "agent01 never executed");
        var child = new Fixture().harness(new Mock());
        expect(ControlledHarness.Stop.class, child::createNestedAgent);
        terminal(child, "BLOCKED");
    }

    private static void apiFailures() throws Exception {
        for (String stage : List.of("CREATE", "RETRIEVE", "INPUT")) {
            var mock = new Mock();
            mock.failureStage = stage;
            var harness = new Fixture().registered(mock);
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, "FAILED");
            check(!harness.evidenceJson().contains("untrusted upstream diagnostic"), "upstream exceptions not retained");
            expect(ControlledHarness.Stop.class, harness::markDiscoveryGateReady);
        }
    }

    private static void responseValidation() throws Exception {
        for (String missing : List.of("id", "object", "status", "error", "incomplete_details", "created_at",
                "output", "instructions", "previous_response_id", "metadata", "tools", "model")) {
            var mock = new Mock();
            mock.createdTransform = value -> { value.remove(missing); return value; };
            var harness = new Fixture().registered(mock);
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, "FAILED");
        }
        for (String field : List.of("model", "instructions", "previous_response_id", "tool_choice")) {
            var mock = new Mock();
            mock.createdTransform = value -> { value.put(field, "unexpected"); return value; };
            var harness = new Fixture().registered(mock);
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, "FAILED");
        }
        var tool = new Mock();
        tool.createdTransform = value -> {
            value.put("output", List.of(Json.object("type", "function_call", "name", "spawn_agent")));
            return value;
        };
        expect(ControlledHarness.Stop.class, new Fixture().registered(tool)::createMasterContext);
    }

    private static void retrieveValidation() throws Exception {
        for (String field : List.of("id", "created_at", "output", "instructions")) {
            var mock = new Mock();
            mock.retrievedTransform = value -> {
                value.put(field, field.equals("created_at") ? 2 : field.equals("output") ? List.of() : "different");
                return value;
            };
            var harness = new Fixture().registered(mock);
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, "FAILED");
        }
    }

    private static void inputValidation() throws Exception {
        for (String problem : List.of("first", "last", "marker", "duplicate", "missing", "pagination")) {
            var mock = new Mock();
            mock.pageTransform = page -> {
                switch (problem) {
                    case "first" -> page.put("first_id", "unrelated");
                    case "last" -> page.put("last_id", "unrelated");
                    case "marker" -> page.put("data", List.of(Json.object("id", "msg_1", "type", "message",
                            "role", "user", "content", List.of(Json.object("type", "input_text", "text", "wrong marker")))));
                    case "duplicate" -> page.put("data", List.of(((List<?>) page.get("data")).getFirst(),
                            ((List<?>) page.get("data")).getFirst()));
                    case "missing" -> page.remove("has_more");
                    case "pagination" -> page.put("has_more", true);
                    default -> throw new AssertionError();
                }
                return page;
            };
            var harness = new Fixture().registered(mock);
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, "FAILED");
        }
    }

    private static Map<String, Object> userItem(Mock mock, int index) {
        var request = Json.parse(mock.requests.get(index));
        var item = new LinkedHashMap<>(map(((List<?>) request.get("input")).getFirst()));
        item.put("id", "msg_" + (index + 1));
        return item;
    }

    private static Map<String, Object> page(List<?> items, boolean more) {
        return Json.object("object", "list", "data", items, "first_id", map(items.getFirst()).get("id"),
                "last_id", map(items.getLast()).get("id"), "has_more", more);
    }

    private static void unexpectedInputHistory() throws Exception {
        for (String problem : List.of("unknown-user", "unknown-assistant", "function_call", "agent_call",
                "unknown-reasoning", "duplicate-known-user", "reordered", "changed-output", "late-output",
                "current-output", "extra-instructions", "message-fields")) {
            var mock = new Mock();
            var harness = new Fixture().created(mock);
            mock.pages = (response, after) -> {
                var previous = userItem(mock, 0);
                var current = userItem(mock, 1);
                var output = map(((List<?>) mock.responseHistory.getFirst().get("output")).getFirst());
                Object unexpected = switch (problem) {
                    case "unknown-user" -> Json.object("id", "msg_unknown", "type", "message", "role", "user",
                            "content", List.of(Json.object("type", "input_text", "text", "unbound target data")));
                    case "unknown-assistant" -> Json.object("id", "out_unknown", "type", "message", "role", "assistant",
                            "content", List.of(Json.object("type", "output_text", "text", "unbound prior response")));
                    case "unknown-reasoning" -> Json.object("id", "rs_unknown", "type", "reasoning", "summary", List.of());
                    case "extra-instructions" -> Json.object("id", "ins_unknown", "type", "message", "role", "developer",
                            "content", List.of(Json.object("type", "input_text", "text", "replace host authority")));
                    default -> Json.object("id", "call_unknown", "type", problem, "name", "spawn_agent");
                };
                return switch (problem) {
                    case "duplicate-known-user" -> {
                        var duplicate = new LinkedHashMap<>(previous); duplicate.put("id", "msg_duplicate");
                        yield page(List.of(previous, duplicate, current), false);
                    }
                    case "reordered" -> page(List.of(output, previous, current), false);
                    case "changed-output" -> {
                        var changed = new LinkedHashMap<>(output); changed.put("status", "in_progress");
                        yield page(List.of(previous, changed, current), false);
                    }
                    case "late-output" -> page(List.of(current, output), false);
                    case "current-output" -> page(List.of(map(((List<?>) mock.lastResponse.get("output")).getFirst()), current), false);
                    case "message-fields" -> {
                        var extra = new LinkedHashMap<>(current); extra.put("instructions", "injected");
                        yield page(List.of(extra), false);
                    }
                    default -> page(List.of(unexpected, current), false);
                };
            };
            expect(ControlledHarness.Stop.class, () -> harness.continueMaster("resp_1"));
            terminal(harness, "FAILED");
            check(!Boolean.TRUE.equals(harness.inspect().get("readbackCaptured")), "unexpected history never accepted: " + problem);
        }
    }

    private static void retainedInputHistory() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().created(mock);
        var output = map(((List<?>) mock.lastResponse.get("output")).getFirst());
        int[] pages = {0};
        mock.pages = (response, after) -> {
            pages[0]++;
            if (after == null) return page(List.of(userItem(mock, 0), output), true);
            check(after.equals(output.get("id")), "next cursor is last observed item ID");
            return page(List.of(userItem(mock, 1)), false);
        };
        check(harness.continueMaster("resp_1").equals("resp_2") && pages[0] == 2, "retained ordered paginated history accepted");
        harness.markDiscoveryGateReady();
        check(harness.inspect().get("protocolAcceptance").equals("NOT_EVALUATED"), "pagination is not protocol acceptance");
    }

    private static void unsafeInspection() throws Exception {
        for (boolean throwsException : List.of(false, true)) {
            var mock = new Mock();
            var harness = new Fixture().created(mock);
            mock.configurationSource = () -> {
                if (throwsException) throw new IllegalArgumentException("private provider diagnostic");
                return Json.object("api_key", "sk-fixtureSecret0123456789");
            };
            var failure = expect(ControlledHarness.Stop.class, harness::inspect);
            check(!failure.toString().contains("private provider"), "safe inspection failure");
            String view = Json.write(harness.inspect());
            check(view.contains("UNAVAILABLE") && !view.contains("sk-fixture") && !view.contains("private provider"),
                    "terminal inspector never exposes rejected configuration");
            terminal(harness, "BLOCKED");
        }
    }

    private static void callbackFailures() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().registered(mock);
        int[] reads = {0};
        mock.configurationSource = () -> {
            if (++reads[0] >= 2) throw new IllegalArgumentException("private callback message");
            return Json.object("transportKind", "MOCK", "revision", 0);
        };
        var failure = expect(ControlledHarness.Stop.class, harness::createMasterContext);
        check(failure.getCause() == null && !failure.toString().contains("private callback"), "assembly failure sanitized");
        terminal(harness, "FAILED");
        check(mock.requests.isEmpty(), "assembly failure never executes");
        for (String operation : List.of("ready", "role", "material")) {
            var callbackMock = new Mock();
            var active = new Fixture().created(callbackMock);
            if (operation.equals("material")) active.markDiscoveryGateReady();
            callbackMock.safetyCheck = value -> { throw new IllegalArgumentException("private callback message"); };
            var stopped = expect(ControlledHarness.Stop.class, () -> {
                switch (operation) {
                    case "ready" -> active.markDiscoveryGateReady();
                    case "role" -> active.switchRole("01-target-analysis", "resp_1");
                    default -> active.discoveryMaterial();
                }
            });
            check(!stopped.toString().contains("private callback"), "evidence failure sanitized");
            terminal(active, "BLOCKED");
        }
    }

    private static void targetAliasBeforeFreeze() throws Exception {
        var fixture = new Fixture();
        Path alias = Files.createSymbolicLink(fixture.root.resolve("target-alias"), fixture.trusted);
        expect(IOException.class, () -> TrustedInputs.freeze(fixture.trusted, alias));
        var harness = new ControlledHarness(fixture.trusted, alias,
                new ControlledHarness.Config("fixture-model-v1", 128), new Mock());
        expect(ControlledHarness.Stop.class, harness::freezeTrustedInputs);
        check(((List<?>) harness.inspect().get("trustedSourceRegistry")).isEmpty(), "alias rejected before trusted registration");
        var secretFixture = new Fixture();
        Files.writeString(secretFixture.trusted.resolve("MASTER.md"), "sk-fixtureSecret0123456789");
        var secret = secretFixture.harness(new Mock());
        expect(ControlledHarness.Stop.class, secret::freezeTrustedInputs);
        check(secret.inspect().get("instructionAssemblyIdentity") == null, "rejected secret bytes have no published hash");
        check(!secret.evidenceJson().contains("sk-fixture"), "rejected secret instructions not retained");
    }

    private static void discoveryConfiguration() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().created(mock);
        harness.markDiscoveryGateReady();
        var material = harness.discoveryMaterial();
        check(material.get("effectiveConfigurationFingerprint").equals(Json.evidenceFingerprint(material.get("effectiveConfiguration"))),
                "returned configuration fingerprint binds exact returned value");
        check(map(material.get("currentObservation")).get("runtimeConfiguration").equals(material.get("effectiveConfiguration")),
                "provider observation and material share exact configuration");
        int[] reads = {0};
        mock.configurationSource = () -> Json.object("transportKind", "MOCK", "revision", ++reads[0] >= 3 ? 1 : 0);
        expect(ControlledHarness.Stop.class, harness::discoveryMaterial);
        terminal(harness, "BLOCKED");
        var throwing = new Mock();
        throwing.configurationSource = () -> { throw new IllegalArgumentException("sensitive constructor diagnostic"); };
        var rejected = expect(ControlledHarness.Stop.class, () -> new Fixture().harness(throwing));
        check(rejected.getCause() == null && !rejected.toString().contains("sensitive constructor"), "constructor sanitized");
    }

    private static void malformedOutput() throws Exception {
        for (String missing : List.of("id", "status", "content", "role")) {
            var mock = new Mock();
            mock.createdTransform = response -> {
                var output = new LinkedHashMap<>(map(((List<?>) response.get("output")).getFirst()));
                output.remove(missing);
                response.put("output", List.of(output));
                return response;
            };
            var harness = new Fixture().registered(mock);
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, "FAILED");
        }
    }

    private static void inFlightShutdown() throws Exception {
        for (boolean blocked : List.of(false, true)) {
            var mock = new Mock();
            var harness = new Fixture().registered(mock);
            mock.duringCreate = body -> {
                if (!blocked) harness.close();
                else {
                    try { harness.createNestedAgent(); }
                    catch (ControlledHarness.Stop expected) { /* A transport cannot cure the terminal state. */ }
                }
            };
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, blocked ? "BLOCKED" : "CLOSED");
            check(((List<?>) harness.inspect().get("responseLineage")).isEmpty(), "response cannot reopen a terminal context");
        }
    }

    private static void partialEvidence() throws Exception {
        var mock = new Mock();
        mock.failureStage = "INPUT";
        var harness = new Fixture().registered(mock);
        expect(ControlledHarness.Stop.class, harness::createMasterContext);
        String evidence = harness.evidenceJson();
        check(evidence.contains("RESPONSE_CREATED_READOUT") && evidence.contains("RESPONSE_RETRIEVED"), "reached readbacks retained");
        check(harness.inspect().get("responseId").equals("resp_1"), "known ID survives later failure");
        check(Boolean.FALSE.equals(harness.inspect().get("readbackCaptured")), "failed readback never complete");
    }

    private static void httpBoundary() throws Exception {
        String credential = "unit-fixture-credential";
        var requests = new ArrayList<HttpRequest>();
        var client = new HttpResponsesClient(credential, request -> {
            requests.add(request);
            return new HttpResponsesClient.ExchangeResult(200, "{}".getBytes(StandardCharsets.UTF_8));
        });
        String exact = "{\"model\":\"fixture\",\"instructions\":\"é🧪\"}";
        client.create(exact);
        client.retrieve("resp_fixture");
        client.inputItems("resp_fixture", "msg_cursor");
        check(requests.get(0).method().equals("POST") && body(requests.get(0)).equals(exact), "exact UTF8 request body");
        check(requests.get(0).uri().toString().equals("https://api.openai.com/v1/responses"), "public create endpoint");
        check(requests.get(1).uri().getPath().equals("/v1/responses/resp_fixture"), "public retrieval endpoint");
        check(requests.get(2).uri().getQuery().equals("order=asc&limit=100&after=msg_cursor"), "bounded ordered input listing");
        check(requests.get(0).headers().firstValue("Authorization").orElseThrow().equals("Bearer " + credential), "credential only at transport");
        check(!Json.write(client.configuration()).contains(credential), "configuration excludes credential");
        check(client.configuration().get("transportKind").equals("MOCK_EXCHANGE"), "mock identifies itself");
    }

    private static String body(HttpRequest request) {
        var result = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
            @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.join();
    }

    private static void httpFailures() throws Exception {
        for (int status : List.of(302, 400, 401, 429, 500)) {
            var client = new HttpResponsesClient("unit-credential", request ->
                    new HttpResponsesClient.ExchangeResult(status, "private upstream text".getBytes(StandardCharsets.UTF_8)));
            var failure = expect(IOException.class, () -> client.create("{}"));
            check(failure.getMessage().equals("API_HTTP_" + status) && failure.getCause() == null, "safe HTTP status only");
        }
        int[] calls = {0};
        var client = new HttpResponsesClient("unit-credential", request -> {
            calls[0]++;
            return new HttpResponsesClient.ExchangeResult(200, new byte[] {(byte) 0xff});
        });
        expect(IOException.class, () -> client.retrieve("../other"));
        expect(IOException.class, () -> client.inputItems("resp_valid", "bad&cursor"));
        expect(IOException.class, () -> client.create("\ud800"));
        check(calls[0] == 0, "invalid requests never exchanged");
        expect(IOException.class, () -> client.create("{}"));
        var oversized = new HttpResponsesClient("unit-credential", request ->
                new HttpResponsesClient.ExchangeResult(200, new byte[4 * 1024 * 1024 + 1]));
        expect(IOException.class, () -> oversized.create("{}"));
    }

    private static void credentialSafety() throws Exception {
        String credential = "fixture-credential-quote\"tail";
        var client = new HttpResponsesClient(credential, request ->
                new HttpResponsesClient.ExchangeResult(200, "{}".getBytes(StandardCharsets.UTF_8)));
        String escaped = Json.write(Json.object("value", credential));
        String unicode = "{\"value\":\"" + credential.chars().mapToObj(c -> String.format("\\u%04x", c))
                .collect(Collectors.joining()) + "\"}";
        for (String text : List.of(credential, escaped, unicode, Json.write(Json.object("nested", escaped)))) {
            var failure = expect(IllegalArgumentException.class, () -> client.rejectCredentialMaterial(text));
            check(!failure.toString().contains(credential) && failure.getCause() == null, "credential rejection is sanitized");
            var evidence = new Evidence(client::rejectCredentialMaterial);
            expect(IllegalArgumentException.class, () -> evidence.append("FIXTURE", Json.object("raw", text)));
            check(!evidence.serialize().contains(credential) && !evidence.serialize().contains("\\u0066"), "rejected secret not retained");
        }
        String encodedArray = "[" + unicode + "]";
        expect(IllegalArgumentException.class, () -> client.rejectCredentialMaterial(encodedArray));
        expect(IllegalArgumentException.class, () -> client.rejectCredentialMaterial(unicode + " trailing invalid JSON"));
        String nestedJson = unicode;
        for (int i = 0; i < 10; i++) nestedJson = Json.write(Json.object("nested", nestedJson));
        String excessiveNesting = nestedJson;
        expect(IllegalArgumentException.class, () -> client.rejectCredentialMaterial(excessiveNesting));
        var fixture = new Fixture();
        var leaking = new HttpResponsesClient(credential, request ->
                new HttpResponsesClient.ExchangeResult(200, unicode.getBytes(StandardCharsets.UTF_8)));
        var harness = fixture.harness(leaking);
        harness.freezeTrustedInputs();
        harness.registerTargetMetadata();
        var failure = expect(ControlledHarness.Stop.class, harness::createMasterContext);
        check(failure.getCause() == null && !failure.toString().contains(credential), "API secret failure sanitized");
        check(!harness.evidenceJson().contains(credential) && !harness.evidenceJson().contains("\\u0066"), "API secret absent from retained evidence");
        terminal(harness, "FAILED");
    }

    private static void evidenceIntegrity() throws Exception {
        var mutable = new LinkedHashMap<String, Object>();
        mutable.put("observation", "original");
        var evidence = new Evidence(ignored -> {});
        evidence.append("HOST", mutable);
        mutable.put("observation", "caller-forged");
        check(!evidence.serialize().contains("caller-forged"), "retained evidence detached from caller mutation");
        evidence.verify();
        expect(UnsupportedOperationException.class, () -> map(evidence.snapshot().getFirst().get("material"))
                .put("observation", "forged"));
        var harness = new Fixture().created(new Mock());
        expect(UnsupportedOperationException.class, () -> map(harness.inspect().get("runtimeConfiguration"))
                .put("model", "forged"));
        check(harness.inspect().get("protocolAcceptance").equals("NOT_EVALUATED"), "inspector owns acceptance representation");
    }

    private static void responseIdReuse() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().created(mock);
        mock.createdTransform = value -> { value.put("id", "resp_1"); return value; };
        expect(ControlledHarness.Stop.class, () -> harness.continueMaster("resp_1"));
        terminal(harness, "FAILED");
        check(mock.requests.size() == 2, "reuse detected in returned API data");
        check(((List<?>) harness.inspect().get("responseLineage")).size() == 1,
                "duplicate returned ID never adds lineage");
        check(harness.inspect().get("responseId").equals("resp_1"), "prior known identity retained");
    }

    private static void reopenDenial() throws Exception {
        var mock = new Mock();
        var invalid = new Fixture().harness(mock);
        expect(ControlledHarness.Stop.class, invalid::registerTargetMetadata);
        terminal(invalid, "BLOCKED");
        expect(ControlledHarness.Stop.class, invalid::freezeTrustedInputs);
        expect(ControlledHarness.Stop.class, invalid::createMasterContext);
        check(mock.requests.isEmpty(), "invalid state cannot silently restart");
        var closedMock = new Mock();
        var closed = new Fixture().created(closedMock);
        closed.close();
        terminal(closed, "CLOSED");
        expect(ControlledHarness.Stop.class, closed::freezeTrustedInputs);
        expect(ControlledHarness.Stop.class, closed::registerTargetMetadata);
        expect(ControlledHarness.Stop.class, closed::createMasterContext);
        expect(ControlledHarness.Stop.class, () -> closed.continueMaster("resp_1"));
        terminal(closed, "CLOSED");
        check(closedMock.requests.size() == 1, "closed context cannot make a fresh API request");
    }

    private static void freezeRejection() throws Exception {
        var duplicate = new Fixture().harness(new Mock());
        duplicate.freezeTrustedInputs();
        expect(ControlledHarness.Stop.class, duplicate::freezeTrustedInputs);
        terminal(duplicate, "BLOCKED");

        var hardlinked = new Fixture();
        Files.createLink(hardlinked.root.resolve("master-alias"), hardlinked.trusted.resolve("MASTER.md"));
        var linkedHarness = hardlinked.harness(new Mock());
        expect(ControlledHarness.Stop.class, linkedHarness::freezeTrustedInputs);
        terminal(linkedHarness, "BLOCKED");

        var malformed = new Fixture();
        Files.write(malformed.trusted.resolve("MASTER.md"), new byte[] {(byte) 0xc3, (byte) 0x28});
        var malformedHarness = malformed.harness(new Mock());
        expect(ControlledHarness.Stop.class, malformedHarness::freezeTrustedInputs);
        terminal(malformedHarness, "BLOCKED");
        check(((List<?>) malformedHarness.inspect().get("trustedSourceRegistry")).isEmpty(),
                "partial invalid trusted registry is never installed");
    }

    private static void readyAccessDenial() throws Exception {
        for (String phase : List.of("READ_ALLOWED", "MUTATION_ALLOWED", "VALIDATION_ALLOWED")) {
            var mock = new Mock();
            var harness = new Fixture().created(mock);
            harness.markDiscoveryGateReady();
            expect(ControlledHarness.Stop.class, () -> harness.requestTargetAccess(phase));
            terminal(harness, "BLOCKED");
            check(mock.requests.size() == 1, "access denial never starts specialist execution");
        }
    }

    @SuppressWarnings("unchecked")
    private static void corruptedState() throws Exception {
        // Reflection deliberately simulates host-memory corruption; it is no public authority API.
        var lineageHarness = new Fixture().created(new Mock());
        var lineageField = ControlledHarness.class.getDeclaredField("lineage");
        lineageField.setAccessible(true);
        var lineage = (List<Map<String, Object>>) lineageField.get(lineageHarness);
        var changedLink = new LinkedHashMap<>(lineage.getFirst());
        changedLink.put("previousResponseId", "resp_unknown");
        lineage.set(0, Json.object("ordinal", changedLink.get("ordinal"), "role", changedLink.get("role"),
                "responseId", changedLink.get("responseId"), "previousResponseId", changedLink.get("previousResponseId"),
                "requestFingerprint", changedLink.get("requestFingerprint")));
        expect(ControlledHarness.Stop.class, lineageHarness::inspect);
        terminal(lineageHarness, "BLOCKED");

        var evidenceHarness = new Fixture().created(new Mock());
        var evidenceField = ControlledHarness.class.getDeclaredField("evidence");
        evidenceField.setAccessible(true);
        var evidence = (Evidence) evidenceField.get(evidenceHarness);
        var eventsField = Evidence.class.getDeclaredField("events");
        eventsField.setAccessible(true);
        var events = (List<Map<String, Object>>) eventsField.get(evidence);
        var event = events.getFirst();
        events.set(0, Json.object("sequence", event.get("sequence"), "kind", event.get("kind"),
                "material", Json.object("corrupted", true), "materialFingerprint", event.get("materialFingerprint")));
        expect(ControlledHarness.Stop.class, evidenceHarness::inspect);
        terminal(evidenceHarness, "BLOCKED");
        expect(IllegalStateException.class, evidenceHarness::evidenceJson);

        var requestHarness = new Fixture().created(new Mock());
        var activeField = ControlledHarness.class.getDeclaredField("activeRequest");
        activeField.setAccessible(true);
        Object active = activeField.get(requestHarness);
        var components = active.getClass().getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            var accessor = components[i].getAccessor();
            accessor.setAccessible(true);
            values[i] = accessor.invoke(active);
            if (components[i].getName().equals("body")) {
                var body = new LinkedHashMap<>(Json.parse((String) values[i]));
                body.remove("instructions");
                values[i] = Json.write(body);
            }
        }
        var constructor = active.getClass().getDeclaredConstructor(types);
        constructor.setAccessible(true);
        activeField.set(requestHarness, constructor.newInstance(values));
        expect(ControlledHarness.Stop.class, requestHarness::inspect);
        terminal(requestHarness, "BLOCKED");
    }

    private static void structuredSecretRejection() throws Exception {
        String sensitive = "fixture-sensitive-value";
        String credential = "configured-fixture-credential";
        var client = new HttpResponsesClient(credential, request ->
                new HttpResponsesClient.ExchangeResult(200, "{}".getBytes(StandardCharsets.UTF_8)));
        String scalar = Json.write(Json.object("password", sensitive));
        String array = Json.write(Json.object("password", List.of(sensitive)));
        String nested = Json.write(Json.object("password", Json.object("value", sensitive)));
        String escapedKey = "{\"\\u0070assword\":[\"" + sensitive + "\"]}";
        String escapedCredential = credential.chars().mapToObj(c -> String.format("\\u%04x", c))
                .collect(Collectors.joining());
        var payloads = List.of(scalar, array, nested, escapedKey, "[" + array + "]",
                Json.write(Json.object("wrapped", scalar)), Json.write(Json.object("wrapped", "[" + nested + "]")),
                Json.write(List.of(Json.object("wrapped", array))), "{\"password\":[\"" + sensitive + "\"]",
                "[{\"pass\\u0077ord\":[\"" + sensitive + "\"]}",
                Json.write(Json.object("content", List.of(credential))),
                "[\"" + escapedCredential + "\"]");
        for (String payload : payloads) {
            var guardFailure = expect(IllegalArgumentException.class, () -> client.rejectCredentialMaterial(payload));
            check(guardFailure.getCause() == null && !guardFailure.toString().contains(sensitive)
                    && !guardFailure.toString().contains(credential), "secret rejection has safe code only");
            var evidence = new Evidence(client::rejectCredentialMaterial);
            expect(IllegalArgumentException.class, () -> evidence.append("UNTRUSTED_READOUT", Json.object("rawBody", payload)));
            check(evidence.snapshot().isEmpty(), "unsafe candidate produces no retained event");
            String serialized = evidence.serialize();
            check(!serialized.contains(sensitive) && !serialized.contains(credential)
                    && !serialized.contains("materialFingerprint") && !serialized.contains("UNTRUSTED_READOUT"),
                    "rejected candidate retains neither payload nor fingerprint");

            var mock = new Mock();
            mock.safetyCheck = client::rejectCredentialMaterial;
            mock.createdTransform = response -> { response.put("untrustedFixtureReadout", payload); return response; };
            var harness = new Fixture().registered(mock);
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, "FAILED");
            String exported = harness.evidenceJson();
            check(!exported.contains(sensitive) && !exported.contains(credential)
                    && !exported.contains("untrustedFixtureReadout") && !exported.contains("RESPONSE_CREATED_READOUT"),
                    "public evidence export retains no rejected response or response fingerprint");
        }
        for (String key : List.of("password", "api_key", "apiKey", "token", "access_token", "authorization",
                "bearer", "secret", "private_key")) {
            for (Object value : List.of(sensitive, 123, false, List.of(sensitive), Json.object("nested", List.of(sensitive)))) {
                var structured = Json.object(key, value);
                expect(IllegalArgumentException.class, () -> client.rejectCredentialMaterial(Json.write(structured)));
                var evidence = new Evidence(client::rejectCredentialMaterial);
                expect(IllegalArgumentException.class, () -> evidence.append("STRUCTURED_FIXTURE", structured));
                check(evidence.snapshot().isEmpty(), "populated sensitive fields of every supported value type reject");
            }
        }
        String harmless = Json.write(Json.object("wrapped", "[{\"message\":\"harmless fixture\"}]"));
        client.rejectCredentialMaterial(harmless);
        var accepted = new Evidence(client::rejectCredentialMaterial);
        accepted.append("SAFE_FIXTURE", Json.object("rawBody", harmless));
        check(accepted.snapshot().size() == 1, "harmless nested JSON remains usable");
    }

    private static void emptyCredentialStructures() throws Exception {
        var guard = new HttpResponsesClient("fixture-schema-credential", request -> {
            throw new AssertionError("Schema inspection must not invoke transport");
        });
        // Exact MASTER fragment and minimal reproducers: the former populated() checked only
        // outer container size, even when every descendant value was an empty placeholder.
        String fragment = "{\"authorization\":{\"callerAuthorization\":{},\"checkpointReference\":\"\"}}";
        var payloads = new ArrayList<>(List.of(fragment, "{\"authorization\":{\"x\":\"\"}}",
                "{\"token\":[null]}",
                "{\"authorization\":{\"nested\":[null,\"\",{},[],{\"field\":[]}]}}",
                "{\"token\":[null,\"\",{\"value\":\"\"}]}",
                "{\"\\u0061uthorization\":{\"field\":\"\"}}",
                "Schema: " + fragment + " end.", "```json\n" + fragment + "\n```",
                "Schema: " + Json.write(fragment), "First {} then " + fragment + " then [null].",
                "Schema field names: authorization token password secret credential apiKey.",
                "{\"required\":[\"authorization\",\"token\",\"password\",\"secret\",\"credential\",\"apiKey\"]}"));
        for (String key : List.of("password", "api_key", "apiKey", "token", "access_token",
                "authorization", "bearer", "secret", "private_key")) {
            payloads.add(Json.write(Json.object(key, "")));
            payloads.add(Json.write(Json.object(key, Json.object())));
            Evidence.inspectSecretMaterial(Json.object(key, Json.object("field", List.of("", Json.object()))),
                    guard::rejectCredentialMaterial);
        }
        for (String payload : payloads) {
            Evidence.rejectObviousSecrets(payload);
            guard.rejectCredentialMaterial(payload);
            var evidence = new Evidence(guard::rejectCredentialMaterial);
            evidence.append("SCHEMA_FIXTURE", Json.object("rawBody", payload));
            check(evidence.snapshot().size() == 1 && evidence.serialize().contains("SCHEMA_FIXTURE"),
                    "safe field names and empty placeholders survive retention and reinspection");
        }
    }

    private static void credentialStructureValues() throws Exception {
        var guard = new HttpResponsesClient("fixture-schema-credential", request -> {
            throw new AssertionError("Credential inspection must not invoke transport");
        });
        for (String key : List.of("password", "api_key", "apiKey", "token", "access_token",
                "authorization", "bearer", "secret", "private_key", " AUTHORIZATION ", "To_K-eN")) {
            // No named/string placeholder exemptions: even one character, whitespace, false or zero
            // under an inherited sensitive key must reject, independent of the prose regex.
            for (Object value : List.of("actual-secret", "x", " ", "{}", "null", "<redacted>", "string", 0, false)) {
                for (Object container : List.of(value, List.of(value), Json.object("field", value),
                        Json.object("empty", Json.object(), "nested", List.of(Json.object("field", value))))) {
                    var candidate = Json.object(key, container);
                    var failure = expect(IllegalArgumentException.class,
                            () -> Evidence.inspectSecretMaterial(candidate, guard::rejectCredentialMaterial));
                    check(failure.getMessage().equals("SECRET_MATERIAL_REJECTED"), "sensitive value ancestry preserved");
                    expect(IllegalArgumentException.class, () -> guard.rejectCredentialMaterial(Json.write(candidate)));
                }
            }
        }
        // Structural keys are still inspected, including configured values that look like placeholders.
        for (String credential : List.of("<redacted>", "string")) {
            var configured = new HttpResponsesClient(credential, request -> {
                throw new AssertionError("Configured credential inspection must not invoke transport");
            });
            String encoded = credential.chars().mapToObj(c -> String.format("\\u%04x", c))
                    .collect(Collectors.joining());
            for (String payload : List.of(Json.write(Json.object("authorization", Json.object(credential, ""))),
                    "{\"authorization\":{\"" + encoded + "\":\"\"}}",
                    Json.write(Json.object("note", credential)), "{\"note\":\"" + encoded + "\"}")) {
                expect(IllegalArgumentException.class, () -> configured.rejectCredentialMaterial(payload));
                var evidence = new Evidence(configured::rejectCredentialMaterial);
                expect(IllegalArgumentException.class, () -> evidence.append("REJECTED_SCHEMA", Json.object("rawBody", payload)));
                check(evidence.snapshot().isEmpty(), "placeholder-looking configured material is never retained");
            }
        }
        for (String key : List.of("sk-fixtureSecret0123456789", "-----BEGIN PRIVATE KEY-----",
                "Authorization: Bearer fixture-value"))
            expect(IllegalArgumentException.class,
                    () -> guard.rejectCredentialMaterial(Json.write(Json.object("authorization", Json.object(key, "")))));
        expect(IllegalArgumentException.class,
                () -> guard.rejectCredentialMaterial("{\"authorization\":{\"x\":{\"password\":\"\",\"password\":\"x\"}}}"));
        expect(IllegalArgumentException.class,
                () -> guard.rejectCredentialMaterial("{\"token\":" + "[".repeat(65) + "null" + "]".repeat(65) + "}"));
    }

    private static void credentialStructureRetention() throws Exception {
        String credential = "fixture-schema-credential";
        String secret = "fixture-schema-secret";
        String sensitive = Json.write(Json.object("authorization",
                Json.object("callerAuthorization", Json.object(), "checkpointReference", secret)));
        String encoded = secret.chars().mapToObj(c -> String.format("\\u%04x", c))
                .collect(Collectors.joining());
        var payloads = new ArrayList<>(List.of(sensitive, "Before " + sensitive + " after.",
                "```json\n" + sensitive + "\n```", "[" + sensitive + "]",
                Json.write(Json.object("note", "Nested: " + sensitive)), Json.write(sensitive),
                "First {\"token\":[null]} then " + sensitive + " after {}.",
                "{\"\\u0061uthorization\":{\"field\":\"" + encoded + "\"}}",
                "{\"authorization\":{\"field\":[null,{},\"" + encoded + "\"]}}",
                Json.write(Json.object("authorization", Json.object(credential, ""))),
                "Result: \"prefix \"" + encoded + "\" suffix\""));
        for (int backslashes : List.of(1, 2, 3, 4))
            payloads.add("Result: \"first\"" + "\\".repeat(backslashes) + "\"" + encoded + "\"last\"");
        for (String payload : payloads) {
            assertEmbeddedRejected(payload, credential, secret);
            var guard = new HttpResponsesClient(credential, request -> {
                throw new AssertionError("Credential inspection must not invoke transport");
            });
            // Exercise the input-item readback boundary as well as response text above.
            var mock = new Mock();
            mock.safetyCheck = guard::rejectCredentialMaterial;
            mock.pageTransform = page -> {
                var item = new LinkedHashMap<>(map(((List<?>) page.get("data")).getFirst()));
                item.put("schemaFixture", payload);
                page.put("data", List.of(item));
                return page;
            };
            var harness = new Fixture().registered(mock);
            expect(ControlledHarness.Stop.class, harness::createMasterContext);
            terminal(harness, "FAILED");
            String exported = harness.evidenceJson();
            check(!exported.contains(secret) && !exported.contains(credential) && !exported.contains(encoded)
                    && !exported.contains("schemaFixture") && !exported.contains("INPUT_ITEMS_RETRIEVED")
                    && !exported.contains("msg_1"), "rejected input page, item and fingerprints are never retained");
            check(((List<?>) harness.inspect().get("providerItemBindings")).size() == 1,
                    "only the earlier safe output item is bound");
            check(Boolean.FALSE.equals(harness.inspect().get("readbackCaptured")), "rejected input prevents readiness");
        }
    }

    private static void unsafeCandidateConfiguration() throws Exception {
        var mock = new Mock();
        var harness = new Fixture().registered(mock);
        int[] reads = {0};
        mock.configurationSource = () -> ++reads[0] == 2
                ? Json.object("transportKind", "MOCK", "revision", 0, "password", List.of("fixture-config-secret"))
                : Json.object("transportKind", "MOCK", "revision", 0);
        expect(ControlledHarness.Stop.class, harness::createMasterContext);
        terminal(harness, "FAILED");
        check(reads[0] >= 2 && mock.requests.isEmpty(), "unsafe candidate configuration never dispatches");
        String serialized = harness.evidenceJson();
        check(!serialized.contains("fixture-config-secret") && !serialized.contains("REQUEST_ASSEMBLED"),
                "unsafe configuration supplies neither a retained request nor a credential-derived fingerprint");
    }

    private static void evidenceCallbackMutation() throws Exception {
        var mutable = new LinkedHashMap<String, Object>();
        mutable.put("observation", "original");
        boolean[] invoked = {false};
        var evidence = new Evidence(text -> {
            Evidence.rejectObviousSecrets(text);
            if (!invoked[0] && text.startsWith("{") && text.contains("original")) {
                invoked[0] = true;
                mutable.put("password", List.of("fixture-callback-secret"));
            }
        });
        evidence.append("HOST", mutable);
        check(invoked[0], "successful callback attempted to replace validated material");
        check(map(evidence.snapshot().getFirst().get("material")).equals(Json.object("observation", "original")),
                "retained evidence uses the original immutable candidate");
        check(evidence.snapshot().getFirst().get("materialFingerprint")
                        .equals(Json.evidenceFingerprint(Json.object("observation", "original"))),
                "fingerprint binds only the checked candidate");
        check(!evidence.serialize().contains("fixture-callback-secret"), "callback secret never retained or exported");
    }

    private static void embeddedSecretRejection() throws Exception {
        String secret = "fixture-secret";
        String credential = "fixture-embedded-credential";
        String unicodeCredential = credential.chars().mapToObj(c -> String.format("\\u%04x", c))
                .collect(Collectors.joining());
        String sensitive = "{\"password\":[\"fixture-secret\"]}";
        String encodedCredential = "{\"message\":\"" + unicodeCredential + "\"}";
        var payloads = new ArrayList<>(List.of(
                "Result: " + sensitive,
                sensitive + " end of result.",
                "```json\n" + sensitive + "\n```",
                "Result: [" + sensitive + "] end.",
                "Result: {\"\\u0070assword\":[\"fixture-secret\"]}",
                "Result: " + encodedCredential,
                "```json\n[" + encodedCredential + "]\n```",
                "Result: " + Json.write(sensitive),
                "Result: \"" + unicodeCredential + "\"",
                "Result: " + Json.write(Json.object("note", "Nested result: " + sensitive)),
                "Before {placeholder} then {\"ok\":true} and " + sensitive,
                "Before " + Json.write(Json.object("note", "quoted } ] and escaped \"quote\"")) + " after " + sensitive,
                "Result: {\"password\":[\"fixture-secret\"]",
                "Result: {\"password\":[\"fixture-secret\"}]",
                "Result: {\"note\":\"bad\\x escape\"}",
                "Result: {\"note\":1,\"note\":2}",
                "Result: {password: [\"fixture-secret\"]}",
                "Result: \"here " + sensitive,
                "Result: \"here [\"" + unicodeCredential + "\"]",
                "Result: {\u00a0\"password\":[\"fixture-secret\"]}",
                "Result: {\u200b\"password\":[\"fixture-secret\"]}",
                "Result: {/*comment*/\"password\":[\"fixture-secret\"]}"));
        for (Object value : List.of(secret, false, 123, Json.object("nested", List.of(secret))))
            payloads.add("Result: " + Json.write(Json.object("password", value)) + " done.");
        for (String payload : payloads) assertEmbeddedRejected(payload, credential, secret);
    }

    private static void assertEmbeddedRejected(String payload, String credential, String secret) throws Exception {
        var credentialGuard = new HttpResponsesClient(credential, request -> {
            throw new AssertionError("Credential inspection must not invoke transport");
        });
        var mock = new Mock();
        mock.safetyCheck = credentialGuard::rejectCredentialMaterial;
        mock.createdTransform = response -> withOutputText(response, payload);
        var harness = new Fixture().registered(mock);
        var failure = expect(ControlledHarness.Stop.class, harness::createMasterContext);
        check(failure.getCause() == null && !failure.toString().contains(secret)
                && !failure.toString().contains(credential), "embedded rejection exposes sanitized code only");
        terminal(harness, "FAILED");
        String exported = harness.evidenceJson();
        check(!exported.contains(secret) && !exported.contains(credential)
                && !exported.contains("\\u0066") && !exported.contains("RESPONSE_CREATED_READOUT")
                && !exported.contains("PROVIDER_ITEM_BOUND"),
                "rejected provider text and its response/item fingerprint evidence are absent from public export");
        check(((List<?>) harness.inspect().get("responseLineage")).isEmpty(), "rejected response establishes no lineage");
        expect(ControlledHarness.Stop.class, harness::discoveryMaterial);
        String encodedCredential = credential.chars().mapToObj(c -> String.format("\\u%04x", c))
                .collect(Collectors.joining());
        exported = harness.evidenceJson();
        check(!exported.contains(encodedCredential) && !exported.contains(Json.write(encodedCredential))
                && !exported.contains("PROVIDER_OBSERVATION") && !exported.contains("RAW_HOST_DISCOVERY_MATERIAL"),
                "encoded secret and publication material remain absent after denied discovery");
    }

    private static Map<String, Object> withOutputText(Map<String, Object> response, String text) {
        var output = new LinkedHashMap<>(map(((List<?>) response.get("output")).getFirst()));
        output.put("content", List.of(Json.object("type", "output_text", "text", text)));
        response.put("output", List.of(output));
        return response;
    }

    private static void harmlessEmbeddedJson() throws Exception {
        for (String payload : List.of(
                "Use {placeholder} and [README]; ordinary prose can include a closing } or ].",
                "Use the opening brace {",
                "He said \"{placeholder}\" and \"a brace } or [README]\".",
                "The sentence contains an unmatched ordinary \"quotation",
                "Result: {\"ok\":true} done.",
                "{\"ok\":true} trailing prose.",
                "```json\n{\"message\":\"harmless\"}\n```",
                "Result: [1, {\"note\":\"safe\"}, false] done.",
                "First {\"ok\":true}, then [null, 1], last {\"note\":\"safe\"}.",
                "Result: " + Json.write(Json.object("note", "a quoted } ] and escaped \"quote\"")),
                "Result: " + Json.write("Nested: {\"message\":\"safe\"}"),
                "Result: {\"message\":\"\\u0068\\u0069\"}")) {
            var mock = new Mock();
            mock.createdTransform = response -> withOutputText(response, payload);
            var harness = new Fixture().registered(mock);
            check(harness.createMasterContext().equals("resp_1"), "harmless prose/JSON completes correlated creation");
            check(harness.evidenceJson().contains("RESPONSE_CREATED_READOUT"), "safe response evidence retained");
        }
    }

    private static void embeddedInspectionLimits() throws Exception {
        for (String payload : List.of(
                "Result: " + "[".repeat(65) + "0" + "]".repeat(65),
                "Result: " + "{} ".repeat(4097),
                "Result: {\"note\":\"" + "a".repeat(4 * 1024 * 1024 + 1) + "\"}"))
            assertEmbeddedRejected(payload, "fixture-limit-credential", "fixture-secret-not-present");
    }

    private static void ambiguousQuoteCredentials() throws Exception {
        String credential = "unit-key";
        String encoded = "\\u0075\\u006e\\u0069\\u0074\\u002d\\u006b\\u0065\\u0079";
        // Exact independent-review counterexample, including both otherwise harmless quote spans.
        String reviewer = "Result: \"prefix \"\\u0075\\u006e\\u0069\\u0074\\u002d\\u006b\\u0065\\u0079\" suffix\"";
        for (String payload : List.of(
                reviewer,
                "Result: \"first\" \"" + encoded + "\" \"last\"",
                "Result: \"first\"\"" + encoded + "\"\"last\"",
                "Result: \"first\"plain " + encoded + "\"last\"",
                "Result: \"first\"\n" + encoded + "\"last\"",
                "Result: \"prefix \"" + encoded,
                "Result: \"prefix \"bad\\u00xz\" suffix\"",
                "Result: \"first\"\\\"" + encoded + "\"last\"",
                "Result: \"one\" \"two\" \"prefix \"" + encoded + "\" suffix\"",
                "Result: \"first\"\\u0022" + encoded + "\\u0022\"last\"",
                "Result: " + Json.write("prefix \"" + credential + "\" suffix"),
                "Result: \"first\" " + Json.write("prefix \"" + encoded + "\" suffix") + " \"last\""))
            assertEmbeddedRejected(payload, credential, credential);
        for (int backslashes : List.of(2, 3, 4))
            assertEmbeddedRejected("Result: \"first\"" + "\\".repeat(backslashes) + "\"" + encoded + "\"last\"",
                    credential, credential);
        for (String payload : List.of(
                "He said \"hello\" and then \"goodbye\".",
                "He said \"hello\"\nthen \"goodbye\".",
                "Result: \"first\"\"second\"\"third\"",
                "Result: \"\\u0068\\u0069\"",
                "Result: " + Json.write("prefix \"hello\" suffix"),
                "Result: " + Json.write("prefix \"hello\" suffix") + " \"last\"",
                "Result: " + Json.write("Nested {\"note\":\"safe\"}"))) {
            var mock = new Mock();
            mock.createdTransform = response -> withOutputText(response, payload);
            var harness = new Fixture().registered(mock);
            check(harness.createMasterContext().equals("resp_1"), "ordinary/encoded quote controls remain accepted");
            check(harness.evidenceJson().contains("RESPONSE_CREATED_READOUT"), "safe quote evidence retained");
        }
    }

    private static void successfulCloseCallbacks() throws Exception {
        for (String operation : List.of("freeze", "metadata", "ready", "discovery")) {
            var mock = new Mock();
            var fixture = new Fixture();
            var harness = fixture.harness(mock);
            if (!operation.equals("freeze")) harness.freezeTrustedInputs();
            if (operation.equals("ready") || operation.equals("discovery")) {
                harness.registerTargetMetadata();
                harness.createMasterContext();
            }
            if (operation.equals("discovery")) harness.markDiscoveryGateReady();
            boolean[] invoked = {false};
            mock.safetyCheck = value -> {
                String trigger = switch (operation) {
                    case "freeze" -> "Trusted fixture specification";
                    case "metadata" -> "\"gitWorktreeRoot\"";
                    case "ready" -> "\"state\":\"DISCOVERY_GATE_READY\"";
                    default -> "\"materialKind\":\"RAW_HOST_DISCOVERY_MATERIAL\"";
                };
                if (!invoked[0] && value.contains(trigger)) {
                    invoked[0] = true;
                    harness.close();
                }
                Evidence.rejectObviousSecrets(value);
            };
            expect(ControlledHarness.Stop.class, () -> {
                switch (operation) {
                    case "freeze" -> harness.freezeTrustedInputs();
                    case "metadata" -> harness.registerTargetMetadata();
                    case "ready" -> harness.markDiscoveryGateReady();
                    default -> harness.discoveryMaterial();
                }
            });
            check(invoked[0], "successful callback reached its lifecycle stage");
            terminal(harness, "CLOSED");
            check(mock.requests.size() == (operation.equals("freeze") || operation.equals("metadata") ? 0 : 1),
                    "shutdown dispatches no additional request");
            assertNoProgressAfterClose(harness.evidenceJson());
            if (operation.equals("freeze"))
                check(((List<?>) harness.inspect().get("trustedSourceRegistry")).isEmpty(),
                        "shutdown during freeze cannot install candidate trusted registry");
        }
    }

    private static void assertNoProgressAfterClose(String serialized) {
        boolean closed = false;
        for (Object value : (List<?>) Json.parse(serialized).get("events")) {
            var event = map(value);
            if (closed) {
                String kind = (String) event.get("kind");
                check(!Set.of("TRUSTED_INPUTS_FROZEN", "TARGET_METADATA_REGISTERED", "REQUEST_ASSEMBLED",
                        "CREATION_EVIDENCE_CAPTURED", "LOCAL_DISCOVERY_MATERIAL_READY", "PROVIDER_OBSERVATION",
                        "PROVIDER_EFFECTIVE_CONFIGURATION", "PROVIDER_CONTROL_DEFINITION").contains(kind),
                        "no progress evidence may publish after close");
            }
            if (event.get("kind").equals("CLOSED")) closed = true;
        }
        check(closed, "shutdown event retained");
    }

    private static void successfulReentrantCallbacks() throws Exception {
        for (String outer : List.of("create", "continue", "metadata")) {
            var fixture = new Fixture();
            var mock = new Mock();
            var harness = fixture.harness(mock);
            harness.freezeTrustedInputs();
            if (!outer.equals("metadata")) harness.registerTargetMetadata();
            if (outer.equals("continue")) harness.createMasterContext();
            int before = mock.requests.size();
            boolean[] invoked = {false};
            mock.configurationSource = () -> {
                if (!invoked[0]) {
                    invoked[0] = true;
                    try {
                        if (outer.equals("metadata")) harness.freezeTrustedInputs();
                        else harness.createMasterContext();
                    } catch (ControlledHarness.Stop expected) {
                        // An otherwise successful provider callback cannot cure the nested trust failure.
                    }
                }
                return Json.object("transportKind", "MOCK", "revision", 0);
            };
            expect(ControlledHarness.Stop.class, () -> {
                switch (outer) {
                    case "create" -> harness.createMasterContext();
                    case "continue" -> harness.continueMaster("resp_1");
                    default -> harness.registerTargetMetadata();
                }
            });
            check(invoked[0], "configuration callback attempted reentry");
            terminal(harness, "BLOCKED");
            check(mock.requests.size() == before, "reentrant lifecycle calls cannot send stale or null-previous requests");
            check(!harness.evidenceJson().contains("\"kind\":\"LOCAL_DISCOVERY_MATERIAL_READY\""),
                    "nested failure cannot publish readiness");
        }
    }

    private static void crossResponseOutputIdentity() throws Exception {
        for (String material : List.of("identical", "changed", "masquerade-history")) {
            var mock = new Mock();
            var harness = new Fixture().created(mock);
            var original = map(((List<?>) mock.lastResponse.get("output")).getFirst());
            mock.createdTransform = response -> {
                var reused = new LinkedHashMap<>(original);
                if (material.equals("changed")) reused.put("content", List.of(
                        Json.object("type", "output_text", "text", "Fresh response with reused output identity.")));
                response.put("output", List.of(reused));
                return response;
            };
            if (material.equals("masquerade-history")) mock.pages = (response, after) ->
                    page(List.of(map(((List<?>) mock.lastResponse.get("output")).getFirst()), userItem(mock, 1)), false);
            expect(ControlledHarness.Stop.class, () -> harness.continueMaster("resp_1"));
            terminal(harness, "FAILED");
            check(mock.lastResponse.get("id").equals("resp_2"), "fresh response ID cannot bless reused item identity");
            check(!Boolean.TRUE.equals(harness.inspect().get("readbackCaptured")), "reused output identity never accepted");
        }
    }

    private static void crossResponseInputIdentity() throws Exception {
        for (String problem : List.of("current-output-as-history", "historical-user-id-for-current", "changed-historical-user")) {
            var mock = new Mock();
            var harness = new Fixture().created(mock);
            mock.pages = (response, after) -> {
                var current = userItem(mock, 1);
                if (problem.equals("current-output-as-history")) return page(List.of(
                        map(((List<?>) mock.lastResponse.get("output")).getFirst()), current), false);
                if (problem.equals("historical-user-id-for-current")) {
                    var reused = new LinkedHashMap<>(current);
                    reused.put("id", userItem(mock, 0).get("id"));
                    return page(List.of(reused), false);
                }
                var changed = new LinkedHashMap<>(userItem(mock, 0));
                changed.put("content", List.of(Json.object("type", "input_text", "text", "Changed historical user input.")));
                return page(List.of(changed, current), false);
            };
            expect(ControlledHarness.Stop.class, () -> harness.continueMaster("resp_1"));
            terminal(harness, "FAILED");
        }
    }

    private static void freshIdentityControl() throws Exception {
        var duplicated = new Mock();
        duplicated.createdTransform = response -> {
            var output = map(((List<?>) response.get("output")).getFirst());
            response.put("output", List.of(output, output));
            return response;
        };
        var rejected = new Fixture().registered(duplicated);
        expect(ControlledHarness.Stop.class, rejected::createMasterContext);
        terminal(rejected, "FAILED");
        var fresh = new Mock();
        var harness = new Fixture().created(fresh);
        check(harness.continueMaster("resp_1").equals("resp_2"), "fresh IDs with omitted history remain accepted");
        check(map(((List<?>) fresh.lastResponse.get("output")).getFirst()).get("id").equals("out_2"),
                "accepted continuation has fresh output ID");
        check(harness.inspect().get("state").equals("CREATION_EVIDENCE_CAPTURED"), "fresh continuation readback retained");
    }
}
