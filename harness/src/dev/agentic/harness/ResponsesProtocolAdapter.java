package dev.agentic.harness;

import java.util.*;

/** Direct Responses protocol only. No orchestration, artifact acceptance or filesystem authority. */
public final class ResponsesProtocolAdapter implements ProviderTransport {
    private final ResponsesClient client;

    public ResponsesProtocolAdapter(ResponsesClient client) { this.client = Objects.requireNonNull(client); }
    @Override public Capabilities capabilities() { return new Capabilities(true, true, true); }
    @Override public Map<String,Object> configuration() {
        return Json.object("protocolAdapter", "OPENAI_RESPONSES_V1", "transport", client.configuration());
    }
    @Override public void rejectCredentialMaterial(String text) { client.rejectCredentialMaterial(text); }

    @Override public Prepared prepare(Turn turn) {
        Map<String,Object> input = Json.object("type", "message", "role", "user", "content",
                List.of(Json.object("type", "input_text", "text", turn.input())));
        Map<String,Object> request = Json.object("model", turn.model(), "instructions", turn.instructions(),
                "input", List.of(input), "previous_response_id", turn.previousId(),
                "metadata", metadata(turn.correlation()), "tools", List.of(), "tool_choice", "none",
                "parallel_tool_calls", false, "store", true, "stream", false, "background", false,
                "truncation", "disabled", "max_output_tokens", turn.maxOutputTokens());
        return new Prepared(turn, Json.write(request), input);
    }
    private static Map<String,Object> metadata(Map<String,Object> correlation) {
        return Json.object("host_context", correlation.get("contextId"), "host_turn", correlation.get("ordinal"),
                "host_role", correlation.get("role"));
    }
    @Override public Observation create(Prepared prepared) throws Exception {
        // Exactly the frozen, screened host-approved body; no second request assembly.
        return decode(client.create(prepared.body()), prepared, null);
    }
    @Override public Observation retrieve(Prepared prepared, String responseId) throws Exception {
        return decode(client.retrieve(responseId), prepared, responseId);
    }
    private Observation decode(String raw, Prepared prepared, String expectedId) {
        screen(raw);
        var response = validateResponse(raw, Json.parse(prepared.body()), expectedId);
        var metadata = ExecutionPlan.map(response.get("metadata"));
        Map<String,Object> echo = Json.object("instructions", response.get("instructions"),
                "previousId", response.get("previous_response_id"), "model", response.get("model"),
                "maxOutputTokens", response.get("max_output_tokens"), "correlation",
                Json.object("contextId", metadata.get("host_context"), "ordinal", metadata.get("host_turn"),
                        "role", metadata.get("host_role")));
        List<Item> outputs = new ArrayList<>();
        for (Object value : (List<?>)response.get("output")) {
            var item = ExecutionPlan.map(value);
            outputs.add(new Item((String)item.get("id"), ItemKind.OUTPUT, null, item));
        }
        // Foundation turns can contain refusals/multiple texts, as before. Execution requires one text.
        String text = prepared.turn().binding() == null ? null : structuredText(response);
        // Preserve the old Number.equals timestamp comparison, including numeric type, without
        // imposing host evidence JSON's integer-only schema on the provider's finite timestamp.
        Object timestamp = response.get("created_at");
        return new Observation((String)response.get("id"), (String)response.get("previous_response_id"), true,
                echo, text, outputs, timestamp.getClass().getName() + ":" + timestamp, raw);
    }
    private void screen(String raw) {
        if (raw == null || raw.length() > 4 * 1024 * 1024)
            throw new IllegalArgumentException("RESPONSE_BODY_LIMIT");
        Evidence.rejectObviousSecrets(raw);
        client.rejectCredentialMaterial(raw);
    }

    @Override public ContextPage inputItems(String responseId, String cursor) throws Exception {
        String raw = client.inputItems(responseId, cursor);
        screen(raw);
        var page = Json.parse(raw);
        if (!"list".equals(page.get("object")) || !(page.get("data") instanceof List<?> items)
                || items.isEmpty() || items.size() > 100 || !(page.get("has_more") instanceof Boolean))
            throw new IllegalArgumentException("INPUT_ITEMS_REQUIRED_FIELDS");
        List<Item> decoded = new ArrayList<>();
        String firstId = null, lastId = null;
        for (Object value : items) {
            var item = ExecutionPlan.map(value);
            if (!(item.get("id") instanceof String id) || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,255}"))
                throw new IllegalArgumentException("INPUT_ITEM_ID_INVALID");
            if (firstId == null) firstId = id;
            lastId = id;
            if ("message".equals(item.get("type")) && Set.of("user", "system", "developer").contains(item.get("role"))) {
                if (!Set.of("id", "type", "role", "content", "status").containsAll(item.keySet())
                        || (item.containsKey("status") && !"completed".equals(item.get("status"))))
                    throw new IllegalArgumentException("UNEXPECTED_INPUT_ITEM");
                boolean user = "user".equals(item.get("role"));
                Map<String,Object> comparison = Json.object("type", item.get("type"), "role", item.get("role"),
                        "content", item.get("content"));
                decoded.add(new Item(id, user ? ItemKind.USER_INPUT : ItemKind.INSTRUCTIONS,
                        user ? null : messageText(item), comparison));
            } else decoded.add(new Item(id, ItemKind.OUTPUT, null, item));
        }
        if (!Objects.equals(firstId, page.get("first_id")) || !Objects.equals(lastId, page.get("last_id")))
            throw new IllegalArgumentException("INPUT_PAGINATION_MISMATCH");
        return new ContextPage(decoded, (Boolean)page.get("has_more"), lastId, raw);
    }

    private Map<String, Object> validateResponse(String raw, Map<String, Object> request, String expectedId) {
        var response = Json.parse(raw);
        Object id = response.get("id");
        if (!(id instanceof String value) || !value.matches("resp_[A-Za-z0-9_-]{1,200}")
                || (expectedId != null && !expectedId.equals(value))
                || !"response".equals(response.get("object")) || !"completed".equals(response.get("status"))
                || !response.containsKey("error") || response.get("error") != null
                || !response.containsKey("incomplete_details") || response.get("incomplete_details") != null
                || !(response.get("created_at") instanceof Number timestamp)
                || !Double.isFinite(timestamp.doubleValue()) || timestamp.doubleValue() < 0
                || !(response.get("output") instanceof List<?>))
            throw new IllegalArgumentException("RESPONSE_REQUIRED_FIELDS");
        for (String field : List.of("model", "instructions", "previous_response_id", "metadata", "tools",
                "tool_choice", "parallel_tool_calls", "store", "background", "truncation", "max_output_tokens")) {
            if (!response.containsKey(field) || !Objects.equals(request.get(field), response.get(field)))
                throw new IllegalArgumentException("RESPONSE_CONFIGURATION_MISMATCH");
        }
        for (String field : List.of("conversation", "prompt", "context_management")) {
            if (response.get(field) != null) throw new IllegalArgumentException("UNBOUND_CONTEXT_CONFIGURATION");
        }
        // No function, hosted tool, handoff, or child-agent output is permitted by this empty tool registry.
        Set<String> outputIds = new HashSet<>();
        for (Object output : (List<?>) response.get("output")) {
            if (!(output instanceof Map<?, ?> item)
                    || !(item.get("id") instanceof String itemId)
                    || !itemId.matches("[A-Za-z][A-Za-z0-9_-]{0,255}") || !outputIds.add(itemId)
                    || !("reasoning".equals(item.get("type"))
                    || ("message".equals(item.get("type")) && "assistant".equals(item.get("role")))))
                throw new IllegalArgumentException("UNREGISTERED_TOOL_OR_CHILD");
            if ("message".equals(item.get("type"))) {
                if (!"completed".equals(item.get("status")) || !(item.get("content") instanceof List<?> content)
                        || content.isEmpty()) throw new IllegalArgumentException("INCOMPLETE_OUTPUT_MESSAGE");
                for (Object part : content) {
                    if (!(part instanceof Map<?, ?> entry)
                            || !("output_text".equals(entry.get("type")) && entry.get("text") instanceof String
                            || "refusal".equals(entry.get("type")) && entry.get("refusal") instanceof String))
                        throw new IllegalArgumentException("UNSUPPORTED_OUTPUT_CONTENT");
                }
            } else if (!(item.get("summary") instanceof List<?>)) {
                throw new IllegalArgumentException("INCOMPLETE_REASONING_ITEM");
            }
        }
        return response;
    }

    private static String structuredText(Map<String, Object> response) {
        List<String> texts = new ArrayList<>();
        for (Object itemValue : (List<?>) response.get("output")) {
            Map<?, ?> item = (Map<?, ?>) itemValue;
            if (!"message".equals(item.get("type"))) continue;
            for (Object partValue : (List<?>) item.get("content")) {
                Map<?, ?> part = (Map<?, ?>) partValue;
                if (!"output_text".equals(part.get("type")))
                    throw new IllegalArgumentException("STRUCTURED_RESPONSE_REQUIRED");
                texts.add((String) part.get("text"));
            }
        }
        if (texts.size() != 1) throw new IllegalArgumentException("ONE_STRUCTURED_RESPONSE_REQUIRED");
        return texts.getFirst();
    }

    private static String messageText(Map<?, ?> item) {
        if (!(item.get("content") instanceof List<?> content)) throw new IllegalArgumentException("INPUT_CONTENT");
        StringBuilder text = new StringBuilder();
        for (Object value : content) {
            if (!(value instanceof Map<?, ?> part) || !(part.get("text") instanceof String fragment)
                    || !"input_text".equals(part.get("type")) || !Set.of("type", "text").equals(part.keySet()))
                throw new IllegalArgumentException("UNSUPPORTED_INPUT_CONTENT");
            text.append(fragment);
        }
        return text.toString();
    }

}
