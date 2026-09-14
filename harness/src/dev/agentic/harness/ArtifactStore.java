package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Current-session exact-byte handoffs. Candidate receipt never supplies acceptance credit. */
final class ArtifactStore {
    record Artifact(String role, String text, Map<String,Object> fingerprint,
                    Map<String,Object> binding, String providerResponseId,
                    String acceptanceInvocationId, String acceptanceProviderResponseId) {
        byte[] bytes() { return text.getBytes(StandardCharsets.UTF_8); }
        Map<String,Object> view() {
            return Json.object("role", role, "exactText", text, "fingerprint", fingerprint,
                    "invocationBinding", binding, "providerResponseId", providerResponseId,
                    "acceptanceStatus", "ACCEPTED", "acceptanceInvocationId", acceptanceInvocationId,
                    "acceptanceProviderResponseId", acceptanceProviderResponseId);
        }
    }
    private final Map<String,Artifact> accepted = new LinkedHashMap<>();
    private final Evidence evidence;

    ArtifactStore(Evidence evidence) { this.evidence = evidence; }

    void accept(String role, String exactText, Map<String,Object> binding, String providerResponseId,
                String masterInvocationId, String masterResponseId) {
        if (accepted.containsKey(role)) throw new IllegalArgumentException("ARTIFACT_SUBSTITUTION_DENIED");
        evidence.safe(exactText);
        Artifact value = new Artifact(role, exactText,
                Json.fingerprint(role, exactText.getBytes(StandardCharsets.UTF_8)),
                Json.object("binding", binding).get("binding") instanceof Map<?,?> map
                        ? ExecutionPlan.map(map) : null,
                providerResponseId, masterInvocationId, masterResponseId);
        evidence.append("ANALYSIS_OR_PLAN_ACCEPTED", value.view());
        accepted.put(role, value);
    }

    Artifact required(String role) {
        verify();
        Artifact value = accepted.get(role);
        if (value == null) throw new IllegalStateException("ACCEPTED_ARTIFACT_REQUIRED");
        return value;
    }
    Map<String,byte[]> bytes() {
        verify();
        Map<String,byte[]> result = new LinkedHashMap<>();
        accepted.forEach((role,artifact) -> result.put(role, artifact.bytes()));
        return Collections.unmodifiableMap(result);
    }
    List<Map<String,Object>> views() { verify(); return accepted.values().stream().map(Artifact::view).toList(); }
    List<Map<String,Object>> fingerprints() {
        verify(); return accepted.values().stream().map(Artifact::fingerprint).toList();
    }
    private void verify() {
        evidence.verify();
        for (Artifact artifact : accepted.values())
            if (!artifact.fingerprint().equals(Json.fingerprint(artifact.role(), artifact.bytes())))
                throw new IllegalStateException("ACCEPTED_ARTIFACT_CHANGED");
    }
}
