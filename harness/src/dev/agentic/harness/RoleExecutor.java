package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** One host-selected invocation at a time, using host-correlated provider observations. */
final class RoleExecutor {
    static final int MAX_TURNS = 96;
    record Invocation(String id, TrustedInputs.Role role, String predecessorInvocationId,
                      Map<String,Object> bundleFingerprint, List<Map<String,Object>> inputFingerprints,
                      Map<String,Object> invocationArtifactFingerprint) {}
    record Reply(String kind, String artifactText, Map<String,Object> artifactFingerprint,
                 Map<String,Object> toolRequest, Object decision, List<String> reasons,
                 Map<String,Object> binding, String providerResponseId) {}
    private final ControlledHarness harness;
    private final Evidence evidence;
    private final List<Map<String,Object>> history = new ArrayList<>();
    private final Set<String> operationIds = new HashSet<>();
    private final String runId;
    private final Map<String,Object> hostTaskContext;
    private Invocation active;
    private String previousResponseId, previousInvocationId;
    private int invocationCounter, turnCounter;

    RoleExecutor(ControlledHarness harness, Evidence evidence) { this(harness, evidence, null); }
    RoleExecutor(ControlledHarness harness, Evidence evidence, byte[] staticResolution) {
        this.harness = harness; this.evidence = evidence; this.runId = harness.executionContextId();
        this.hostTaskContext = staticResolution == null ? null : MigrationInput.immutable(Json.object(
                "format", "HOST_STATIC_TASK_CONTEXT_V1", "exactText", MigrationInput.utf8(staticResolution),
                "fingerprint", Json.fingerprint("CALLER_RESOLUTION", staticResolution)));
    }
    String nextInvocationId() {
        if (active != null) throw new IllegalStateException("INVOCATION_ALREADY_ACTIVE");
        return runId + "-invocation-" + (invocationCounter + 1);
    }
    Invocation begin(TrustedInputs.Role role, Map<String,Object> bundle,
                     List<Map<String,Object>> inputs, Map<String,Object> invocationArtifact) {
        if (active != null) throw new IllegalStateException("INVOCATION_ALREADY_ACTIVE");
        harness.verifyExecutionBoundary();
        active = new Invocation(runId + "-invocation-" + ++invocationCounter, Objects.requireNonNull(role),
                previousInvocationId, bundle, List.copyOf(inputs), invocationArtifact);
        evidence.append("INVOCATION_STARTED", Json.object("runId", runId, "invocationId", active.id(),
                "role", role.id, "predecessorInvocationId", previousInvocationId,
                "predecessorResponseId", previousResponseId));
        return active;
    }
    Reply turn(Invocation invocation, Map<String,Object> data, String artifactRole,
               Consumer<String> artifactValidator) {
        if (active != invocation) throw new IllegalArgumentException("STALE_INVOCATION");
        if (++turnCounter > MAX_TURNS) throw new IllegalStateException("PROVIDER_TURN_BUDGET_EXHAUSTED");
        Map<String,Object> binding = Json.object("runId", runId, "sessionId", runId,
                "invocationId", invocation.id(), "role", invocation.role().id,
                "predecessorInvocationId", invocation.predecessorInvocationId(),
                "previousResponseId", previousResponseId, "turnOrdinal", turnCounter,
                "specificationFingerprint", specification(invocation.role()),
                "instructionFingerprint", harness.executionTrustedInputs().assemblyIdentity(),
                "runAuthorityBundleFingerprint", invocation.bundleFingerprint(),
                "inputArtifactFingerprints", invocation.inputFingerprints(),
                "invocationArtifactFingerprint", invocation.invocationArtifactFingerprint());
        Reply[] candidate = new Reply[1];
        String response = harness.executionTurn(invocation.role(), previousResponseId, binding, hostTaskContext, data, text -> {
            evidence.safe(text);
            Map<String,Object> output = Json.parse(text);
            ExecutionPlan.fields(output, Set.of("format", "binding", "kind", "artifactText", "artifactFingerprint",
                    "toolRequest", "decision", "reasons"));
            if (!"HOST_EXECUTION_RESPONSE_V1".equals(output.get("format")) || !binding.equals(output.get("binding")))
                throw new IllegalArgumentException("RESPONSE_INVOCATION_MISMATCH");
            String kind = ExecutionPlan.string(output.get("kind"));
            List<String> reasons = ExecutionPlan.strings(output.get("reasons"));
            String artifactText = null; Map<String,Object> fingerprint = null, tool = null;
            Object decision = output.get("decision");
            switch (kind) {
                case "ARTIFACT" -> {
                    if (invocation.role() == TrustedInputs.Role.MASTER || artifactRole == null
                            || output.get("toolRequest") != null || decision != null)
                        throw new IllegalArgumentException("ARTIFACT_RESPONSE_SHAPE");
                    // Artifact payloads are exact text, not identifiers: legal JSON whitespace
                    // (including newlines) must survive unchanged through hashing and handoff.
                    if (!(output.get("artifactText") instanceof String exactText) || exactText.isBlank()
                            || !StandardCharsets.UTF_8.newEncoder().canEncode(exactText))
                        throw new IllegalArgumentException("ARTIFACT_TEXT_REQUIRED");
                    artifactText = exactText;
                    fingerprint = Json.fingerprint(artifactRole, artifactText.getBytes(StandardCharsets.UTF_8));
                    // Null requests host binding. A supplied legacy hash is only a checked claim,
                    // never authority. The exact provider artifact bytes are not rewritten.
                    if (output.get("artifactFingerprint") != null && !fingerprint.equals(output.get("artifactFingerprint")))
                        throw new IllegalArgumentException("ARTIFACT_FINGERPRINT_MISMATCH");
                    artifactValidator.accept(artifactText);
                }
                case "TOOL_REQUEST" -> {
                    if (invocation.role() == TrustedInputs.Role.MASTER || output.get("artifactText") != null
                            || output.get("artifactFingerprint") != null || decision != null || !reasons.isEmpty())
                        throw new IllegalArgumentException("TOOL_RESPONSE_SHAPE");
                    tool = ExecutionPlan.map(output.get("toolRequest"));
                    validateTool(tool, invocation);
                }
                case "DECISION" -> {
                    if (invocation.role() != TrustedInputs.Role.MASTER || output.get("artifactText") != null
                            || output.get("artifactFingerprint") != null || output.get("toolRequest") != null)
                        throw new IllegalArgumentException("MASTER_DECISION_REQUIRED");
                    if (!Objects.equals(data.get("proposedDecision"), decision)
                            && !(Set.of("BLOCKED", "FAILED").contains(decision) && !reasons.isEmpty()))
                        throw new IllegalArgumentException("MASTER_DECISION_MISMATCH");
                }
                default -> throw new IllegalArgumentException("UNKNOWN_RESPONSE_KIND");
            }
            candidate[0] = new Reply(kind, artifactText, fingerprint, tool, decision, reasons, binding, null);
        });
        // Readback and provider-item checks have completed before any tools or handoffs can run.
        Reply value = Objects.requireNonNull(candidate[0]);
        Reply reply = new Reply(value.kind(), value.artifactText(), value.artifactFingerprint(), value.toolRequest(),
                value.decision(), value.reasons(), binding, response);
        if (reply.toolRequest() != null) operationIds.add((String) reply.toolRequest().get("operationId"));
        Map<String,Object> record = Json.object("binding", binding, "providerResponseId", response,
                "kind", reply.kind(), "outputArtifactFingerprint", reply.artifactFingerprint());
        evidence.append("INVOCATION_TURN_VERIFIED", record);
        history.add(record);
        previousResponseId = response;
        return reply;
    }

    private void validateTool(Map<String,Object> tool, Invocation invocation) {
        if ("RUN_VALIDATION".equals(tool.get("operation"))) {
            ExecutionPlan.fields(tool, Set.of("operationId", "operation", "invocationId", "role", "authorityId"));
            if (invocation.role() != TrustedInputs.Role.VALIDATION || !invocation.id().equals(tool.get("invocationId"))
                    || !invocation.role().id.equals(tool.get("role"))) throw new IllegalArgumentException("VALIDATION_INVOCATION_MISMATCH");
            ExecutionPlan.string(tool.get("authorityId"));
            String id = ExecutionPlan.string(tool.get("operationId"));
            if (operationIds.contains(id)) throw new IllegalArgumentException("TOOL_REPLAY_DENIED");
            return;
        }
        Set<String> fields = new HashSet<>(Set.of("operationId", "invocationId", "role", "operation", "scope",
                "rootFilesystemIdentity", "path", "query", "expectedBeforeFingerprint", "content"));
        boolean mutation = Set.of("CREATE_TARGET_FILE", "WRITE_TARGET_TEXT").contains(tool.get("operation"));
        if (mutation) { fields.add("grantId"); ExecutionPlan.string(tool.get("grantId")); }
        ExecutionPlan.fields(tool, fields);
        String operationId = ExecutionPlan.string(tool.get("operationId"));
        if (operationIds.contains(operationId)) throw new IllegalArgumentException("TOOL_REPLAY_DENIED");
        if (!invocation.id().equals(tool.get("invocationId")) || !invocation.role().id.equals(tool.get("role")))
            throw new IllegalArgumentException("TOOL_INVOCATION_MISMATCH");
        for (String field : List.of("operation", "scope", "rootFilesystemIdentity")) ExecutionPlan.string(tool.get(field));
        if (!(tool.get("path") instanceof String)) throw new IllegalArgumentException("TOOL_PATH_REQUIRED");
        for (String field : List.of("query", "content"))
            if (tool.get(field) != null && !(tool.get(field) instanceof String)) throw new IllegalArgumentException("TOOL_FIELD_TYPE");
        if (tool.get("expectedBeforeFingerprint") != null) ExecutionPlan.map(tool.get("expectedBeforeFingerprint"));
    }
    void finish(Invocation invocation) {
        if (active != invocation) throw new IllegalArgumentException("STALE_INVOCATION");
        previousInvocationId = invocation.id(); active = null;
    }
    Map<String,Object> specification(TrustedInputs.Role role) {
        return ExecutionPlan.map(harness.executionTrustedInputs().roleBindings().stream()
                .filter(value -> role.id.equals(value.get("role"))).findFirst().orElseThrow().get("specificationFingerprint"));
    }
    String runId() { return runId; }
    List<Map<String,Object>> history() { return List.copyOf(history); }
}
