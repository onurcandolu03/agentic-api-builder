package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;

/** Offline proof of the live host wiring, including null-hash replies and the real fixed JDK worker. */
public final class LiveLauncherTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) throw new IllegalArgumentException("FIXTURE_ARGUMENTS_REQUIRED");
        Path temp = Path.of(args[0]).toRealPath(), trusted = Path.of(args[1]).toRealPath();
        if (args.length == 3) {
            if ("input-guards".equals(args[2])) {
                inputGuards(temp, trusted);
                return;
            }
            if (!List.of("success", "wrong-hash", "readback-drift", "binding-drift", "static-tamper", "master-reject")
                    .contains(args[2])) throw new IllegalArgumentException("UNKNOWN_FOCUSED_SCENARIO");
            scenario(temp, trusted, args[2]);
            System.out.println("PASS live preparation " + args[2]);
            return;
        }
        check(LiveFixture.TEXTS.equals(ImplementationFixtures.TEXTS), "reviewed four-file bytes match existing fixture");
        check(Arrays.equals(LiveFixture.resolution(), ImplementationFixtures.resolution()), "reviewed static predicates");
        for (String fault : List.of("success", "wrong-hash", "readback-drift", "binding-drift", "static-tamper", "master-reject")) {
            scenario(temp, trusted, fault);
            System.out.println("PASS live preparation " + fault);
        }
        inputGuards(temp, trusted);
        System.out.println("PASS 8 live preparation checks; mocked provider, fixed local JDK validation");
    }

    private static void inputGuards(Path temp, Path trusted) throws Exception {
        Path invalid = Files.createTempFile(temp, "invalid-input-", ".json");
        for (byte[] bytes : List.of(new byte[]{(byte)0xc3, 0x28}, "{}".getBytes(StandardCharsets.UTF_8),
                new byte[MigrationInput.MAX_BYTES + 1])) {
            Files.write(invalid, bytes);
            boolean rejected = false;
            try { LiveLauncher.load(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "bounded strict UTF-8 JSON routing");
        }
        System.out.println("PASS live preparation input routing guards");
        boolean gitRejected = false;
        try { LiveFixture.prepare(trusted); } catch (IllegalArgumentException expected) { gitRejected = true; }
        check(gitRejected, "fixture cannot be prepared inside Git");
        System.out.println("PASS live preparation Git fixture guard");
    }

    private static void scenario(Path temp, Path trusted, String fault) throws Exception {
        Path inputFile = LiveFixture.prepare(temp);
        MigrationInput original = LiveLauncher.load(inputFile);
        // Caller JSON cannot select the model, validation profile, static authority or executable.
        var caller = Json.parse(original.text());
        caller.put("provider", "unregistered-company-profile");
        caller.put("endpoint", "https://untrusted.invalid");
        caller.put("credentialSource", "UNTRUSTED_ENVIRONMENT");
        caller.put("executable", "/untrusted/program");
        caller.put("command", List.of("untrusted-command"));
        caller.put("model", "caller-must-not-select");
        caller.put("maxOutputTokens", 1);
        caller.put("staticResolution", Json.object("expectedText", "untrusted override"));
        caller.put("validationProfile", Json.object("command", List.of("untrusted-command")));
        Files.write(inputFile, Json.bytes(caller));
        MigrationInput input = LiveLauncher.load(inputFile);
        Fixture fixture = new Fixture(trusted, input.sourceRoot(), input.targetRoot(), input);
        Mock mock = new Mock();
        Set<String> contextRoles = new HashSet<>();
        mock.answer = turn -> {
            var binding = map(turn.get("binding")); String role = (String)binding.get("role");
            var context = map(turn.get("hostTaskContext"));
            check(MigrationInput.utf8(LiveFixture.resolution()).equals(context.get("exactText")), "retained host context each turn");
            check(Json.fingerprint("CALLER_RESOLUTION", LiveFixture.resolution()).equals(context.get("fingerprint")), "host context fingerprint");
            check(((List<?>)binding.get("inputArtifactFingerprints")).contains(context.get("fingerprint")), "context bound to invocation");
            contextRoles.add(role);
            var data = data(turn);
            Map<String,Object> reply;
            if (role.equals(TrustedInputs.Role.VALIDATION.id)) {
                if (((List<?>)data.get("toolResults")).isEmpty()) reply = envelope(turn, "TOOL_REQUEST", null, null,
                        Json.object("operationId", "live-validation", "operation", "RUN_VALIDATION",
                                "invocationId", binding.get("invocationId"), "role", role,
                                "authorityId", map(data.get("validationAuthority")).get("authorityId")), null, List.of());
                else reply = artifact(turn, "VALIDATION_RESULT", map(data.get("resultContract")));
            } else reply = ImplementationFixtures.answer(turn, fixture);
            if (fault.equals("master-reject") && role.equals("MASTER")
                    && "ACCEPT_VALIDATION".equals(map(data.get("proposedDecision")).get("action")))
                return envelope(turn, "DECISION", null, null, null, "FAILED", List.of("Host test rejects validation acceptance."));
            if (fault.equals("static-tamper") && role.equals(TrustedInputs.Role.DOMAIN.id)) {
                context.put("exactText", "Model replacement authority");
                if (reply.get("kind").equals("TOOL_REQUEST"))
                    return replace(reply, "toolRequest", replace(map(reply.get("toolRequest")), "content", "record Value(int value) {}\n"));
            }
            if (reply.get("kind").equals("ARTIFACT")) {
                // Preserve legal whitespace exactly. There is no model-side digest computation.
                reply = replace(reply, "artifactText", "\n\t" + reply.get("artifactText") + "\n", "artifactFingerprint", null);
                if (fault.equals("wrong-hash")) reply.put("artifactFingerprint", Json.fingerprint("SOURCE_ANALYSIS", new byte[]{1}));
                if (fault.equals("binding-drift")) reply.put("binding", replace(binding, "previousResponseId", "resp_wrong"));
            }
            return reply;
        };
        ResponsesClient provider = new ResponsesClient() {
            @Override public String create(String body) { return mock.create(body); }
            @Override public String retrieve(String id) {
                String raw = mock.retrieve(id);
                if (!fault.equals("readback-drift")) return raw;
                var response = Json.parse(raw);
                var message = map(((List<?>)response.get("output")).getFirst());
                var content = map(((List<?>)message.get("content")).getFirst());
                var reply = Json.parse((String)content.get("text"));
                if (reply.get("kind").equals("ARTIFACT")) {
                    reply.put("artifactText", reply.get("artifactText") + " ");
                    content.put("text", Json.write(reply));
                }
                return Json.write(response);
            }
            @Override public String inputItems(String id, String after) { return mock.inputItems(id, after); }
            @Override public Map<String,Object> configuration() { return mock.configuration(); }
            @Override public void rejectCredentialMaterial(String text) { mock.rejectCredentialMaterial(text); }
        };
        try {
            var result = LiveLauncher.run(trusted, input, new ControlledHarness.Config("host-selected-model", 16384), provider);
            check(result.status().equals("SUCCESS") == fault.equals("success"), fault + ": " + result.status() + " " + result.code());
            for (var request : mock.requests) {
                check(request.get("model").equals("host-selected-model") && ((Number)request.get("max_output_tokens")).intValue() == 16384, "host configuration only");
                check(((List<?>)request.get("tools")).isEmpty(), "no process/shell tools");
            }
            if (fault.equals("success")) {
                check(result.code().equals("VALIDATION_MASTER_ACCEPTED"), "final MASTER acceptance");
                check(Boolean.TRUE.equals(result.report().get("validationExecuted")), "six-argument launcher reaches real validation");
                check(contextRoles.size() == 9, "MASTER and 00–07 receive host context");
                var accepted = (List<?>)result.report().get("acceptedArtifacts");
                check(accepted.size() == 8, "all specialist artifacts accepted");
                for (Object item : accepted) {
                    var a = map(item); String text = (String)a.get("exactText");
                    check(text.startsWith("\n\t") && text.endsWith("\n"), "exact artifact whitespace retained");
                    check(Json.fingerprint((String)a.get("role"), text.getBytes(StandardCharsets.UTF_8)).equals(a.get("fingerprint")), "host-bound artifact hash");
                }
                for (var entry : LiveFixture.approvedSources().entrySet())
                    check(Files.readString(input.targetRoot().resolve(entry.getKey())).equals(entry.getValue()), "reviewed bytes validated");
            } else if (!fault.equals("master-reject")) {
                check(!Boolean.TRUE.equals(result.report().get("validationExecuted")), "rejection before validation");
                if (!fault.equals("static-tamper")) check(((List<?>)result.report().get("acceptedArtifacts")).isEmpty(), "bad source has no acceptance");
            }
            check(Files.readString(input.sourceRoot().resolve("src/LegacyOperation.java")).equals(LiveFixture.SOURCE), "source unchanged");
            for (Path path : List.of(input.sourceRoot(), input.sourceRoot().resolve("src"), input.sourceRoot().resolve("src/LegacyOperation.java")))
                check(Files.getPosixFilePermissions(path).stream().noneMatch(p -> p.name().endsWith("WRITE")), "source read-only modes preserved");
        } finally {
            // Test cleanup only; prepared live sources stay read-only.
            Files.setPosixFilePermissions(input.sourceRoot(), PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(input.sourceRoot().resolve("src"), PosixFilePermissions.fromString("rwx------"));
        }
    }
}
