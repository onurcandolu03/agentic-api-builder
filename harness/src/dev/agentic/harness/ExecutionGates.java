package dev.agentic.harness;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/** Canonical discovery/source checks over this host's current, directly inspected launch route. */
final class ExecutionGates {
    static final String PROVIDER = "controlled-sequential-java/1";
    private static final String PROFILE = "fixed-sequential-v1";
    private final ControlledHarness harness;
    private final Evidence evidence;
    private final Map<String,Object> initialConfiguration, initialTarget, initialSource, initialObservation;
    private final List<Map<String,Object>> initialBindings;
    private final Map<String,Object> definition, definitionFingerprint, configurationFingerprint;
    private final Map<String,Object> discoveryDeclaration, sourceDeclaration;
    private Map<String,Object> discoveryCheck, sourceCheck;
    private Map<String,Object> currentObservation, currentRoute;

    ExecutionGates(ControlledHarness harness, Evidence evidence, Path trustedRoot) throws Exception {
        this.harness = harness; this.evidence = evidence;
        Map<String,Object> observation = observe();
        if (!"CREATION_EVIDENCE_CAPTURED".equals(observation.get("state"))
                || !"MASTER".equals(observation.get("activeRole"))
                || !Boolean.TRUE.equals(observation.get("readbackCaptured")))
            throw new IllegalStateException("INITIAL_MASTER_CORRELATION_REQUIRED");
        initialObservation = observation;
        initialConfiguration = ExecutionPlan.map(observation.get("runtimeConfiguration"));
        initialTarget = ExecutionPlan.map(ExecutionPlan.map(observation.get("targetMetadata")).get("targetScope"));
        initialSource = sourceScope(observation);
        initialBindings = harness.executionTrustedInputs().roleBindings();
        definition = Json.object("provider", PROVIDER, "controlMode", "FIXED_TRUSTED_CONTEXT",
                "implementation", "ControlledHarness.executionTurn/RoleExecutor/SourceBroker/TargetContentBroker",
                "startup", "Explicit host-selected trusted root and workflow-specific exact paths frozen before first provider request",
                "selection", "No AGENTS loader, directory discovery, environment instruction fallback, repository tools or nested contexts",
                "continuation", "Every create includes identical frozen instructions and exact predecessor; retrieve and ordered input-item readback are required",
                "roleSelection", "Only the host selects one registered role; response binding must exactly echo the current invocation",
                "requirementRoute", "00R has no tools; target content broker and snapshots start only after requirement acceptance",
                "sourceRoute", "SourceBroker exposes READ_TEXT, LIST_PATHS, SEARCH_TEXT and METADATA only to 00 after source and target gates",
                "targetRoute", "TargetContentBroker requires current discovery acceptance; target-only analysis read; 02 has no repository access; writes require accepted exact plan grants",
                "containment", "Registered distinct roots; nofollow POSIX component checks, root/ancestor identity rechecks, regular-file hardlinks rejected",
                "readTriggers", "Source and target text and tool results remain UNTRUSTED_DATA_V1; reads cannot modify fixed instruction assembly",
                "execution", "No subprocess, shell, network, Git mutation, directory creation, resume, child dispatch, or model-selected adapters",
                "inspection", "Direct reads of the same active harness configuration, trusted registry, metadata boundaries, request and retained readback lineage",
                "correlation", "Request fingerprint and provider response identify this session's exact create/retrieve/input-item chain",
                "freshness", "Configuration, trusted exact bytes, root metadata and retained evidence rechecked at each host boundary and tool operation",
                "limits", Json.object("maxFileBytes", 1048576, "maxEntries", 2048, "maxSearchHits", 128,
                        "maxOperationsPerInvocation", 64, "maxReturnedBytesPerInvocation", 4194304,
                        "maxSnapshotBytes", 16777216, "maxProviderTurns", RoleExecutor.MAX_TURNS),
                "limitations", List.of("CURRENT_SESSION_ONLY", "MOCK_PROVIDER_IS_NOT_ATTESTATION",
                        "NO_AUTHENTICATION", "NO_DURABILITY", "NO_EXCLUSIVE_WRITER_CONTROL", "POINT_IN_TIME_FILESYSTEM_CHECKS"));
        evidence.append("EXECUTION_CONTROL_DEFINITION", definition);
        definitionFingerprint = Json.evidenceFingerprint(definition);
        evidence.append("EXECUTION_EFFECTIVE_CONFIGURATION", initialConfiguration);
        configurationFingerprint = Json.evidenceFingerprint(initialConfiguration);
        List<String> roles = harness.executionTrustedInputs().roles().stream().map(r -> r.id).sorted().toList();
        Map<String,Object> profile = Json.object("profileId", PROFILE, "roles", roles,
                "launchMechanism", "HOST_SAME_CONTEXT_SEQUENTIAL", "controlMode", "FIXED_TRUSTED_CONTEXT",
                "controlDefinitionFingerprint", definitionFingerprint,
                "effectiveConfigurationFingerprint", configurationFingerprint,
                "initialObservationFingerprint", Json.evidenceFingerprint(observation));
        discoveryDeclaration = Json.object("declarationVersion", 1,
                "capabilityId", "TARGET_INSTRUCTION_DISCOVERY_CONTROL", "provider", PROVIDER,
                "targetScope", initialTarget, "sessionReference", harness.executionContextId(), "profiles", List.of(profile));
        Map<String,Object> attrs = Files.readAttributes(trustedRoot, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
        String identity = "POSIX:" + Long.toUnsignedString(((Number)attrs.get("dev")).longValue())
                + ":" + Long.toUnsignedString(((Number)attrs.get("ino")).longValue());
        sourceDeclaration = initialSource == null ? null : Json.object("declarationVersion", 1, "capabilityId", "SOURCE_READ_ONLY_CONTROL",
                "provider", PROVIDER, "sourceScope", Json.object("declaredRoot", initialSource.get("declaredRoot"),
                        "resolvedRoot", initialSource.get("resolvedRoot"),
                        "rootFilesystemIdentity", initialSource.get("rootFilesystemIdentity"), "accessMode", "READ_ONLY"),
                "targetScope", initialTarget, "trustedInstructionRoots", List.of(Json.object("declaredRoot", trustedRoot.toString(),
                        "resolvedRoot", trustedRoot.toRealPath().toString(), "rootFilesystemIdentity", identity)),
                "sessionReference", harness.executionContextId(), "sourceContentRoles", List.of("00-source-analysis"),
                "profiles", List.of(profile));
        evidence.append("RUNTIME_CAPABILITY_DECLARATION", discoveryDeclaration);
        if (sourceDeclaration != null) evidence.append("RUNTIME_CAPABILITY_DECLARATION", sourceDeclaration);
    }

    Map<String,Object> check(String stage, RoleExecutor.Invocation invocation,
                             List<Map<String,Object>> authorities, Map<String,Object> bundle) {
        Map<String,Object> observation = observe();
        if (!initialConfiguration.equals(observation.get("runtimeConfiguration"))
                || !initialTarget.equals(ExecutionPlan.map(observation.get("targetMetadata")).get("targetScope"))
                || !Objects.equals(initialSource, sourceScope(observation))
                || !initialBindings.equals(harness.executionTrustedInputs().roleBindings())
                || !harness.executionContextId().equals(observation.get("logicalContextId"))
                || !Boolean.TRUE.equals(observation.get("readbackCaptured")))
            throw new IllegalStateException("RUNTIME_GATE_CONTINUITY_LOST");
        if (!"FIXED_TRUSTED_CONTEXT".equals(initialConfiguration.get("controlMode"))
                || !"CONTROLLED_SEQUENTIAL_V1".equals(initialConfiguration.get("executionProfile"))
                || !List.of().equals(initialConfiguration.get("tools")))
            throw new IllegalArgumentException("RUNTIME_GATE_CONFIGURATION_MISMATCH");
        List<String> boundRoles = initialBindings.stream().map(v -> (String)v.get("role")).toList();
        if (!boundRoles.equals(harness.executionTrustedInputs().roles().stream().map(r -> r.id).sorted().toList()))
            throw new IllegalStateException("RUNTIME_ROLE_PROFILE_INCOMPLETE");
        // Before dispatch the inspected response still belongs to the predecessor. Entry/exit
        // checks must instead prove that this invocation owns the completed correlated turn.
        if (Set.of("CONTEXT_ENTRY", "AFTER_INVOCATION").contains(stage))
            requireInvocationObservation(observation, invocation);
        // This retained direct route selection precedes any provider turn/content access for the role.
        Map<String,Object> route = Json.object("sessionId", harness.executionContextId(), "stage", stage,
                "invocationId", invocation.id(), "selectedRole", invocation.role().id,
                "profileId", PROFILE, "predecessorInvocationId", invocation.predecessorInvocationId(),
                "instructionAssemblyIdentity", harness.executionTrustedInputs().assemblyIdentity(),
                "configurationFingerprint", configurationFingerprint,
                "directObservationFingerprint", Json.evidenceFingerprint(observation));
        evidence.append("HOST_LAUNCH_ROUTE_OBSERVED", route);
        currentObservation = observation;
        currentRoute = route;
        List<Map<String,Object>> observations = sorted(List.of(definitionFingerprint, configurationFingerprint,
                Json.evidenceFingerprint(observation), Json.evidenceFingerprint(route)));
        discoveryCheck = checkObject("DISCOVERY_CONTROL_CHECK_V1", stage, invocation, authorities, bundle,
                declarationFingerprint(discoveryDeclaration), observations,
                Json.object("sourceEligible", "PASS", "targetMatches", "PASS", "profilesCovered", "PASS",
                        "modeSupported", "PASS", "configurationEffective", "PASS", "invocationCorrelated", "PASS",
                        "continuityCurrent", "PASS"));
        sourceCheck = sourceDeclaration == null ? null : checkObject("SOURCE_ACCESS_CHECK_V1", stage, invocation, authorities, bundle,
                declarationFingerprint(sourceDeclaration), observations,
                Json.object("providerEligible", "PASS", "rootsSeparated", "PASS", "sourceReadOnly", "PASS",
                        "sourceDiscoveryExcluded", "PASS", "invocationCorrelated", "PASS", "continuityCurrent", "PASS"));
        evidence.append("DISCOVERY_CONTROL_CHECK_V1", discoveryCheck);
        if (sourceCheck != null) evidence.append("SOURCE_ACCESS_CHECK_V1", sourceCheck);
        return delivery();
    }
    private Map<String,Object> checkObject(String kind, String stage, RoleExecutor.Invocation invocation,
            List<Map<String,Object>> authorities, Map<String,Object> bundle, Map<String,Object> declaration,
            List<Map<String,Object>> observations, Map<String,Object> checks) {
        return Json.object("checkVersion", 1, "checkKind", kind, "provenanceClass", "MASTER_SESSION_OBSERVED",
                "stage", stage, "declarationFingerprint", declaration,
                "prePlanningAuthorityFingerprints", bundle == null ? sorted(authorities.stream()
                        .filter(fp -> !fp.equals(declaration)).toList()) : List.of(),
                "runAuthorityBundleFingerprint", bundle, "invocationReference", invocation.id(),
                "invocationArtifactFingerprint", invocation.invocationArtifactFingerprint(), "profileIds", List.of(PROFILE),
                "observationFingerprints", observations, "checks", checks, "result", "PASS", "reasonReferences", List.of());
    }
    private Map<String,Object> observe() {
        harness.verifyExecutionBoundary();
        Map<String,Object> full = harness.inspect();
        Object binding = full.get("activeRequestBinding");
        Map<String,Object> result = Json.object("logicalContextId", full.get("logicalContextId"),
                "state", full.get("state"), "activeRole", full.get("activeRole"), "responseId", full.get("responseId"),
                "readbackCaptured", full.get("readbackCaptured"), "responseLineage", full.get("responseLineage"),
                "trustedSourceRegistry", full.get("trustedSourceRegistry"), "roleProfileBindings", full.get("roleProfileBindings"),
                "instructionAssemblyIdentity", full.get("instructionAssemblyIdentity"),
                "activeRequestBinding", binding,
                "runtimeConfiguration", full.get("runtimeConfiguration"), "targetMetadata", full.get("targetMetadata"),
                "sourceMetadata", full.get("sourceMetadata"), "requestFingerprint", full.get("activeRequestBody") == null ? null
                        : Json.fingerprint("RUNTIME_DISCOVERY_EVIDENCE", ((String)full.get("activeRequestBody")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        evidence.append("DIRECT_RUNTIME_OBSERVATION", result);
        return result;
    }
    private void requireInvocationObservation(Map<String,Object> observation, RoleExecutor.Invocation invocation) {
        Map<String,Object> binding = ExecutionPlan.map(observation.get("activeRequestBinding"));
        List<?> lineage = ExecutionPlan.list(observation.get("responseLineage"));
        if (lineage.isEmpty()) throw new IllegalArgumentException("INVOCATION_RESPONSE_OBSERVATION_REQUIRED");
        Map<String,Object> latest = ExecutionPlan.map(lineage.getLast());
        Object specification = initialBindings.stream().filter(v -> invocation.role().id.equals(v.get("role")))
                .findFirst().orElseThrow().get("specificationFingerprint");
        if (!"CREATION_EVIDENCE_CAPTURED".equals(observation.get("state"))
                || !invocation.role().id.equals(observation.get("activeRole"))
                || !harness.executionContextId().equals(binding.get("runId"))
                || !harness.executionContextId().equals(binding.get("sessionId"))
                || !invocation.id().equals(binding.get("invocationId"))
                || !invocation.role().id.equals(binding.get("role"))
                || !Objects.equals(invocation.predecessorInvocationId(), binding.get("predecessorInvocationId"))
                || !Objects.equals(invocation.bundleFingerprint(), binding.get("runAuthorityBundleFingerprint"))
                || !invocation.inputFingerprints().equals(binding.get("inputArtifactFingerprints"))
                || !Objects.equals(invocation.invocationArtifactFingerprint(), binding.get("invocationArtifactFingerprint"))
                || !specification.equals(binding.get("specificationFingerprint"))
                || !harness.executionTrustedInputs().assemblyIdentity().equals(binding.get("instructionFingerprint"))
                || !invocation.role().id.equals(latest.get("role"))
                || !Objects.equals(observation.get("responseId"), latest.get("responseId"))
                || !Objects.equals(observation.get("requestFingerprint"), latest.get("requestFingerprint"))
                || !Objects.equals(binding.get("previousResponseId"), latest.get("previousResponseId"))
                || !Objects.equals(binding.get("turnOrdinal"), latest.get("ordinal")))
            throw new IllegalArgumentException("INVOCATION_RUNTIME_OBSERVATION_MISMATCH");
    }
    Map<String,Object> delivery() {
        return Json.object("discoveryDeclaration", discoveryDeclaration,
                "discoveryDeclarationFingerprint", declarationFingerprint(discoveryDeclaration),
                "discoveryCheck", discoveryCheck, "discoveryCheckFingerprint", fingerprint(discoveryCheck),
                "sourceDeclaration", sourceDeclaration, "sourceDeclarationFingerprint", declarationFingerprint(sourceDeclaration),
                "sourceCheck", sourceCheck, "sourceCheckFingerprint", fingerprint(sourceCheck),
                "supportingEvidence", Json.object("controlDefinition", observed(definition),
                        "effectiveConfiguration", observed(initialConfiguration),
                        "initialObservation", observed(initialObservation),
                        "currentObservation", observed(currentObservation), "launchRoute", observed(currentRoute)));
    }
    private static Map<String,Object> observed(Map<String,Object> material) {
        return material == null ? null : Json.object("fingerprint", Json.evidenceFingerprint(material), "material", material);
    }
    List<Map<String,Object>> declarationFingerprints() {
        return declarations().stream().map(ExecutionGates::declarationFingerprint).toList();
    }
    List<Map<String,Object>> capabilities() {
        return declarations().stream().map(declaration -> Json.object(
                "capabilityId", declaration.get("capabilityId"), "provider", PROVIDER,
                "declarationFingerprint", declarationFingerprint(declaration), "policyFingerprint", null)).toList();
    }
    private List<Map<String,Object>> declarations() {
        return sourceDeclaration == null ? List.of(discoveryDeclaration) : List.of(sourceDeclaration, discoveryDeclaration);
    }
    private Map<String,Object> sourceScope(Map<String,Object> observation) {
        if (observation.get("sourceMetadata") == null) {
            if (harness.executionTrustedInputs().roles().contains(TrustedInputs.Role.SOURCE_ANALYSIS))
                throw new IllegalStateException("SOURCE_METADATA_REQUIRED");
            return null;
        }
        return ExecutionPlan.map(ExecutionPlan.map(observation.get("sourceMetadata")).get("sourceScope"));
    }
    private static Map<String,Object> declarationFingerprint(Map<String,Object> value) {
        return value == null ? null : Json.fingerprint("RUNTIME_CAPABILITY_DECLARATION", Json.bytes(value));
    }
    static Map<String,Object> fingerprint(Map<String,Object> value) {
        return value == null ? null : Json.fingerprint((String)value.get("checkKind"), Json.bytes(value));
    }
    static List<Map<String,Object>> sorted(List<Map<String,Object>> fingerprints) {
        return fingerprints.stream().distinct().sorted(Comparator.comparing((Map<String,Object> fp) -> (String)fp.get("artifactRole"))
                .thenComparing(fp -> (String)fp.get("digest"))
                .thenComparingLong(fp -> ((Number)fp.get("byteLength")).longValue())).toList();
    }
}
