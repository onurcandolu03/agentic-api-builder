package dev.agentic.harness;

import java.util.*;

/** Structural and referential gates for exact analyst/planner bytes; semantic acceptance remains MASTER's job. */
final class ArtifactContracts {
    static final List<String> SOURCE_AREAS = List.of("SCOPE", "OPERATION_ENTRY_POINT", "CALL_CHAIN", "INPUT_CONTRACT",
            "OUTPUT_CONTRACT", "PERSISTENCE", "EXTERNAL_DEPENDENCIES", "BUSINESS_RULES", "VALIDATION",
            "ERROR_BEHAVIOR", "TRANSACTIONS", "TECHNOLOGY_PROFILE", "CALLER_RECONCILIATION");
    static final List<String> TARGET_AREAS = List.of("PROJECT_STRUCTURE", "ARCHITECTURE", "DATABASE", "DOMAIN_AND_DTO",
            "MAPPING", "SERVICE", "API", "SUPPORTING_CONVENTIONS", "TESTING", "CODING_CONVENTIONS");
    private static final String STATUS = "SUCCESS PARTIAL BLOCKED FAILED";
    private static final String FINDING = "OBSERVED NOT_OBSERVED UNCERTAIN NOT_APPLICABLE";
    private static final int MAX_BYTES = 2_097_152;
    private ArtifactContracts() {}

    static Map<String, Object> validate(TrustedInputs.Role role, byte[] bytes, MigrationInput input,
                                        Map<String, byte[]> accepted) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) fail("ARTIFACT_SIZE");
        String text = MigrationInput.utf8(bytes);
        Evidence.rejectObviousSecrets(text);
        Map<String, Object> value;
        try { value = Json.parse(text); }
        catch (RuntimeException malformed) { throw new IllegalArgumentException("ARTIFACT_JSON_REJECTED"); }
        switch (role) {
            case SOURCE_ANALYSIS -> source(value, input);
            case ANALYSIS -> target(value, input);
            case PLANNING -> plan(value, input, accepted);
            default -> fail("ARTIFACT_ROLE");
        }
        // Canonical reserialization is NOT the retained artifact: caller retains bytes and hashes those exact bytes.
        return MigrationInput.immutable(value);
    }

    private static void source(Map<String, Object> v, MigrationInput input) {
        fields(v, "analysisVersion status project analysisScope callerProvidedMigrationInfo technologyProfile operationEntryPoint callChain inputContract outputContract persistenceBehavior externalDependencies businessRules validationBehavior errorBehavior transactionBehavior findings evidence conflicts uncertainties blockingQuestions analysisCoverage");
        equal(v.get("analysisVersion"), 1, "ANALYSIS_VERSION");
        String status = enumeration(v.get("status"), STATUS);
        Map<String, Object> project = object(v.get("project"));
        fields(project, "name root"); string(project.get("name"));
        equal(project.get("root"), input.sourceRoot().toString(), "SOURCE_IDENTITY");
        Map<String, Object> scope = object(v.get("analysisScope"));
        fields(scope, "migrationMode requestedOperation callerLocatorIds resolvedEntryPointId candidateEntryPoints");
        equal(scope.get("migrationMode"), "SOURCE_TO_TARGET", "MIGRATION_MODE");
        equal(scope.get("requestedOperation"), input.requestedOperation(), "SOURCE_OPERATION");
        Map<String, Map<String, Object>> findings = ids(v.get("findings"), "F");
        Map<String, Map<String, Object>> evidence = ids(v.get("evidence"), "E");
        Map<String, Map<String, Object>> caller = ids(v.get("callerProvidedMigrationInfo"), "MI");
        Map<String, Map<String, Object>> conflicts = ids(v.get("conflicts"), "C");
        Map<String, Map<String, Object>> uncertainties = ids(v.get("uncertainties"), "U");
        Map<String, Map<String, Object>> blockers = ids(v.get("blockingQuestions"), "BQ");
        findings.values().forEach(f -> finding(f, evidence, true));
        evidence.values().forEach(e -> evidence(e, findings, true));
        for (var record : caller.values()) {
            fields(record, "id provenance artifactRole resolutionReference sourceArtifactFingerprint reference category value redacted verification sourceFindingIds sourceEvidenceIds conflictIds");
            equal(record.get("provenance"), "CALLER_PROVIDED", "CALLER_PROVENANCE");
            Object callerValue = callerReference(record, input);
            enumeration(record.get("category"), "SCOPE REQUIREMENT CONSTRAINT TECHNICAL_HINT UNSPECIFIED");
            bool(record.get("redacted"));
            if (Boolean.FALSE.equals(record.get("redacted"))) equal(record.get("value"), callerValue, "CALLER_VALUE_SUBSTITUTION");
            String verification = enumeration(record.get("verification"), "CONFIRMED CONTRADICTED UNVERIFIED NOT_APPLICABLE");
            refs(record.get("sourceFindingIds"), findings);
            refs(record.get("sourceEvidenceIds"), evidence);
            refs(record.get("conflictIds"), conflicts);
            if (verification.equals("CONFIRMED") && list(record.get("sourceEvidenceIds")).isEmpty()) fail("CALLER_UNEVIDENCED");
            if (verification.equals("CONTRADICTED") && list(record.get("conflictIds")).isEmpty()) fail("CALLER_CONFLICT_MISSING");
        }
        refs(scope.get("callerLocatorIds"), caller);
        Map<String, Map<String, Object>> candidates = ids(scope.get("candidateEntryPoints"), "EP");
        for (var candidate : candidates.values()) {
            fields(candidate, "id path type symbol kind findingStatus findingIds evidenceIds disposition reason");
            entry(candidate, findings, evidence);
            enumeration(candidate.get("disposition"), "RESOLVED ELIMINATED UNRESOLVED");
            nonempty(candidate.get("reason"));
        }
        boolean usable = usable(status);
        Object resolved = scope.get("resolvedEntryPointId");
        if (resolved == null) {
            if (usable || v.get("operationEntryPoint") != null) fail("SOURCE_ENTRY_UNRESOLVED");
        } else {
            String id = nonempty(resolved);
            Map<String, Object> entry = object(v.get("operationEntryPoint"));
            fields(entry, "id path type symbol kind findingStatus findingIds evidenceIds");
            entry(entry, findings, evidence);
            equal(entry.get("id"), id, "ENTRY_ID");
            equal(entry.get("findingStatus"), "OBSERVED", "ENTRY_NOT_OBSERVED");
            Map<String, Object> candidate = candidates.get(id);
            if (candidate == null) fail("ENTRY_CANDIDATE_MISSING");
            equal(candidate.get("disposition"), "RESOLVED", "ENTRY_CANDIDATE_UNRESOLVED");
            entry.forEach((key, value) -> equal(candidate.get(key), value, "ENTRY_COPY_MISMATCH"));
            if (candidates.values().stream().filter(c -> "RESOLVED".equals(c.get("disposition"))).count() != 1)
                fail("ENTRY_AMBIGUOUS");
        }
        var chain = ids(v.get("callChain"), "CC");
        Map<String, Map<String, Object>> reachable = new LinkedHashMap<>();
        if (resolved != null) reachable.put((String) resolved, object(v.get("operationEntryPoint")));
        for (var hop : chain.values()) {
            fields(hop, "id path type symbol calledFromIds relationship findingStatus findingIds evidenceIds");
            path(hop.get("path")); string(hop.get("type")); string(hop.get("symbol")); nonempty(hop.get("relationship"));
            enumeration(hop.get("findingStatus"), "OBSERVED UNCERTAIN");
            refs(hop.get("findingIds"), findings); refs(hop.get("evidenceIds"), evidence);
            refs(hop.get("calledFromIds"), reachable);
            if (list(hop.get("calledFromIds")).isEmpty()) fail("CALL_CHAIN_UNREACHABLE");
            reachable.put((String) hop.get("id"), hop);
        }
        for (String key : words("technologyProfile externalDependencies businessRules validationBehavior errorBehavior transactionBehavior")) refs(v.get(key), findings);
        arrayObjectRefs(v.get("inputContract"), "parameters requestTypes fields findingIds", findings);
        arrayObjectRefs(v.get("outputContract"), "resultTypes responseTypes fields findingIds", findings);
        arrayObjectRefs(v.get("persistenceBehavior"), "mechanisms queries tables columns joins filters parameters ordering grouping pagination resultMappings findingIds", findings);
        for (var conflict : conflicts.values()) {
            fields(conflict, "id topic description variants migrationCritical downstreamImpact resolution resolutionCallerInfoIds");
            nonempty(conflict.get("topic")); nonempty(conflict.get("description")); string(conflict.get("downstreamImpact"));
            bool(conflict.get("migrationCritical"));
            String resolution = enumeration(conflict.get("resolution"), "UNRESOLVED PRESERVE_AS_SCOPE_SPECIFIC EXPLICIT_CALLER_REQUIREMENT");
            refs(conflict.get("resolutionCallerInfoIds"), caller);
            if (list(conflict.get("variants")).size() < 2) fail("CONFLICT_VARIANTS");
            for (Object item : list(conflict.get("variants"))) {
                var variant = object(item); fields(variant, "provenance statement callerInfoIds findingIds evidenceIds");
                enumeration(variant.get("provenance"), "CALLER_PROVIDED SOURCE_OBSERVED"); nonempty(variant.get("statement"));
                refs(variant.get("callerInfoIds"), caller); refs(variant.get("findingIds"), findings); refs(variant.get("evidenceIds"), evidence);
            }
            if (usable && Boolean.TRUE.equals(conflict.get("migrationCritical")) && resolution.equals("UNRESOLVED")) fail("CRITICAL_CONFLICT");
        }
        for (var uncertainty : uncertainties.values()) uncertainty(uncertainty, evidence, caller, true);
        for (var blocker : blockers.values()) {
            fields(blocker, "id code question whyBlocking affectedAreas callerInfoIds evidenceIds conflictIds uncertaintyIds resolutionRoute");
            enumeration(blocker.get("code"), "SOURCE_BOUNDARY_UNAVAILABLE MISSING_SCOPE OPERATION_NOT_RESOLVED AMBIGUOUS_OPERATION CRITICAL_EVIDENCE_GAP CONFIG_SOURCE_CONFLICT RUNTIME_CAPABILITY_UNAVAILABLE");
            nonempty(blocker.get("question")); nonempty(blocker.get("whyBlocking")); strings(blocker.get("affectedAreas"));
            refs(blocker.get("callerInfoIds"), caller); refs(blocker.get("evidenceIds"), evidence);
            refs(blocker.get("conflictIds"), conflicts); refs(blocker.get("uncertaintyIds"), uncertainties);
            enumeration(blocker.get("resolutionRoute"), "HOST_INPUT_REGISTRATION REFRESH_SOURCE_ANALYSIS");
        }
        coverage(v, SOURCE_AREAS, status, true);
        status(v, status, true);
        if (usable && (findings.isEmpty() || evidence.isEmpty() || string(project.get("name")).isBlank())) fail("SOURCE_EVIDENCE_MISSING");
    }

    private static void target(Map<String, Object> v, MigrationInput input) {
        fields(v, "analysisVersion status project modules architecture database domainAndDto mapping service api supportingConventions testing codingConventions evidence uncertainties conflicts blockingQuestions analysisCoverage");
        equal(v.get("analysisVersion"), 1, "ANALYSIS_VERSION");
        String status = enumeration(v.get("status"), STATUS);
        var project = object(v.get("project"));
        fields(project, "name root buildSystem javaVersion springBootVersion frameworks importantDirectories");
        string(project.get("name")); equal(project.get("root"), input.targetRoot().toString(), "TARGET_IDENTITY");
        for (String key : words("buildSystem springBootVersion")) {
            var property = object(project.get(key)); fields(property, "findingStatus value evidenceIds");
            enumeration(property.get("findingStatus"), FINDING); string(property.get("value")); strings(property.get("evidenceIds"));
        }
        var java = object(project.get("javaVersion")); fields(java, "findingStatus declared effective evidenceIds");
        enumeration(java.get("findingStatus"), FINDING); strings(java.get("declared")); string(java.get("effective")); strings(java.get("evidenceIds"));
        list(project.get("frameworks")); list(project.get("importantDirectories"));
        for (Object item : list(v.get("modules"))) {
            var module = object(item); fields(module, "name path type sourceRoots testRoots resourceRoots packages dependenciesOnModules responsibilities evidenceIds");
            nonempty(module.get("name")); directory(module.get("path")); string(module.get("type"));
            for (String key : words("sourceRoots testRoots resourceRoots")) paths(module.get(key));
            for (String key : words("packages dependenciesOnModules responsibilities evidenceIds")) strings(module.get(key));
        }
        var architecture = arrayObject(v.get("architecture"), "summary style layers dependencyFlows interfaceImplementationPatterns domainBoundaries representativeFlows findings", Set.of("summary"));
        string(architecture.get("summary"));
        var database = object(v.get("database")); fields(database, "technologies repositoryStyles operations transactions pagination filtering schemaMigrations findings");
        for (String key : words("technologies repositoryStyles transactions pagination filtering schemaMigrations findings")) list(database.get(key));
        arrayObject(database.get("operations"), "insert update delete select join customQuery", Set.of());
        arrayObject(v.get("domainAndDto"), "entities domainObjects requestDtos responseDtos internalDtos conversionBoundaries apiExposurePatterns findings", Set.of());
        var mapping = arrayObject(v.get("mapping"), "mapperPresence technologies styles locations usagePatterns representativeExamples findings", Set.of("mapperPresence"));
        enumeration(mapping.get("mapperPresence"), FINDING);
        arrayObject(v.get("service"), "interfacePatterns implementationPatterns dependencyInjection businessLogicPlacement transactionBoundaries validationResponsibilities exceptionBehavior findings", Set.of());
        arrayObject(v.get("api"), "controllerConventions endpointConventions requestPatterns responsePatterns httpHandling validation errorHandling handlerPatterns findings", Set.of());
        arrayObject(v.get("supportingConventions"), "constants enums configuration properties aspects utilities logging findings", Set.of());
        arrayObject(v.get("testing"), "structure frameworks controllerTests serviceTests repositoryTests integrationTests mockingConventions fixturesAndTestData findings", Set.of());
        arrayObject(v.get("codingConventions"), "naming packages classDesign methodDesign nullabilityAndOptional exceptions logging formatting implementationGuide", Set.of());
        Map<String, Map<String, Object>> findings = new LinkedHashMap<>(); collectFindings(v, findings);
        var evidence = ids(v.get("evidence"), "E");
        findings.values().forEach(f -> finding(f, evidence, false));
        evidence.values().forEach(e -> evidence(e, findings, false));
        for (String key : words("buildSystem javaVersion springBootVersion")) refs(object(project.get(key)).get("evidenceIds"), evidence);
        for (var u : ids(v.get("uncertainties"), "U").values()) uncertainty(u, evidence, Map.of(), false);
        for (var c : ids(v.get("conflicts"), "C").values()) {
            fields(c, "id topic description variants likelyExplanation resolution");
            nonempty(c.get("topic")); nonempty(c.get("description")); string(c.get("likelyExplanation"));
            enumeration(c.get("resolution"), "PRESERVE_AS_SCOPE_SPECIFIC REQUIRES_CLARIFICATION UNRESOLVED");
            if (list(c.get("variants")).size() < 2) fail("CONFLICT_VARIANTS");
            for (Object item : list(c.get("variants"))) {
                var variant = object(item); fields(variant, "scope pattern evidenceIds");
                strings(variant.get("scope")); nonempty(variant.get("pattern")); refs(variant.get("evidenceIds"), evidence);
            }
        }
        for (var b : ids(v.get("blockingQuestions"), "BQ").values()) {
            fields(b, "id question whyBlocking affectedAreas evidenceIds requestedFrom");
            nonempty(b.get("question")); nonempty(b.get("whyBlocking")); strings(b.get("affectedAreas"));
            refs(b.get("evidenceIds"), evidence); string(b.get("requestedFrom"));
        }
        coverage(v, TARGET_AREAS, status, false); status(v, status, false);
        if (usable(status) && (findings.isEmpty() || evidence.isEmpty() || string(project.get("name")).isBlank())) fail("TARGET_EVIDENCE_MISSING");
        walkReferences(v, "evidenceIds", evidence);
    }

    private static void plan(Map<String, Object> v, MigrationInput input, Map<String, byte[]> accepted) {
        fields(v, "planningVersion status migrationMode sourceProject targetProject migrationRequest requirements targetMappings componentDecisions implementationOrder validationPlan risks manualReviewItems blockingIssues coverage");
        equal(v.get("planningVersion"), 1, "PLANNING_VERSION");
        String status = enumeration(v.get("status"), STATUS);
        equal(v.get("migrationMode"), "SOURCE_TO_TARGET", "MIGRATION_MODE");
        byte[] sourceBytes = accepted.get("SOURCE_ANALYSIS"), targetBytes = accepted.get("TARGET_ANALYSIS");
        if (sourceBytes == null || targetBytes == null) fail("PLAN_ACCEPTED_ANALYSIS_MISSING");
        var source = Json.parse(MigrationInput.utf8(sourceBytes)); var target = Json.parse(MigrationInput.utf8(targetBytes));
        if (!usable(string(source.get("status"))) || !usable(string(target.get("status")))) fail("PLAN_ANALYSIS_UNUSABLE");
        var sp = object(v.get("sourceProject")); fields(sp, "name root analysisVersion analysisStatus analysisFingerprint requestedOperation resolvedEntryPointId");
        var tp = object(v.get("targetProject")); fields(tp, "name root analysisVersion analysisStatus analysisFingerprint relevantModules relevantAnalysisAreas");
        analysisBinding(sp, source, "SOURCE_ANALYSIS", sourceBytes); analysisBinding(tp, target, "TARGET_ANALYSIS", targetBytes);
        var sourceScope = object(source.get("analysisScope"));
        equal(sp.get("requestedOperation"), input.requestedOperation(), "PLAN_OPERATION");
        equal(sp.get("resolvedEntryPointId"), sourceScope.get("resolvedEntryPointId"), "PLAN_SOURCE_ENTRY");
        list(tp.get("relevantModules")); list(tp.get("relevantAnalysisAreas"));
        var request = object(v.get("migrationRequest"));
        fields(request, "summary sourceInformationProvided sourceInformationReferences sourceCallerInfoIds scopeConstraints normalizationNotes");
        nonempty(request.get("summary")); bool(request.get("sourceInformationProvided")); strings(request.get("normalizationNotes"));
        for (Object ref : list(request.get("sourceInformationReferences"))) callerReference(object(ref), input);
        for (Object item : list(request.get("scopeConstraints"))) {
            var constraint = object(item); fields(constraint, "id description sourceReferences");
            nonempty(constraint.get("id")); nonempty(constraint.get("description"));
            for (Object ref : list(constraint.get("sourceReferences"))) callerReference(object(ref), input);
        }
        for (String key : words("requirements targetMappings componentDecisions implementationOrder risks manualReviewItems blockingIssues")) list(v.get(key));
        var requirements = ids(v.get("requirements"), "MR");
        for (var requirement : requirements.values()) {
            fields(requirement, "id description derivation source sourceCallerInfoIds sourceFindingIds sourceEvidenceIds acceptanceIntent affectedCapabilities");
            nonempty(requirement.get("description")); nonempty(requirement.get("acceptanceIntent")); strings(requirement.get("affectedCapabilities"));
            String derivation = enumeration(requirement.get("derivation"), "CALLER_EXPLICIT SOURCE_BEHAVIOR_PRESERVATION");
            var origin = object(requirement.get("source")); fields(origin, "primaryType references");
            enumeration(origin.get("primaryType"), "USER_REQUEST CALLER_MIGRATION_INFORMATION");
            if (list(origin.get("references")).isEmpty()) fail("REQUIREMENT_CALLER_AUTHORITY_MISSING");
            for (Object ref : list(origin.get("references"))) callerReference(object(ref), input);
            if (derivation.equals("SOURCE_BEHAVIOR_PRESERVATION") && (list(requirement.get("sourceFindingIds")).isEmpty()
                    || list(requirement.get("sourceEvidenceIds")).isEmpty())) fail("REQUIREMENT_SOURCE_EVIDENCE_MISSING");
        }
        arrayObject(v.get("validationPlan"), "existingRelevantTests plannedTestChanges validationExpectations coverageGaps", Set.of());
        var coverage = object(v.get("coverage"));
        fields(coverage, "requirements decisions sourceReferences targetReferences expectedTouchedFiles undeterminedTouchedFiles noImplementationChangeRequired planningAreas notes");
        var counts = object(coverage.get("requirements")); fields(counts, "total mapped withComponentDecision withValidationExpectation");
        for (Object count : counts.values()) count(count, false);
        equal(counts.get("total"), requirements.size(), "PLAN_REQUIREMENT_COUNT");
        var decisionCounts = object(coverage.get("decisions")); fields(decisionCounts, "total reuseExisting extendExisting createNew manualReviewRequired");
        for (Object count : decisionCounts.values()) count(count, false);
        equal(decisionCounts.get("total"), list(v.get("componentDecisions")).size(), "PLAN_DECISION_COUNT");
        paths(coverage.get("expectedTouchedFiles")); list(coverage.get("undeterminedTouchedFiles"));
        bool(coverage.get("noImplementationChangeRequired")); list(coverage.get("planningAreas")); strings(coverage.get("notes"));
        var sf = ids(source.get("findings"), "F"); var se = ids(source.get("evidence"), "E");
        Map<String, Map<String, Object>> tf = new LinkedHashMap<>(); collectFindings(target, tf);
        var te = ids(target.get("evidence"), "E");
        for (String key : words("sourceFindingIds sourceEvidenceIds sourceCallerInfoIds sourceUncertaintyIds sourceConflictIds sourceBlockingQuestionIds targetFindingIds targetEvidenceIds targetUncertaintyIds targetConflictIds targetBlockingQuestionIds")) {
            Map<String, Map<String, Object>> namespace = switch (key) {
                case "sourceFindingIds" -> sf; case "sourceEvidenceIds" -> se;
                case "sourceCallerInfoIds" -> ids(source.get("callerProvidedMigrationInfo"), "MI");
                case "sourceUncertaintyIds" -> ids(source.get("uncertainties"), "U");
                case "sourceConflictIds" -> ids(source.get("conflicts"), "C");
                case "sourceBlockingQuestionIds" -> ids(source.get("blockingQuestions"), "BQ");
                case "targetFindingIds" -> tf; case "targetEvidenceIds" -> te;
                case "targetUncertaintyIds" -> ids(target.get("uncertainties"), "U");
                case "targetConflictIds" -> ids(target.get("conflicts"), "C");
                default -> ids(target.get("blockingQuestions"), "BQ");
            };
            walkReferences(v, key, namespace);
        }
        referenceCoverage(object(coverage.get("sourceReferences")), source, sf, se, true);
        referenceCoverage(object(coverage.get("targetReferences")), target, tf, te, false);
        planDetails(v, requirements, target, tf, te, status);
        if (status.equals("SUCCESS") && (!list(v.get("blockingIssues")).isEmpty() || !list(coverage.get("undeterminedTouchedFiles")).isEmpty())) fail("PLAN_SUCCESS_BLOCKERS");
        if ((status.equals("BLOCKED") || status.equals("FAILED")) && list(v.get("blockingIssues")).isEmpty()) fail("PLAN_FAILURE_REASON_MISSING");
    }

    private static final String PLAN_PHASES = "INPUT_VALIDATION REQUIREMENT_EXTRACTION TARGET_MAPPING PLANNING_DECISIONS EXECUTION_ORDERING VALIDATION_PLANNING OUTPUT_VALIDATION";
    private static final String PLAN_TARGET_REFS = "targetFindingIds targetEvidenceIds targetUncertaintyIds targetConflictIds targetBlockingQuestionIds targetCoverageReferences";
    private static final String PLAN_SOURCE_REFS = "sourceCallerInfoIds sourceFindingIds sourceEvidenceIds sourceUncertaintyIds sourceConflictIds sourceBlockingQuestionIds sourceCoverageReferences";

    /** Plan structure is checked before any candidate retention; execution feasibility is a separate gate. */
    private static void planDetails(Map<String, Object> v, Map<String, Map<String, Object>> requirements,
                                    Map<String, Object> target, Map<String, Map<String, Object>> findings,
                                    Map<String, Map<String, Object>> evidence, String status) {
        var project = object(v.get("targetProject"));
        for (Object item : list(project.get("relevantModules"))) {
            var module = object(item); fields(module, "name path targetEvidenceIds");
            nonempty(module.get("name")); directory(module.get("path"));
            boolean found = list(target.get("modules")).stream().map(ArtifactContracts::object)
                    .anyMatch(m -> Objects.equals(m.get("name"), module.get("name")) && Objects.equals(m.get("path"), module.get("path")));
            if (!found) fail("PLAN_MODULE_IDENTITY");
        }
        Set<String> areas = new HashSet<>();
        for (Object item : list(project.get("relevantAnalysisAreas"))) {
            var area = object(item); fields(area, "area coverageResult migrationRelevance");
            String name = enumeration(area.get("area"), String.join(" ", TARGET_AREAS));
            if (!areas.add(name)) fail("PLAN_DUPLICATE_ANALYSIS_AREA");
            nonempty(area.get("migrationRelevance"));
            Object expected = object(list(object(target.get("analysisCoverage")).get("areas")).get(TARGET_AREAS.indexOf(name))).get("result");
            equal(area.get("coverageResult"), expected, "PLAN_COVERAGE_SUBSTITUTION");
        }
        var mappings = ids(v.get("targetMappings"), "TM");
        var decisions = ids(v.get("componentDecisions"), "CD");
        var steps = ids(v.get("implementationOrder"), "STEP");
        Set<String> mappedRequirements = new HashSet<>(), decidedRequirements = new HashSet<>();
        Set<String> expectedTouched = new TreeSet<>(), mutationDecisions = new HashSet<>();
        Set<String> expectedPathAliases = new HashSet<>();
        for (var mapping : mappings.values()) {
            fields(mapping, "id requirementId targetScopes targetLayers targetComponents existingPaths " + PLAN_TARGET_REFS + " componentDecisionIds mappingRationale unresolvedIssues");
            String requirement = nonempty(mapping.get("requirementId"));
            if (!requirements.containsKey(requirement)) fail("PLAN_MAPPING_REQUIREMENT");
            mappedRequirements.add(requirement);
            for (String key : words("targetScopes targetLayers targetComponents unresolvedIssues")) strings(mapping.get(key));
            planPaths(mapping.get("existingPaths")); refs(mapping.get("componentDecisionIds"), decisions);
            nonempty(mapping.get("mappingRationale"));
            for (Object id : list(mapping.get("componentDecisionIds")))
                if (!list(decisions.get(id).get("requirementIds")).contains(requirement)) fail("PLAN_MAPPING_DECISION_REQUIREMENT");
            if (status.equals("SUCCESS") && (!list(mapping.get("unresolvedIssues")).isEmpty() || list(mapping.get("componentDecisionIds")).isEmpty())) fail("PLAN_MAPPING_INCOMPLETE");
        }
        int decisionOffset = 0;
        Map<String, Integer> decisionOffsets = new HashMap<>();
        for (var decision : decisions.values()) {
            fields(decision, "id requirementIds targetComponent targetLayer targetScope existingPath expectedLocation expectedLocationKind decision rationale " + PLAN_TARGET_REFS
                    + " implementationConstraints callableContracts declarationContracts dependencies expectedChangeScope confidence");
            String id = nonempty(decision.get("id")); decisionOffsets.put(id, decisionOffset++);
            refs(decision.get("requirementIds"), requirements);
            if (list(decision.get("requirementIds")).isEmpty()) fail("PLAN_DECISION_REQUIREMENTS");
            list(decision.get("requirementIds")).forEach(r -> decidedRequirements.add(string(r)));
            nonempty(decision.get("targetComponent")); nonempty(decision.get("targetLayer")); strings(decision.get("targetScope"));
            if (list(decision.get("targetScope")).isEmpty()) fail("PLAN_DECISION_SCOPE");
            nonempty(decision.get("rationale")); enumeration(decision.get("confidence"), "HIGH MEDIUM LOW");
            String action = enumeration(decision.get("decision"), "REUSE_EXISTING EXTEND_EXISTING CREATE_NEW MANUAL_REVIEW_REQUIRED");
            String locationKind = enumeration(decision.get("expectedLocationKind"), "EXACT_PATH DIRECTORY PACKAGE UNDETERMINED NOT_APPLICABLE");
            if (decision.get("existingPath") != null) planPath(decision.get("existingPath"));
            if (decision.get("expectedLocation") != null) {
                if (locationKind.equals("EXACT_PATH") || locationKind.equals("DIRECTORY")) planPath(decision.get("expectedLocation"));
                else nonempty(decision.get("expectedLocation"));
            }
            refs(decision.get("dependencies"), decisions);
            if (list(decision.get("dependencies")).contains(id)) fail("PLAN_SELF_DEPENDENCY");
            var change = object(decision.get("expectedChangeScope")); fields(change, "changeType responsibilities expectedPaths");
            String changeType = enumeration(change.get("changeType"), "NO_CHANGE MODIFY CREATE REVIEW_ONLY");
            planPaths(change.get("expectedPaths"));
            var decisionPaths = list(change.get("expectedPaths"));
            int implementationDuties = 0;
            for (Object duty : list(change.get("responsibilities"))) {
                var responsibility = object(duty); fields(responsibility, "responsibilityClass description");
                if (enumeration(responsibility.get("responsibilityClass"), "IMPLEMENTATION PRESERVATION").equals("IMPLEMENTATION")) implementationDuties++;
                nonempty(responsibility.get("description"));
            }
            boolean mutable = action.equals("EXTEND_EXISTING") || action.equals("CREATE_NEW");
            if (mutable) {
                if (implementationDuties == 0 || decisionPaths.size() != 1) fail("PLAN_MUTATION_RESPONSIBILITY_OR_PATH");
                mutationDecisions.add(id); String path = string(decisionPaths.getFirst());
                if (expectedTouched.add(path) && !expectedPathAliases.add(path.toLowerCase(Locale.ROOT))) fail("PLAN_PATH_ALIAS");
                if (action.equals("CREATE_NEW")) {
                    if (decision.get("existingPath") != null || !locationKind.equals("EXACT_PATH") || !changeType.equals("CREATE")) fail("PLAN_CREATE_AUTHORITY");
                    equal(decision.get("expectedLocation"), path, "PLAN_CREATE_PATH");
                } else {
                    if (!locationKind.equals("NOT_APPLICABLE") || !changeType.equals("MODIFY")) fail("PLAN_MODIFY_AUTHORITY");
                    equal(decision.get("existingPath"), path, "PLAN_MODIFY_PATH");
                }
            } else if (action.equals("REUSE_EXISTING")) {
                if (decision.get("existingPath") == null || !locationKind.equals("NOT_APPLICABLE") || !changeType.equals("NO_CHANGE")
                        || !decisionPaths.isEmpty() || implementationDuties != 0) fail("PLAN_REUSE_AUTHORITY");
            } else if (!changeType.equals("REVIEW_ONLY") || !decisionPaths.isEmpty() || !"LOW".equals(decision.get("confidence"))) fail("PLAN_REVIEW_AUTHORITY");
            if (!action.equals("MANUAL_REVIEW_REQUIRED")) {
                if (list(decision.get("targetFindingIds")).isEmpty() || list(decision.get("targetEvidenceIds")).isEmpty()) fail("PLAN_DECISION_TARGET_EVIDENCE");
                for (Object findingId : list(decision.get("targetFindingIds")))
                    if (!list(decision.get("targetEvidenceIds")).containsAll(list(findings.get(findingId).get("evidenceIds")))) fail("PLAN_DECISION_EVIDENCE_SUPPORT");
                if (decision.get("existingPath") != null && list(decision.get("targetEvidenceIds")).stream()
                        .noneMatch(e -> Objects.equals(evidence.get(e).get("path"), decision.get("existingPath")))) fail("PLAN_EXISTING_PATH_UNEVIDENCED");
            }
            planContracts(decision, requirements);
            // Constraint applicability needs canonical semantic projection, which this milestone does not implement.
            if (!list(decision.get("implementationConstraints")).isEmpty()) fail("PLAN_CONSTRAINT_PROJECTION_UNAVAILABLE");
        }
        planSteps(steps, decisions, mutationDecisions);
        Set<String> validatedRequirements = planValidation(object(v.get("validationPlan")), requirements, decisions);
        planIssues(v, requirements, decisions, status);
        var coverage = object(v.get("coverage"));
        equal(list(coverage.get("expectedTouchedFiles")), new ArrayList<>(expectedTouched), "PLAN_TOUCHED_PATH_UNION");
        equal(coverage.get("noImplementationChangeRequired"), mutationDecisions.isEmpty() && decisions.values().stream()
                .noneMatch(d -> "MANUAL_REVIEW_REQUIRED".equals(d.get("decision"))), "PLAN_NO_CHANGE_CLAIM");
        var counts = object(coverage.get("requirements"));
        equal(counts.get("mapped"), mappedRequirements.size(), "PLAN_MAPPING_COUNT");
        equal(counts.get("withComponentDecision"), decidedRequirements.size(), "PLAN_DECIDED_COUNT");
        equal(counts.get("withValidationExpectation"), validatedRequirements.size(), "PLAN_VALIDATION_COUNT");
        var decisionCounts = object(coverage.get("decisions"));
        String[] names = {"reuseExisting", "extendExisting", "createNew", "manualReviewRequired"};
        String[] actions = {"REUSE_EXISTING", "EXTEND_EXISTING", "CREATE_NEW", "MANUAL_REVIEW_REQUIRED"};
        for (int i = 0; i < names.length; i++) {
            String action = actions[i];
            equal(decisionCounts.get(names[i]), (int) decisions.values().stream().filter(d -> action.equals(d.get("decision"))).count(), "PLAN_ACTION_COUNT");
        }
        for (Object item : list(coverage.get("undeterminedTouchedFiles"))) {
            var unresolved = object(item); fields(unresolved, "componentDecisionId responsibility reasonUndetermined resolutionNeeded");
            if (!decisions.containsKey(unresolved.get("componentDecisionId"))) fail("PLAN_UNRESOLVED_DECISION");
            for (String key : words("responsibility reasonUndetermined resolutionNeeded")) nonempty(unresolved.get(key));
        }
        var phases = list(coverage.get("planningAreas"));
        if (phases.size() != words(PLAN_PHASES).size()) fail("PLAN_PHASE_COVERAGE");
        for (int i = 0; i < phases.size(); i++) {
            var phase = object(phases.get(i)); fields(phase, "phase result notes");
            equal(phase.get("phase"), words(PLAN_PHASES).get(i), "PLAN_PHASE_ORDER");
            enumeration(phase.get("result"), "COMPLETE PARTIAL BLOCKED FAILED NOT_PERFORMED"); nonempty(phase.get("notes"));
            if (status.equals("SUCCESS") && !"COMPLETE".equals(phase.get("result"))) fail("PLAN_SUCCESS_INCOMPLETE_PHASE");
        }
        if (status.equals("SUCCESS") && (requirements.isEmpty() || !mappedRequirements.equals(requirements.keySet())
                || !decidedRequirements.equals(requirements.keySet()) || !validatedRequirements.equals(requirements.keySet()))) fail("PLAN_REQUIREMENT_COVERAGE");
    }

    private static void planContracts(Map<String, Object> decision, Map<String, Map<String, Object>> requirements) {
        Set<String> signatures = new HashSet<>(), declarations = new HashSet<>();
        for (Object item : list(decision.get("callableContracts"))) {
            var callable = object(item); fields(callable, "name parameters returnType signatureSemantics requirementIds targetFindingIds targetEvidenceIds");
            nonempty(callable.get("name")); nullableNonempty(callable.get("returnType")); strings(callable.get("signatureSemantics"));
            Set<String> parameterNames = new HashSet<>();
            for (Object parameter : list(callable.get("parameters"))) {
                var p = object(parameter); fields(p, "name type");
                if (!parameterNames.add(nonempty(p.get("name")))) fail("PLAN_DUPLICATE_PARAMETER"); nullableNonempty(p.get("type"));
            }
            if (!signatures.add(Json.write(Json.object("name", callable.get("name"), "parameters", callable.get("parameters"))))) fail("PLAN_DUPLICATE_CALLABLE");
            planContractReferences(callable, decision, requirements);
        }
        for (Object item : list(decision.get("declarationContracts"))) {
            var declaration = object(item); fields(declaration, "kind name type value usageSemantics requirementIds targetFindingIds targetEvidenceIds");
            nonempty(declaration.get("kind")); nonempty(declaration.get("name")); nullableNonempty(declaration.get("type"));
            if (declaration.get("value") != null) string(declaration.get("value")); strings(declaration.get("usageSemantics"));
            if (!declarations.add(string(declaration.get("name")))) fail("PLAN_DUPLICATE_DECLARATION");
            planContractReferences(declaration, decision, requirements);
        }
        if (Set.of("REUSE_EXISTING", "MANUAL_REVIEW_REQUIRED").contains(decision.get("decision"))
                && (!list(decision.get("callableContracts")).isEmpty() || !list(decision.get("declarationContracts")).isEmpty())) fail("PLAN_NONMUTATING_CONTRACT");
    }

    private static void planContractReferences(Map<String, Object> contract, Map<String, Object> decision,
                                                Map<String, Map<String, Object>> requirements) {
        refs(contract.get("requirementIds"), requirements);
        if (list(contract.get("requirementIds")).isEmpty()) fail("PLAN_CONTRACT_REQUIREMENTS");
        for (String key : words("requirementIds targetFindingIds targetEvidenceIds"))
            if (!list(decision.get(key)).containsAll(list(contract.get(key)))) fail("PLAN_CONTRACT_AUTHORITY");
    }

    private static void planSteps(Map<String, Map<String, Object>> steps, Map<String, Map<String, Object>> decisions,
                                   Set<String> mutable) {
        Map<String, String> owners = new HashMap<>(); Set<String> paths = new HashSet<>();
        long sequence = 0;
        for (var step : steps.values()) {
            fields(step, "id sequence specialistRole requirementIds componentDecisionIds objective prerequisiteStepIds reusedComponentDecisionIds expectedPaths implementationConstraints completionEvidenceExpected");
            count(step.get("sequence"), false); long next = ((Number) step.get("sequence")).longValue();
            if (next <= sequence) fail("PLAN_STEP_SEQUENCE"); sequence = next;
            String role = enumeration(step.get("specialistRole"), "03-domain-contract-implementation 04-persistence-mapping-implementation 05-service-api-implementation 06-test-implementation");
            refs(step.get("componentDecisionIds"), decisions); refs(step.get("prerequisiteStepIds"), steps); refs(step.get("reusedComponentDecisionIds"), decisions);
            strings(step.get("requirementIds")); planPaths(step.get("expectedPaths")); nonempty(step.get("objective")); strings(step.get("completionEvidenceExpected"));
            if (list(step.get("completionEvidenceExpected")).isEmpty() || list(step.get("componentDecisionIds")).isEmpty()) fail("PLAN_STEP_EMPTY");
            if (!list(step.get("implementationConstraints")).isEmpty()) fail("PLAN_CONSTRAINT_PROJECTION_UNAVAILABLE");
            Set<String> expectedRequirements = new TreeSet<>(), expectedPaths = new TreeSet<>(), predecessors = new HashSet<>(), reused = new HashSet<>();
            for (Object value : list(step.get("componentDecisionIds"))) {
                String id = string(value); var decision = decisions.get(id);
                if (!mutable.contains(id) || owners.containsKey(id)) fail("PLAN_STEP_DECISION_OWNERSHIP");
                planOwnership(role, decision);
                list(decision.get("requirementIds")).forEach(r -> expectedRequirements.add(string(r)));
                list(object(decision.get("expectedChangeScope")).get("expectedPaths")).forEach(p -> expectedPaths.add(string(p)));
                for (Object dependency : list(decision.get("dependencies"))) {
                    if (list(step.get("componentDecisionIds")).contains(dependency)) fail("PLAN_INTERNAL_DEPENDENCY_UNSUPPORTED");
                    if ("REUSE_EXISTING".equals(decisions.get(dependency).get("decision"))) reused.add(string(dependency));
                    else {
                        String predecessor = owners.get(dependency);
                        if (predecessor == null) fail("PLAN_FORWARD_OR_UNRESOLVED_DEPENDENCY"); predecessors.add(predecessor);
                    }
                }
            }
            if (expectedPaths.size() != 1 || !paths.add(expectedPaths.iterator().next().toLowerCase(Locale.ROOT))) fail("PLAN_STEP_PATH_COMPOSABILITY");
            equal(list(step.get("expectedPaths")), new ArrayList<>(expectedPaths), "PLAN_STEP_PATHS");
            equal(list(step.get("requirementIds")), new ArrayList<>(expectedRequirements), "PLAN_STEP_REQUIREMENTS");
            equal(new HashSet<>(list(step.get("prerequisiteStepIds"))), predecessors, "PLAN_STEP_PREDECESSORS");
            equal(new HashSet<>(list(step.get("reusedComponentDecisionIds"))), reused, "PLAN_STEP_REUSE");
            for (Object id : list(step.get("componentDecisionIds"))) owners.put(string(id), string(step.get("id")));
        }
        equal(owners.keySet(), mutable, "PLAN_MUTATION_STEP_COVERAGE");
    }

    private static void planOwnership(String role, Map<String, Object> decision) {
        String component = string(decision.get("targetComponent")), layer = string(decision.get("targetLayer"));
        boolean allowed = switch (role) {
            case "03-domain-contract-implementation" -> Set.of("DOMAIN_MODEL", "API_DATA_CONTRACT").contains(layer)
                    && Set.of("DOMAIN_MODEL", "API_REQUEST_MODEL", "API_RESPONSE_MODEL").contains(component);
            case "04-persistence-mapping-implementation" -> layer.equals("PERSISTENCE_MAPPING") && Set.of("MAPPER", "PERSISTENCE_REPOSITORY").contains(component);
            case "05-service-api-implementation" -> layer.equals("SERVICE_API") && Set.of("SERVICE_INTERFACE", "SERVICE_IMPLEMENTATION", "CONTROLLER").contains(component);
            case "06-test-implementation" -> layer.equals("TEST_SOURCE") && component.equals("AUTOMATED_TEST_SOURCE");
            default -> false;
        };
        if (!allowed || !list(decision.get("targetScope")).equals(List.of(component))) fail("PLAN_OWNER_UNSUPPORTED_OR_MISMATCHED");
    }

    private static Set<String> planValidation(Map<String, Object> validation, Map<String, Map<String, Object>> requirements,
                                              Map<String, Map<String, Object>> decisions) {
        var tests = ids(validation.get("plannedTestChanges"), "VT");
        var expectations = ids(validation.get("validationExpectations"), "VE");
        var gaps = ids(validation.get("coverageGaps"), "TG"); Set<String> covered = new HashSet<>();
        for (var test : tests.values()) {
            fields(test, "id requirementIds componentDecisionId action expectedPath testScope rationale targetFindingIds targetEvidenceIds");
            refs(test.get("requirementIds"), requirements); nonempty(test.get("testScope")); nonempty(test.get("rationale"));
            var decision = decisions.get(nonempty(test.get("componentDecisionId"))); if (decision == null) fail("PLAN_TEST_DECISION");
            if (!"AUTOMATED_TEST_SOURCE".equals(decision.get("targetComponent")) || !"TEST_SOURCE".equals(decision.get("targetLayer"))) fail("PLAN_TEST_OWNERSHIP");
            equal(test.get("action"), decision.get("decision"), "PLAN_TEST_ACTION");
            equal(test.get("requirementIds"), decision.get("requirementIds"), "PLAN_TEST_REQUIREMENTS");
            var paths = list(object(decision.get("expectedChangeScope")).get("expectedPaths"));
            equal(test.get("expectedPath"), paths.isEmpty() ? null : paths.getFirst(), "PLAN_TEST_PATH");
        }
        for (Object item : list(validation.get("existingRelevantTests"))) {
            var existing = object(item); fields(existing, "path symbol observedCoverage targetEvidenceIds componentDecisionId plannedUse");
            planPath(existing.get("path")); nonempty(existing.get("symbol")); strings(existing.get("observedCoverage"));
            enumeration(existing.get("plannedUse"), "REUSE_EXISTING EXTEND_EXISTING");
            var decision = decisions.get(nonempty(existing.get("componentDecisionId"))); if (decision == null) fail("PLAN_EXISTING_TEST_DECISION");
            equal(existing.get("path"), decision.get("existingPath"), "PLAN_EXISTING_TEST_PATH");
            equal(existing.get("plannedUse"), decision.get("decision"), "PLAN_EXISTING_TEST_ACTION");
            equal(decision.get("targetComponent"), "AUTOMATED_TEST_SOURCE", "PLAN_EXISTING_TEST_OWNERSHIP");
        }
        for (var expectation : expectations.values()) {
            fields(expectation, "id requirementIds acceptanceIntent verificationType observableOutcome testChangeIds targetEvidenceIds");
            refs(expectation.get("requirementIds"), requirements); refs(expectation.get("testChangeIds"), tests);
            if (list(expectation.get("requirementIds")).isEmpty()) fail("PLAN_VALIDATION_REQUIREMENTS");
            nonempty(expectation.get("acceptanceIntent")); nonempty(expectation.get("observableOutcome"));
            enumeration(expectation.get("verificationType"), "AUTOMATED_TEST STATIC_INSPECTION BUILD MANUAL_REVIEW");
            list(expectation.get("requirementIds")).forEach(r -> covered.add(string(r)));
        }
        for (var gap : gaps.values()) {
            fields(gap, "id requirementIds area description targetFindingIds targetEvidenceIds impact resolution");
            refs(gap.get("requirementIds"), requirements); nonempty(gap.get("area")); nonempty(gap.get("description")); nonempty(gap.get("resolution"));
            enumeration(gap.get("impact"), "NON_BLOCKING BLOCKING");
        }
        return covered;
    }

    private static void planIssues(Map<String, Object> v, Map<String, Map<String, Object>> requirements,
                                   Map<String, Map<String, Object>> decisions, String status) {
        var reviews = ids(v.get("manualReviewItems"), "MRI"); var risks = ids(v.get("risks"), "RISK"); var blockers = ids(v.get("blockingIssues"), "BI");
        for (var review : reviews.values()) {
            fields(review, "id requirementIds componentDecisionIds question reason requiredResolution " + PLAN_SOURCE_REFS + " " + PLAN_TARGET_REFS + " blocking");
            refs(review.get("requirementIds"), requirements); refs(review.get("componentDecisionIds"), decisions);
            for (String key : words("question reason requiredResolution")) nonempty(review.get(key)); bool(review.get("blocking"));
        }
        for (var risk : risks.values()) {
            fields(risk, "id requirementIds description impact likelihood " + PLAN_SOURCE_REFS + " " + PLAN_TARGET_REFS + " mitigationOrHandoff blocking");
            refs(risk.get("requirementIds"), requirements); nonempty(risk.get("description")); nonempty(risk.get("mitigationOrHandoff")); bool(risk.get("blocking"));
            enumeration(risk.get("impact"), "LOW MEDIUM HIGH"); enumeration(risk.get("likelihood"), "LOW MEDIUM HIGH UNKNOWN");
        }
        for (var blocker : blockers.values()) {
            fields(blocker, "id phase category description requirementIds manualReviewItemIds " + PLAN_SOURCE_REFS + " " + PLAN_TARGET_REFS + " resolutionNeeded");
            enumeration(blocker.get("phase"), PLAN_PHASES);
            enumeration(blocker.get("category"), "MALFORMED_INPUT MISSING_ACCEPTED_ANALYSIS UNUSABLE_SOURCE_ANALYSIS INCOMPLETE_SOURCE_ANALYSIS UNUSABLE_TARGET_ANALYSIS INCOMPLETE_TARGET_ANALYSIS MISSING_MIGRATION_DETAIL CONFLICTING_INPUT MIGRATION_SENSITIVE_AMBIGUITY MANUAL_REVIEW PLANNING_FAILURE");
            nonempty(blocker.get("description")); nonempty(blocker.get("resolutionNeeded")); refs(blocker.get("requirementIds"), requirements); refs(blocker.get("manualReviewItemIds"), reviews);
        }
        if (status.equals("SUCCESS") && (reviews.values().stream().anyMatch(r -> Boolean.TRUE.equals(r.get("blocking")))
                || risks.values().stream().anyMatch(r -> Boolean.TRUE.equals(r.get("blocking")))
                || list(object(v.get("validationPlan")).get("coverageGaps")).stream().map(ArtifactContracts::object).anyMatch(g -> "BLOCKING".equals(g.get("impact")))
                || decisions.values().stream().anyMatch(d -> "MANUAL_REVIEW_REQUIRED".equals(d.get("decision"))))) fail("PLAN_SUCCESS_BLOCKING_ISSUE");
        if (status.equals("FAILED") && blockers.values().stream().noneMatch(b -> Set.of("INPUT_VALIDATION", "OUTPUT_VALIDATION").contains(b.get("phase")))) fail("PLAN_FAILURE_PHASE");
    }

    private static void nullableNonempty(Object value) { if (value != null) nonempty(value); }
    private static void planPath(Object value) { ExecutionPlan.canonicalPath(nonempty(value)); }
    private static void planPaths(Object value) { strings(value); for (Object entry : list(value)) planPath(entry); }

    private static void referenceCoverage(Map<String, Object> c, Map<String, Object> analysis,
                                          Map<String, Map<String, Object>> findings, Map<String, Map<String, Object>> evidence, boolean source) {
        fields(c, (source ? "callerInfoIds " : "") + "findingIds evidenceIds uncertaintyIds conflictIds blockingQuestionIds coverageReferences");
        if (source) refs(c.get("callerInfoIds"), ids(analysis.get("callerProvidedMigrationInfo"), "MI"));
        refs(c.get("findingIds"), findings); refs(c.get("evidenceIds"), evidence);
        refs(c.get("uncertaintyIds"), ids(analysis.get("uncertainties"), "U"));
        refs(c.get("conflictIds"), ids(analysis.get("conflicts"), "C"));
        refs(c.get("blockingQuestionIds"), ids(analysis.get("blockingQuestions"), "BQ"));
        strings(c.get("coverageReferences"));
    }

    private static void analysisBinding(Map<String, Object> binding, Map<String, Object> analysis, String role, byte[] bytes) {
        var project = object(analysis.get("project"));
        equal(binding.get("name"), project.get("name"), "PLAN_PROJECT_NAME");
        equal(binding.get("root"), project.get("root"), "PLAN_PROJECT_ROOT");
        equal(binding.get("analysisVersion"), analysis.get("analysisVersion"), "PLAN_ANALYSIS_VERSION");
        equal(binding.get("analysisStatus"), analysis.get("status"), "PLAN_ANALYSIS_STATUS");
        equal(binding.get("analysisFingerprint"), Json.fingerprint(role, bytes), "PLAN_ANALYSIS_FINGERPRINT");
    }

    private static Object callerReference(Map<String, Object> ref, MigrationInput input) {
        if (ref.containsKey("type")) {
            fields(ref, "type artifactRole resolutionReference sourceArtifactFingerprint reference");
            enumeration(ref.get("type"), "USER_REQUEST CALLER_MIGRATION_INFORMATION");
        } else if (!ref.containsKey("provenance")) fail("CALLER_REFERENCE_TYPE");
        equal(ref.get("artifactRole"), "CALLER_MIGRATION_REQUEST", "CALLER_ARTIFACT_UNREGISTERED");
        if (ref.get("resolutionReference") != null) fail("CALLER_RESOLUTION_UNREGISTERED");
        equal(ref.get("sourceArtifactFingerprint"), input.fingerprint(), "CALLER_FINGERPRINT");
        String pointer = string(ref.get("reference"));
        Object value = input.callerInformation();
        if (!pointer.isEmpty()) {
            if (!pointer.startsWith("/")) fail("CALLER_REFERENCE");
            for (String segment : pointer.substring(1).split("/", -1)) {
                if (segment.matches(".*~(?:[^01]|$).*")) fail("CALLER_REFERENCE");
                String key = segment.replace("~1", "/").replace("~0", "~");
                if (value instanceof Map<?, ?> map && map.containsKey(key)) value = map.get(key);
                else if (value instanceof List<?> list && key.matches("0|[1-9][0-9]*")) {
                    try { value = list.get(Integer.parseInt(key)); } catch (RuntimeException invalid) { fail("CALLER_REFERENCE"); }
                } else fail("CALLER_REFERENCE");
            }
        }
        return value;
    }

    private static void finding(Map<String, Object> f, Map<String, Map<String, Object>> evidence, boolean source) {
        fields(f, source ? "id topic findingStatus statement scope sampleBasis evidenceIds details" : "id topic findingStatus statement scope prevalence sampleBasis evidenceIds implementationConstraint");
        nonempty(f.get("topic")); nonempty(f.get("statement")); strings(f.get("scope")); string(f.get("sampleBasis"));
        String status = enumeration(f.get("findingStatus"), FINDING); refs(f.get("evidenceIds"), evidence);
        if (source) object(f.get("details")); else {
            enumeration(f.get("prevalence"), "CONSISTENT MAJORITY ISOLATED CONFLICTING UNKNOWN"); string(f.get("implementationConstraint"));
        }
        if (status.equals("OBSERVED") && list(f.get("evidenceIds")).isEmpty()) fail("FINDING_UNEVIDENCED");
        if (status.equals("NOT_OBSERVED") && string(f.get("sampleBasis")).isBlank()) fail("ABSENCE_UNSCOPED");
        for (Object id : list(f.get("evidenceIds"))) {
            if (!list(evidence.get((String) id).get("supports")).contains(f.get("id"))) fail("FINDING_EVIDENCE_MISMATCH");
        }
    }

    private static void evidence(Map<String, Object> e, Map<String, Map<String, Object>> findings, boolean source) {
        fields(e, source ? "id kind path type symbol annotationOrConfigKey location observation supports" : "id kind path symbol location observation supports");
        enumeration(e.get("kind"), "BUILD SOURCE TEST CONFIGURATION DIRECTORY DOCUMENTATION");
        if ("DIRECTORY".equals(e.get("kind"))) directory(e.get("path")); else path(e.get("path"));
        string(e.get("symbol")); string(e.get("location")); nonempty(e.get("observation")); refs(e.get("supports"), findings);
        if (source) { string(e.get("type")); string(e.get("annotationOrConfigKey")); }
    }

    private static void entry(Map<String, Object> entry, Map<String, Map<String, Object>> findings, Map<String, Map<String, Object>> evidence) {
        path(entry.get("path")); nonempty(entry.get("type")); nonempty(entry.get("symbol")); nonempty(entry.get("kind"));
        String status = enumeration(entry.get("findingStatus"), "OBSERVED UNCERTAIN");
        refs(entry.get("findingIds"), findings); refs(entry.get("evidenceIds"), evidence);
        if (status.equals("OBSERVED") && (list(entry.get("evidenceIds")).isEmpty() || list(entry.get("findingIds")).isEmpty())) fail("ENTRY_UNEVIDENCED");
    }

    private static void uncertainty(Map<String, Object> u, Map<String, Map<String, Object>> evidence,
                                     Map<String, Map<String, Object>> caller, boolean source) {
        fields(u, "id topic description reason affectedScopes " + (source ? "callerInfoIds " : "") + "evidenceIds downstreamImpact resolutionNeeded");
        nonempty(u.get("topic")); nonempty(u.get("description"));
        enumeration(u.get("reason"), "INSUFFICIENT_EVIDENCE AMBIGUOUS_EVIDENCE INACCESSIBLE_SCOPE CONFLICTING_EVIDENCE RUNTIME_DEPENDENT");
        strings(u.get("affectedScopes")); refs(u.get("evidenceIds"), evidence);
        if (source) refs(u.get("callerInfoIds"), caller);
        string(u.get("downstreamImpact")); string(u.get("resolutionNeeded"));
    }

    private static void coverage(Map<String, Object> v, List<String> expected, String status, boolean source) {
        var c = object(v.get("analysisCoverage")); fields(c, "scope inventory areas samplingNotes limitations");
        var scope = object(c.get("scope")); fields(scope, "included excluded inaccessible");
        for (Object paths : scope.values()) strings(paths);
        var inventory = object(c.get("inventory")); fields(inventory, "filesDiscovered filesInspected" + (source ? "" : " modulesDiscovered modulesInspected"));
        for (Object count : inventory.values()) count(count, true);
        var areas = list(c.get("areas")); if (areas.size() != expected.size()) fail("COVERAGE_AREAS");
        for (int i = 0; i < areas.size(); i++) {
            var area = object(areas.get(i)); fields(area, "area result inspectedPaths sampleSize notes");
            equal(area.get("area"), expected.get(i), "COVERAGE_AREA_ORDER");
            String result = enumeration(area.get("result"), "COMPLETE PARTIAL NOT_APPLICABLE NOT_INSPECTED");
            paths(area.get("inspectedPaths")); count(area.get("sampleSize"), true); string(area.get("notes"));
            if (status.equals("SUCCESS") && (result.equals("PARTIAL") || result.equals("NOT_INSPECTED"))) fail("SUCCESS_COVERAGE_INCOMPLETE");
        }
        strings(c.get("samplingNotes")); strings(c.get("limitations"));
    }

    private static void status(Map<String, Object> v, String status, boolean source) {
        var blockers = list(v.get("blockingQuestions"));
        var limits = list(object(v.get("analysisCoverage")).get("limitations"));
        if (usable(status) && !blockers.isEmpty()) fail("USABLE_ANALYSIS_HAS_BLOCKER");
        if (status.equals("BLOCKED") && blockers.isEmpty() && (source || limits.isEmpty())) fail("BLOCKED_REASON_MISSING");
        if (status.equals("FAILED") && limits.isEmpty()) fail("FAILED_REASON_MISSING");
    }

    private static void collectFindings(Object value, Map<String, Map<String, Object>> result) {
        var target = object(value);
        var project = object(target.get("project"));
        collectFindingArray(project.get("frameworks"), result);
        collectFindingArray(project.get("importantDirectories"), result);
        // These schema sections contain finding/pattern arrays, with only the named
        // scalar fields and database operations container as exceptions. Never infer
        // membership from an element's ID: every declared array element must validate.
        for (String section : words("architecture database domainAndDto mapping service api supportingConventions testing codingConventions")) {
            for (var field : object(target.get(section)).entrySet()) {
                if (section.equals("architecture") && field.getKey().equals("summary")
                        || section.equals("mapping") && field.getKey().equals("mapperPresence")) continue;
                if (section.equals("database") && field.getKey().equals("operations")) {
                    for (Object array : object(field.getValue()).values()) collectFindingArray(array, result);
                } else collectFindingArray(field.getValue(), result);
            }
        }
    }

    private static void collectFindingArray(Object value, Map<String, Map<String, Object>> result) {
        for (var entry : ids(value, "F").entrySet())
            if (result.putIfAbsent(entry.getKey(), entry.getValue()) != null) fail("DUPLICATE_FINDING");
    }

    private static void walkReferences(Object value, String name, Map<String, Map<String, Object>> namespace) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (name.equals(entry.getKey())) refs(entry.getValue(), namespace);
                else walkReferences(entry.getValue(), name, namespace);
            }
        } else if (value instanceof List<?> list) for (Object item : list) walkReferences(item, name, namespace);
    }

    private static void arrayObjectRefs(Object value, String fields, Map<String, Map<String, Object>> refs) {
        var object = arrayObject(value, fields, Set.of()); object.values().forEach(item -> refs(item, refs));
    }
    private static Map<String, Object> arrayObject(Object value, String fields, Set<String> nonArrays) {
        var result = object(value); fields(result, fields);
        result.forEach((key, item) -> { if (!nonArrays.contains(key)) list(item); }); return result;
    }
    private static Map<String, Map<String, Object>> ids(Object value, String prefix) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object item : list(value)) {
            var object = object(item); String id = nonempty(object.get("id"));
            if (!id.matches(prefix + "-[0-9]{3,}") || result.putIfAbsent(id, object) != null) fail("ARTIFACT_ID");
        }
        return result;
    }
    private static void refs(Object value, Map<String, Map<String, Object>> namespace) {
        strings(value); for (Object ref : list(value)) if (!namespace.containsKey(ref)) fail("ARTIFACT_REFERENCE");
    }
    private static List<String> words(String value) { return List.of(value.split(" ")); }
    static void fields(Map<String, Object> value, String keys) {
        if (!value.keySet().equals(new LinkedHashSet<>(words(keys)))) fail("ARTIFACT_FIELDS");
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) fail("ARTIFACT_OBJECT"); return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked") static List<Object> list(Object value) {
        if (!(value instanceof List<?>)) fail("ARTIFACT_ARRAY"); return (List<Object>) value;
    }
    static String string(Object value) { if (!(value instanceof String)) fail("ARTIFACT_STRING"); return (String) value; }
    static String nonempty(Object value) { String result = string(value); if (result.isBlank()) fail("ARTIFACT_EMPTY"); Json.identifier(result); return result; }
    private static String enumeration(Object value, String allowed) {
        String text = string(value); if (!words(allowed).contains(text)) fail("ARTIFACT_ENUM"); return text;
    }
    private static void bool(Object value) { if (!(value instanceof Boolean)) fail("ARTIFACT_BOOLEAN"); }
    private static void count(Object value, boolean nullable) {
        if (value == null && nullable) return;
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) fail("ARTIFACT_COUNT");
    }
    private static void strings(Object value) {
        Set<String> seen = new HashSet<>();
        for (Object item : list(value)) if (!seen.add(string(item))) fail("ARTIFACT_DUPLICATE_REFERENCE");
    }
    private static void paths(Object value) { strings(value); for (Object item : list(value)) path(item); }
    private static void directory(Object value) { if (!string(value).isEmpty()) path(value); }
    private static void path(Object value) {
        String path = nonempty(value);
        if (path.startsWith("/") || path.endsWith("/") || path.contains("\\") || path.contains(":") || path.contains("//")) fail("ARTIFACT_PATH");
        for (String segment : path.split("/")) if (segment.equals(".") || segment.equals("..")) fail("ARTIFACT_PATH");
    }
    private static boolean usable(String status) { return status.equals("SUCCESS") || status.equals("PARTIAL"); }
    static void equal(Object actual, Object expected, String code) { if (!Objects.equals(actual, expected)) fail(code); }
    private static void fail(String code) { throw new IllegalArgumentException(code); }
}
