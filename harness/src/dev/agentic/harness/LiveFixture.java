package dev.agentic.harness;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Reviewed synthetic code, owned by the host. Never load approved code from migration input or target. */
final class LiveFixture {
    static final String SOURCE = """
            final class LegacyOperation {
                String readItem(String value) { return value; }
            }
            """;
    static final String TARGET = """
            final class TargetConventions {
                record Value(String value) {}
                static final class Mapper { Value map(String value) { return new Value(value); } }
                static final class Service {
                    private final Mapper mapper;
                    Service(Mapper mapper) { this.mapper = mapper; }
                    Value find(String value) { return mapper.map(value); }
                }
                static void verifiesMapping() {
                    if (!new Mapper().map("example").value().equals("example")) throw new AssertionError();
                }
            }
            """;
    // Descriptive metadata only. No dependency resolution or project build is performed.
    static final String BUILD = """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
            <artifactId>target-fixture</artifactId><version>1</version>
            <properties><maven.compiler.release>21</maven.compiler.release></properties>
            <dependencies><dependency><groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId><version>3.4.0</version>
            </dependency></dependencies></project>
            """;
    static final List<String> TEXTS = List.of("record Value(String value) {}\n",
            "final class ValueMapper { Value map(String value) { return new Value(value); } }\n",
            TARGET + "final class ReadItemService { String readItem(String value) { return new ValueMapper().map(value).value(); } }\n",
            "final class ReadItemContractTest { static void verifiesIdentity() { if (!new ReadItemService().readItem(\"example\").equals(\"example\")) throw new AssertionError(); } }\n");
    static final List<String> DUTIES = List.of("Declare the String payload Value record.",
            "Map String input into Value without changing it.",
            "Provide readItem returning the mapped String unchanged; retain TargetConventions.",
            "Add static assertion source for the service String identity contract.");

    static Map<String,String> approvedSources() {
        Map<String,String> sources = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) sources.put(ValidationProfile.SOURCES.get(i), TEXTS.get(i));
        return Map.copyOf(sources);
    }

    static byte[] resolution() {
        List<ExecutionPlan.StaticRule> rules = new ArrayList<>();
        for (int i = 0; i < 4; i++) rules.add(new ExecutionPlan.StaticRule(
                "/componentDecisions/" + i + "/expectedChangeScope/responsibilities/0",
                ValidationProfile.SOURCES.get(i), TEXTS.get(i), DUTIES.get(i)));
        return Json.bytes(ExecutionPlan.callerResolution(rules, List.of()));
    }

    /** Creates a fresh disposable fixture; never overwrites an existing source or target. */
    static Path prepare(Path parent) throws Exception {
        Path realParent = parent.toRealPath();
        for (Path ancestor = realParent; ancestor != null; ancestor = ancestor.getParent())
            if (Files.exists(ancestor.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("NON_GIT_FIXTURE_PARENT_REQUIRED");
        Path root = Files.createTempDirectory(realParent, "controlled-live-");
        Path source = Files.createDirectory(root.resolve("source"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path sourceDir = Files.createDirectory(source.resolve("src"));
        Files.createDirectory(target.resolve("src"));
        Path sourceFile = sourceDir.resolve("LegacyOperation.java");
        Files.writeString(sourceFile, SOURCE);
        Files.writeString(target.resolve("src/TargetConventions.java"), TARGET);
        Files.writeString(target.resolve("pom.xml"), BUILD);
        Files.setPosixFilePermissions(sourceFile, PosixFilePermissions.fromString("r--r--r--"));
        Files.setPosixFilePermissions(sourceDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("r-xr-xr-x"));
        Path input = root.resolve("migration.json");
        Files.write(input, Json.bytes(Json.object("migration", Json.object("settings", Json.object(
                "sourceProjectPath", source.toString(), "targetProjectPath", target.toString(),
                "sourceOperationName", "readItem")))));
        return input;
    }
}
