package dev.agentic.harness;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Local host material, NOT a DISCOVERY_CONTROL_CHECK or accepted orchestration artifact. */
final class Evidence {
    private static final Pattern OBVIOUS_SECRET = Pattern.compile(
            "(?i)(sk-[a-z0-9_-]{12,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|"
                    + "authorization[\\\"\\s:]+bearer\\s+\\S+|"
                    + "(?:password|api[_-]?key|access[_-]?token)[\\\"]?\\s*[=:]\\s*[\\\"]?[^\\s\\\"]{4,})");
    private static final Set<String> SENSITIVE_KEYS = Set.of("password", "apikey", "token", "accesstoken",
            "authorization", "bearer", "secret", "privatekey");
    private static final int MAX_INSPECTION_DEPTH = 64;
    private static final int MAX_FRAGMENT_CHARS = 4 * 1024 * 1024;
    private static final int MAX_FRAGMENTS = 4096;
    private static final int MAX_INSPECTION_NODES = 100_000;
    private static final class InspectionBudget {
        long characters = 32L * 1024 * 1024;
        int fragments;
        int nodes;
        void visit(int depth) {
            if (depth > MAX_INSPECTION_DEPTH || ++nodes > MAX_INSPECTION_NODES)
                throw new IllegalArgumentException("SECRET_INSPECTION_LIMIT_EXCEEDED");
        }
        void text(int length) {
            if ((characters -= length) < 0) throw new IllegalArgumentException("SECRET_INSPECTION_LIMIT_EXCEEDED");
        }
    }
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final Consumer<String> credentialCheck;
    private int retainedCount;
    private Map<String, Object> retainedIdentity = Json.evidenceFingerprint(List.of());

    Evidence(Consumer<String> credentialCheck) { this.credentialCheck = credentialCheck; }

    static void rejectObviousSecrets(String text) {
        if (text == null) throw new IllegalArgumentException("UNINSPECTABLE_JSON_REJECTED");
        inspectSecretMaterial(text, ignored -> {});
    }

    /** Checks selected credential field names and strings, not authenticity or universal secret detection. */
    static void inspectSecretMaterial(Object value, Consumer<String> credentialCheck) {
        inspectSecretMaterial(value, credentialCheck, 0, new InspectionBudget());
    }

    private static void inspectSecretMaterial(Object value, Consumer<String> credentialCheck, int depth,
                                              InspectionBudget budget) {
        budget.visit(depth);
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("UNINSPECTABLE_JSON_REJECTED");
                String normalized = key.strip().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                // Preserve the relationship: arrays, objects, numbers and booleans can carry credentials too.
                if (SENSITIVE_KEYS.contains(normalized) && populated(entry.getValue()))
                    throw new IllegalArgumentException("SECRET_MATERIAL_REJECTED");
                inspectSecretMaterial(key, credentialCheck, depth + 1, budget);
                inspectSecretMaterial(entry.getValue(), credentialCheck, depth + 1, budget);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) inspectSecretMaterial(item, credentialCheck, depth + 1, budget);
        } else if (value instanceof String text) {
            budget.text(text.length());
            if (OBVIOUS_SECRET.matcher(text).find()) throw new IllegalArgumentException("SECRET_MATERIAL_REJECTED");
            credentialCheck.accept(text);
            inspectFragments(text, credentialCheck, depth, budget);
        }
    }

    /** Linear extraction; strict parsing and decoded inspection own all JSON semantics. */
    private static void inspectFragments(String text, Consumer<String> credentialCheck, int depth,
                                         InspectionBudget budget) {
        if (text.stripLeading().startsWith("\uFEFF")) throw new IllegalArgumentException("UNINSPECTABLE_JSON_REJECTED");
        for (int start = 0; start < text.length(); start++) {
            char opening = text.charAt(start);
            int end;
            if (opening == '"') end = quotedEnd(text, start);
            else if ((opening == '{' || opening == '[') && looksLikeContainer(text, start))
                end = containerEnd(text, start, depth);
            else continue;
            if (end < 0) return; // An unmatched ordinary prose quote has no escapes or containers.
            if (++budget.fragments > MAX_FRAGMENTS)
                throw new IllegalArgumentException("SECRET_INSPECTION_LIMIT_EXCEEDED");
            budget.text(end - start);
            Object parsed;
            try { parsed = Json.parseValue(text.substring(start, end)); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("UNINSPECTABLE_JSON_REJECTED"); }
            inspectSecretMaterial(parsed, credentialCheck, depth + 1, budget);
            if (opening == '"') rejectAmbiguousQuoteBoundary(text, end, budget);
            start = end - 1; // Never reparse each nested opening; traverse the decoded value instead.
        }
    }

    private static void rejectAmbiguousQuoteBoundary(String text, int end, InspectionBudget budget) {
        // The closing quote could also open a string in the following prose gap. Never skip
        // an escape there: it could encode a credential under that alternative interpretation.
        // Ordinary prose/adjacent quotes need no reinterpretation; plaintext was already checked.
        for (int next = end; next < text.length() && text.charAt(next) != '"'; next++) {
            fragmentLength(next - end + 1);
            budget.text(1);
            if (text.charAt(next) == '\\') throw new IllegalArgumentException("AMBIGUOUS_JSON_QUOTE_REJECTED");
        }
    }

    private static boolean looksLikeContainer(String text, int start) {
        int next = start + 1;
        while (next < text.length() && candidateSpace(text.charAt(next))) next++;
        if (next == text.length()) return false;
        char value = text.charAt(next);
        if (text.charAt(start) == '{') {
            if (value == '"' || value == '}' || value == '\\' || value == '\'' || value == '/') return true;
            // A key/colon pair is recognizable malformed JSON; a plain {placeholder} is prose.
            int keyEnd = next;
            while (keyEnd < text.length() && Character.isJavaIdentifierPart(text.charAt(keyEnd))) keyEnd++;
            while (keyEnd < text.length() && candidateSpace(text.charAt(keyEnd))) keyEnd++;
            return keyEnd < text.length() && text.charAt(keyEnd) == ':';
        }
        if (value == '"' || value == '{' || value == '[' || value == ']' || value == '-' || value == '\\'
                || value == '\'' || value == '/' || value >= '0' && value <= '9') return true;
        for (String literal : List.of("true", "false", "null")) {
            int end = next + literal.length();
            if (text.startsWith(literal, next) && (end == text.length()
                    || candidateSpace(text.charAt(end)) || ",]}".indexOf(text.charAt(end)) >= 0)) return true;
        }
        return false;
    }

    private static int quotedEnd(String text, int start) {
        boolean escaped = false, hasEscape = false, hasContainer = false;
        for (int i = start + 1; i < text.length(); i++) {
            fragmentLength(i - start + 1);
            char value = text.charAt(i);
            if (escaped) escaped = false;
            else if (value == '\\') { escaped = true; hasEscape = true; }
            else if (value == '"') {
                int previous = i - 1;
                while (previous > start && candidateSpace(text.charAt(previous))) previous--;
                // An ordinary prose quote must not consume the opening of {"key":...} or ["value"].
                // Escaped quotes were handled above. This raw overlapping interpretation is ambiguous.
                if (previous > start && (text.charAt(previous) == '{' || text.charAt(previous) == '['))
                    throw new IllegalArgumentException("UNINSPECTABLE_JSON_REJECTED");
                return i + 1;
            }
            if (value == '{' || value == '[') hasContainer = true;
        }
        if (hasEscape || hasContainer || text.substring(0, start).isBlank())
            throw new IllegalArgumentException("UNINSPECTABLE_JSON_REJECTED");
        return -1;
    }

    private static int containerEnd(String text, int start, int depth) {
        char[] closing = new char[MAX_INSPECTION_DEPTH];
        int nesting = 0;
        boolean quoted = false, escaped = false;
        for (int i = start; i < text.length(); i++) {
            fragmentLength(i - start + 1);
            char value = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') {
                if (nesting + depth >= MAX_INSPECTION_DEPTH)
                    throw new IllegalArgumentException("SECRET_INSPECTION_LIMIT_EXCEEDED");
                closing[nesting++] = value == '{' ? '}' : ']';
            } else if (value == '}' || value == ']') {
                if (nesting == 0 || closing[--nesting] != value)
                    throw new IllegalArgumentException("UNINSPECTABLE_JSON_REJECTED");
                if (nesting == 0) return i + 1;
            }
        }
        throw new IllegalArgumentException("UNINSPECTABLE_JSON_REJECTED");
    }

    private static void fragmentLength(int length) {
        if (length > MAX_FRAGMENT_CHARS) throw new IllegalArgumentException("SECRET_INSPECTION_LIMIT_EXCEEDED");
    }

    private static boolean candidateSpace(char value) {
        // Recognize malformed non-JSON spacing too; the strict parser will reject it.
        return Character.isWhitespace(value) || Character.isSpaceChar(value) || Character.getType(value) == Character.FORMAT;
    }

    private static boolean populated(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isEmpty();
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    void safe(String raw) {
        if (raw == null) throw new IllegalArgumentException("UNSAFE_EVIDENCE_REJECTED");
        safeValue(raw);
    }

    private void safeValue(Object value) {
        try {
            inspectSecretMaterial(value, credentialCheck);
        } catch (RuntimeException rejected) {
            throw new IllegalArgumentException("UNSAFE_EVIDENCE_REJECTED");
        }
    }

    void append(String kind, Map<String, Object> material) {
        verify();
        // Callbacks cannot replace the bytes between inspection and fingerprinting/retention.
        var candidate = Json.object("kind", kind, "material", material);
        safeValue(candidate);
        safe(Json.write(candidate));
        Object frozenMaterial = candidate.get("material");
        Map<String, Object> event = Json.object("sequence", events.size() + 1,
                "kind", kind, "material", frozenMaterial,
                "materialFingerprint", Json.evidenceFingerprint(frozenMaterial));
        events.add(event);
        retainedCount = events.size();
        retainedIdentity = Json.evidenceFingerprint(events);
    }

    List<Map<String, Object>> snapshot() { return List.copyOf(events); }

    void verify() {
        if (events.size() != retainedCount || !retainedIdentity.equals(Json.evidenceFingerprint(events)))
            throw new IllegalStateException("EVIDENCE_CHANGED");
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            if (!Objects.equals(event.get("sequence"), i + 1)
                    || !Json.evidenceFingerprint(event.get("material")).equals(event.get("materialFingerprint")))
                throw new IllegalStateException("EVIDENCE_CHANGED");
        }
    }

    String serialize() {
        verify();
        String raw = Json.write(Json.object("format", "HOST_EVIDENCE_V1", "protocolAcceptance", "NOT_EVALUATED",
                "events", snapshot()));
        safe(raw);
        return raw;
    }
}
