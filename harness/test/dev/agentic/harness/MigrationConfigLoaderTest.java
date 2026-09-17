package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Offline serialization/input guards only: no provider, project builds or validation worker. */
public final class MigrationConfigLoaderTest {
    private static final String JSON = """
            {"migration":{"settings":{"sourceProjectPath":"/source-fixture","targetProjectPath":"/target-fixture","sourceOperationName":"readItem"}},
             "futureCallerField":{"values":["123","true",true,false,7,2147483648,1.25,null],"nested":{"name":"example"}}}
            """;
    private static final String YAML = """
            # Data only; the host chooses this file and its format.
            migration:
              settings:
                sourceProjectPath: /source-fixture
                targetProjectPath: /target-fixture
                sourceOperationName: readItem
            futureCallerField:
              values: ['123', "true", true, false, 7, 2147483648, 1.25, null]
              nested:
                name: example
            """;
    private static final String FLAT = """
            sourceProjectPath: /source-fixture
            targetProjectPath: /target-fixture
            sourceOperationName: readItem
            """;
    private static int passed;
    private static Path temp;

    public static void main(String[] args) throws Exception {
        temp = Path.of(args[0]);
        directives();
        MigrationInput original = MigrationInput.fromJson(JSON);
        MigrationInput loaded = load(".json", JSON);
        check(Arrays.equals(original.bytes(), loaded.bytes()) && original.text().equals(loaded.text())
                && original.fingerprint().equals(loaded.fingerprint()), "JSON exact bytes unchanged");
        for (String extension : List.of(".yaml", ".yml")) {
            MigrationInput yaml = load(extension, YAML);
            same(original, yaml);
            check(yaml.text().startsWith("{") && yaml.fingerprint().equals(
                    Json.fingerprint("CALLER_MIGRATION_REQUEST", yaml.bytes())), "normalized JSON evidence");
            check(yaml.callerInformation().containsKey("futureCallerField"), "existing unknown caller data retained");
            var values = (List<?>) ((Map<?, ?>) yaml.callerInformation().get("futureCallerField")).get("values");
            check(values.get(0).equals("123") && values.get(1).equals("true"), "quoted strings");
            check(values.get(2).equals(true) && values.get(4) instanceof Integer
                    && values.get(5) instanceof Long && values.get(6) instanceof Double
                    && values.get(7) == null, "JSON boolean, numeric and null types");
            try { yaml.callerInformation().put("new", true); throw new AssertionError("mutable"); }
            catch (UnsupportedOperationException expected) { passed++; }
            same(yaml, load(extension, JSON)); // JSON flow syntax is also YAML syntax.
            rejected(extension, YAML.replace("sourceOperationName: readItem", "sourceOperationName: true"));
            rejected(extension, YAML.replace("sourceOperationName: readItem", "sourceOperationName: 123"));
            rejected(extension, YAML.replace("sourceOperationName: readItem", "sourceOperationName: null"));
            rejected(extension, YAML.replace("sourceOperationName: readItem", "sourceOperationName:"));
            rejected(extension, YAML.replace("sourceOperationName: readItem", "sourceOperationName: []"));
            rejected(extension, YAML.replace("sourceOperationName", "unknownOperation"));
            rejected(extension, YAML.replace("/source-fixture", "/target-fixture/source"));
            rejected(extension, YAML.replace("/source-fixture", "/source-fixture/../outside"));
            for (String quoted : List.of("'123'", "\"true\""))
                check(load(extension, FLAT.replace("readItem", quoted)).requestedOperation()
                        .equals(quoted.substring(1, quoted.length() - 1)), "routing strings retain type");
        }

        for (String invalid : List.of("", "# empty", "{}", "[]", "null", "x: [broken", "x: \"unterminated",
                "x: 1\nx: 2", "x: null\n'x': 2", "x: {y: 1, y: 2}",
                "x: !custom value", "x: !!str value", "x: !include /etc/passwd",
                "x: !include https://untrusted.invalid/config", "x: !!map {}", "x: !!seq []",
                "x: !!binary SGVsbG8=", "x: !!timestamp 2026-09-17", "x: !!set {a: null}",
                "x: !!java.net.URL [https://untrusted.invalid]",
                "x: !!dev.agentic.harness.MigrationConfigLoaderTest$ConstructionProbe {}",
                "x: &anchor value", "x: &anchor []", "x: &anchor {}", "x: *missing",
                "x: &a [*a]", "x: &a [one, two]\ny: [*a, *a]", "x: {<<: {y: 1}}",
                "? [complex, key]\n: value", "1: value", "true: value", "null: value",
                "---\nx: 1\n---\nx: 2", "%YAML 1.1\n---\nx: 1",
                "%TAG !e! tag:example.com,2026:\n---\nx: 1")) {
            String candidate;
            if (List.of("", "# empty", "{}", "[]", "null").contains(invalid)) candidate = invalid;
            else if (invalid.startsWith("%") || invalid.startsWith("---"))
                candidate = invalid.replaceFirst("x: 1", FLAT + "x: 1");
            else candidate = FLAT + invalid;
            rejected(".yaml", candidate);
        }
        check(System.getProperty("migration.yaml.construction.probe") == null, "no class loading or construction");
        for (String number : List.of("0", "-0", "-42", "9223372036854775808", "1.25", "1e3", "1e400")) {
            MigrationInput json = load(".json", "{\"sourceProjectPath\":\"/source-fixture\","
                    + "\"targetProjectPath\":\"/target-fixture\",\"sourceOperationName\":\"readItem\",\"value\":" + number + "}");
            same(json, load(".yaml", FLAT + "value: " + number));
        }
        for (String literal : List.of("yes", "no", "on", "off", "TRUE", "Null", "~", "012", "0x10", ".nan",
                "2026-09-17", "${HOME}", "$(touch /tmp/never)", "https://untrusted.invalid/file"))
            check(load(".yaml", FLAT + "value: " + literal).callerInformation().get("value").equals(literal),
                    "fixed plain string semantics; no interpolation/inclusion");
        same(load(".yaml", FLAT + "value:\n"), load(".yaml", FLAT + "value: null\n"));
        check(load(".yaml", FLAT + "value: |\n  hello\n  world\n").callerInformation().get("value")
                .equals("hello\nworld\n"), "block string");
        rejected(".yaml", FLAT + "x: " + "[".repeat(60) + "0" + "]".repeat(60));
        rejected(".yaml", FLAT + "x: [" + "0,".repeat(100_001) + "0]");
        rejected(".yaml", FLAT + "#" + "x".repeat(MigrationInput.MAX_BYTES));
        rejected(".yaml", FLAT + "value: '" + "\\".repeat(MigrationInput.MAX_BYTES / 2) + "'");
        rejected(".yaml", new byte[]{(byte) 0xc3, 0x28});
        rejected(".json", new byte[]{(byte) 0xc3, 0x28});
        rejected(".json", " ".repeat(MigrationInput.MAX_BYTES + 1));
        rejected(".json", JSON + "{}");
        rejected(".json", JSON.replace("\"sourceOperationName\":", "\"sourceOperationName\":\"other\",\"sourceOperationName\":"));
        rejected(".json", FLAT); // File contents cannot change the parser.
        rejected(".txt", JSON);
        rejected(".YAML", YAML);

        // Existing MigrationInput retains these names as inert caller information. The loader
        // cannot create host configuration, provider transports, validation profiles or grants.
        for (String key : List.of("provider", "endpoint", "credentialSource", "model", "maxOutputTokens",
                "validationProfile", "executable", "command", "staticResolution", "filesystemAuthority",
                "processAuthority", "parser")) {
            MigrationInput yaml = load(".yaml", FLAT + key + ": untrusted-override\n");
            MigrationInput json = MigrationInput.fromJson(yaml.text());
            same(json, yaml);
            check(yaml.requestedOperation().equals("readItem") && yaml.sourceRoot().equals(Path.of("/source-fixture"))
                    && yaml.targetRoot().equals(Path.of("/target-fixture")), "caller extras cannot change routing");
        }
        for (String secret : List.of("password: secret-value", "api_key: secret-value", "authorization: Bearer secret-value",
                "password: 123", "password: true", "password: ['secret-value']",
                "\"pass\\u0077ord\": secret-value")) {
            rejected(".yaml", FLAT + secret);
        }
        diagnostic(".yaml", FLAT + "x: !private-tag PRIVATE_PARSER_MARKER");
        diagnostic(".yaml", FLAT + "password: PRIVATE_SECRET_MARKER");
        diagnostic(".json", "{PRIVATE_PARSER_MARKER");
        System.out.println("PASS " + passed + " migration config/input checks; offline, no provider calls");
    }

    private static void directives() throws Exception {
        for (String directive : List.of("%IGNORED arbitrary-value", "%YAML 1.2",
                "%TAG !e! tag:example.com,2026:", "%IGNORED", "%IGNORED [arbitrary] {arguments} \"text\"")) {
            rejected(".yaml", directive + "\n---\n" + FLAT);
            rejected(".yml", "# preceding comment\r\n\r\n" + directive + "\r\n---\r\n" + FLAT);
        }
        for (String scalar : List.of("'%IGNORED arbitrary-value'", "\"%IGNORED arbitrary-value\"",
                "'first\n%IGNORED arbitrary-value'", "\"first\n%IGNORED arbitrary-value\"",
                "|\n  %IGNORED arbitrary-value\n", ">\n  %IGNORED arbitrary-value\n",
                "prefix %IGNORED arbitrary-value")) {
            String expected = scalar.startsWith("|") || scalar.startsWith(">") ? "%IGNORED arbitrary-value\n"
                    : scalar.contains("first") ? "first %IGNORED arbitrary-value"
                    : scalar.startsWith("prefix") ? "prefix %IGNORED arbitrary-value" : "%IGNORED arbitrary-value";
            check(load(".yaml", FLAT + "value: " + scalar).callerInformation().get("value").equals(expected),
                    "percent inside scalar content is data, including column-zero quoted continuation");
        }
        same(load(".yaml", FLAT), load(".yaml", "# %IGNORED arbitrary-value\n" + FLAT
                + "# trailing %IGNORED arbitrary-value\n"));
        same(load(".yaml", FLAT), load(".yaml", FLAT.replace("readItem", "readItem # %IGNORED arbitrary-value")));
        diagnostic(".yaml", "%IGNORED PRIVATE_DIRECTIVE_MARKER\n---\n" + FLAT);
    }

    public static final class ConstructionProbe {
        static { System.setProperty("migration.yaml.construction.probe", "loaded"); }
        public ConstructionProbe() { System.setProperty("migration.yaml.construction.probe", "constructed"); }
    }

    private static MigrationInput load(String extension, String text) throws Exception {
        Path file = Files.createTempFile(temp, "migration-input-", extension);
        Files.writeString(file, text);
        return LiveLauncher.load(file);
    }

    private static void same(MigrationInput a, MigrationInput b) {
        check(a.callerInformation().equals(b.callerInformation()) && a.sourceRoot().equals(b.sourceRoot())
                && a.targetRoot().equals(b.targetRoot()) && a.requestedOperation().equals(b.requestedOperation()),
                "same MigrationInput semantics");
    }

    private static void rejected(String extension, String text) throws Exception {
        rejected(extension, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void rejected(String extension, byte[] bytes) throws Exception {
        Path file = Files.createTempFile(temp, "migration-input-", extension);
        Files.write(file, bytes);
        try { LiveLauncher.load(file); throw new AssertionError("accepted invalid config"); }
        catch (IllegalArgumentException expected) {
            check(expected.getCause() == null && expected.getMessage().matches("[A-Z0-9_]+"), "sanitized rejection");
        }
    }

    private static void diagnostic(String extension, String text) throws Exception {
        Path file = Files.createTempFile(temp, "PRIVATE_FILENAME_MARKER-", extension);
        Files.writeString(file, text);
        Path log = Files.createTempFile(temp, "launcher-diagnostic-", ".log");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LiveLauncher.class.getName(), "run",
                temp.toString(), file.toString(), "unused-host-model", "1");
        builder.environment().remove("OPENAI_API_KEY");
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("launcher rejection timeout");
        }
        check(process.exitValue() == 1 && Files.readString(log).equals(
                "LIVE_LAUNCH_REJECTED: check host arguments, fixture, JDK and host provider credentials.\n"),
                "launcher suppresses parser source, secrets, paths and stack traces");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
}
