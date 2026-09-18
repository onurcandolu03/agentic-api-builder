package dev.agentic.harness;

import java.util.*;
import static dev.agentic.harness.ArtifactContracts.*;

/** Host-derived operation validation lineage. No source lineage or command selection. */
final class OperationValidation {
    static Map<String,Object> bind(MigrationInput input, ArtifactStore artifacts, OperationImplementation implementation,
                                   RepositoryFiles.Snapshot state, ValidationProfile profile, String runId,
                                   Map<String,Object> specification, List<Map<String,Object>> effects) {
        if (profile == null) throw new IllegalStateException("NEW_OPERATION_VALIDATION_AUTHORITY_REQUIRED");
        var policy = profile.operationPolicy();
        var bytes = artifacts.bytes();
        var plan = ArtifactContracts.validate(TrustedInputs.Role.OPERATION_PLANNING, bytes.get("OPERATION_PLAN"), input, bytes);
        equal(plan.get("status"), "SUCCESS", "OPERATION_VALIDATION_PLAN_NOT_READY");
        equal(policy.get("operationPlanFingerprint"), artifacts.required("OPERATION_PLAN").fingerprint(), "OPERATION_VALIDATION_POLICY_PLAN");
        equal(implementation.lineage(), OperationImplementation.lineage(input, bytes), "OPERATION_VALIDATION_LINEAGE");
        implementation.requireComplete(state);
        var ids = list(plan.get("testObligations")).stream().map(ArtifactContracts::object).map(t -> string(t.get("id"))).toList();
        equal(new HashSet<>(ids), profile.operationTests().keySet(), "OPERATION_VALIDATION_TEST_COVERAGE");
        var accepted = implementation.ledger().stream().filter(e -> "NEW_OPERATION_STAGE_ACCEPTED".equals(e.get("eventType"))).toList();
        equal(accepted.size(), 4, "OPERATION_VALIDATION_STAGE_COUNT");
        var predecessors = new ArrayList<Map<String,Object>>();
        var acceptedEffects = new ArrayList<Object>();
        for (int i = 0; i < 4; i++) {
            var role = OperationImplementation.ROLES.get(i);
            var a = artifacts.required("OPERATION_IMPLEMENTATION_RESULT:" + role.id);
            var result = Json.parse(a.text()); var event = accepted.get(i); var acceptance = object(event.get("acceptance"));
            equal(a.binding().get("role"), role.id, "OPERATION_VALIDATION_STAGE_ROLE");
            equal(a.binding().get("runId"), runId, "OPERATION_VALIDATION_RUN");
            equal(result.get("lineage"), implementation.lineage(), "OPERATION_VALIDATION_STAGE_LINEAGE");
            equal(result.get("runAuthorityBundleFingerprint"), implementation.bundleFingerprint(), "OPERATION_VALIDATION_BUNDLE");
            equal(result.get("status"), "SUCCESS", "OPERATION_VALIDATION_STAGE_STATUS");
            equal(event.get("stage"), role.id, "OPERATION_VALIDATION_STAGE_ORDER");
            equal(acceptance.get("artifactFingerprint"), a.fingerprint(), "OPERATION_VALIDATION_ACCEPTANCE");
            equal(acceptance.get("acceptanceInvocationId"), a.acceptanceInvocationId(), "OPERATION_VALIDATION_ACCEPTANCE");
            equal(result.get("grantEffects"), Json.parseValue(Json.write(event.get("hostObservedEffects"))), "OPERATION_VALIDATION_EFFECTS");
            acceptedEffects.addAll(list(event.get("hostObservedEffects")));
            predecessors.add(Json.object("artifactRole", a.role(), "fingerprint", a.fingerprint(),
                    "acceptanceInvocationId", a.acceptanceInvocationId(), "acceptanceProviderResponseId", a.acceptanceProviderResponseId(),
                    "stageEvidence", event));
        }
        var actualEffects = effects.stream().map(m -> {
            var grant = object(m.get("grant"));
            return Json.object("grantId", grant.get("grantId"), "invocationId", grant.get("invocationId"), "role", grant.get("role"),
                    "pathKey", grant.get("pathKey"), "action", grant.get("action"), "beforeState", m.get("beforeState"), "afterState", m.get("afterState"));
        }).toList();
        equal(Json.parseValue(Json.write(acceptedEffects)), Json.parseValue(Json.write(actualEffects)), "OPERATION_VALIDATION_EFFECT_LEDGER");
        return Json.object("workflow", "NEW_OPERATION", "lineage", implementation.lineage(),
                "implementationAuthorityFingerprint", implementation.bundleFingerprint(), "implementationResults", predecessors,
                "targetIdentity", implementation.bundle().get("targetIdentity"),
                "implementationEffects", effects, "implementationLedgerFingerprint", Json.evidenceFingerprint(implementation.ledger()),
                "postImplementationFingerprint", Json.evidenceFingerprint(state.entries()),
                "obligations", Json.object("requestedOperation", plan.get("requestedOperation"), "databaseMapping", plan.get("databaseMapping"),
                        "fieldMappings", plan.get("fieldMappings"), "componentDecisions", plan.get("componentDecisions"),
                        "requiredBehavioralChanges", plan.get("requiredBehavioralChanges"), "placement", plan.get("placement"),
                        "testObligations", plan.get("testObligations"), "validationObligations", plan.get("validationObligations")),
                "staticObligationEvidence", "HOST_APPROVED_EXACT_FILES_REVALIDATED", "agent07SpecificationFingerprint", specification,
                "hostValidationPolicy", policy, "hostValidationPolicyFingerprint", Json.fingerprint("OPERATION_VALIDATION_POLICY", Json.bytes(policy)));
    }
}
