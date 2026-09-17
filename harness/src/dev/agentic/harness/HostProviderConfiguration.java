package dev.agentic.harness;

/** Frozen host selection. No URLs, credential values, class loading or executable configuration.
 * This factory is never called with migration fields or model output.
 */
public record HostProviderConfiguration(String profile) {
    public static final String DIRECT_OPENAI = "DIRECT_OPENAI_RESPONSES";

    public HostProviderConfiguration {
        if (!DIRECT_OPENAI.equals(profile)) throw new IllegalArgumentException("UNKNOWN_HOST_PROVIDER_PROFILE");
    }

    public static HostProviderConfiguration directOpenAI() {
        return new HostProviderConfiguration(DIRECT_OPENAI);
    }

    public ProviderTransport open() {
        return new ResponsesProtocolAdapter(HttpResponsesClient.fromEnvironment());
    }
}
