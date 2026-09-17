package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import static dev.agentic.harness.RuntimeTest.*;

/** Provider-neutral offline boundary tests. No provider SDK, environment credential or network access. */
public final class ProviderTransportTest {
    private static Path temp;
    private static int passed;
    private static final ControlledHarness.Config CONFIG = new ControlledHarness.Config("host-model", 1024);
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        temp = Path.of(args[0]).toRealPath();
        run("opaque native identities and frozen dispatch", ProviderTransportTest::opaqueAndFrozen);
        run("host invocation gate uses retained logical binding", ProviderTransportTest::logicalGate);
        run("exact Unicode and whitespace artifact hashing", () -> artifact(false));
        run("supplied incorrect fingerprint rejected", () -> artifact(true));
        run("unknown host profiles fail closed", ProviderTransportTest::profiles);
        run("incomplete capabilities fail before dispatch", ProviderTransportTest::capabilities);
        run("prepared turn substitution fails before dispatch", ProviderTransportTest::preparedDrift);
        for (String fault : List.of("identity", "output", "previous", "echo", "completion"))
            run("readback " + fault + " rejected", () -> readback(fault));
        for (String fault : List.of("unknown", "reordered", "stale", "current-output", "id-drift"))
            run("context " + fault + " rejected", () -> context(fault));
        run("creation predecessor drift rejected", ProviderTransportTest::creationDrift);
        run("provider errors remain sanitized", ProviderTransportTest::sanitized);
        run("decoded secrets rejected before retention", ProviderTransportTest::decodedSecret);
        run("decoded native item secrets rejected before retention", ProviderTransportTest::decodedItemSecret);
        run("unsupported Responses identity rejected", ProviderTransportTest::responsesIdentity);
        run("Responses finite numeric timestamps remain compatible", ProviderTransportTest::responsesTimestamp);
        run("user input structural equality and negative cases", () -> structuralContext(false));
        run("historical output structural equality and negative cases", () -> structuralContext(true));
        System.out.println("PASS " + passed + " provider boundary checks; offline only");
    }
    private static void run(String name, Check body) throws Exception {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable failure) { throw new AssertionError(name, failure); }
    }

    private static void structuralFingerprintSemantics() throws Exception {
        var collisionA = Json.object("a", Json.object(), "b", 1);
        var collisionB = Json.object("a", Json.object("b", 1));
        check(!Json.structuralFingerprint(collisionA).equals(Json.structuralFingerprint(collisionB)), "nested map framing");
        var reordered = new LinkedHashMap<String,Object>();
        reordered.put("b", 1); reordered.put("a", Json.object());
        check(Json.structuralFingerprint(collisionA).equals(Json.structuralFingerprint(reordered)), "map order ignored");
        check(!Json.structuralFingerprint(List.of("first", "second")).equals(Json.structuralFingerprint(List.of("second", "first"))), "list order retained");
        check(!Json.structuralFingerprint(Integer.valueOf(1)).equals(Json.structuralFingerprint(Long.valueOf(1))), "numeric type retained");
        check(Json.structuralFingerprint("é🧪").equals(Json.structuralFingerprint("é🧪")), "valid Unicode accepted");
        var malformedKey = new LinkedHashMap<String,Object>(); malformedKey.put("\uD800", 1);
        reject(IllegalArgumentException.class, () -> Json.structuralFingerprint("\uD800"));
        reject(IllegalArgumentException.class, () -> Json.structuralFingerprint(malformedKey));
    }
    private static <T extends Throwable> T reject(Class<T> type, Check body) throws Exception {
        try { body.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError("wrong rejection", failure);
        }
        throw new AssertionError("rejection required");
    }
    private static ControlledHarness harness(NativeMock provider, boolean execution) throws Exception {
        var fixture = fixture(temp);
        var harness = new ControlledHarness(fixture.trusted(), fixture.source(), fixture.target(), CONFIG, provider, execution);
        harness.freezeTrustedInputs(); harness.registerTargetMetadata();
        return harness;
    }
    private static void failed(ControlledHarness harness, String code) {
        var observed = harness.inspect();
        check(observed.get("state").equals("FAILED"), "terminal failure");
        check(observed.get("failureCode").equals(code), "unchanged failure classification");
        check(observed.get("targetAccessPhase").equals("CLOSED"), "target closed");
        check(Boolean.FALSE.equals(observed.get("readbackCaptured")), "no readback credit");
    }
    private static void opaqueAndFrozen() throws Exception {
        var provider = new NativeMock(); var harness = harness(provider, false);
        provider.onCreate = prepared -> {
            var inspected = harness.inspect();
            check(prepared.body().equals(inspected.get("activeRequestBody")), "exact approved payload dispatched");
            check(!prepared.body().startsWith("{"), "host never parses native body");
        };
        check(harness.createMasterContext().equals("native/session:1"), "opaque response ID");
        check(harness.continueMaster("native/session:1").equals("native/session:2"), "opaque continuation");
        check(provider.requests.getLast().turn().previousId().equals("native/session:1"), "exact predecessor");
        check(provider.requests.getFirst().turn().instructions().equals(provider.requests.getLast().turn().instructions()), "fixed assembly");
        reject(UnsupportedOperationException.class, () -> provider.requests.getFirst().inputEvidence().put("forged", true));
        reject(UnsupportedOperationException.class, () -> provider.requests.getFirst().turn().correlation().put("forged", true));
        check(harness.inspect().get("targetAccessPhase").equals("METADATA_ONLY"), "no grant from transport");
        reject(ControlledHarness.Stop.class, () -> harness.continueMaster("native/stale"));
        check(provider.requests.size() == 2, "no reset/fallback after stale predecessor");
    }
    private static void logicalGate() throws Exception {
        var fixture = fixture(temp); var provider = new NativeMock();
        var harness = new ControlledHarness(fixture.trusted(), fixture.source(), fixture.target(), CONFIG, provider, true);
        harness.freezeTrustedInputs(); harness.registerTargetMetadata();
        var evidence = new Evidence(provider::rejectCredentialMaterial);
        var executor = new RoleExecutor(harness, evidence);
        var invocation = executor.begin(TrustedInputs.Role.MASTER, null, List.of(), null);
        var decision = Json.object("action", "REGISTER_INPUTS");
        provider.answer = turn -> envelope(turn, "DECISION", null, null, null, decision, List.of());
        executor.turn(invocation, Json.object("proposedDecision", decision), null, ignored -> {});
        var gates = new ExecutionGates(harness, evidence, fixture.trusted());
        gates.check("AFTER_INVOCATION", invocation, List.of(), null);
        var stale = new RoleExecutor.Invocation("stale", invocation.role(), invocation.predecessorInvocationId(),
                null, List.of(), null);
        reject(IllegalArgumentException.class, () -> gates.check("AFTER_INVOCATION", stale, List.of(), null));
        check(provider.requests.size() == 1, "gates never dispatch");
    }
    private static void artifact(boolean wrongHash) throws Exception {
        var provider = new NativeMock(); var harness = harness(provider, true);
        var executor = new RoleExecutor(harness, new Evidence(provider::rejectCredentialMaterial));
        var invocation = executor.begin(TrustedInputs.Role.SOURCE_ANALYSIS, null, List.of(), null);
        String exact = "\n\t{\"text\":\"e\u0301 / é / İstanbul / 😀\"}\r\n ";
        provider.answer = turn -> envelope(turn, "ARTIFACT", exact,
                wrongHash ? Json.fingerprint("SOURCE_ANALYSIS", new byte[]{1}) : null, null, null, List.of());
        if (wrongHash) {
            reject(ControlledHarness.Stop.class, () -> executor.turn(invocation, Json.object(), "SOURCE_ANALYSIS", ignored -> {}));
            failed(harness, "API_CREATE_OR_CORRELATION_FAILED");
        } else {
            var reply = executor.turn(invocation, Json.object(), "SOURCE_ANALYSIS", ignored -> {});
            check(exact.equals(reply.artifactText()), "exact artifact text survives protocol boundary");
            check(Json.fingerprint("SOURCE_ANALYSIS", exact.getBytes(StandardCharsets.UTF_8)).equals(reply.artifactFingerprint()), "host exact UTF-8 hash");
            check(!Json.fingerprint("SOURCE_ANALYSIS", exact.strip().getBytes(StandardCharsets.UTF_8)).equals(reply.artifactFingerprint()), "no trimming");
        }
    }
    private static void profiles() throws Exception {
        check(HostProviderConfiguration.directOpenAI().profile().equals(HostProviderConfiguration.DIRECT_OPENAI), "host default");
        for (String profile : Arrays.asList(null, "", "OPENCODE", "https://untrusted.invalid", "java.lang.Runtime"))
            reject(IllegalArgumentException.class, () -> new HostProviderConfiguration(profile));
    }
    private static void capabilities() throws Exception {
        for (var capability : List.of(new ProviderTransport.Capabilities(false, true, true),
                new ProviderTransport.Capabilities(true, false, true), new ProviderTransport.Capabilities(true, true, false))) {
            var provider = new NativeMock(); provider.capabilities = capability;
            reject(ControlledHarness.Stop.class, () -> harness(provider, false));
            check(provider.requests.isEmpty(), "capability refusal before dispatch");
        }
    }
    private static void preparedDrift() throws Exception {
        var provider = new NativeMock(); var harness = harness(provider, false);
        provider.prepareTransform = prepared -> {
            var t = prepared.turn();
            return new ProviderTransport.Prepared(new ProviderTransport.Turn(t.instructions(), t.assemblyIdentity(), t.binding(),
                    t.input(), "wrong", t.model(), t.maxOutputTokens(), t.correlation()), prepared.body(), prepared.inputEvidence());
        };
        reject(ControlledHarness.Stop.class, harness::createMasterContext);
        failed(harness, "API_CREATE_OR_CORRELATION_FAILED");
        check(provider.requests.isEmpty(), "changed logical request never dispatched");
    }
    private static ProviderTransport.Observation change(ProviderTransport.Observation o, String fault) {
        return new ProviderTransport.Observation(fault.equals("identity") ? "other/native" : o.responseId(),
                fault.equals("previous") ? "wrong/predecessor" : o.previousId(), !fault.equals("completion"),
                fault.equals("echo") ? replace(o.configurationEcho(), "model", "wrong-model") : o.configurationEcho(),
                fault.equals("output") ? "changed" : o.outputText(), o.outputs(), o.creationIdentity(), o.rawEvidence());
    }
    private static void readback(String fault) throws Exception {
        var provider = new NativeMock(); var harness = harness(provider, false);
        provider.retrieveTransform = o -> change(o, fault);
        reject(ControlledHarness.Stop.class, harness::createMasterContext);
        failed(harness, "API_READBACK_OR_CORRELATION_FAILED");
    }
    private static void creationDrift() throws Exception {
        var provider = new NativeMock(); var harness = harness(provider, false);
        provider.createTransform = o -> change(o, "previous");
        reject(ControlledHarness.Stop.class, harness::createMasterContext);
        failed(harness, "API_CREATE_OR_CORRELATION_FAILED");
    }
    private static void context(String fault) throws Exception {
        var provider = new NativeMock(); var harness = harness(provider, false);
        harness.createMasterContext();
        provider.pageTransform = page -> {
            var current = page.items().getFirst(); var old = provider.inputs.getFirst();
            List<ProviderTransport.Item> items = switch (fault) {
                case "unknown" -> List.of(new ProviderTransport.Item("alien/input", ProviderTransport.ItemKind.USER_INPUT,
                        null, Json.object("input", "untrusted context")), current);
                case "reordered" -> List.of(current, old);
                case "stale" -> List.of(old);
                case "current-output" -> List.of(provider.latest.outputs().getFirst(), current);
                case "id-drift" -> List.of(new ProviderTransport.Item("replacement/input", old.kind(), old.text(), old.nativeEvidence()), current);
                default -> throw new AssertionError();
            };
            return new ProviderTransport.ContextPage(items, false, null, "native history");
        };
        reject(ControlledHarness.Stop.class, () -> harness.continueMaster("native/session:1"));
        failed(harness, "API_READBACK_OR_CORRELATION_FAILED");
    }
    private static void sanitized() throws Exception {
        var provider = new NativeMock(); var harness = harness(provider, false);
        provider.onCreate = ignored -> { throw new IllegalStateException("secret-provider-credential raw failure"); };
        var failure = reject(ControlledHarness.Stop.class, harness::createMasterContext);
        check(failure.getMessage().equals("API_CREATE_OR_CORRELATION_FAILED") && failure.getCause() == null, "sanitized exception");
        check(!harness.evidenceJson().contains("secret-provider-credential"), "no secret diagnostic");
    }
    private static void responsesIdentity() throws Exception {
        var fixture = fixture(temp); var mock = new Mock();
        mock.answer = ignored -> Json.object("ack", true);
        mock.responseTransform = response -> replace(response, "id", "native/session:1");
        var harness = new ControlledHarness(fixture.trusted(), fixture.source(), fixture.target(), CONFIG,
                new ResponsesProtocolAdapter(mock), true);
        harness.freezeTrustedInputs(); harness.registerTargetMetadata();
        var binding = Json.object("role", "MASTER");
        reject(ControlledHarness.Stop.class, () -> harness.executionTurn(TrustedInputs.Role.MASTER, null,
                binding, null, Json.object(), ignored -> {}));
        failed(harness, "API_CREATE_OR_CORRELATION_FAILED");
    }

    private static void decodedSecret() throws Exception {
        var provider = new NativeMock(); var harness = harness(provider, false);
        provider.createTransform = o -> new ProviderTransport.Observation(o.responseId(), o.previousId(), true,
                o.configurationEcho(), "secret-provider-credential", o.outputs(), o.creationIdentity(), o.rawEvidence());
        reject(ControlledHarness.Stop.class, harness::createMasterContext);
        failed(harness, "API_CREATE_OR_CORRELATION_FAILED");
        check(!harness.evidenceJson().contains("secret-provider-credential"), "decoded secret never retained");
    }

    private static void decodedItemSecret() throws Exception {
        for (boolean inputPage : List.of(false, true)) {
            var provider = new NativeMock(); var harness = harness(provider, false);
            if (inputPage) provider.pageTransform = page -> new ProviderTransport.ContextPage(
                    List.of(new ProviderTransport.Item("native/input:1", ProviderTransport.ItemKind.USER_INPUT,
                            null, Json.object("value", "secret-provider-credential"))), false, null, page.rawEvidence());
            else provider.createTransform = o -> new ProviderTransport.Observation(o.responseId(), o.previousId(), true,
                    o.configurationEcho(), o.outputText(), List.of(new ProviderTransport.Item("native/output:1",
                            ProviderTransport.ItemKind.OUTPUT, null, Json.object("value", "secret-provider-credential"))),
                    o.creationIdentity(), o.rawEvidence());
            reject(ControlledHarness.Stop.class, harness::createMasterContext);
            failed(harness, inputPage ? "API_READBACK_OR_CORRELATION_FAILED" : "API_CREATE_OR_CORRELATION_FAILED");
            check(!harness.evidenceJson().contains("secret-provider-credential"), "decoded native secret never retained");
        }
    }

    private static void responsesTimestamp() throws Exception {
        var mock = new Mock(); mock.answer = ignored -> Json.object("ack", true);
        ResponsesClient fractional = new ResponsesClient() {
            public String create(String body) { return mock.create(body).replace("\"created_at\":1", "\"created_at\":1.25"); }
            public String retrieve(String id) { return mock.retrieve(id).replace("\"created_at\":1", "\"created_at\":1.25"); }
            public String inputItems(String id, String cursor) { return mock.inputItems(id, cursor); }
            public Map<String,Object> configuration() { return mock.configuration(); }
            public void rejectCredentialMaterial(String text) { mock.rejectCredentialMaterial(text); }
        };
        var adapter = new ResponsesProtocolAdapter(fractional);
        var turn = new ProviderTransport.Turn("trusted fixture", Json.object(), null, "{}", null, "host-model", 128,
                Json.object("contextId", "fixture", "ordinal", "1", "role", "MASTER"));
        var prepared = adapter.prepare(turn);
        var created = adapter.create(prepared);
        check(created.sameObservation(adapter.retrieve(prepared, created.responseId())), "fractional timestamp equality");
        check(created.creationIdentity().toString().endsWith(":1.25"), "timestamp was not truncated");
        Json.write(created.view());
    }

    private static void structuralContext(boolean historical) throws Exception {
        if (!historical) structuralFingerprintSemantics();
        responsesMemberOrder(historical);
        if (!historical) retainedInputIntegrity();
        for (String change : List.of("member-order", "value", "missing", "extra", "list-order", "scalar-type", "numeric-type")) {
            var provider = new NativeMock(); var harness = harness(provider, false);
            var nested = Json.object("number", 1, "text", "unchanged",
                    "parts", List.of(Json.object("x", 1, "y", "first"), Json.object("x", 2, "y", "second")));
            if (historical) provider.createTransform = o -> {
                var item = o.outputs().getFirst();
                return new ProviderTransport.Observation(o.responseId(), o.previousId(), o.completed(), o.configurationEcho(),
                        o.outputText(), List.of(new ProviderTransport.Item(item.id(), item.kind(), item.text(),
                                Json.object("text", item.text(), "nested", nested))), o.creationIdentity(), o.rawEvidence());
            };
            else provider.prepareTransform = p -> new ProviderTransport.Prepared(p.turn(), p.body(),
                    Json.object("input", p.turn().input(), "nested", nested));
            harness.createMasterContext();
            var retained = historical ? provider.latest.outputs().getFirst() : provider.inputs.getFirst();
            var changed = new LinkedHashMap<>(nested);
            switch (change) {
                case "value" -> changed.put("text", "changed");
                case "missing" -> changed.remove("text");
                case "extra" -> changed.put("extra", true);
                case "list-order" -> changed.put("parts", ((List<?>)nested.get("parts")).reversed());
                case "scalar-type" -> changed.put("number", "1");
                case "numeric-type" -> changed.put("number", 1L);
                case "member-order" -> { }
                default -> throw new AssertionError(change);
            }
            var nativeEvidence = map(reverseMembers(replace(retained.nativeEvidence(), "nested", changed)));
            var readback = new ProviderTransport.Item(retained.id(), retained.kind(), retained.text(), nativeEvidence);
            check(retained.nativeEvidence().equals(nativeEvidence) == change.equals("member-order"), "structural test premise");
            if (change.equals("member-order")) check(!Json.evidenceFingerprint(retained.nativeEvidence())
                    .equals(Json.evidenceFingerprint(nativeEvidence)), "different member order must exercise different byte hashes");
            if (change.equals("numeric-type")) check(Json.evidenceFingerprint(retained.nativeEvidence())
                    .equals(Json.evidenceFingerprint(replace(retained.nativeEvidence(), "nested", changed))),
                    "integer and long serialize identically but must not match structurally");
            provider.pageTransform = page -> new ProviderTransport.ContextPage(
                    List.of(readback, page.items().getFirst()), false, null, "native structural history");
            if (change.equals("member-order")) {
                check(harness.continueMaster("native/session:1").equals("native/session:2"), "member ordering accepted");
                check(Boolean.TRUE.equals(harness.inspect().get("readbackCaptured")), "readback still required");
            } else {
                reject(ControlledHarness.Stop.class, () -> harness.continueMaster("native/session:1"));
                failed(harness, "API_READBACK_OR_CORRELATION_FAILED");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void retainedInputIntegrity() throws Exception {
        for (String change : List.of("integer-to-long", "long-to-integer", "member-order",
                "list-order", "value", "missing", "extra")) {
            var provider = new NativeMock();
            boolean originalLong = change.equals("long-to-integer");
            Object originalNumber = Integer.valueOf(1);
            if (originalLong) originalNumber = Long.valueOf(1);
            var original = Json.object("nested", Json.object("number", originalNumber,
                    "parts", List.of("first", "second")));
            provider.prepareTransform = p -> new ProviderTransport.Prepared(p.turn(), p.body(), original);
            var harness = harness(provider, false);
            harness.createMasterContext();
            // Deliberate host-memory corruption, matching the existing harness integrity tests.
            var field = ControlledHarness.class.getDeclaredField("requestInputs");
            field.setAccessible(true);
            var retained = (Map<Integer,Map<String,Object>>)field.get(harness);
            if (change.equals("integer-to-long")) check(!Json.structuralFingerprint(original).equals(
                    Json.structuralFingerprint(Json.object("nested", Json.object("number", 1L,
                            "parts", List.of("first", "second"))))), "typed structural fingerprints differ");
            switch (change) {
                case "integer-to-long" -> retained.put(1, Json.object("nested", Json.object("number", 1L,
                        "parts", List.of("first", "second"))));
                case "long-to-integer" -> retained.put(1, Json.object("nested", Json.object("number", 1,
                        "parts", List.of("first", "second"))));
                case "member-order" -> retained.put(1, (Map<String,Object>) reverseMembers(original));
                case "list-order" -> retained.put(1, Json.object("nested", Json.object("number", 1,
                        "parts", List.of("second", "first"))));
                case "value" -> retained.put(1, Json.object("nested", Json.object("number", 2,
                        "parts", List.of("first", "second"))));
                case "missing" -> retained.remove(1);
                case "extra" -> retained.put(1, Json.object("nested", Json.object("number", 1,
                        "parts", List.of("first", "second")), "extra", true));
                default -> throw new AssertionError(change);
            }
            if (change.equals("member-order")) {
                harness.markDiscoveryGateReady();
                check("DISCOVERY_GATE_READY".equals(harness.inspect().get("state")), "structural reorder remains equivalent");
            } else {
                reject(ControlledHarness.Stop.class, harness::markDiscoveryGateReady);
                var view = harness.inspect();
                check("BLOCKED".equals(view.get("state")), "input registry corruption blocks: " + change + " / " + view);
                check("CLOSED".equals(view.get("targetAccessPhase")), "corruption closes target access: " + change);
            }
        }
    }

    /** Exercise actual Responses decoding, including nested content objects, with reordered keys. */
    private static void responsesMemberOrder(boolean historical) throws Exception {
        var fixture = fixture(temp); var mock = new Mock(); mock.answer = ignored -> Json.object("ack", true);
        ResponsesClient reordered = new ResponsesClient() {
            public String create(String body) { return mock.create(body); }
            public String retrieve(String id) { return mock.retrieve(id); }
            public String inputItems(String id, String cursor) {
                var page = Json.parse(mock.inputItems(id, cursor));
                var items = new ArrayList<Object>((List<?>)page.get("data"));
                if (historical && mock.requests.size() == 2)
                    items.addFirst(((List<?>)Json.parse(mock.responses.get("resp_1")).get("output")).getFirst());
                return Json.write(reverseMembers(replace(page, "data", items,
                        "first_id", map(items.getFirst()).get("id"), "last_id", map(items.getLast()).get("id"))));
            }
            public Map<String,Object> configuration() { return mock.configuration(); }
            public void rejectCredentialMaterial(String text) { mock.rejectCredentialMaterial(text); }
        };
        var harness = new ControlledHarness(fixture.trusted(), fixture.source(), fixture.target(), CONFIG, reordered, true);
        harness.freezeTrustedInputs(); harness.registerTargetMetadata();
        String id = harness.executionTurn(TrustedInputs.Role.MASTER, null, Json.object("role", "MASTER"), null,
                Json.object(), ignored -> {});
        if (historical) id = harness.executionTurn(TrustedInputs.Role.MASTER, id, Json.object("role", "MASTER"), null,
                Json.object(), ignored -> {});
        check(id.equals(historical ? "resp_2" : "resp_1"), "Responses member order accepted");
    }

    private static Object reverseMembers(Object value) {
        if (value instanceof Map<?,?> object) {
            Map<String,Object> reversed = new LinkedHashMap<>();
            for (var key : new ArrayList<>(object.keySet()).reversed())
                reversed.put((String)key, reverseMembers(object.get(key)));
            return reversed;
        }
        if (value instanceof List<?> list) return list.stream().map(ProviderTransportTest::reverseMembers).toList();
        return value;
    }

    /** Deliberately non-Responses in-memory protocol, with opaque IDs and no Responses wire fields. */
    private static final class NativeMock implements ProviderTransport {
        final List<Prepared> requests = new ArrayList<>();
        final List<Item> inputs = new ArrayList<>();
        Observation latest;
        Capabilities capabilities = new Capabilities(true, true, true);
        Consumer<Prepared> onCreate = ignored -> {};
        Function<Map<String,Object>, Map<String,Object>> answer = ignored -> Json.object("ack", true);
        UnaryOperator<Prepared> prepareTransform = UnaryOperator.identity();
        UnaryOperator<Observation> createTransform = UnaryOperator.identity(), retrieveTransform = UnaryOperator.identity();
        UnaryOperator<ContextPage> pageTransform = UnaryOperator.identity();
        @Override public Capabilities capabilities() { return capabilities; }
        @Override public Map<String,Object> configuration() { return Json.object("profile", "NATIVE_OFFLINE_FIXTURE"); }
        @Override public void rejectCredentialMaterial(String text) {
            Evidence.rejectObviousSecrets(text);
            if (text.contains("secret-provider-credential")) throw new IllegalArgumentException("SECRET_REJECTED");
        }
        @Override public Prepared prepare(Turn turn) {
            return prepareTransform.apply(new Prepared(turn, "NATIVE PAYLOAD\n" + Json.write(Json.object(
                    "configuration", turn.echo(), "binding", turn.binding(), "input", turn.input())), Json.object("input", turn.input())));
        }
        @Override public Observation create(Prepared prepared) {
            onCreate.accept(prepared); requests.add(prepared);
            int ordinal = requests.size();
            String text = prepared.turn().binding() == null ? "ready" : Json.write(answer.apply(Json.parse(prepared.turn().input())));
            latest = createTransform.apply(new Observation("native/session:" + ordinal, prepared.turn().previousId(), true,
                    prepared.turn().echo(), text,
                    List.of(new Item("native/output:" + ordinal, ItemKind.OUTPUT, text, Json.object("text", text))),
                    "creation:" + ordinal, "native creation"));
            inputs.add(new Item("native/input:" + ordinal, ItemKind.USER_INPUT, null, prepared.inputEvidence()));
            return latest;
        }
        @Override public Observation retrieve(Prepared prepared, String id) {
            check(latest.responseId().equals(id), "readback uses native ID");
            return retrieveTransform.apply(latest);
        }
        @Override public ContextPage inputItems(String id, String cursor) {
            check(latest.responseId().equals(id) && cursor == null, "readback context binding");
            return pageTransform.apply(new ContextPage(List.of(inputs.getLast()), false, null, "native context"));
        }
    }
}
