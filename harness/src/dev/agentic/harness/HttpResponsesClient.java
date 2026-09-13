package dev.agentic.harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Ordinary Responses REST API only; no SDK discovery, endpoint override, retry or child runtime. */
public final class HttpResponsesClient implements ResponsesClient {
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final Pattern RESPONSE_ID = Pattern.compile("resp_[A-Za-z0-9_-]{1,200}");
    private static final Pattern ITEM_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,255}");
    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
    };

    private final String apiKey;
    private final String credentialSource;
    private final HttpClient client;
    private final Exchange exchange;

    @FunctionalInterface
    interface Exchange { ExchangeResult send(HttpRequest request) throws Exception; }

    record ExchangeResult(int status, byte[] body) {
        ExchangeResult { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }

    public HttpResponsesClient(String apiKey) { this(apiKey, "CONSTRUCTOR", null); }

    public static HttpResponsesClient fromEnvironment() {
        return new HttpResponsesClient(System.getenv("OPENAI_API_KEY"), "OPENAI_API_KEY_ENVIRONMENT", null);
    }

    /** Package-private transport fixture; its active configuration explicitly identifies the mock. */
    HttpResponsesClient(String apiKey, Exchange exchange) {
        this(apiKey, "TEST_INJECTION", java.util.Objects.requireNonNull(exchange));
    }

    private HttpResponsesClient(String apiKey, String credentialSource, Exchange fixture) {
        if (apiKey == null || apiKey.isBlank() || apiKey.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("API_CREDENTIAL_REQUIRED");
        this.apiKey = apiKey;
        this.credentialSource = credentialSource;
        this.client = fixture == null ? HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(DIRECT).build() : null;
        this.exchange = fixture == null ? this::httpExchange : fixture;
    }

    @Override public Map<String, Object> configuration() {
        return Json.object("adapterImplementation", getClass().getName(), "adapterVersion", "1",
                "transportKind", client == null ? "MOCK_EXCHANGE" : "OPENAI_RESPONSES_HTTPS",
                "endpoint", ENDPOINT.toString(), "timeoutMillis", TIMEOUT.toMillis(),
                "connectTimeoutMillis", client == null ? TIMEOUT.toMillis()
                        : client.connectTimeout().orElseThrow().toMillis(),
                "redirectPolicy", client == null ? "NEVER" : client.followRedirects().name(),
                "proxyPolicy", client == null ? "MOCK_EXCHANGE" : client.proxy().orElseThrow() == DIRECT
                        ? "DIRECT_ONLY" : "UNEXPECTED_PROXY",
                "maximumBodyBytes", MAX_BODY_BYTES, "hostAutomaticRetries", false,
                "credentialSource", credentialSource);
    }

    @Override public String create(String exactRequestBody) throws Exception {
        if (exactRequestBody == null) throw new BoundaryException("API_REQUEST_REQUIRED");
        rejectCredentialMaterial(exactRequestBody);
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(exactRequestBody));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException failure) { throw new BoundaryException("API_REQUEST_UTF8_REJECTED"); }
        if (bytes.length > MAX_BODY_BYTES) throw new BoundaryException("API_REQUEST_TOO_LARGE");
        return send(ENDPOINT, bytes);
    }

    @Override public String retrieve(String responseId) throws Exception {
        requireId(RESPONSE_ID, responseId);
        return send(URI.create(ENDPOINT + "/" + responseId), null);
    }

    @Override public String inputItems(String responseId, String after) throws Exception {
        requireId(RESPONSE_ID, responseId);
        if (after != null) requireId(ITEM_ID, after);
        return send(URI.create(ENDPOINT + "/" + responseId + "/input_items?order=asc&limit=100"
                + (after == null ? "" : "&after=" + after)), null);
    }

    private static void requireId(Pattern pattern, String value) throws BoundaryException {
        if (value == null || !pattern.matcher(value).matches()) throw new BoundaryException("API_ID_REJECTED");
    }

    @Override public void rejectCredentialMaterial(String text) {
        if (text == null) throw new IllegalArgumentException("API_TEXT_REQUIRED");
        Evidence.inspectSecretMaterial(text, this::rejectKnownCredential);
    }

    private void rejectKnownCredential(String text) {
        // Cover retained raw bodies and bodies embedded in deterministic JSON evidence.
        String encoded = apiKey;
        for (int i = 0; i < 5; i++) {
            if (text.contains(encoded)) throw new IllegalArgumentException("SECRET_MATERIAL_REJECTED");
            String quoted = Json.write(encoded);
            encoded = quoted.substring(1, quoted.length() - 1);
        }
    }

    private String send(URI uri, byte[] body) throws BoundaryException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey).header("Accept", "application/json");
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            ExchangeResult result = exchange.send(builder.build());
            if (result.status() != 200) throw new BoundaryException(
                    result.status() >= 100 && result.status() <= 599 ? "API_HTTP_" + result.status() : "API_HTTP_INVALID");
            byte[] bytes = result.body();
            if (bytes.length > MAX_BODY_BYTES) throw new BoundaryException("API_BODY_TOO_LARGE");
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            rejectCredentialMaterial(raw);
            return raw;
        } catch (BoundaryException failure) { throw failure; }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new BoundaryException("API_INTERRUPTED");
        } catch (Exception failure) { throw new BoundaryException("API_BOUNDARY_REJECTED"); }
    }

    private ExchangeResult httpExchange(HttpRequest request) throws BoundaryException {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new ExchangeResult(response.statusCode(), response.body());
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new BoundaryException("API_TIMEOUT");
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new BoundaryException("API_INTERRUPTED");
        } catch (ExecutionException failure) {
            pending.cancel(true);
            throw new BoundaryException("API_TRANSPORT_FAILED");
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return completed; }

        @Override public void onSubscribe(Flow.Subscription candidate) {
            if (subscription != null) candidate.cancel();
            else { subscription = candidate; subscription.request(1); }
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (completed.isDone()) { subscription.cancel(); return; }
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_BODY_BYTES) {
                    subscription.cancel();
                    completed.completeExceptionally(new BoundaryException("API_BODY_TOO_LARGE"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) {
            completed.completeExceptionally(new BoundaryException("API_TRANSPORT_FAILED"));
        }

        @Override public void onComplete() { completed.complete(bytes.toByteArray()); }
    }

    /** Contains a host-owned code only, never request headers, response bodies, secrets or causes. */
    private static final class BoundaryException extends IOException {
        BoundaryException(String code) { super(code); }
    }
}
