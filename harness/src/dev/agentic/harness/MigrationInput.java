package dev.agentic.harness;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Caller-owned input; exact original JSON bytes remain the authority for all additional fields. */
public final class MigrationInput {
    public static final int MAX_BYTES = 1_048_576;
    private final byte[] bytes;
    private final String text;
    private final Map<String, Object> information;
    private final Path sourceRoot, targetRoot;
    private final String requestedOperation;
    private final Workflow workflow;

    private MigrationInput(byte[] supplied) {
        if (supplied == null || supplied.length == 0 || supplied.length > MAX_BYTES)
            throw new IllegalArgumentException("MIGRATION_INPUT_SIZE");
        this.bytes = supplied.clone();
        this.text = utf8(this.bytes);
        Evidence.rejectObviousSecrets(text);
        try { this.information = immutable(Json.parse(text)); }
        catch (RuntimeException malformed) { throw new IllegalArgumentException("MIGRATION_INPUT_JSON_REJECTED"); }
        workflow = Workflow.read(information);
        Map<String, Object> routing = information;
        if (workflow == Workflow.NEW_OPERATION) {
            if (routing.containsKey("migration") || routing.containsKey("settings"))
                throw new IllegalArgumentException("WORKFLOW_ROUTING_AMBIGUOUS");
            targetRoot = root(routing, "targetProjectPath");
            sourceRoot = null;
            OperationRequirement.validateInput(routing);
            requestedOperation = (String)routing.get("operationName");
            return;
        }
        if (routing.containsKey("migration")) {
            routing = object(routing.get("migration"));
            rejectNestedWorkflow(routing);
        }
        if (routing.containsKey("settings")) {
            routing = object(routing.get("settings"));
            rejectNestedWorkflow(routing);
        }
        sourceRoot = root(routing, "sourceProjectPath");
        targetRoot = root(routing, "targetProjectPath");
        requestedOperation = required(routing, "sourceOperationName");
        if (sourceRoot.startsWith(targetRoot) || targetRoot.startsWith(sourceRoot))
            throw new IllegalArgumentException("MIGRATION_INPUT_ROOT_OVERLAP");
    }

    public static MigrationInput fromJson(byte[] bytes) { return new MigrationInput(bytes); }
    public static MigrationInput fromJson(String text) {
        if (text == null) throw new IllegalArgumentException("MIGRATION_INPUT_MISSING");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            return fromJson(bytes);
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw new IllegalArgumentException("INVALID_UTF8");
        }
    }

    public byte[] bytes() { return bytes.clone(); }
    public String text() { return text; }
    public Path sourceRoot() { return sourceRoot; }
    public Workflow workflow() { return workflow; }
    public Path targetRoot() { return targetRoot; }
    public String requestedOperation() { return requestedOperation; }
    /** Unknown structures are retained, not interpreted as requirements or repository facts. */
    public Map<String, Object> callerInformation() { return information; }
    public Map<String, Object> fingerprint() { return Json.fingerprint("CALLER_MIGRATION_REQUEST", bytes); }

    private static void rejectNestedWorkflow(Map<String,Object> routing) {
        if (routing.containsKey("workflow")) throw new IllegalArgumentException("WORKFLOW_ROUTING_AMBIGUOUS");
    }

    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("INVALID_UTF8");
        }
    }

    private static Path root(Map<String, Object> routing, String key) {
        Path result = Path.of(required(routing, key));
        if (!result.isAbsolute() || !result.equals(result.normalize()))
            throw new IllegalArgumentException("MIGRATION_INPUT_ROOT");
        return result;
    }

    private static String required(Map<String, Object> routing, String key) {
        if (!(routing.get(key) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("MIGRATION_INPUT_MISSING_ROUTING");
        Json.identifier(value);
        return value;
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("MIGRATION_INPUT_ROUTING_SHAPE");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked") static <T> T immutable(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, immutable(item)));
            return (T) Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return (T) list.stream().map(MigrationInput::immutable).toList();
        return value;
    }
}
