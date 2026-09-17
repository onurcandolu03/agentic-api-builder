package dev.agentic.harness;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.events.*;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.Scanner;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.Token;

/** Host-selected serialization only. YAML events never reach a YAML constructor or resolver. */
final class MigrationConfigLoader {
    private MigrationConfigLoader() {}

    static MigrationInput load(Path file) throws java.io.IOException {
        String name = file.getFileName().toString();
        boolean yaml = name.endsWith(".yaml") || name.endsWith(".yml");
        if (!yaml && !name.endsWith(".json"))
            throw new IllegalArgumentException("MIGRATION_INPUT_FORMAT_REJECTED");
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) {
            bytes = stream.readNBytes(MigrationInput.MAX_BYTES + 1);
        }
        if (!yaml) return MigrationInput.fromJson(bytes);
        if (bytes.length == 0 || bytes.length > MigrationInput.MAX_BYTES)
            throw new IllegalArgumentException("MIGRATION_INPUT_SIZE");
        try {
            // Emit only JSON syntax, then reuse ALL existing input parsing, secret screening,
            // routing and normalization. Preserve number lexemes for Jackson's existing types,
            // including decimals/large numbers; Json.write is intentionally integer-only.
            return MigrationInput.fromJson(new DataOnlyYaml(MigrationInput.utf8(bytes)).json());
        } catch (RuntimeException rejected) {
            // SnakeYAML exceptions can contain source excerpts, tags, paths and secrets.
            throw new IllegalArgumentException("MIGRATION_INPUT_YAML_REJECTED");
        }
    }

    private static final class DataOnlyYaml {
        private static final int MAX_DEPTH = 50, MAX_NODES = 100_000;
        private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
        private final Parser parser;
        private final StringBuilder output = new StringBuilder();
        private int nodes;

        DataOnlyYaml(String text) {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(MigrationInput.MAX_BYTES);
            // Composer/constructor options do not protect the event API. Depth, duplicate keys,
            // anchors, aliases and tags are rejected explicitly below before any expansion.
            parser = new ParserImpl(new DirectiveGuard(new ScannerImpl(new StreamReader(text), options)));
        }

        /** Lexical guard: reject source directive tokens before ParserImpl can discard unknown
         * names. The scanner keeps quoted/block/plain scalar content and comments distinct.
         * Streaming delegation preserves the existing event-level depth and node limits.
         */
        private record DirectiveGuard(Scanner source) implements Scanner {
            @Override public boolean checkToken(Token.ID... choices) {
                if (source.checkToken(Token.ID.Directive)) reject();
                return source.checkToken(choices);
            }
            @Override public Token peekToken() { return checked(source.peekToken()); }
            @Override public Token getToken() { return checked(source.getToken()); }
            @Override public void resetDocumentIndex() { source.resetDocumentIndex(); }
            private static Token checked(Token token) {
                if (token.getTokenId() == Token.ID.Directive) reject();
                return token;
            }
        }

        String json() {
            expect(Event.ID.StreamStart);
            Event start = parser.getEvent();
            if (!(start instanceof DocumentStartEvent document) || document.getVersion() != null
                    || (document.getTags() != null && !document.getTags().isEmpty())) reject();
            node(0);
            expect(Event.ID.DocumentEnd);
            expect(Event.ID.StreamEnd);
            return output.toString();
        }

        private void node(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) reject();
            Event event = parser.getEvent();
            safe(event);
            if (event instanceof ScalarEvent scalar) {
                String value = scalar.getValue();
                if (scalar.isPlain() && (value.equals("true") || value.equals("false")
                        || value.equals("null") || NUMBER.matcher(value).matches())) append(value);
                else if (scalar.isPlain() && value.isEmpty()) append("null");
                else append(Json.write(value));
            } else if (event instanceof MappingStartEvent) {
                append("{");
                Set<String> keys = new HashSet<>();
                while (!parser.checkEvent(Event.ID.MappingEnd)) {
                    if (++nodes > MAX_NODES) reject();
                    Event key = parser.getEvent();
                    safe(key);
                    // JSON object names are strings. Never stringify YAML collections or
                    // implicitly typed keys; numeric/boolean/null-looking keys must be quoted.
                    if (!(key instanceof ScalarEvent scalar)) { reject(); return; }
                    String value = scalar.getValue();
                    if (scalar.isPlain() && (value.isEmpty() || value.equals("null")
                            || value.equals("true") || value.equals("false")
                            || NUMBER.matcher(value).matches() || value.equals("<<"))) reject();
                    if (!keys.add(value)) reject();
                    if (keys.size() > 1) append(",");
                    append(Json.write(value));
                    append(":");
                    node(depth + 1);
                }
                expect(Event.ID.MappingEnd);
                append("}");
            } else if (event instanceof SequenceStartEvent) {
                append("[");
                boolean first = true;
                while (!parser.checkEvent(Event.ID.SequenceEnd)) {
                    if (!first) append(",");
                    node(depth + 1);
                    first = false;
                }
                expect(Event.ID.SequenceEnd);
                append("]");
            } else reject();
        }

        private static void safe(Event event) {
            if (event instanceof AliasEvent
                    || (event instanceof NodeEvent node && node.getAnchor() != null)
                    || (event instanceof ScalarEvent scalar && scalar.getTag() != null)
                    || (event instanceof CollectionStartEvent collection && collection.getTag() != null)) reject();
        }

        private void expect(Event.ID id) {
            Event event = parser.getEvent();
            if (event == null || !event.is(id)) reject();
        }

        private void append(String text) {
            if ((long) output.length() + text.length() > MigrationInput.MAX_BYTES) reject();
            output.append(text);
        }

        private static void reject() { throw new IllegalArgumentException("YAML_DATA_REJECTED"); }
    }
}
