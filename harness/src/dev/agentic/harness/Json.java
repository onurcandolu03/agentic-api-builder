package dev.agentic.harness;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Ordered, integer-only host JSON. API bodies are retained separately as exact UTF-8. */
final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build();

    private Json() {}

    static Map<String, Object> object(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("JSON_PAIRS");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            if (result.containsKey(key)) throw new IllegalArgumentException("JSON_DUPLICATE_KEY");
            result.put(key, freeze(pairs[i + 1]));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object freeze(Object value) {
        if (value instanceof List<?> list) return list.stream().map(Json::freeze).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, entry) -> result.put((String) key, freeze(entry)));
            return Collections.unmodifiableMap(result);
        }
        validate(value);
        return value;
    }

    static String write(Object value) {
        validate(value);
        return MAPPER.writeValueAsString(value);
    }

    static byte[] bytes(Object value) { return write(value).getBytes(StandardCharsets.UTF_8); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String raw) {
        Object value = parseValue(raw);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON_OBJECT_REQUIRED");
        return (Map<String, Object>) value;
    }

    static Object parseValue(String raw) { return MAPPER.readValue(raw, Object.class); }

    static void identifier(String value) {
        if (value == null || value.isBlank() || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_IDENTIFIER");
        scalarString(value);
    }

    private static void validate(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long) return;
        if (value instanceof String s) { scalarString(s); return; }
        if (value instanceof List<?> list) { list.forEach(Json::validate); return; }
        if (value instanceof Map<?, ?> map) {
            // Member order belongs to the host schema, never to a generic alphabetical sort.
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON_KEY");
                scalarString(key);
                validate(entry.getValue());
            }
            return;
        }
        throw new IllegalArgumentException("NON_CANONICAL_HOST_JSON");
    }

    private static void scalarString(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("INVALID_UNICODE");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("INVALID_UNICODE");
        }
    }

    static Map<String, Object> fingerprint(String role, byte[] bytes) {
        identifier(role);
        try {
            return object("algorithm", "SHA-256", "digest",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "byteLength", bytes.length, "artifactRole", role);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    static Map<String, Object> evidenceFingerprint(Object value) {
        return fingerprint("RUNTIME_DISCOVERY_EVIDENCE", bytes(value));
    }

    /** Structural integrity for retained comparison values; distinct from exact JSON bytes. */
    static Map<String, Object> structuralFingerprint(Object value) {
        return fingerprint("STRUCTURAL_COMPARISON_INTEGRITY", structuralBytes(value));
    }

    private static byte[] structuralBytes(Object value) {
        var out = new java.io.ByteArrayOutputStream();
        encodeStructural(value, out);
        return out.toByteArray();
    }

    private static void encodeStructural(Object value, java.io.ByteArrayOutputStream out) {
        if (value == null) { out.write('N'); return; }
        if (value instanceof Boolean bool) { out.write(bool ? 'T' : 'F'); return; }
        if (value instanceof Integer number) { writeScalar(out, 'I', number.toString()); return; }
        if (value instanceof Long number) { writeScalar(out, 'L', number.toString()); return; }
        if (value instanceof String text) { writeScalar(out, 'S', text); return; }
        if (value instanceof List<?> list) {
            writeScalar(out, 'A', Integer.toString(list.size()));
            list.forEach(item -> encodeStructural(item, out));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeScalar(out, 'O', Integer.toString(map.size()));
            map.keySet().stream().map(key -> (String) key).sorted().forEach(key -> {
                writeScalar(out, 'K', key);
                encodeStructural(map.get(key), out);
            });
            return;
        }
        throw new IllegalArgumentException("NON_CANONICAL_HOST_JSON");
    }

    private static void writeScalar(java.io.ByteArrayOutputStream out, int tag, String value) {
        scalarString(value);
        out.write(tag);
        byte[] bytes;
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw new IllegalArgumentException("INVALID_UNICODE");
        }
        out.writeBytes(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }
}
