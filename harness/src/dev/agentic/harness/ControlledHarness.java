package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Single cooperative host context. No migration scheduler or protocol acceptance is implemented. */
public final class ControlledHarness {
    public enum State {
        INITIALIZED, TRUSTED_INPUTS_FROZEN, TARGET_METADATA_REGISTERED, MASTER_CONTEXT_CREATED,
        CREATION_EVIDENCE_CAPTURED, DISCOVERY_GATE_READY, BLOCKED, FAILED, CLOSED
    }

    public record Config(String model, int maxOutputTokens) {
        public Config {
            Json.identifier(model);
            if (maxOutputTokens < 1) throw new IllegalArgumentException("INVALID_TOKEN_LIMIT");
        }
    }

    public static final class Stop extends IllegalStateException {
        private Stop(String code) { super(code); }
    }

    private record Request(int ordinal, String role, String previous, String marker,
                           String body, Map<String, Object> configurationIdentity, ProviderTransport.Prepared prepared) {
        Map<String, Object> identity() {
            return Json.fingerprint("RUNTIME_DISCOVERY_EVIDENCE", body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record ExpectedItem(Map<?, ?> item, String kind, String origin) {}

    private static final String VERSION = "controlled-provider-harness/1";
    private static final List<Object> TOOLS = List.of();
    private final Path trustedRoot;
    private final Path declaredSource;
    private final Path declaredTarget;
    private final Config config;
    private final ProviderTransport client;
    private final boolean executionMode;
    private final TargetBroker broker = new TargetBroker();
    private final SourceBoundary sourceBoundary;
    private final Evidence evidence;
    private final String contextId = UUID.randomUUID().toString();
    private final List<Map<String, Object>> lineage = new ArrayList<>();
    private final Map<String, Map<String, Object>> providerItems = new LinkedHashMap<>();
    // Full immutable comparison objects stay in host memory; the journal keeps their fingerprints.
    private final Map<Integer, Map<String, Object>> requestInputs = new LinkedHashMap<>();
    private final String initializedConfiguration;
    private final String startupCwd = Path.of("").toAbsolutePath().normalize().toString();
    private TrustedInputs trusted;
    private TrustedInputs.Role role = TrustedInputs.Role.MASTER;
    private State state = State.INITIALIZED;
    private Request activeRequest;
    private String responseId;
    private String failureCode;
    private boolean configurationChanged;
    private boolean readbackCaptured;
    private boolean requestInFlight;
    private boolean operationActive;
    private boolean inspectionActive;
    private int callbackDepth;
    private long freshnessCounter;
    private long roleSwitchCounter;

    /** Compatibility entry points for the direct Responses transport. All arguments are host-owned. */
    public ControlledHarness(Path trustedRoot, Path declaredTarget, Config config, ResponsesClient client) {
        this(trustedRoot, null, declaredTarget, config, new ResponsesProtocolAdapter(client), false);
    }
    public ControlledHarness(Path trustedRoot, Path declaredSource, Path declaredTarget,
                             Config config, ResponsesClient client) {
        this(trustedRoot, declaredSource, declaredTarget, config, new ResponsesProtocolAdapter(client), false);
    }
    public ControlledHarness(Path trustedRoot, Path declaredTarget, Config config, ProviderTransport client) {
        this(trustedRoot, null, declaredTarget, config, client, false);
    }
    public ControlledHarness(Path trustedRoot, Path declaredSource, Path declaredTarget,
                             Config config, ProviderTransport client) {
        this(trustedRoot, declaredSource, declaredTarget, config, client, false);
    }
    /** Only the host coordinator creates this profile; legacy foundation entry points stay closed. */
    ControlledHarness(Path trustedRoot, Path declaredSource, Path declaredTarget,
                      Config config, ResponsesClient client, boolean executionMode) {
        this(trustedRoot, declaredSource, declaredTarget, config, new ResponsesProtocolAdapter(client), executionMode);
    }
    ControlledHarness(Path trustedRoot, Path declaredSource, Path declaredTarget,
                      Config config, ProviderTransport client, boolean executionMode) {
        this.trustedRoot = Objects.requireNonNull(trustedRoot);
        this.declaredSource = declaredSource;
        this.declaredTarget = Objects.requireNonNull(declaredTarget);
        this.sourceBoundary = declaredSource == null ? null : new SourceBoundary();
        this.config = Objects.requireNonNull(config);
        this.client = Objects.requireNonNull(client);
        this.executionMode = executionMode;
        this.evidence = new Evidence(text -> providerCallback(() -> {
            client.rejectCredentialMaterial(text);
            return null;
        }));
        operationActive = true;
        try {
            evidence.safe(declaredTarget.toString());
            if (declaredSource != null) evidence.safe(declaredSource.toString());
            providerCallback(client::capabilities).requireSupported();
            var initial = runtimeConfiguration();
            this.initializedConfiguration = Json.write(initial);
            evidence.safe(initializedConfiguration);
            evidence.append("INITIALIZED", Json.object("logicalContextId", contextId, "configuration", initial));
        } catch (RuntimeException failure) { throw new Stop("INITIALIZATION_REJECTED"); }
        finally { operationActive = false; }
    }

    private Map<String, Object> runtimeConfiguration() {
        return Json.object("harnessVersion", VERSION, "executionProfile", executionMode ? "CONTROLLED_SEQUENTIAL_V1" : "FOUNDATION_ONLY",
                "javaRuntimeVersion", Runtime.version().toString(),
                "controlMode", "FIXED_TRUSTED_CONTEXT", "launchMechanism", "HOST_SAME_CONTEXT_SEQUENTIAL",
                "startupCwd", startupCwd, "trustedRootDesignation", trustedRoot.toString(),
                "sourceRootDesignation", declaredSource == null ? null : declaredSource.toString(),
                "model", config.model(), "maxOutputTokens", config.maxOutputTokens(), "tools", TOOLS,
                "contextPolicy", "EXACT_PREDECESSOR_NO_RESET", "readbackRequired", true,
                "providerCapabilities", capabilities(),
                "transport", providerCallback(client::configuration));
    }

    private Map<String,Object> capabilities() {
        var value = providerCallback(client::capabilities);
        value.requireSupported();
        return Json.object("independentReadback", value.independentReadback(),
                "orderedContext", value.orderedContext(), "frozenDispatch", value.frozenDispatch());
    }

    /** synchronized alone permits same-thread callbacks to enter another lifecycle operation. */
    private void beginOperation() {
        ensureUsable();
        if (operationActive || callbackDepth != 0) throw stop(State.BLOCKED, "REENTRANT_OPERATION");
        operationActive = true;
    }

    private <T> T providerCallback(Supplier<T> callback) {
        boolean alreadyTerminal = terminal();
        callbackDepth++;
        T result;
        try { result = callback.get(); }
        finally { callbackDepth--; }
        // A callback may close/block the host and return normally. Never continue that operation.
        if (!alreadyTerminal) ensureUsable();
        return result;
    }

    public synchronized void freezeTrustedInputs() {
        beginOperation();
        try {
            require(State.INITIALIZED);
            try {
                verifyConfiguration();
                if (sourceBoundary != null) {
                    var metadata = sourceBoundary.register(declaredSource, trustedRoot, declaredTarget);
                    evidence.safe(Json.write(metadata.view()));
                    evidence.append("SOURCE_METADATA_REGISTERED", metadata.view());
                    require(State.INITIALIZED);
                }
                var frozen = TrustedInputs.freeze(trustedRoot, declaredTarget, executionMode);
                if (sourceBoundary != null) sourceBoundary.verify();
                evidence.safe(frozen.instructions());
                evidence.append("TRUSTED_INPUTS_FROZEN", Json.object("registry", frozen.registry(),
                        "roleBindings", frozen.roleBindings(), "exactInstructionPayload", frozen.instructions(),
                        "assemblyIdentity", frozen.assemblyIdentity()));
                require(State.INITIALIZED);
                if (sourceBoundary != null) sourceBoundary.verify();
                trusted = frozen;
                state = State.TRUSTED_INPUTS_FROZEN;
            } catch (Exception failure) { throw stop(State.BLOCKED, "TRUSTED_INPUT_FREEZE_REJECTED"); }
        } finally { operationActive = false; }
    }

    public synchronized void registerTargetMetadata() {
        beginOperation();
        try {
            require(State.TRUSTED_INPUTS_FROZEN);
            try {
                verifyConfiguration();
                var metadata = broker.inspect(declaredTarget);
                trusted.verify(Path.of(metadata.resolvedRoot()));
                if (sourceBoundary != null) sourceBoundary.verify();
                evidence.safe(Json.write(metadata.view()));
                evidence.append("TARGET_METADATA_REGISTERED", metadata.view());
                require(State.TRUSTED_INPUTS_FROZEN);
                if (sourceBoundary != null) sourceBoundary.verify();
                state = State.TARGET_METADATA_REGISTERED;
            } catch (Exception failure) { throw stop(State.BLOCKED, "TARGET_METADATA_REJECTED"); }
        } finally { operationActive = false; }
    }

    public synchronized String createMasterContext() {
        beginOperation();
        try {
            require(State.TARGET_METADATA_REGISTERED);
            if (responseId != null || !lineage.isEmpty()) throw stop(State.BLOCKED, "CONTEXT_ALREADY_EXISTS");
            return masterTurn(null);
        } finally { operationActive = false; }
    }

    public synchronized String continueMaster(String expectedPreviousResponseId) {
        beginOperation();
        try {
            if (state != State.CREATION_EVIDENCE_CAPTURED && state != State.DISCOVERY_GATE_READY)
                throw stop(State.BLOCKED, "INVALID_TRANSITION");
            requireLineage(expectedPreviousResponseId);
            if (role != TrustedInputs.Role.MASTER)
                throw stop(State.BLOCKED, "SPECIALIST_PROTOCOL_GATES_NOT_IMPLEMENTED");
            return masterTurn(responseId);
        } finally { operationActive = false; }
    }

    private String masterTurn(String previous) {
        return providerTurn(previous, null, null, null);
    }

    /** Host-only transport path. The coordinator, never response data, supplies role and binding. */
    synchronized String executionTurn(TrustedInputs.Role selectedRole, String expectedPrevious,
                                      Map<String, Object> binding, Map<String, Object> hostTaskContext, Map<String, Object> data,
                                      Consumer<String> validateStructuredOutput) {
        beginOperation();
        try {
            if (!executionMode || (state != State.TARGET_METADATA_REGISTERED
                    && state != State.CREATION_EVIDENCE_CAPTURED && state != State.DISCOVERY_GATE_READY))
                throw stop(State.BLOCKED, "EXECUTION_PROFILE_REQUIRED");
            if (!Objects.equals(responseId, expectedPrevious)
                    || !selectedRole.id.equals(binding.get("role")))
                throw stop(State.FAILED, "EXECUTION_BINDING_MISMATCH");
            role = selectedRole;
            roleSwitchCounter++;
            String marker = Json.write(Json.object("format", "HOST_EXECUTION_TURN_V1", "binding", binding,
                    "hostTaskContext", hostTaskContext,
                    "data", Json.object("format", "UNTRUSTED_DATA_V1", "value", data)));
            return providerTurn(responseId, marker, binding, Objects.requireNonNull(validateStructuredOutput));
        } finally { operationActive = false; }
    }

    private String providerTurn(String previous, String executionMarker, Map<String,Object> binding, Consumer<String> validateStructuredOutput) {
        verifyActive();
        if (requestInFlight || (executionMarker == null && role != TrustedInputs.Role.MASTER))
            throw stop(State.BLOCKED, "INVALID_TURN");
        requestInFlight = true;
        readbackCaptured = false;
        state = State.TARGET_METADATA_REGISTERED;
        String stage = "CREATE";
        try {
            int ordinal = lineage.size() + 1;
            String marker = executionMarker != null ? executionMarker : "HOST_TURN logicalContext=" + contextId + " ordinal=" + ordinal
                    + " role=" + role.id + " purpose=CONTROL_CONTEXT_ONLY; acknowledge readiness for host evidence review."
                    + " Do not start specialist execution or claim a discovery gate PASS.";
            var turn = new ProviderTransport.Turn(trusted.instructions(), trusted.assemblyIdentity(), binding,
                    marker, previous, config.model(), config.maxOutputTokens(),
                    Json.object("contextId", contextId, "ordinal", Integer.toString(ordinal), "role", role.id));
            evidence.safe(turn.input());
            evidence.safe(Json.write(turn.echo()));
            var prepared = providerCallback(() -> client.prepare(turn));
            if (!turn.equals(prepared.turn())) throw new IllegalArgumentException("PREPARED_TURN_CHANGED");
            Request candidateRequest = new Request(ordinal, role.id, previous, marker, prepared.body(),
                    Json.evidenceFingerprint(verifiedConfiguration()), prepared);
            evidence.safe(Json.write(turn.view()));
            evidence.safe(Json.write(prepared.inputEvidence()));
            evidence.safe(candidateRequest.body());
            activeRequest = candidateRequest;
            evidence.append("REQUEST_ASSEMBLED", Json.object("logicalContextId", contextId,
                    "selectedRole", role.id, "ordinal", ordinal, "previousResponseId", previous,
                    "exactRequestBody", activeRequest.body(), "requestFingerprint", activeRequest.identity(),
                    "configurationFingerprint", activeRequest.configurationIdentity(),
                    "logicalTurn", turn.view(),
                    "inputEvidenceFingerprint", Json.structuralFingerprint(prepared.inputEvidence())));
            requestInputs.put(ordinal, prepared.inputEvidence());
            verifyActive();
            ensureUsable();
            if (!Objects.equals(activeRequest.previous(), responseId)
                    || activeRequest.ordinal() != lineage.size() + 1
                    || !Objects.equals(activeRequest.prepared().turn().previousId(), responseId))
                throw stop(State.BLOCKED, "DISPATCH_PREDECESSOR_CHANGED");
            // This is exactly the immutable body exposed by inspect(); no adapter-side reassembly.
            var created = client.create(activeRequest.prepared());
            ensureUsable();
            validateObservation(created, turn, null);
            if (validateStructuredOutput != null) {
                if (created.outputText() == null) throw new IllegalArgumentException("ONE_STRUCTURED_RESPONSE_REQUIRED");
                validateStructuredOutput.accept(created.outputText());
            }
            ensureUsable();
            String returnedId = created.responseId();
            if (lineage.stream().anyMatch(link -> returnedId.equals(link.get("responseId"))))
                throw new IllegalArgumentException("RESPONSE_ID_REUSED");
            for (var item : created.outputs()) {
                if (providerItems.containsKey(item.id()))
                    throw new IllegalArgumentException("PROVIDER_OUTPUT_ID_REUSED");
            }
            evidence.append("RESPONSE_CREATED_READOUT", Json.object("ordinal", ordinal,
                    "rawBody", created.rawEvidence(), "observation", created.view()));
            for (var item : created.outputs()) bindProviderItem(item.view(), "OUTPUT", returnedId);
            ensureUsable();
            responseId = returnedId;
            lineage.add(Json.object("ordinal", ordinal, "role", role.id, "responseId", responseId,
                    "previousResponseId", previous, "requestFingerprint", activeRequest.identity()));
            state = State.MASTER_CONTEXT_CREATED;
            verifyActive();
            stage = "READBACK";
            var retrieved = client.retrieve(activeRequest.prepared(), responseId);
            ensureUsable();
            validateObservation(retrieved, turn, responseId);
            if (!created.sameObservation(retrieved))
                throw new IllegalArgumentException("RESPONSE_READBACK_CHANGED");
            evidence.append("RESPONSE_RETRIEVED", Json.object("responseId", responseId,
                    "rawBody", retrieved.rawEvidence(), "observation", retrieved.view()));
            captureInputItems();
            verifyActive();
            readbackCaptured = true;
            requestInFlight = false;
            state = State.CREATION_EVIDENCE_CAPTURED;
            evidence.append("CREATION_EVIDENCE_CAPTURED", observation());
            ensureUsable();
            return responseId;
        } catch (Stop failure) { throw failure; }
        catch (Exception failure) {
            // Never retain API error messages/bodies or exception causes.
            throw stop(State.FAILED, stage.equals("CREATE") ? "API_CREATE_OR_CORRELATION_FAILED"
                    : "API_READBACK_OR_CORRELATION_FAILED");
        } finally { requestInFlight = false; }
    }

    private void validateObservation(ProviderTransport.Observation observed, ProviderTransport.Turn turn, String expectedId) {
        if (observed.rawEvidence().length() > 4 * 1024 * 1024)
            throw new IllegalArgumentException("RESPONSE_BODY_LIMIT");
        evidence.safe(observed.rawEvidence());
        evidence.safe(Json.write(observed.configurationEcho()));
        if (observed.outputText() != null) evidence.safe(observed.outputText());
        for (var item : observed.outputs()) evidence.safe(Json.write(item.view()));
        evidence.safe(Json.write(observed.view()));
        if (!observed.completed() || !Objects.equals(turn.previousId(), observed.previousId())
                || (expectedId != null && !expectedId.equals(observed.responseId()))
                || !turn.echo().equals(observed.configurationEcho()))
            throw new IllegalArgumentException("RESPONSE_CONFIGURATION_MISMATCH");
        Set<String> ids = new HashSet<>();
        for (var item : observed.outputs())
            if (item.kind() != ProviderTransport.ItemKind.OUTPUT || !ids.add(item.id()))
                throw new IllegalArgumentException("UNREGISTERED_TOOL_OR_CHILD");
    }

    synchronized SourceBoundary executionSourceBoundary() {
        if (!executionMode) throw new IllegalStateException("EXECUTION_PROFILE_REQUIRED");
        return sourceBoundary;
    }

    synchronized TargetBroker executionTargetMetadata() {
        if (!executionMode) throw new IllegalStateException("EXECUTION_PROFILE_REQUIRED");
        return broker;
    }

    synchronized TrustedInputs executionTrustedInputs() {
        if (!executionMode || trusted == null) throw new IllegalStateException("EXECUTION_PROFILE_REQUIRED");
        return trusted;
    }

    synchronized void verifyExecutionBoundary() { verifyActive(); }

    synchronized String executionContextId() { return contextId; }

    private void captureInputItems() throws Exception {
        String after = null;
        Set<String> seen = new HashSet<>(), cursors = new HashSet<>();
        List<ExpectedItem> expected = retainedInputSequence();
        int lastMatched = -1;
        boolean instructionSeen = false;
        for (int pageNumber = 1; pageNumber <= 16; pageNumber++) {
            verifyActive();
            var page = client.inputItems(responseId, after);
            ensureUsable();
            evidence.safe(page.rawEvidence());
            for (var item : page.items()) evidence.safe(Json.write(item.view()));
            evidence.append("INPUT_ITEMS_RETRIEVED", Json.object("responseId", responseId,
                    "pageNumber", pageNumber, "after", after, "order", "asc", "rawBody", page.rawEvidence(),
                    "items", page.items().stream().map(ProviderTransport.Item::summary).toList(),
                    "hasMore", page.hasMore(), "nextCursor", page.nextCursor()));
            if (page.items().isEmpty() || page.items().size() > 100)
                throw new IllegalArgumentException("INPUT_ITEMS_REQUIRED_FIELDS");
            for (var item : page.items()) {
                String id = item.id();
                if (!seen.add(id)) throw new IllegalArgumentException("INPUT_ITEM_ID_INVALID");
                var priorBinding = providerItems.get(id);
                if (priorBinding != null && "OUTPUT".equals(priorBinding.get("kind"))
                        && responseId.equals(priorBinding.get("origin")))
                    throw new IllegalArgumentException("CURRENT_OUTPUT_IS_NOT_INPUT_HISTORY");
                if (item.kind() == ProviderTransport.ItemKind.INSTRUCTIONS) {
                    if (instructionSeen || lastMatched >= 0 || !trusted.instructions().equals(item.text()))
                        throw new IllegalArgumentException("UNEXPECTED_INSTRUCTION_ITEM");
                    instructionSeen = true;
                    bindProviderItem(item.view(), "INSTRUCTIONS", "FIXED_ASSEMBLY");
                    continue;
                }
                int match = -1;
                for (int index = lastMatched + 1; index < expected.size(); index++) {
                    var candidate = expected.get(index);
                    if (!candidate.kind().equals(item.kind().name())) continue;
                    // Match structures, not serialized hashes: map order is irrelevant, while
                    // list order and scalar types/values retain Map/List.equals semantics.
                    if (candidate.kind().equals("USER_INPUT")
                            ? candidate.item().equals(item.nativeEvidence())
                            : candidate.item().equals(item.view())) {
                        match = index; break;
                    }
                }
                if (match < 0) throw new IllegalArgumentException("UNCORRELATED_INPUT_HISTORY");
                var selected = expected.get(match);
                bindProviderItem(item.view(), selected.kind(), selected.origin());
                lastMatched = match;
            }
            if (!page.hasMore()) {
                if (lastMatched != expected.size() - 1)
                    throw new IllegalArgumentException("INPUT_REQUEST_NOT_CORRELATED");
                return;
            }
            after = page.nextCursor();
            if (after == null || after.isEmpty() || !cursors.add(after))
                throw new IllegalArgumentException("INPUT_PAGINATION_MISMATCH");
        }
        throw new IllegalArgumentException("INPUT_READBACK_LIMIT");
    }

    /** Listings may omit history, but every observed item must belong to this ordered host chain. */
    private List<ExpectedItem> retainedInputSequence() {
        List<ExpectedItem> sequence = new ArrayList<>();
        for (var event : evidence.snapshot()) {
            var material = (Map<?, ?>) event.get("material");
            if ("REQUEST_ASSEMBLED".equals(event.get("kind"))) {
                sequence.add(new ExpectedItem(retainedRequestInput(material),
                        "USER_INPUT", "REQUEST:" + material.get("ordinal")));
            } else if ("RESPONSE_CREATED_READOUT".equals(event.get("kind"))
                    && !Objects.equals(material.get("ordinal"), activeRequest.ordinal())) {
                var observed = (Map<?, ?>)material.get("observation");
                for (Object output : (List<?>)observed.get("outputs")) {
                    var summary = (Map<?, ?>)output;
                    var bound = providerItems.get(summary.get("id"));
                    if (bound == null || !"OUTPUT".equals(bound.get("kind"))
                            || !Objects.equals(observed.get("responseId"), bound.get("origin"))
                            || !summary.get("itemFingerprint").equals(Json.evidenceFingerprint(bound.get("item"))))
                        throw new IllegalArgumentException("RETAINED_OUTPUT_HISTORY_CHANGED");
                    sequence.add(new ExpectedItem((Map<?, ?>)bound.get("item"),
                            "OUTPUT", (String)observed.get("responseId")));
                }
            }
        }
        return sequence;
    }

    private Map<String,Object> retainedRequestInput(Map<?,?> material) {
        var input = requestInputs.get(material.get("ordinal"));
        if (input == null || !Json.structuralFingerprint(input).equals(material.get("inputEvidenceFingerprint")))
            throw new IllegalStateException("RETAINED_REQUEST_INPUT_CHANGED");
        return input;
    }

    /** One context-scoped identity for every observed provider ID, independent of listing omissions. */
    private void bindProviderItem(Map<?, ?> item, String kind, String origin) {
        String id = (String)item.get("id");
        var binding = Json.object("itemId", id, "kind", kind, "origin", origin, "item", item);
        var existing = providerItems.get(id);
        if (existing != null) {
            if (!existing.equals(binding)) throw new IllegalArgumentException("PROVIDER_ITEM_ID_CONFLICT");
            return;
        }
        if (kind.equals("USER_INPUT") && providerItems.values().stream().anyMatch(value ->
                kind.equals(value.get("kind")) && origin.equals(value.get("origin"))))
            throw new IllegalArgumentException("PROVIDER_USER_ID_CHANGED");
        evidence.append("PROVIDER_ITEM_BOUND", binding);
        ensureUsable();
        providerItems.put(id, binding);
    }

    /** Local prerequisites only: deliberately grants neither a protocol PASS nor target access. */
    public synchronized void markDiscoveryGateReady() {
        beginOperation();
        try {
            require(State.CREATION_EVIDENCE_CAPTURED);
            try {
                verifyActive();
                if (!readbackCaptured || requestInFlight || role != TrustedInputs.Role.MASTER || lineage.isEmpty())
                    throw stop(State.BLOCKED, "CREATION_EVIDENCE_MISSING");
                state = State.DISCOVERY_GATE_READY;
                evidence.append("LOCAL_DISCOVERY_MATERIAL_READY", observation());
            } catch (RuntimeException failure) { throw stop(State.BLOCKED, "READINESS_EVIDENCE_REJECTED"); }
        } finally { operationActive = false; }
    }

    /** Role selection only. All execution still requires future existing MASTER protocol artifacts. */
    public synchronized void switchRole(String requestedRole, String expectedResponseId) {
        beginOperation();
        try {
            if (state != State.CREATION_EVIDENCE_CAPTURED && state != State.DISCOVERY_GATE_READY)
                throw stop(State.BLOCKED, "INVALID_TRANSITION");
            try {
                verifyActive();
                requireLineage(expectedResponseId);
                TrustedInputs.Role next;
                try { next = TrustedInputs.Role.find(requestedRole); }
                catch (RuntimeException invalid) { throw stop(State.BLOCKED, "UNKNOWN_ROLE"); }
                if (next == role || (role != TrustedInputs.Role.MASTER && next != TrustedInputs.Role.MASTER))
                    throw stop(State.BLOCKED, "NON_SEQUENTIAL_ROLE_SWITCH");
                role = next;
                roleSwitchCounter++;
                evidence.append("ROLE_SELECTED", observation());
            } catch (RuntimeException failure) { throw stop(State.BLOCKED, "ROLE_EVIDENCE_REJECTED"); }
        } finally { operationActive = false; }
    }

    public synchronized void executeSelectedSpecialist() {
        beginOperation();
        try {
            throw stop(State.BLOCKED, "SPECIALIST_PROTOCOL_GATES_NOT_IMPLEMENTED");
        } finally { operationActive = false; }
    }

    public synchronized void requestTargetAccess(String phase) {
        beginOperation();
        try {
            ensureUsable();
            verifyActive();
            try { broker.request(TargetBroker.Phase.valueOf(phase)); }
            catch (Exception denied) { throw stop(State.BLOCKED, "TARGET_ACCESS_DENIED"); }
        } finally { operationActive = false; }
    }

    /** Host metadata route only. There is no model tool, source content read, or source phase grant. */
    public synchronized Map<String, Object> inspectSourceLocator(Path locator) {
        beginOperation();
        try {
            if (trusted == null || sourceBoundary == null || sourceBoundary.metadata() == null)
                throw stop(State.BLOCKED, "SOURCE_METADATA_UNAVAILABLE");
            try {
                verifyActive();
                var metadata = sourceBoundary.inspectLocator(locator);
                evidence.safe(Json.write(metadata));
                evidence.append("SOURCE_LOCATOR_OBSERVED", metadata);
                verifyActive();
                return metadata;
            } catch (Exception failure) { throw stop(State.BLOCKED, "SOURCE_LOCATOR_REJECTED"); }
        } finally { operationActive = false; }
    }

    public synchronized void createNestedAgent() {
        beginOperation();
        try {
            throw stop(State.BLOCKED, "NESTED_AGENT_UNSUPPORTED");
        } finally { operationActive = false; }
    }
    public synchronized void replaceConfiguration(Config ignored) {
        beginOperation();
        try {
            configurationChanged = true;
            throw stop(State.BLOCKED, "CONFIGURATION_FROZEN");
        } finally { operationActive = false; }
    }

    private void requireLineage(String expected) {
        if (expected == null || responseId == null || !expected.equals(responseId) || lineage.isEmpty())
            throw stop(State.BLOCKED, "LINEAGE_MISMATCH");
    }

    private void verifyConfiguration() {
        verifiedConfiguration();
    }

    private Map<String, Object> verifiedConfiguration() {
        var current = runtimeConfiguration();
        String serialized = Json.write(current);
        // Validate this exact callback snapshot before it can supply a retained fingerprint.
        evidence.safe(serialized);
        if (!initializedConfiguration.equals(serialized)) {
            configurationChanged = true;
            throw stop(State.BLOCKED, "CONFIGURATION_CHANGED");
        }
        ensureUsable();
        return current;
    }

    private void verifyActive() {
        ensureUsable();
        try {
            var currentConfiguration = verifiedConfiguration();
            evidence.verify();
            int requestCount = 0;
            for (var event : evidence.snapshot()) {
                if ("REQUEST_ASSEMBLED".equals(event.get("kind"))) {
                    retainedRequestInput((Map<?,?>)event.get("material"));
                    requestCount++;
                }
            }
            if (requestInputs.size() != requestCount)
                throw new IllegalStateException("REQUEST_INPUT_REGISTRY_CHANGED");
            var itemEvidence = evidence.snapshot().stream().filter(e -> "PROVIDER_ITEM_BOUND".equals(e.get("kind")))
                    .map(e -> (Map<?, ?>) e.get("material")).toList();
            if (!itemEvidence.equals(new ArrayList<>(providerItems.values())))
                throw new IllegalStateException("PROVIDER_ITEM_REGISTRY_CHANGED");
            if (trusted != null) trusted.verify(broker.metadata() == null ? declaredTarget.normalize()
                    : Path.of(broker.metadata().resolvedRoot()));
            if (broker.metadata() != null) broker.verify();
            if (sourceBoundary != null && sourceBoundary.metadata() != null) sourceBoundary.verify();
            String previous = null;
            for (int i = 0; i < lineage.size(); i++) {
                var link = lineage.get(i);
                if (!Objects.equals(link.get("ordinal"), i + 1)
                        || !Objects.equals(link.get("previousResponseId"), previous))
                    throw new IllegalStateException("LINEAGE_CHANGED");
                var requests = evidence.snapshot().stream().filter(e -> "REQUEST_ASSEMBLED".equals(e.get("kind")))
                        .map(e -> (Map<?, ?>) e.get("material"))
                        .filter(m -> Objects.equals(m.get("ordinal"), link.get("ordinal"))).toList();
                var responses = evidence.snapshot().stream().filter(e -> "RESPONSE_CREATED_READOUT".equals(e.get("kind")))
                        .map(e -> (Map<?, ?>) e.get("material"))
                        .filter(m -> Objects.equals(m.get("ordinal"), link.get("ordinal"))).toList();
                if (requests.size() != 1 || responses.size() != 1
                        || !Objects.equals(link.get("requestFingerprint"), requests.getFirst().get("requestFingerprint"))
                        || !Objects.equals(link.get("role"), requests.getFirst().get("selectedRole"))
                        || !Objects.equals(link.get("previousResponseId"), requests.getFirst().get("previousResponseId")))
                    throw new IllegalStateException("LINEAGE_EVIDENCE_CHANGED");
                var observedResponse = (Map<?, ?>)responses.getFirst().get("observation");
                if (!Objects.equals(link.get("responseId"), observedResponse.get("responseId"))
                        || !Objects.equals(link.get("previousResponseId"), observedResponse.get("previousId")))
                    throw new IllegalStateException("RESPONSE_LINEAGE_CHANGED");
                previous = (String) link.get("responseId");
            }
            if (!Objects.equals(responseId, previous)) throw new IllegalStateException("LINEAGE_CHANGED");
            if (activeRequest != null) {
                if (!activeRequest.configurationIdentity().equals(Json.evidenceFingerprint(currentConfiguration))
                        || !trusted.instructions().equals(activeRequest.prepared().turn().instructions())
                        || !trusted.assemblyIdentity().equals(activeRequest.prepared().turn().assemblyIdentity())
                        || !activeRequest.body().equals(activeRequest.prepared().body()))
                    throw new IllegalStateException("ACTIVE_REQUEST_CHANGED");
                var matching = evidence.snapshot().stream().filter(e -> "REQUEST_ASSEMBLED".equals(e.get("kind")))
                        .map(e -> (Map<?, ?>) e.get("material"))
                        .filter(m -> Objects.equals(m.get("ordinal"), activeRequest.ordinal())).toList();
                if (matching.size() != 1 || !activeRequest.body().equals(matching.getFirst().get("exactRequestBody"))
                        || !activeRequest.prepared().turn().view().equals(matching.getFirst().get("logicalTurn"))
                        || !Json.structuralFingerprint(activeRequest.prepared().inputEvidence())
                                .equals(matching.getFirst().get("inputEvidenceFingerprint")))
                    throw new IllegalStateException("REQUEST_EVIDENCE_CHANGED");
            }
            if (readbackCaptured && !requestInFlight) {
                var captures = evidence.snapshot().stream().filter(e -> "CREATION_EVIDENCE_CAPTURED".equals(e.get("kind")))
                        .map(e -> (Map<?, ?>) e.get("material"))
                        .filter(m -> Objects.equals(m.get("responseId"), responseId)).toList();
                if (captures.size() != 1 || !Boolean.TRUE.equals(captures.getFirst().get("readbackCaptured"))
                        || !Objects.equals(captures.getFirst().get("responseLineage"), lineage)
                        || !Objects.equals(captures.getFirst().get("activeRequestBody"), activeRequest.body()))
                    throw new IllegalStateException("READBACK_EVIDENCE_MISSING");
            }
            freshnessCounter++;
            ensureUsable();
        } catch (Stop failure) { throw failure; }
        catch (Exception failure) { throw stop(State.BLOCKED, "ACTIVE_STATE_VERIFICATION_FAILED"); }
    }

    /** Fresh provider readout of active objects; no caller-supplied observation fields. */
    public synchronized Map<String, Object> inspect() {
        boolean ownsOperation = beginInspection();
        try {
            if (!terminal()) verifyActive();
            try { return observation(); }
            catch (RuntimeException unavailable) {
                stop(State.BLOCKED, "INSPECTION_UNAVAILABLE");
                // A rejected current configuration is unavailable, never the old configuration
                // presented as still effective. No provider exception or rejected bytes escape.
                return Json.object("format", "HOST_RUNTIME_INSPECTION_V1", "logicalContextId", contextId,
                        "state", state.name(), "targetAccessPhase", broker.phase().name(),
                        "sourceAccessState", sourceBoundary == null ? "NOT_DESIGNATED" : sourceBoundary.state().name(),
                        "configurationChangedSinceInitialization", configurationChanged,
                        "runtimeConfigurationObservation", "UNAVAILABLE", "protocolAcceptance", "NOT_EVALUATED",
                        "failureCode", failureCode);
            }
        } finally { endInspection(ownsOperation); }
    }

    private boolean beginInspection() {
        if (callbackDepth != 0 || inspectionActive) throw stop(State.BLOCKED, "REENTRANT_INSPECTION");
        boolean ownsOperation = !operationActive;
        operationActive = true;
        inspectionActive = true;
        return ownsOperation;
    }

    private void endInspection(boolean ownsOperation) {
        inspectionActive = false;
        if (ownsOperation) operationActive = false;
    }

    private Map<String, Object> observation() {
        var currentConfiguration = runtimeConfiguration();
        evidence.safe(Json.write(currentConfiguration));
        if (!terminal() && !initializedConfiguration.equals(Json.write(currentConfiguration))) {
            configurationChanged = true;
            throw stop(State.BLOCKED, "CONFIGURATION_CHANGED");
        }
        var result = Json.object("format", "HOST_RUNTIME_INSPECTION_V1", "provider", VERSION,
                "logicalContextId", contextId, "state", state.name(), "activeRole", role.id,
                "responseId", responseId, "responseLineage", lineage,
                "providerItemBindings", executionMode ? providerItems.values().stream().map(item -> Json.object(
                        "itemId", item.get("itemId"), "kind", item.get("kind"), "origin", item.get("origin"),
                        "itemFingerprint", Json.evidenceFingerprint(item.get("item")))).toList() : new ArrayList<>(providerItems.values()),
                "trustedSourceRegistry", trusted == null ? List.of() : trusted.registry(),
                "trustedRegistryIdentity", trusted == null ? null : trusted.registryIdentity(),
                "instructionAssemblyIdentity", trusted == null ? null : trusted.assemblyIdentity(),
                "roleProfileBindings", trusted == null ? List.of() : trusted.roleBindings(),
                "runtimeConfiguration", currentConfiguration,
                "runtimeConfigurationIdentity", Json.evidenceFingerprint(currentConfiguration),
                "registeredTools", TOOLS, "targetAccessPhase", broker.phase().name(),
                "targetMetadata", broker.metadata() == null ? null : broker.metadata().view(),
                "targetMetadataIdentity", broker.metadata() == null ? null : Json.evidenceFingerprint(broker.metadata().view()),
                "sourceAccessState", sourceBoundary == null ? "NOT_DESIGNATED" : sourceBoundary.state().name(),
                "sourceMetadata", sourceBoundary == null || sourceBoundary.metadata() == null
                        ? null : sourceBoundary.metadata().view(),
                "sourceMetadataIdentity", sourceBoundary == null || sourceBoundary.metadata() == null
                        ? null : Json.evidenceFingerprint(sourceBoundary.metadata().view()),
                "activeRequestBody", activeRequest == null ? null : activeRequest.body(),
                "activeRequestBinding", activeRequest == null ? null : activeRequest.prepared().turn().binding(),
                "requestInFlight", requestInFlight, "readbackCaptured", readbackCaptured,
                "freshnessCounter", freshnessCounter, "roleSwitchCounter", roleSwitchCounter,
                "configurationChangedSinceInitialization", configurationChanged,
                "protocolAcceptance", "NOT_EVALUATED", "failureCode", failureCode);
        evidence.safe(Json.write(result));
        return result;
    }

    /** Raw definition/configuration/observation material for future canonical artifact construction. */
    public synchronized Map<String, Object> discoveryMaterial() {
        beginOperation();
        try {
            if (state != State.DISCOVERY_GATE_READY) throw stop(State.BLOCKED, "LOCAL_MATERIAL_NOT_READY");
            try {
                verifyActive();
                var definition = Json.object("provider", VERSION, "controlMode", "FIXED_TRUSTED_CONTEXT",
                        "launchMechanism", "HOST_SAME_CONTEXT_SEQUENTIAL", "instructionSelection",
                        "Explicit host root plus ten fixed paths; exact UTF-8 retained before first request; no discovery fallback",
                        "readAndCwdTriggers", "No repository instruction selection; metadata only; source and target content access remain unavailable",
                        "continuation", "Same retained response lineage; identical instructions on every request; no resume or repair",
                        "nestedContexts", "Unavailable; nested creation terminally blocks",
                        "inspection", "ControlledHarness.inspect reads active request/configuration/registry/broker/lineage objects",
                        "correlation", "Exact request, host turn marker and metadata, response ID, retrieve and input-item pages",
                        "freshness", "Recheck retained evidence, trusted bytes, metadata and transport configuration at each boundary",
                        "limitations", List.of("NO_PROTOCOL_ACCEPTANCE", "NO_AUTHENTICATION", "NO_DURABILITY", "POINT_IN_TIME_FILESYSTEM_CHECKS"));
                var observation = observation();
                var effectiveConfiguration = observation.get("runtimeConfiguration");
                evidence.append("PROVIDER_CONTROL_DEFINITION", definition);
                evidence.append("PROVIDER_EFFECTIVE_CONFIGURATION", Json.object("configuration", effectiveConfiguration));
                evidence.append("PROVIDER_OBSERVATION", observation);
                var material = Json.object("materialKind", "RAW_HOST_DISCOVERY_MATERIAL", "protocolAcceptance", "NOT_EVALUATED",
                        "controlDefinition", definition, "controlDefinitionFingerprint", Json.evidenceFingerprint(definition),
                        "effectiveConfiguration", effectiveConfiguration,
                        "effectiveConfigurationFingerprint", Json.evidenceFingerprint(effectiveConfiguration),
                        "currentObservation", observation, "currentObservationFingerprint", Json.evidenceFingerprint(observation),
                        "sessionReference", contextId, "targetScope", broker.metadata().scope(),
                        "sourceMetadata", sourceBoundary == null ? null : sourceBoundary.metadata().view(),
                        "roleProfileBindings", trusted.roleBindings());
                evidence.safe(Json.write(material));
                verifyConfiguration();
                require(State.DISCOVERY_GATE_READY);
                return material;
            } catch (RuntimeException failure) { throw stop(State.BLOCKED, "DISCOVERY_MATERIAL_REJECTED"); }
        } finally { operationActive = false; }
    }

    public synchronized String evidenceJson() {
        boolean ownsOperation = beginInspection();
        try {
            if (!terminal()) verifyActive();
            try { return evidence.serialize(); }
            catch (RuntimeException failure) { throw stop(State.BLOCKED, "EVIDENCE_UNAVAILABLE"); }
        } finally { endInspection(ownsOperation); }
    }

    public synchronized void close() {
        if (!terminal()) {
            state = State.CLOSED;
            broker.close();
            if (sourceBoundary != null) sourceBoundary.close();
            try { evidence.append("CLOSED", Json.object("logicalContextId", contextId)); }
            catch (RuntimeException unavailable) { failureCode = "CLOSE_EVIDENCE_UNAVAILABLE"; }
        }
    }

    private boolean terminal() { return state == State.BLOCKED || state == State.FAILED || state == State.CLOSED; }
    private void ensureUsable() { if (terminal()) throw new Stop(failureCode == null ? "CONTEXT_CLOSED" : failureCode); }
    private void require(State expected) {
        ensureUsable();
        if (state != expected || requestInFlight) throw stop(State.BLOCKED, "INVALID_TRANSITION");
    }
    private Stop stop(State terminalState, String code) {
        if (!terminal()) {
            state = terminalState;
            failureCode = code;
            broker.close();
            if (sourceBoundary != null) sourceBoundary.close();
            try { evidence.append("STOPPED", Json.object("logicalContextId", contextId, "state", state.name(), "code", code)); }
            catch (RuntimeException unavailable) { /* Preserve terminal state even if evidence itself is corrupt/unavailable. */ }
        }
        return new Stop(failureCode == null ? "CONTEXT_CLOSED" : failureCode);
    }
}
