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
                           String body, Map<String, Object> configurationIdentity) {
        Map<String, Object> identity() {
            return Json.fingerprint("RUNTIME_DISCOVERY_EVIDENCE", body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record ExpectedItem(Map<?, ?> item, String kind, String origin) {}

    private static final String VERSION = "controlled-responses-harness/1";
    private static final List<Object> TOOLS = List.of();
    private final Path trustedRoot;
    private final Path declaredSource;
    private final Path declaredTarget;
    private final Config config;
    private final ResponsesClient client;
    private final boolean executionMode;
    private final TargetBroker broker = new TargetBroker();
    private final SourceBoundary sourceBoundary;
    private final Evidence evidence;
    private final String contextId = UUID.randomUUID().toString();
    private final List<Map<String, Object>> lineage = new ArrayList<>();
    private final Map<String, Map<String, Object>> providerItems = new LinkedHashMap<>();
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

    /** Arguments are trusted host input, never target-derived configuration or model output. */
    public ControlledHarness(Path trustedRoot, Path declaredTarget, Config config, ResponsesClient client) {
        this(trustedRoot, null, declaredTarget, config, client);
    }

    /** Optional source designation adds metadata checks only; it does not enable source analysis. */
    public ControlledHarness(Path trustedRoot, Path declaredSource, Path declaredTarget,
                             Config config, ResponsesClient client) {
        this(trustedRoot, declaredSource, declaredTarget, config, client, false);
    }

    /** Only the host coordinator creates this profile; legacy foundation entry points stay closed. */
    ControlledHarness(Path trustedRoot, Path declaredSource, Path declaredTarget,
                      Config config, ResponsesClient client, boolean executionMode) {
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
                "controlMode", "FIXED_TRUSTED_CONTEXT", "launchMechanism", "RESPONSES_SAME_CONTEXT_SEQUENTIAL",
                "startupCwd", startupCwd, "trustedRootDesignation", trustedRoot.toString(),
                "sourceRootDesignation", declaredSource == null ? null : declaredSource.toString(),
                "model", config.model(), "maxOutputTokens", config.maxOutputTokens(), "tools", TOOLS,
                "toolChoice", "none", "store", true, "stream", false, "background", false,
                "parallelToolCalls", false, "truncation", "disabled",
                "transport", providerCallback(client::configuration));
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
        return providerTurn(previous, null, null);
    }

    /** Host-only transport path. The coordinator, never response data, supplies role and binding. */
    synchronized String executionTurn(TrustedInputs.Role selectedRole, String expectedPrevious,
                                      Map<String, Object> binding, Map<String, Object> data,
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
                    "data", Json.object("format", "UNTRUSTED_DATA_V1", "value", data)));
            return providerTurn(responseId, marker, Objects.requireNonNull(validateStructuredOutput));
        } finally { operationActive = false; }
    }

    private String providerTurn(String previous, String executionMarker, Consumer<String> validateStructuredOutput) {
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
            var metadata = Json.object("host_context", contextId, "host_turn", Integer.toString(ordinal),
                    "host_role", role.id);
            Map<String, Object> request = Json.object("model", config.model(), "instructions", trusted.instructions(),
                    "input", List.of(Json.object("type", "message", "role", "user", "content",
                            List.of(Json.object("type", "input_text", "text", marker)))),
                    "previous_response_id", previous, "metadata", metadata, "tools", TOOLS,
                    "tool_choice", "none", "parallel_tool_calls", false, "store", true,
                    "stream", false, "background", false, "truncation", "disabled",
                    "max_output_tokens", config.maxOutputTokens());
            Request candidateRequest = new Request(ordinal, role.id, previous, marker, Json.write(request),
                    Json.evidenceFingerprint(verifiedConfiguration()));
            evidence.safe(candidateRequest.body());
            activeRequest = candidateRequest;
            evidence.append("REQUEST_ASSEMBLED", Json.object("logicalContextId", contextId,
                    "selectedRole", role.id, "ordinal", ordinal, "previousResponseId", previous,
                    "exactRequestBody", activeRequest.body(), "requestFingerprint", activeRequest.identity(),
                    "configurationFingerprint", activeRequest.configurationIdentity()));
            verifyActive();
            ensureUsable();
            if (!Objects.equals(activeRequest.previous(), responseId)
                    || activeRequest.ordinal() != lineage.size() + 1
                    || !Objects.equals(Json.parse(activeRequest.body()).get("previous_response_id"), responseId))
                throw stop(State.BLOCKED, "DISPATCH_PREDECESSOR_CHANGED");
            // This is exactly the immutable body exposed by inspect(); no adapter-side reassembly.
            String raw = client.create(activeRequest.body());
            ensureUsable();
            if (raw == null || raw.length() > 4 * 1024 * 1024)
                throw new IllegalArgumentException("RESPONSE_BODY_LIMIT");
            evidence.safe(raw);
            Map<String, Object> created = validateResponse(raw, request, null);
            if (validateStructuredOutput != null) validateStructuredOutput.accept(structuredText(created));
            ensureUsable();
            String returnedId = (String) created.get("id");
            if (lineage.stream().anyMatch(link -> returnedId.equals(link.get("responseId"))))
                throw new IllegalArgumentException("RESPONSE_ID_REUSED");
            // Check every ID before retaining any part of a conflicting response.
            for (Object output : (List<?>) created.get("output")) {
                Map<?, ?> item = (Map<?, ?>) output;
                if (providerItems.containsKey((String) item.get("id")))
                    throw new IllegalArgumentException("PROVIDER_OUTPUT_ID_REUSED");
            }
            evidence.append("RESPONSE_CREATED_READOUT", Json.object("ordinal", ordinal, "rawBody", raw));
            for (Object output : (List<?>) created.get("output")) {
                Map<?, ?> item = (Map<?, ?>) output;
                bindProviderItem(item, "OUTPUT", returnedId);
            }
            ensureUsable();
            responseId = returnedId;
            lineage.add(Json.object("ordinal", ordinal, "role", role.id, "responseId", responseId,
                    "previousResponseId", previous, "requestFingerprint", activeRequest.identity()));
            state = State.MASTER_CONTEXT_CREATED;
            verifyActive();
            stage = "READBACK";
            String retrievedRaw = client.retrieve(responseId);
            ensureUsable();
            if (retrievedRaw == null || retrievedRaw.length() > 4 * 1024 * 1024)
                throw new IllegalArgumentException("RESPONSE_BODY_LIMIT");
            evidence.safe(retrievedRaw);
            var retrieved = validateResponse(retrievedRaw, request, responseId);
            if (!Objects.equals(created.get("output"), retrieved.get("output"))
                    || !Objects.equals(created.get("created_at"), retrieved.get("created_at")))
                throw new IllegalArgumentException("RESPONSE_READBACK_CHANGED");
            evidence.append("RESPONSE_RETRIEVED", Json.object("responseId", responseId, "rawBody", retrievedRaw));
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

    private static String structuredText(Map<String, Object> response) {
        List<String> texts = new ArrayList<>();
        for (Object itemValue : (List<?>) response.get("output")) {
            Map<?, ?> item = (Map<?, ?>) itemValue;
            if (!"message".equals(item.get("type"))) continue;
            for (Object partValue : (List<?>) item.get("content")) {
                Map<?, ?> part = (Map<?, ?>) partValue;
                if (!"output_text".equals(part.get("type")))
                    throw new IllegalArgumentException("STRUCTURED_RESPONSE_REQUIRED");
                texts.add((String) part.get("text"));
            }
        }
        if (texts.size() != 1) throw new IllegalArgumentException("ONE_STRUCTURED_RESPONSE_REQUIRED");
        return texts.getFirst();
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

    private Map<String, Object> validateResponse(String raw, Map<String, Object> request, String expectedId) {
        var response = Json.parse(raw);
        Object id = response.get("id");
        if (!(id instanceof String value) || !value.matches("resp_[A-Za-z0-9_-]{1,200}")
                || (expectedId != null && !expectedId.equals(value))
                || !"response".equals(response.get("object")) || !"completed".equals(response.get("status"))
                || !response.containsKey("error") || response.get("error") != null
                || !response.containsKey("incomplete_details") || response.get("incomplete_details") != null
                || !(response.get("created_at") instanceof Number timestamp)
                || !Double.isFinite(timestamp.doubleValue()) || timestamp.doubleValue() < 0
                || !(response.get("output") instanceof List<?>))
            throw new IllegalArgumentException("RESPONSE_REQUIRED_FIELDS");
        for (String field : List.of("model", "instructions", "previous_response_id", "metadata", "tools",
                "tool_choice", "parallel_tool_calls", "store", "background", "truncation", "max_output_tokens")) {
            if (!response.containsKey(field) || !Objects.equals(request.get(field), response.get(field)))
                throw new IllegalArgumentException("RESPONSE_CONFIGURATION_MISMATCH");
        }
        for (String field : List.of("conversation", "prompt", "context_management")) {
            if (response.get(field) != null) throw new IllegalArgumentException("UNBOUND_CONTEXT_CONFIGURATION");
        }
        // No function, hosted tool, handoff, or child-agent output is permitted by this empty tool registry.
        Set<String> outputIds = new HashSet<>();
        for (Object output : (List<?>) response.get("output")) {
            if (!(output instanceof Map<?, ?> item)
                    || !(item.get("id") instanceof String itemId)
                    || !itemId.matches("[A-Za-z][A-Za-z0-9_-]{0,255}") || !outputIds.add(itemId)
                    || !("reasoning".equals(item.get("type"))
                    || ("message".equals(item.get("type")) && "assistant".equals(item.get("role")))))
                throw new IllegalArgumentException("UNREGISTERED_TOOL_OR_CHILD");
            if ("message".equals(item.get("type"))) {
                if (!"completed".equals(item.get("status")) || !(item.get("content") instanceof List<?> content)
                        || content.isEmpty()) throw new IllegalArgumentException("INCOMPLETE_OUTPUT_MESSAGE");
                for (Object part : content) {
                    if (!(part instanceof Map<?, ?> entry)
                            || !("output_text".equals(entry.get("type")) && entry.get("text") instanceof String
                            || "refusal".equals(entry.get("type")) && entry.get("refusal") instanceof String))
                        throw new IllegalArgumentException("UNSUPPORTED_OUTPUT_CONTENT");
                }
            } else if (!(item.get("summary") instanceof List<?>)) {
                throw new IllegalArgumentException("INCOMPLETE_REASONING_ITEM");
            }
        }
        return response;
    }

    private void captureInputItems() throws Exception {
        String after = null;
        Set<String> seen = new HashSet<>();
        List<ExpectedItem> expected = retainedInputSequence();
        int lastMatched = -1;
        boolean instructionSeen = false;
        for (int pageNumber = 1; pageNumber <= 16; pageNumber++) {
            verifyActive();
            String raw = client.inputItems(responseId, after);
            ensureUsable();
            evidence.safe(raw);
            evidence.append("INPUT_ITEMS_RETRIEVED", Json.object("responseId", responseId,
                    "pageNumber", pageNumber, "after", after, "order", "asc", "rawBody", raw));
            var page = Json.parse(raw);
            if (!"list".equals(page.get("object")) || !(page.get("data") instanceof List<?> items)
                    || items.isEmpty() || items.size() > 100 || !(page.get("has_more") instanceof Boolean))
                throw new IllegalArgumentException("INPUT_ITEMS_REQUIRED_FIELDS");
            String firstId = null, lastId = null;
            for (Object value : items) {
                if (!(value instanceof Map<?, ?> item) || !(item.get("id") instanceof String id)
                        || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,255}") || !seen.add(id))
                    throw new IllegalArgumentException("INPUT_ITEM_ID_INVALID");
                if (firstId == null) firstId = id;
                lastId = id;
                var priorBinding = providerItems.get(id);
                if (priorBinding != null && "OUTPUT".equals(priorBinding.get("kind"))
                        && responseId.equals(priorBinding.get("origin")))
                    throw new IllegalArgumentException("CURRENT_OUTPUT_IS_NOT_INPUT_HISTORY");
                if ("message".equals(item.get("type"))
                        && ("system".equals(item.get("role")) || "developer".equals(item.get("role")))) {
                    if (instructionSeen || lastMatched >= 0 || !trusted.instructions().equals(messageText(item))
                            || (item.containsKey("status") && !"completed".equals(item.get("status")))
                            || !Set.of("id", "type", "role", "content", "status").containsAll(item.keySet()))
                        throw new IllegalArgumentException("UNEXPECTED_INSTRUCTION_ITEM");
                    instructionSeen = true;
                    bindProviderItem(item, "INSTRUCTIONS", "FIXED_ASSEMBLY");
                    continue;
                }
                int match = -1;
                for (int index = lastMatched + 1; index < expected.size(); index++) {
                    if (matchesInputItem(expected.get(index).item(), item)) { match = index; break; }
                }
                if (match < 0) throw new IllegalArgumentException("UNCORRELATED_INPUT_HISTORY");
                var selected = expected.get(match);
                bindProviderItem(item, selected.kind(), selected.origin());
                lastMatched = match;
            }
            if (!Objects.equals(firstId, page.get("first_id")) || !Objects.equals(lastId, page.get("last_id")))
                throw new IllegalArgumentException("INPUT_PAGINATION_MISMATCH");
            if (Boolean.FALSE.equals(page.get("has_more"))) {
                if (lastMatched != expected.size() - 1)
                    throw new IllegalArgumentException("INPUT_REQUEST_NOT_CORRELATED");
                return;
            }
            after = lastId;
        }
        throw new IllegalArgumentException("INPUT_READBACK_LIMIT");
    }

    /** API listings may omit historical items. Every returned item must nevertheless be an
     * ordered, unique member of this retained chain; no new context can hide before the marker. */
    private List<ExpectedItem> retainedInputSequence() {
        List<ExpectedItem> sequence = new ArrayList<>();
        for (var event : evidence.snapshot()) {
            var material = (Map<?, ?>) event.get("material");
            if ("REQUEST_ASSEMBLED".equals(event.get("kind"))) {
                var request = Json.parse((String) material.get("exactRequestBody"));
                sequence.add(new ExpectedItem((Map<?, ?>) ((List<?>) request.get("input")).getFirst(),
                        "USER_INPUT", "REQUEST:" + material.get("ordinal")));
            } else if ("RESPONSE_CREATED_READOUT".equals(event.get("kind"))
                    && !Objects.equals(material.get("ordinal"), activeRequest.ordinal())) {
                var response = Json.parse((String) material.get("rawBody"));
                for (Object output : (List<?>) response.get("output"))
                    sequence.add(new ExpectedItem((Map<?, ?>) output, "OUTPUT", (String) response.get("id")));
            }
        }
        return sequence;
    }

    /** One context-scoped identity for every observed provider ID, independent of listing omissions. */
    private void bindProviderItem(Map<?, ?> item, String kind, String origin) {
        String id = (String) item.get("id");
        Object canonicalItem = item;
        if (!kind.equals("OUTPUT")) {
            // User/instruction readback may add completed status; normalize only that documented default.
            canonicalItem = Json.object("id", id, "type", item.get("type"), "role", item.get("role"),
                    "content", item.get("content"), "status", "completed");
        }
        var binding = Json.object("itemId", id, "kind", kind, "origin", origin, "item", canonicalItem);
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

    private static boolean matchesInputItem(Map<?, ?> expected, Map<?, ?> actual) {
        if ("message".equals(expected.get("type")) && "user".equals(expected.get("role"))) {
            if (!Set.of("id", "type", "role", "content", "status").containsAll(actual.keySet())
                    || (actual.containsKey("status") && !"completed".equals(actual.get("status")))) return false;
            return Objects.equals(expected.get("type"), actual.get("type"))
                    && Objects.equals(expected.get("role"), actual.get("role"))
                    && Objects.equals(expected.get("content"), actual.get("content"));
        }
        // Reasoning and assistant outputs have provider IDs and must equal already retained
        // completed output items, including their IDs/content. Unknown tool/child items cannot match.
        return expected.equals(actual);
    }

    private static String messageText(Map<?, ?> item) {
        if (!(item.get("content") instanceof List<?> content)) throw new IllegalArgumentException("INPUT_CONTENT");
        StringBuilder text = new StringBuilder();
        for (Object value : content) {
            if (!(value instanceof Map<?, ?> part) || !(part.get("text") instanceof String fragment)
                    || !"input_text".equals(part.get("type")) || !Set.of("type", "text").equals(part.keySet()))
                throw new IllegalArgumentException("UNSUPPORTED_INPUT_CONTENT");
            text.append(fragment);
        }
        return text.toString();
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
                var observedResponse = Json.parse((String) responses.getFirst().get("rawBody"));
                if (!Objects.equals(link.get("responseId"), observedResponse.get("id"))
                        || !Objects.equals(link.get("previousResponseId"), observedResponse.get("previous_response_id")))
                    throw new IllegalStateException("RESPONSE_LINEAGE_CHANGED");
                previous = (String) link.get("responseId");
            }
            if (!Objects.equals(responseId, previous)) throw new IllegalStateException("LINEAGE_CHANGED");
            if (activeRequest != null) {
                if (!activeRequest.configurationIdentity().equals(Json.evidenceFingerprint(currentConfiguration))
                        || !trusted.instructions().equals(Json.parse(activeRequest.body()).get("instructions")))
                    throw new IllegalStateException("ACTIVE_REQUEST_CHANGED");
                var matching = evidence.snapshot().stream().filter(e -> "REQUEST_ASSEMBLED".equals(e.get("kind")))
                        .map(e -> (Map<?, ?>) e.get("material"))
                        .filter(m -> Objects.equals(m.get("ordinal"), activeRequest.ordinal())).toList();
                if (matching.size() != 1 || !activeRequest.body().equals(matching.getFirst().get("exactRequestBody")))
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
                "providerItemBindings", new ArrayList<>(providerItems.values()),
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
                        "launchMechanism", "RESPONSES_SAME_CONTEXT_SEQUENTIAL", "instructionSelection",
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
