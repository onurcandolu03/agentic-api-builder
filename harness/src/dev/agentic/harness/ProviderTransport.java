package dev.agentic.harness;

import java.util.*;

/** Trusted host adapter contract, never selected or implemented by migration/model data.
 * Adapters decode protocol facts; only the host correlates history and grants authority.
 * IDs and native evidence are opaque. Missing required observations must fail closed.
 * Every turn forbids provider-native tools/children, truncation, context reset and fallback.
 * Adapters must enforce these fixed restrictions and report actual configuration echoes.
 */
public interface ProviderTransport {
    record Capabilities(boolean independentReadback, boolean orderedContext, boolean frozenDispatch) {
        void requireSupported() {
            if (!independentReadback || !orderedContext || !frozenDispatch)
                throw new IllegalArgumentException("PROVIDER_CAPABILITIES_REQUIRED");
        }
    }

    record Turn(String instructions, Map<String,Object> assemblyIdentity, Map<String,Object> binding,
                String input, String previousId, String model, int maxOutputTokens,
                Map<String,Object> correlation) {
        public Turn {
            Objects.requireNonNull(instructions); Objects.requireNonNull(input);
            Json.identifier(model);
            if (maxOutputTokens < 1) throw new IllegalArgumentException("INVALID_TOKEN_LIMIT");
            assemblyIdentity = MigrationInput.immutable(Objects.requireNonNull(assemblyIdentity));
            binding = MigrationInput.immutable(binding);
            correlation = MigrationInput.immutable(Objects.requireNonNull(correlation));
        }
        public Map<String,Object> echo() {
            return Json.object("instructions", instructions, "previousId", previousId,
                    "model", model, "maxOutputTokens", maxOutputTokens, "correlation", correlation);
        }
        Map<String,Object> view() {
            // Exact instructions/input already live in the frozen turn and screened wire evidence.
            return Json.object("assemblyIdentity", assemblyIdentity, "binding", binding,
                    "echoFingerprint", Json.evidenceFingerprint(echo()),
                    "inputFingerprint", Json.evidenceFingerprint(input));
        }
    }

    /** Immutable exact outbound payload. Dispatch MUST send this payload without reconstruction.
     * inputEvidence is protocol-decoded comparison material for the exact logical input.
     */
    record Prepared(Turn turn, String body, Map<String,Object> inputEvidence) {
        public Prepared {
            Objects.requireNonNull(turn); Objects.requireNonNull(body);
            inputEvidence = MigrationInput.immutable(Objects.requireNonNull(inputEvidence));
        }
    }

    enum ItemKind { USER_INPUT, INSTRUCTIONS, OUTPUT }
    /** nativeEvidence is immutable comparison material, never interpreted as host authority.
     * USER_INPUT evidence must match Prepared.inputEvidence for the exact submitted input;
     * INSTRUCTIONS.text is the exact observed assembly; OUTPUT retains all native item facts.
     * Protocol-only defaults may be decoded consistently, but output text must never be changed.
     */
    record Item(String id, ItemKind kind, String text, Map<String,Object> nativeEvidence) {
        public Item {
            opaqueId(id); Objects.requireNonNull(kind);
            nativeEvidence = MigrationInput.immutable(Objects.requireNonNull(nativeEvidence));
        }
        Map<String,Object> view() {
            return Json.object("id", id, "kind", kind.name(), "text", text, "nativeEvidence", nativeEvidence);
        }
        Map<String,Object> summary() {
            return Json.object("id", id, "kind", kind.name(),
                    "itemFingerprint", Json.evidenceFingerprint(view()));
        }
    }

    /** configurationEcho uses Turn.echo's logical schema, decoded from actual provider facts.
     * outputText is the exact single structured text when the turn has an invocation binding.
     * creationIdentity binds stable provider creation facts; readback must independently retrieve
     * them and output items, never return the adapter's cached creation observation.
     */
    record Observation(String responseId, String previousId, boolean completed,
                       Map<String,Object> configurationEcho, String outputText, List<Item> outputs,
                       Object creationIdentity, String rawEvidence) {
        public Observation {
            opaqueId(responseId);
            configurationEcho = MigrationInput.immutable(Objects.requireNonNull(configurationEcho));
            outputs = List.copyOf(outputs);
            creationIdentity = MigrationInput.immutable(Objects.requireNonNull(creationIdentity));
            Objects.requireNonNull(rawEvidence);
        }
        Map<String,Object> view() {
            return Json.object("responseId", responseId, "previousId", previousId, "completed", completed,
                    "configurationEchoFingerprint", Json.evidenceFingerprint(configurationEcho),
                    "outputTextFingerprint", Json.evidenceFingerprint(outputText),
                    "outputs", outputs.stream().map(Item::summary).toList(), "creationIdentity", creationIdentity);
        }
        boolean sameObservation(Observation other) {
            return responseId.equals(other.responseId) && Objects.equals(previousId, other.previousId)
                    && completed == other.completed && configurationEcho.equals(other.configurationEcho)
                    && Objects.equals(outputText, other.outputText) && outputs.equals(other.outputs)
                    && creationIdentity.equals(other.creationIdentity);
        }
    }

    /** Ordered, independently retrieved context page; nextCursor is an opaque adapter cursor. */
    record ContextPage(List<Item> items, boolean hasMore, String nextCursor, String rawEvidence) {
        public ContextPage { items = List.copyOf(items); Objects.requireNonNull(rawEvidence); }
    }

    Capabilities capabilities();
    Map<String,Object> configuration();
    void rejectCredentialMaterial(String text);
    Prepared prepare(Turn turn);
    Observation create(Prepared prepared) throws Exception;
    Observation retrieve(Prepared prepared, String responseId) throws Exception;
    ContextPage inputItems(String responseId, String cursor) throws Exception;

    private static void opaqueId(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("PROVIDER_ID_REQUIRED");
    }
}
