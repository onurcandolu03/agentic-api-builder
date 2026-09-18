package dev.agentic.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Offline specification and trusted-input freeze checks; does not execute an analyst. */
public final class SourceContractTest {
    private static int passed;

    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("REPOSITORY_AND_TEMP_REQUIRED");
        Path root = Path.of(args[0]).toRealPath();
        Path temp = Path.of(args[1]).toRealPath();
        String source = read(root, "agents/00-source-analysis.md");
        String planner = read(root, "agents/02-migration-planning.md");
        String master = read(root, "MASTER.md");
        String contract = read(root, "agents/contracts/orchestration-contract.md");
        run("source schema and evidence structures", () -> sourceSchema(source));
        run("MASTER analysis sequence and draft boundary", () -> masterSequence(master));
        run("planner requires both accepted analyses", () -> plannerInputs(planner));
        run("sparse and rich caller information remain variable", () -> configSemantics(source, planner));
        run("conflicts and ambiguity block without interactive questions", () -> unresolvedIssues(source, planner));
        run("source behavior cannot establish target technology", () -> technologySeparation(source, planner));
        run("source secret output policy", () -> secretPolicy(source));
        run("shared controlled roles, bundle and source gate", () -> sharedContract(contract));
        run("actual trusted documents have exact bytes and complete profiles", () -> trustedChain(root, temp));
        run("full current MASTER passes secret inspection", () -> masterSecretInspection(master));
        run("full actual trusted chain freezes without dispatch or access", () -> trustedChainFreeze(root, temp));
        run("downstream fingerprint correlation includes source authority", () -> specialistBindings(root));
        System.out.println("PASS " + passed + " source contract checks; local specifications and freeze only, no agent execution");
    }

    private static String read(Path root, String relative) throws Exception {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void run(String name, Check check) throws Exception {
        try { check.run(); passed++; }
        catch (Throwable failure) { throw new AssertionError("Source contract check failed: " + name, failure); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String prose(String document) { return document.replaceAll("\\s+", " "); }

    private static void contains(String document, String... clauses) {
        String normalized = prose(document);
        for (String clause : clauses)
            check(normalized.contains(prose(clause)), "Required contract clause missing: " + clause);
    }

    private static List<Map<String, Object>> schemas(String document) {
        var matcher = Pattern.compile("```json\\s*\\n(.*?)\\n```", Pattern.DOTALL).matcher(document);
        List<Map<String, Object>> result = new ArrayList<>();
        while (matcher.find()) result.add(Json.parse(matcher.group(1)));
        check(!result.isEmpty(), "JSON contract structures exist and parse without duplicate keys");
        return result;
    }

    private static Map<String, Object> schema(String document, String member) {
        var matches = schemas(document).stream().filter(value -> value.containsKey(member)).toList();
        check(matches.size() == 1, "One governing schema for " + member);
        return matches.getFirst();
    }

    private static Map<String, Object> schema(String document, String member, String value) {
        var matches = schemas(document).stream().filter(item -> value.equals(item.get(member))).toList();
        check(matches.size() == 1, "One governing schema for " + value);
        return matches.getFirst();
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        check(value instanceof Map<?, ?>, "Required object shape");
        return (Map<String, Object>) value;
    }

    private static void fields(Map<String, Object> value, String... names) {
        check(new ArrayList<>(value.keySet()).equals(List.of(names)), "Exact ordered schema fields: " + value.keySet());
    }

    private static void values(Object value, String... expected) {
        check(value instanceof String, "String enum declaration");
        check(Arrays.asList(((String) value).split(" \\| ")).equals(List.of(expected)), "Exact enum values: " + value);
    }

    private static void sourceSchema(String source) {
        var output = schema(source, "analysisVersion");
        fields(output, "analysisVersion", "status", "project", "analysisScope", "callerProvidedMigrationInfo",
                "technologyProfile", "operationEntryPoint", "callChain", "inputContract", "outputContract",
                "persistenceBehavior", "externalDependencies", "businessRules", "validationBehavior", "errorBehavior",
                "transactionBehavior", "findings", "evidence", "conflicts", "uncertainties", "blockingQuestions", "analysisCoverage");
        check(output.get("analysisVersion").equals(1), "analysis version 1");
        values(output.get("status"), "SUCCESS", "PARTIAL", "BLOCKED", "FAILED");
        fields(object(output.get("project")), "name", "root");
        fields(object(output.get("analysisScope")), "migrationMode", "requestedOperation", "callerLocatorIds",
                "resolvedEntryPointId", "candidateEntryPoints");
        check("SOURCE_TO_TARGET".equals(object(output.get("analysisScope")).get("migrationMode")), "source mode explicit");
        fields(object(output.get("inputContract")), "parameters", "requestTypes", "fields", "findingIds");
        fields(object(output.get("outputContract")), "resultTypes", "responseTypes", "fields", "findingIds");
        fields(object(output.get("persistenceBehavior")), "mechanisms", "queries", "tables", "columns", "joins", "filters",
                "parameters", "ordering", "grouping", "pagination", "resultMappings", "findingIds");
        fields(object(output.get("analysisCoverage")), "scope", "inventory", "areas", "samplingNotes", "limitations");
        fields(schema(source, "id", "U-001"), "id", "topic", "description", "reason", "affectedScopes", "callerInfoIds",
                "evidenceIds", "downstreamImpact", "resolutionNeeded");
        values(schema(source, "area").get("area"), "SCOPE", "OPERATION_ENTRY_POINT", "CALL_CHAIN", "INPUT_CONTRACT",
                "OUTPUT_CONTRACT", "PERSISTENCE", "EXTERNAL_DEPENDENCIES", "BUSINESS_RULES", "VALIDATION",
                "ERROR_BEHAVIOR", "TRANSACTIONS", "TECHNOLOGY_PROFILE", "CALLER_RECONCILIATION");
        var finding = schema(source, "id", "F-001");
        fields(finding, "id", "topic", "findingStatus", "statement", "scope", "sampleBasis", "evidenceIds", "details");
        values(finding.get("findingStatus"), "OBSERVED", "NOT_OBSERVED", "UNCERTAIN", "NOT_APPLICABLE");
        fields(schema(source, "id", "E-001"), "id", "kind", "path", "type", "symbol", "annotationOrConfigKey",
                "location", "observation", "supports");
        fields(schema(source, "id", "CC-001"), "id", "path", "type", "symbol", "calledFromIds", "relationship",
                "findingStatus", "findingIds", "evidenceIds");
        contains(source, "Every material conclusion references evidence IDs", "never invent line numbers",
                "Missing evidence does not prove absence", "Include all areas in the listed order",
                "Empty behavior arrays do not prove absence", "There is no target `implementationConstraint`");
        check(!source.contains("crm-portfolio-work") && !source.contains("getBranchCodeListByRegions")
                && !source.contains("HUMANIST"), "no reference project hardcoding");
    }

    private static void masterSequence(String master) {
        contains(master, "Status: DRAFT, NOT YET EXECUTABLE FOR A FULL MIGRATION.",
                "For `SOURCE_TO_TARGET`, execute these phases in order:",
                "1. `INPUT_REGISTRATION` 2. `SOURCE_ANALYSIS` (00) 3. `TARGET_ANALYSIS` (01) 4. `MIGRATION_PLANNING` (02)",
                "02 requires both accepted analyses in `SOURCE_TO_TARGET`",
                "If 00 returns `BLOCKED` or `FAILED`, do not continue to 01 or 02 requiring that source evidence",
                "registering 00 as trusted does not make it executable",
                "complete MASTER/00–07 profile coverage");
        check(!master.contains("MASTER/01–07"), "no stale MASTER discovery coverage");
    }

    private static void plannerInputs(String planner) {
        contains(planner, "`SOURCE_TO_TARGET` mode, both accepted `source-analysis.json` from agent 00 and accepted `target-analysis.json` from agent 01 are required",
                "Do not inspect either repository to fill analysis gaps",
                "missing source analysis never permits downgrading to `TARGET_ONLY`",
                "an artifact's `status`, filename, or self-asserted acceptance is insufficient",
                "A missing required analysis or acceptance proof blocks before extraction",
                "An operation-level behavior-preservation request plus adequate accepted source evidence is sufficient for extraction",
                "source and target namespaces are never interchangeable");
        var output = schema(planner, "planningVersion");
        values(output.get("migrationMode"), "SOURCE_TO_TARGET", "TARGET_ONLY");
        check(output.containsKey("sourceProject"), "explicit source analysis binding");
        fields(schema(planner, "resolvedEntryPointId"), "name", "root", "analysisVersion", "analysisStatus",
                "analysisFingerprint", "requestedOperation", "resolvedEntryPointId");
        check(object(output.get("targetProject")).containsKey("analysisFingerprint"), "accepted target fingerprint binding");
        fields(object(object(output.get("coverage")).get("sourceReferences")), "callerInfoIds", "findingIds", "evidenceIds",
                "uncertaintyIds", "conflictIds", "blockingQuestionIds", "coverageReferences");
        var requirement = schema(planner, "id", "MR-001");
        check(requirement.containsKey("sourceFindingIds") && requirement.containsKey("sourceEvidenceIds"),
                "source observations traced into required behavior");
        values(requirement.get("derivation"), "CALLER_EXPLICIT", "SOURCE_BEHAVIOR_PRESERVATION");
        var callerReference = object(((List<?>) object(requirement.get("source")).get("references")).getFirst());
        values(callerReference.get("artifactRole"), "CALLER_MIGRATION_REQUEST", "CALLER_RESOLUTION");
        check(callerReference.containsKey("resolutionReference") && callerReference.containsKey("sourceArtifactFingerprint"),
                "caller requirement origin is unambiguous");
    }

    private static void configSemantics(String source, String planner) {
        contains(source, "A sparse config that identifies source root, target root, and requested operation is sufficient to begin discovery",
                "Missing optional request fields, response fields, tables, joins, mapper/repository names, or SQL is not by itself a blocker",
                "`requestProp`, `request`, `responseProp`, `response`, `DbTables`",
                "Preserve relevant unknown additional caller fields",
                "do not reject a config merely for an unfamiliar key",
                "An unclassified field remains `UNSPECIFIED` information, not new authority",
                "Caller technical assertions are not repository truth");
        var info = schema(source, "id", "MI-001");
        check("CALLER_PROVIDED".equals(info.get("provenance")), "caller provenance is mandatory");
        values(info.get("artifactRole"), "CALLER_MIGRATION_REQUEST", "CALLER_RESOLUTION");
        check(info.containsKey("resolutionReference") && info.containsKey("sourceArtifactFingerprint"),
                "multiple caller artifacts are disambiguated and bound");
        values(info.get("category"), "SCOPE", "REQUIREMENT", "CONSTRAINT", "TECHNICAL_HINT", "UNSPECIFIED");
        values(info.get("verification"), "CONFIRMED", "CONTRADICTED", "UNVERIFIED", "NOT_APPLICABLE");
        contains(source, "`value` may be any safe JSON value", "`CONFIRMED` requires source evidence",
                "`CONTRADICTED` requires an explicit conflict");
        // Representability checks only: no mock analyzer pretends to resolve either input.
        var sparse = Json.parse("{\"sourceProjectPath\":\"/source\",\"sourceOperationName\":\"readItem\",\"targetProjectPath\":\"/target\"}");
        check(sparse.size() == 3 && !sparse.containsKey("tables") && !sparse.containsKey("requestProp"), "minimal caller example");
        var rich = Json.parse("""
                {"requestProp":{"recordId":{"type":"String","necessary":true}},
                 "response":{"recordId":{"type":"String","required":true,"dbtable":"Record"}},
                 "DbTables":{"Record":{"UniqId":"recordId"}},
                 "relationships":[{"connectionId":"recordId"}],
                 "futureCallerField":{"preserveThis":[true,7,"hint"]}}
                """);
        for (var example : List.of(sparse, rich)) {
            var record = new LinkedHashMap<>(info);
            record.put("value", example);
            check(Json.parse(Json.write(record)).get("value").equals(example), "caller structure roundtrips without narrowing fields");
        }
        contains(planner, "do not block merely because optional technical hints were omitted",
                "unknown additional fields remain caller-provided information with their original provenance");
    }

    private static void unresolvedIssues(String source, String planner) {
        var conflict = schema(source, "id", "C-001");
        fields(conflict, "id", "topic", "description", "variants", "migrationCritical", "downstreamImpact", "resolution",
                "resolutionCallerInfoIds");
        var variant = object(((List<?>) conflict.get("variants")).getFirst());
        fields(variant, "provenance", "statement", "callerInfoIds", "findingIds", "evidenceIds");
        values(variant.get("provenance"), "CALLER_PROVIDED", "SOURCE_OBSERVED");
        var blocker = schema(source, "id", "BQ-001");
        fields(blocker, "id", "code", "question", "whyBlocking", "affectedAreas", "callerInfoIds", "evidenceIds",
                "conflictIds", "uncertaintyIds", "resolutionRoute");
        values(blocker.get("code"), "SOURCE_BOUNDARY_UNAVAILABLE", "MISSING_SCOPE", "OPERATION_NOT_RESOLVED",
                "AMBIGUOUS_OPERATION", "CRITICAL_EVIDENCE_GAP", "CONFIG_SOURCE_CONFLICT", "RUNTIME_CAPABILITY_UNAVAILABLE");
        values(blocker.get("resolutionRoute"), "HOST_INPUT_REGISTRATION", "REFRESH_SOURCE_ANALYSIS");
        contains(source, "Once execution starts, do not ask interactive technical questions",
                "If multiple migration-relevant matches remain ambiguous, emit an `AMBIGUOUS_OPERATION` blocker and `BLOCKED`",
                "Never silently choose, overwrite, or normalize them into agreement",
                "A conflict that could change migration correctness requires `BLOCKED`",
                "non-critical conflict stays explicit for planner consumption");
        contains(planner, "ask interactive technical questions after execution starts",
                "They never initiate a technical dialogue during this execution",
                "Conflicting caller/source values remain separately attributed");
        for (String id : List.of("RISK-001", "MRI-001", "BI-001")) {
            var issue = schema(planner, "id", id);
            check(issue.containsKey("sourceConflictIds") && issue.containsKey("sourceUncertaintyIds")
                    && issue.containsKey("sourceCallerInfoIds") && issue.containsKey("sourceCoverageReferences"),
                    "source unresolved issues remain traceable in " + id);
        }
    }

    private static void technologySeparation(String source, String planner) {
        contains(source, "00 is SOURCE-ONLY", "Do not inspect target content, choose target technologies",
                "Do not infer a target implementation choice from any source mechanism",
                "Spring Data, JPA", "MyBatis interface/XML", "JDBC, JdbcTemplate, custom DAO",
                "Do not force Spring MVC or HTTP semantics");
        contains(planner, "REUSE_EXISTING", "EXTEND_EXISTING", "CREATE_NEW", "MANUAL_REVIEW_REQUIRED",
                "Source MyBatis does not establish a target MyBatis requirement",
                "Every `REUSE_EXISTING`, `EXTEND_EXISTING`, or `CREATE_NEW` decision must reference at least one applicable target finding");
    }

    private static void secretPolicy(String source) {
        contains(source, "`source-analysis.json` must never intentionally export secret values",
                "Do not emit actual passwords, API keys, tokens, credentials, private keys, database passwords, connection secrets",
                "This rule applies even when a secret appears in caller config, SQL, a URL, source code",
                "Do not reproduce a value to prove it was redacted",
                "block rather than exporting the secret", "This role adds no replacement secret scanner");
        Evidence.rejectObviousSecrets(source);
    }

    private static void sharedContract(String contract) {
        var bundle = schema(contract, "bundleKind", "RUN_AUTHORITY_BUNDLE_V1");
        fields(bundle, "bundleVersion", "bundleKind", "migrationMode", "callerMigrationRequestFingerprint",
                "callerResolutions", "agent00SpecificationFingerprint", "sourceAnalysisFingerprint",
                "agent01SpecificationFingerprint", "targetAnalysisFingerprint", "agent02SpecificationFingerprint",
                "migrationPlanFingerprint", "orchestrationContractFingerprint", "masterSpecificationFingerprint",
                "capabilityRegistry", "runtimeCapabilities");
        contains(contract, "- `AGENT_00_SPECIFICATION`; - `SOURCE_ANALYSIS`; - `AGENT_01_SPECIFICATION`; - `TARGET_ANALYSIS`;",
                "They are `null` only in `TARGET_ONLY`",
                "complete MASTER/00–07 coverage means all nine roles",
                "this target profile additionally excludes source-repository control",
                "metadata and bounded locators without source content or tools");
        var sourceGate = schema(contract, "capabilityId", "SOURCE_READ_ONLY_CONTROL");
        fields(sourceGate, "declarationVersion", "capabilityId", "provider", "sourceScope", "targetScope",
                "trustedInstructionRoots", "sessionReference", "sourceContentRoles", "profiles");
        fields(object(sourceGate.get("sourceScope")), "declaredRoot", "resolvedRoot", "rootFilesystemIdentity", "accessMode");
        check("READ_ONLY".equals(object(sourceGate.get("sourceScope")).get("accessMode")), "source authority is fixed READ_ONLY");
        check(sourceGate.get("sourceContentRoles").equals(List.of("00-source-analysis")), "only 00 may receive source content");
        var targetGate = schema(contract, "capabilityId", "TARGET_INSTRUCTION_DISCOVERY_CONTROL");
        check(sourceGate.get("targetScope").equals(targetGate.get("targetScope")), "target scope remains the separate existing shape");
        fields(object(schema(contract, "checkKind", "DISCOVERY_CONTROL_CHECK_V1").get("checks")),
                "sourceEligible", "targetMatches", "profilesCovered", "modeSupported", "configurationEffective",
                "invocationCorrelated", "continuityCurrent");
        fields(object(schema(contract, "checkKind", "SOURCE_ACCESS_CHECK_V1").get("checks")),
                "providerEligible", "rootsSeparated", "sourceReadOnly", "sourceDiscoveryExcluded",
                "invocationCorrelated", "continuityCurrent");
        check(!contract.contains("MASTER/01–07") && !contract.contains("all eight roles"), "no stale profile cardinality");
    }

    private static void trustedChain(Path root, Path temp) throws Exception {
        Path target = Files.createDirectory(temp.resolve("contract-target"));
        TrustedInputs trusted = TrustedInputs.freeze(root, target);
        var registry = trusted.registry();
        check(registry.size() == 10, "contract plus MASTER and 00–07: ten documents");
        List<String> paths = new ArrayList<>(List.of("agents/contracts/orchestration-contract.md"));
        paths.addAll(Workflow.MIGRATION.roles().stream().map(role -> role.location).toList());
        for (int i = 0; i < paths.size(); i++) {
            Path file = root.resolve(paths.get(i));
            String role = i == 0 ? "ORCHESTRATION_CONTRACT" : Workflow.MIGRATION.roles().get(i - 1).artifactRole;
            check(registry.get(i).get("resolvedLocation").equals(file.toString()), "explicit registry location");
            check(registry.get(i).get("artifactFingerprint").equals(Json.fingerprint(role, Files.readAllBytes(file))),
                    "fingerprint covers exact current trusted bytes for " + paths.get(i));
        }
        var bindings = trusted.roleBindings();
        check(bindings.size() == 9, "complete nine-role profile coverage");
        check(bindings.stream().map(binding -> binding.get("role")).toList().equals(List.of(
                "00-source-analysis", "01-target-analysis", "02-migration-planning",
                "03-domain-contract-implementation", "04-persistence-mapping-implementation",
                "05-service-api-implementation", "06-test-implementation", "07-validation", "MASTER")),
                "canonical profile order differs deliberately from execution registry order");
        trusted.verify(target);
    }

    private static final class OfflineClient implements ResponsesClient {
        private int dispatches;
        private final HttpResponsesClient scanner = new HttpResponsesClient("contract-fixture-credential-92741",
                request -> { throw new AssertionError("OFFLINE_TRANSPORT_REQUIRED"); });

        private AssertionError unexpectedDispatch() {
            dispatches++;
            return new AssertionError("OFFLINE_TRANSPORT_REQUIRED");
        }

        @Override public String create(String body) { throw unexpectedDispatch(); }
        @Override public String retrieve(String responseId) { throw unexpectedDispatch(); }
        @Override public String inputItems(String responseId, String after) { throw unexpectedDispatch(); }
        @Override public Map<String, Object> configuration() { return scanner.configuration(); }
        @Override public void rejectCredentialMaterial(String text) { scanner.rejectCredentialMaterial(text); }
    }

    private static void masterSecretInspection(String master) {
        var client = new OfflineClient();
        Evidence.rejectObviousSecrets(master);
        client.rejectCredentialMaterial(master);
        new Evidence(client::rejectCredentialMaterial).safe(master);
        check(client.dispatches == 0, "full MASTER inspection is strictly local");
    }

    private static void trustedChainFreeze(Path root, Path temp) throws Exception {
        Path target = Files.createDirectory(temp.resolve("contract-freeze-target"));
        TrustedInputs expected = TrustedInputs.freeze(root, target);
        var client = new OfflineClient();
        var harness = new ControlledHarness(root, target,
                new ControlledHarness.Config("offline-contract-model-v1", 128), client);
        harness.freezeTrustedInputs();
        var observation = harness.inspect();
        check("TRUSTED_INPUTS_FROZEN".equals(observation.get("state")), "actual instruction chain freezes successfully");
        check("NOT_EVALUATED".equals(observation.get("protocolAcceptance")), "freeze provides no protocol acceptance");
        check("CLOSED".equals(observation.get("targetAccessPhase")), "freeze grants no target access");
        check(observation.get("targetMetadata") == null, "target metadata is not registered");
        check("NOT_DESIGNATED".equals(observation.get("sourceAccessState")), "freeze grants no source capability");
        check(observation.get("sourceMetadata") == null, "source metadata is not registered");
        check(observation.get("responseId") == null && observation.get("activeRequestBody") == null,
                "no request or response is established");
        check(List.of().equals(observation.get("responseLineage"))
                && List.of().equals(observation.get("providerItemBindings")), "no response lineage or item bindings exist");
        check(Boolean.FALSE.equals(observation.get("requestInFlight"))
                && Boolean.FALSE.equals(observation.get("readbackCaptured")), "no request or readback occurs");
        check(expected.registry().equals(observation.get("trustedSourceRegistry")), "exact ten-document registry retained");
        check(expected.registryIdentity().equals(observation.get("trustedRegistryIdentity")), "exact registry identity retained");
        check(expected.roleBindings().equals(observation.get("roleProfileBindings")), "complete nine-role bindings retained");
        check(expected.assemblyIdentity().equals(observation.get("instructionAssemblyIdentity")), "exact assembly identity retained");

        var evidence = Json.parse(harness.evidenceJson());
        check("NOT_EVALUATED".equals(evidence.get("protocolAcceptance")), "local evidence is not discovery acceptance");
        var events = ((List<?>) evidence.get("events")).stream().map(SourceContractTest::object).toList();
        check(events.stream().map(event -> event.get("kind")).toList().equals(
                List.of("INITIALIZED", "TRUSTED_INPUTS_FROZEN")), "no request, response, item or discovery events retained");
        var retained = object(events.getLast().get("material"));
        check(expected.instructions().equals(retained.get("exactInstructionPayload")), "full exact instruction payload retained");
        check(expected.registry().equals(retained.get("registry")), "full exact registry retained in evidence");
        check(expected.roleBindings().equals(retained.get("roleBindings")), "full role bindings retained in evidence");
        check(expected.assemblyIdentity().equals(retained.get("assemblyIdentity")), "full assembly identity retained in evidence");
        check(client.dispatches == 0, "freeze and local inspection never dispatch transport");
    }

    private static void specialistBindings(Path root) throws Exception {
        for (TrustedInputs.Role role : List.of(TrustedInputs.Role.DOMAIN, TrustedInputs.Role.PERSISTENCE,
                TrustedInputs.Role.SERVICE, TrustedInputs.Role.TESTS, TrustedInputs.Role.VALIDATION)) {
            contains(read(root, role.location), "00/01/02", "source-analysis");
        }
    }
}
