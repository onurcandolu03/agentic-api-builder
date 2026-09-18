package dev.agentic.harness;

import java.util.*;

/** Pure, deterministic caller-data normalization. No filesystem, provider or execution capability. */
final class OperationRequirement {
    private static final List<String> CRITICAL = List.of("tableName", "operationType", "operationName", "requestFields", "responseFields");
    private static final List<String> PLACEMENT = List.of("parentKey", "parentOrder", "filterOrder", "before", "after", "reference");
    private OperationRequirement() {}

    static void validateInput(Map<String,Object> data) {
        for (String key : List.of("tableName", "operationType", "operationName"))
            if (data.containsKey(key)) name(data.get(key));
        if (data.containsKey("operationType") && !Set.of("GET", "INSERT", "UPDATE", "DELETE").contains(data.get("operationType"))) reject();
        if (data.containsKey("requirementText") && !(data.get("requirementText") instanceof String)) reject();
        for (String key : List.of("requestFields", "responseFields")) {
            if (!data.containsKey(key)) continue;
            if (!(data.get(key) instanceof List<?> fields)) { reject(); return; }
            Set<String> names = new HashSet<>();
            for (Object item : fields) {
                var field = object(item);
                if (!Set.of("name", "description").containsAll(field.keySet()) || !field.containsKey("name")) reject();
                name(field.get("name"));
                if (!names.add((String)field.get("name"))) reject();
                if (field.containsKey("description") && !(field.get("description") instanceof String)) reject();
            }
        }
        if (data.containsKey("placement")) {
            var placement = object(data.get("placement"));
            if (!new HashSet<>(PLACEMENT).containsAll(placement.keySet())) reject();
            placement.forEach((key, value) -> {
                if (key.equals("parentOrder") || key.equals("filterOrder")) {
                    if (!(value instanceof Integer || value instanceof Long)) reject();
                } else name(value);
            });
        }
    }

    static Map<String,Object> normalize(MigrationInput input) {
        if (input.workflow() != Workflow.NEW_OPERATION) throw new IllegalArgumentException("REQUIREMENT_WORKFLOW_MISMATCH");
        var data = input.callerInformation();
        Map<String,Object> explicit = new LinkedHashMap<>();
        List<Map<String,Object>> unspecified = new ArrayList<>(), blockers = new ArrayList<>();
        for (String key : CRITICAL) {
            if (data.containsKey(key)) explicit.put(key, caller("/" + key, data.get(key)));
            else blockers.add(Json.object("code", "MISSING_CRITICAL_REQUIREMENT", "reference", "/" + key,
                    "resolutionRoute", "HOST_INPUT_REGISTRATION"));
        }
        if (data.containsKey("requirementText")) explicit.put("requirementText", caller("/requirementText", data.get("requirementText")));
        var placement = data.containsKey("placement") ? object(data.get("placement")) : Map.<String,Object>of();
        if (data.containsKey("placement")) explicit.put("placement", caller("/placement", placement));
        for (String key : PLACEMENT) if (!placement.containsKey(key))
            unspecified.add(discoverable("placement." + key));
        for (String key : List.of("databaseColumns", "javaTypes", "endpointPaths", "persistenceTechnology", "targetConventions"))
            unspecified.add(discoverable(key));
        if (placement.containsKey("before") && placement.containsKey("after"))
            blockers.add(Json.object("code", "CONFLICTING_PLACEMENT", "reference", "/placement",
                    "resolutionRoute", "HOST_INPUT_REGISTRATION"));
        return Json.object("requirementVersion", 1, "workflow", "NEW_OPERATION",
                "status", blockers.isEmpty() ? "SUCCESS" : "BLOCKED",
                "callerRequestFingerprint", input.fingerprint(), "targetProjectRoot", input.targetRoot().toString(),
                "explicitRequirements", explicit, "unspecified", unspecified, "blockingAmbiguities", blockers);
    }

    static void validate(Map<String,Object> artifact, MigrationInput input) {
        if (!normalize(input).equals(artifact)) throw new IllegalArgumentException("OPERATION_REQUIREMENT_MISMATCH");
    }
    private static Map<String,Object> caller(String reference, Object value) {
        return Json.object("provenance", "CALLER_PROVIDED", "reference", reference, "value", value);
    }
    private static Map<String,Object> discoverable(String field) {
        return Json.object("field", field, "resolutionRoute", "TARGET_ANALYSIS_OR_OPERATION_PLANNING");
    }
    private static void name(Object value) {
        if (!(value instanceof String text) || text.isBlank()) reject();
        Json.identifier((String)value);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?>)) reject();
        return (Map<String,Object>)value;
    }
    private static void reject() { throw new IllegalArgumentException("NEW_OPERATION_INPUT_REJECTED"); }
}
