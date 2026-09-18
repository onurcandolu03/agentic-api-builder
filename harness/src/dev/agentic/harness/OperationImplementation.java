package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.agentic.harness.ArtifactContracts.*;

/** Host-only NEW_OPERATION profile. Never parses migration authority or constructs process capabilities. */
final class OperationImplementation {
    static final List<TrustedInputs.Role> ROLES = List.of(TrustedInputs.Role.DOMAIN, TrustedInputs.Role.PERSISTENCE,
            TrustedInputs.Role.SERVICE, TrustedInputs.Role.TESTS);
    record FileRule(String componentId, String path, String action, String text) {
        Map<String,Object> view() {
            return Json.object("componentDecisionId", componentId, "pathKey", path, "action", action,
                    "expectedContentFingerprint", Json.fingerprint("PATH_CONTENT:" + path, text.getBytes(StandardCharsets.UTF_8)));
        }
    }
    record Stage(TrustedInputs.Role role, Map<String,Object> assignment, List<FileRule> files) {}
    private final Map<String,Object> plan, lineage, policyFingerprint;
    private final List<Stage> stages;
    private final List<Map<String,Object>> ledger = new ArrayList<>();
    private int completed;
    private Map<String,Object> bundle, bundleFingerprint;
    private Map<String,Object> active;

    private OperationImplementation(Map<String,Object> plan, Map<String,Object> lineage, Map<String,Object> policyFingerprint, List<Stage> stages) {
        this.plan = object(Json.object("value", plan).get("value")); this.lineage = lineage;
        this.policyFingerprint = policyFingerprint; this.stages = List.copyOf(stages);
    }
    static Map<String,Object> lineage(MigrationInput input, Map<String,byte[]> accepted) {
        return Json.object("workflow", "NEW_OPERATION", "callerRequestFingerprint", input.fingerprint(),
                "operationRequirementFingerprint", Json.fingerprint("OPERATION_REQUIREMENT", accepted.get("OPERATION_REQUIREMENT")),
                "targetAnalysisFingerprint", Json.fingerprint("TARGET_ANALYSIS", accepted.get("TARGET_ANALYSIS")),
                "operationPlanFingerprint", Json.fingerprint("OPERATION_PLAN", accepted.get("OPERATION_PLAN")));
    }
    static Map<String,Object> obligations(Map<String,Object> plan, Map<String,Object> component) {
        var ids = list(component.get("behavioralChangeIds"));
        return Json.object("componentDecision", component, "requestedOperation", plan.get("requestedOperation"),
                "targetConventions", plan.get("targetConventions"), "databaseMapping", plan.get("databaseMapping"),
                "fieldMappings", plan.get("fieldMappings"), "placement", plan.get("placement"),
                "behavioralObligations", list(plan.get("requiredBehavioralChanges")).stream().map(ArtifactContracts::object)
                        .filter(b -> ids.contains(b.get("id"))).toList(),
                "testObligations", list(plan.get("testObligations")).stream().map(ArtifactContracts::object)
                        .filter(t -> list(t.get("componentDecisionIds")).contains(component.get("id"))).toList(),
                "validationObligations", plan.get("validationObligations"));
    }
    static OperationImplementation prepare(MigrationInput input, Map<String,byte[]> accepted, byte[] hostPolicy) {
        byte[] bytes = accepted.get("OPERATION_PLAN");
        if (bytes == null) throw new IllegalArgumentException("OPERATION_PLAN_REQUIRED");
        var plan = ArtifactContracts.validate(TrustedInputs.Role.OPERATION_PLANNING, bytes, input, accepted);
        equal(plan.get("status"), "SUCCESS", "OPERATION_PLAN_NOT_ACCEPTED");
        if (hostPolicy == null) throw new IllegalStateException("NEW_OPERATION_IMPLEMENTATION_AUTHORITY_REQUIRED");
        var policy = Json.parse(MigrationInput.utf8(hostPolicy));
        fields(policy, "authorityVersion authorityKind lineage files");
        equal(policy.get("authorityVersion"), 1, "OPERATION_AUTHORITY_VERSION");
        equal(policy.get("authorityKind"), "NEW_OPERATION_EXACT_FILES_V1", "OPERATION_AUTHORITY_KIND");
        var lineage = lineage(input, accepted);
        equal(policy.get("lineage"), lineage, "OPERATION_AUTHORITY_LINEAGE");
        var target = Json.parse(MigrationInput.utf8(accepted.get("TARGET_ANALYSIS")));
        Map<String,Map<String,Object>> rules = new LinkedHashMap<>();
        for (Object entry : list(policy.get("files"))) {
            var rule = object(entry); fields(rule, "componentDecisionId path expectedText obligationsFingerprint");
            if (rules.putIfAbsent(nonempty(rule.get("componentDecisionId")), rule) != null) fail("DUPLICATE_OPERATION_RULE");
            ExecutionPlan.canonicalPath(nonempty(rule.get("path"))); string(rule.get("expectedText"));
            Evidence.rejectObviousSecrets(string(rule.get("expectedText")));
        }
        int previousOwner = -1;
        for (Object entry : list(plan.get("implementationOrder"))) {
            int owner = ROLES.indexOf(TrustedInputs.Role.find(string(object(entry).get("specialistRole"))));
            if (owner < previousOwner) throw new IllegalStateException("NEW_OPERATION_STAGE_ORDER_REQUIRED");
            previousOwner = owner;
        }
        List<Stage> stages = new ArrayList<>(); Set<String> used = new HashSet<>();
        for (var role : ROLES) {
            List<Map<String,Object>> components = list(plan.get("componentDecisions")).stream().map(ArtifactContracts::object)
                    .filter(c -> OperationPlanContract.owner(string(c.get("component"))).equals(role.id)).toList();
            List<FileRule> files = new ArrayList<>();
            for (var component : components) {
                Object action = component.get("decision");
                if (!Set.of("CREATE", "MODIFY").contains(action == null ? "" : action)) continue;
                String id = string(component.get("id")), path = string(component.get("targetPath"));
                var rule = rules.get(id);
                if (rule == null) throw new IllegalStateException("NEW_OPERATION_INDEPENDENT_OBLIGATION_MECHANISM_REQUIRED");
                equal(rule.get("path"), path, "OPERATION_RULE_PATH");
                equal(rule.get("obligationsFingerprint"), Json.fingerprint("OPERATION_COMPONENT_OBLIGATIONS", Json.bytes(obligations(plan, component))), "OPERATION_RULE_OBLIGATIONS");
                if (action.equals("CREATE")) equal(path, OperationPlanContract.creationPath(input, target, string(component.get("component"))), "OPERATION_CREATION_PATH");
                used.add(id); files.add(new FileRule(id, path, (String)action, string(rule.get("expectedText"))));
            }
            var assignment = Json.object("workflow", "NEW_OPERATION", "stage", role.id, "componentDecisions", components,
                    "allowedWrites", files.stream().map(FileRule::view).toList(),
                    "obligations", components.stream().map(c -> obligations(plan, c)).toList(),
                    "implementationOrder", list(plan.get("implementationOrder")).stream().map(ArtifactContracts::object)
                            .filter(s -> role.id.equals(s.get("specialistRole"))).toList(),
                    "requiredPredecessorStages", ROLES.subList(0, stages.size()).stream().map(r -> r.id).toList(),
                    "noMutation", files.isEmpty());
            stages.add(new Stage(role, assignment, List.copyOf(files)));
        }
        equal(used, rules.keySet(), "EXCESS_OPERATION_RULES");
        return new OperationImplementation(plan, lineage, Json.fingerprint("CALLER_RESOLUTION", hostPolicy), stages);
    }
    Map<String,Object> bind(String runId, Map<String,Object> targetIdentity, RepositoryFiles.Snapshot baseline,
                            List<Map<String,Object>> specifications, List<Map<String,Object>> gates) {
        if (bundle != null) fail("OPERATION_AUTHORITY_ALREADY_BOUND");
        bundle = Json.object("bundleVersion", 1, "bundleKind", "NEW_OPERATION_IMPLEMENTATION_AUTHORITY_V1",
                "workflow", "NEW_OPERATION", "runId", runId, "lineage", lineage, "hostPolicyFingerprint", policyFingerprint,
                "targetIdentity", targetIdentity, "targetBaselineFingerprint", Json.evidenceFingerprint(baseline.entries()),
                "componentDecisions", plan.get("componentDecisions"), "assignments", stages.stream().map(Stage::assignment).toList(),
                "trustedSpecifications", specifications, "runtimeCapabilities", gates,
                "repositoryEffectPolicy", "EXACT_GRANTED_FILES_AND_OBSERVED_CREATE_PARENT_METADATA_ONLY",
                "executionCapabilities", List.of("BOUNDED_TARGET_READ", "EXACT_TARGET_CREATE", "EXACT_TARGET_MODIFY"),
                "validationExecution", "NOT_PERMITTED");
        bundleFingerprint = Json.fingerprint("NEW_OPERATION_IMPLEMENTATION_AUTHORITY_V1", Json.bytes(bundle));
        return bundle;
    }
    List<Stage> stages() { return stages; }
    Map<String,Object> bundleFingerprint() { return bundleFingerprint; }
    Map<String,Object> bundle() { return bundle; }
    void requireComplete(RepositoryFiles.Snapshot state) {
        if (completed != ROLES.size() || active != null || bundle == null) fail("OPERATION_VALIDATION_PREDECESSORS_REQUIRED");
        verifyCompleted(state);
    }
    Map<String,Object> lineage() { return lineage; }
    List<Map<String,Object>> ledger() { return List.copyOf(ledger); }
    Set<String> paths() {
        Set<String> paths = new LinkedHashSet<>();
        for (Object c : list(plan.get("componentDecisions"))) if (object(c).get("targetPath") != null) paths.add(string(object(c).get("targetPath")));
        return paths;
    }
    void preflight(RepositoryFiles.Snapshot state) {
        for (var stage : stages) for (var rule : stage.files) {
            var current = state(state, rule.path);
            if (rule.action.equals("CREATE") ? !"ABSENT".equals(current.get("existence")) : !"REGULAR_FILE".equals(current.get("fileType")))
                throw new IllegalStateException("OPERATION_PATH_STATE_REJECTED");
            if (rule.view().get("expectedContentFingerprint").equals(current.get("contentFingerprint")))
                throw new IllegalStateException("OPERATION_IMPLEMENTATION_ALREADY_SATISFIED");
        }
    }
    void verifyCompleted(RepositoryFiles.Snapshot state) {
        for (int i = 0; i < completed; i++) verifyFiles(stages.get(i), state);
    }
    void checkWrite(TrustedInputs.Role role, String path, String text) {
        if (completed >= stages.size() || stages.get(completed).role != role) fail("OPERATION_WRITE_STAGE");
        var rule = stages.get(completed).files.stream().filter(f -> f.path.equals(path)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("OPERATION_UNGRANTED_PATH"));
        equal(text, rule.text, "OPERATION_UNAPPROVED_CONTENT");
    }
    void verifyFiles(Stage stage, RepositoryFiles.Snapshot state) {
        for (var file : stage.files) equal(state(state, file.path).get("contentFingerprint"),
                file.view().get("expectedContentFingerprint"), "OPERATION_STATIC_OBLIGATION_FAILED");
    }
    Map<String,Object> dispatch(Stage stage, String invocationId, RepositoryFiles.Snapshot before, Map<String,Object> predecessor) {
        if (active != null || bundle == null || completed >= stages.size() || stages.get(completed) != stage) fail("OPERATION_PREDECESSOR_ORDER");
        verifyCompleted(before);
        var event = Json.object("eventType", "NEW_OPERATION_STAGE_DISPATCH", "invocationId", invocationId,
                "runAuthorityBundleFingerprint", bundleFingerprint, "lineage", lineage, "assignment", stage.assignment,
                "beforeStateFingerprint", Json.evidenceFingerprint(before.entries()), "predecessorAcceptance", predecessor,
                "acceptedPredecessorStages", ledger.stream().filter(e -> "NEW_OPERATION_STAGE_ACCEPTED".equals(e.get("eventType"))).toList());
        active = event; ledger.add(event); return event;
    }
    void validateResult(Stage stage, Map<String,Object> result, Map<String,Object> dispatch, List<Map<String,Object>> effects) {
        if (active != dispatch || stages.get(completed) != stage) fail("OPERATION_STALE_DISPATCH");
        fields(result, "implementationVersion workflow agent status lineage runAuthorityBundleFingerprint dispatchFingerprint predecessorAcceptance assignmentFingerprint completedComponentDecisionIds changedFiles grantEffects");
        equal(result.get("implementationVersion"), 1, "OPERATION_RESULT_VERSION");
        equal(result.get("workflow"), "NEW_OPERATION", "OPERATION_RESULT_WORKFLOW");
        equal(result.get("agent"), stage.role.id, "OPERATION_RESULT_ROLE"); equal(result.get("status"), "SUCCESS", "OPERATION_RESULT_STATUS");
        equal(result.get("lineage"), lineage, "OPERATION_RESULT_LINEAGE");
        equal(result.get("runAuthorityBundleFingerprint"), bundleFingerprint, "OPERATION_RESULT_BUNDLE");
        equal(result.get("dispatchFingerprint"), Json.fingerprint("NEW_OPERATION_STAGE_DISPATCH", Json.bytes(dispatch)), "OPERATION_RESULT_DISPATCH");
        equal(result.get("predecessorAcceptance"), dispatch.get("predecessorAcceptance"), "OPERATION_RESULT_PREDECESSOR");
        equal(result.get("assignmentFingerprint"), Json.fingerprint("NEW_OPERATION_STAGE_ASSIGNMENT", Json.bytes(stage.assignment)), "OPERATION_RESULT_ASSIGNMENT");
        equal(result.get("completedComponentDecisionIds"), list(stage.assignment.get("componentDecisions")).stream().map(ArtifactContracts::object).map(c -> c.get("id")).toList(), "OPERATION_RESULT_COMPONENTS");
        equal(result.get("changedFiles"), stage.files.stream().map(FileRule::path).toList(), "OPERATION_RESULT_FILES");
        equal(result.get("grantEffects"), Json.parseValue(Json.write(effects)), "OPERATION_RESULT_EFFECTS");
        equal(effects.size(), stage.files.size(), "OPERATION_MISSING_EFFECTS");
    }
    void commit(Stage stage, Map<String,Object> dispatch, Map<String,Object> acceptance, RepositoryFiles.Snapshot after, List<Map<String,Object>> effects) {
        if (active != dispatch || stages.get(completed) != stage) fail("OPERATION_PREDECESSOR_ORDER");
        verifyCompleted(after); verifyFiles(stage, after);
        ledger.add(Json.object("eventType", "NEW_OPERATION_STAGE_ACCEPTED", "stage", stage.role.id,
                "runAuthorityBundleFingerprint", bundleFingerprint, "dispatchFingerprint", Json.fingerprint("NEW_OPERATION_STAGE_DISPATCH", Json.bytes(dispatch)),
                "acceptance", acceptance, "afterStateFingerprint", Json.evidenceFingerprint(after.entries()), "hostObservedEffects", effects));
        completed++; active = null;
    }
    void terminate(RepositoryFiles.Snapshot observed, String status, String reason) {
        if (active == null) return;
        ledger.add(Json.object("eventType", "NEW_OPERATION_STAGE_TERMINATED", "dispatchFingerprint",
                Json.fingerprint("NEW_OPERATION_STAGE_DISPATCH", Json.bytes(active)), "status", status, "reason", reason,
                "acceptanceStatus", "NOT_ACCEPTED", "observedState", observed == null ? null : observed.entries()));
        active = null;
    }
    static Map<String,Object> state(RepositoryFiles.Snapshot state, String path) {
        return state.entries().stream().filter(e -> path.equals(e.get("pathKey"))).findFirst().orElse(RepositoryFiles.absent(path));
    }
    private static void fail(String code) { throw new IllegalArgumentException(code); }
}
