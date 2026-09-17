package dev.agentic.harness;

import java.nio.file.*;

/** Host-only CLI for the fixed Java fixture. No credential arguments or model-selected configuration. */
public final class LiveLauncher {
    private LiveLauncher() {}

    public static void main(String[] args) {
        int exit = 1;
        try {
            if (args.length == 2 && args[0].equals("prepare")) {
                System.out.println(LiveFixture.prepare(Path.of(args[1])));
                return;
            }
            if (args.length != 5 || !args[0].equals("run")) {
                System.err.println("Usage: prepare <non-git-parent> | run <trusted-root> <migration.json|migration.yaml|migration.yml> <model> <max-output-tokens>");
                System.exit(2);
                return;
            }
            MigrationInput input = load(Path.of(args[2]));
            ControlledHarness.Config config = new ControlledHarness.Config(args[3], Integer.parseInt(args[4]));
            ProviderTransport client = HostProviderConfiguration.directOpenAI().open();
            ControlledPipeline.Result result = run(Path.of(args[1]), input, config, client);
            // The pipeline screens retained evidence; no headers, credentials or raw exceptions are printed.
            System.out.println(Json.write(result.report()));
            exit = result.status().equals("SUCCESS") ? 0 : result.status().equals("BLOCKED") ? 2 : 1;
        } catch (Exception rejected) {
            // Argument, filesystem and transport exceptions may contain caller-controlled material.
            System.err.println("LIVE_LAUNCH_REJECTED: check host arguments, fixture, JDK and host provider credentials.");
        }
        System.exit(exit);
    }

    static MigrationInput load(Path file) throws Exception {
        return MigrationConfigLoader.load(file);
    }

    /** Package-private offline transport seam; the CLI uses the frozen host provider profile. */
    static ControlledPipeline.Result run(Path trustedRoot, MigrationInput input, ControlledHarness.Config config,
                                         ResponsesClient client) throws Exception {
        return run(trustedRoot, input, config, new ResponsesProtocolAdapter(client));
    }
    static ControlledPipeline.Result run(Path trustedRoot, MigrationInput input, ControlledHarness.Config config,
                                         ProviderTransport client) throws Exception {
        return new ControlledPipeline(trustedRoot, input, config, client, LiveFixture.resolution(),
                ValidationProfile.controlledJavaContractTest(LiveFixture.approvedSources())).run();
    }
}
