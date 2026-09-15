package dev.agentic.harness;

import java.util.*;

/** Small inspectable Java fixtures, with full analyst contracts instead of empty SUCCESS envelopes. */
final class AnalysisFixtures {
    static final String SOURCE_PATH = "src/LegacyOperation.java";
    static final String TARGET_PATH = "src/TargetConventions.java";
    static final String SOURCE_TEXT = """
            final class LegacyOperation {
                String readItem(String value) { return value; }
            }
            """;
    static final String TARGET_TEXT = """
            final class TargetConventions {
                record Value(String value) {}
                static final class Mapper { Value map(String value) { return new Value(value); } }
                static final class Service {
                    private final Mapper mapper;
                    Service(Mapper mapper) { this.mapper = mapper; }
                    Value find(String value) { return mapper.map(value); }
                }
                static void verifiesMapping() {
                    if (!new Mapper().map("example").value().equals("example")) throw new AssertionError();
                }
            }
            """;
    static final String BUILD_TEXT = """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
            <artifactId>target-fixture</artifactId><version>1</version>
            <properties><maven.compiler.release>21</maven.compiler.release></properties>
            <dependencies><dependency><groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId><version>3.4.0</version>
            </dependency></dependencies></project>
            """;

    static Map<String, Object> source(MigrationInput input) {
        String pointer = input.callerInformation().containsKey("migration")
                ? "/migration/settings/sourceOperationName" : "/sourceOperationName";
        var caller = Json.object("id", "MI-001", "provenance", "CALLER_PROVIDED", "artifactRole", "CALLER_MIGRATION_REQUEST",
                "resolutionReference", null, "sourceArtifactFingerprint", input.fingerprint(), "reference", pointer,
                "category", "SCOPE", "value", input.requestedOperation(), "redacted", false, "verification", "CONFIRMED",
                "sourceFindingIds", List.of("F-001"), "sourceEvidenceIds", List.of("E-001"), "conflictIds", List.of());
        var finding = Json.object("id", "F-001", "topic", "Requested operation", "findingStatus", "OBSERVED",
                "statement", "The operation returns its String parameter unchanged and invokes no other operation.",
                "scope", List.of(SOURCE_PATH), "sampleBasis", "Entire operation body inspected", "evidenceIds", List.of("E-001"),
                "details", Json.object("parameter", "value", "parameterType", "String", "resultType", "String", "behavior", "identity"));
        var technology = Json.object("id", "F-002", "topic", "Source language", "findingStatus", "OBSERVED",
                "statement", "The operation is implemented in a Java class.", "scope", List.of(SOURCE_PATH),
                "sampleBasis", "Class declaration inspected", "evidenceIds", List.of("E-001"), "details", Json.object("language", "Java"));
        var entry = Json.object("id", "EP-001", "path", SOURCE_PATH, "type", "LegacyOperation", "symbol", input.requestedOperation(),
                "kind", "JAVA_METHOD", "findingStatus", "OBSERVED", "findingIds", List.of("F-001"), "evidenceIds", List.of("E-001"));
        var candidate = new LinkedHashMap<>(entry); candidate.put("disposition", "RESOLVED"); candidate.put("reason", "One matching operation exists in the inspected fixture.");
        List<Map<String, Object>> coverage = new ArrayList<>();
        Set<String> absent = Set.of("PERSISTENCE", "EXTERNAL_DEPENDENCIES", "VALIDATION", "ERROR_BEHAVIOR", "TRANSACTIONS");
        for (String area : ArtifactContracts.SOURCE_AREAS) coverage.add(area(area,
                absent.contains(area) ? "NOT_APPLICABLE" : "COMPLETE", SOURCE_PATH,
                absent.contains(area) ? "The entire operation contains only a parameter return; this behavior is absent." : "The complete fixture operation and caller locator were inspected."));
        return Json.object("analysisVersion", 1, "status", "SUCCESS", "project", Json.object("name", "source-fixture", "root", input.sourceRoot().toString()),
                "analysisScope", Json.object("migrationMode", "SOURCE_TO_TARGET", "requestedOperation", input.requestedOperation(),
                        "callerLocatorIds", List.of("MI-001"), "resolvedEntryPointId", "EP-001", "candidateEntryPoints", List.of(candidate)),
                "callerProvidedMigrationInfo", List.of(caller), "technologyProfile", List.of("F-002"), "operationEntryPoint", entry,
                "callChain", List.of(), "inputContract", Json.object("parameters", List.of("F-001"), "requestTypes", List.of(), "fields", List.of(), "findingIds", List.of("F-001")),
                "outputContract", Json.object("resultTypes", List.of("F-001"), "responseTypes", List.of(), "fields", List.of(), "findingIds", List.of("F-001")),
                "persistenceBehavior", emptyArrays("mechanisms queries tables columns joins filters parameters ordering grouping pagination resultMappings findingIds"),
                "externalDependencies", List.of(), "businessRules", List.of("F-001"), "validationBehavior", List.of(), "errorBehavior", List.of(),
                "transactionBehavior", List.of(), "findings", List.of(finding, technology),
                "evidence", List.of(Json.object("id", "E-001", "kind", "SOURCE", "path", SOURCE_PATH, "type", "LegacyOperation",
                        "symbol", input.requestedOperation(), "annotationOrConfigKey", "", "location", "line 2",
                        "observation", "The String operation returns value directly.", "supports", List.of("F-001", "F-002"))),
                "conflicts", List.of(), "uncertainties", List.of(), "blockingQuestions", List.of(),
                "analysisCoverage", Json.object("scope", Json.object("included", List.of(SOURCE_PATH), "excluded", List.of(), "inaccessible", List.of()),
                        "inventory", Json.object("filesDiscovered", 1, "filesInspected", 1), "areas", coverage,
                        "samplingNotes", List.of("Entire small source fixture inspected; conclusions apply only to this operation."), "limitations", List.of()));
    }

    static Map<String, Object> blockedSource(MigrationInput input) {
        Map<String, Object> result = new LinkedHashMap<>(source(input));
        result.put("status", "BLOCKED");
        var scope = new LinkedHashMap<>(ArtifactContracts.object(result.get("analysisScope")));
        scope.put("resolvedEntryPointId", null);
        var candidate = new LinkedHashMap<>(ArtifactContracts.object(ArtifactContracts.list(scope.get("candidateEntryPoints")).getFirst()));
        candidate.put("disposition", "UNRESOLVED"); candidate.put("reason", "The same requested name is present in two source classes.");
        var other = new LinkedHashMap<>(candidate); other.put("id", "EP-002"); other.put("type", "OtherLegacyOperation");
        other.put("path", "src/OtherLegacyOperation.java");
        scope.put("candidateEntryPoints", List.of(candidate, other)); result.put("analysisScope", scope); result.put("operationEntryPoint", null);
        result.put("blockingQuestions", List.of(Json.object("id", "BQ-001", "code", "AMBIGUOUS_OPERATION",
                "question", "Operation identity is ambiguous across two source classes.", "whyBlocking", "Selecting a class changes the migrated operation.",
                "affectedAreas", List.of("OPERATION_ENTRY_POINT"), "callerInfoIds", List.of("MI-001"), "evidenceIds", List.of("E-001", "E-002"),
                "conflictIds", List.of(), "uncertaintyIds", List.of(), "resolutionRoute", "HOST_INPUT_REGISTRATION")));
        var evidence = new ArrayList<>(ArtifactContracts.list(result.get("evidence")));
        var second = new LinkedHashMap<>(ArtifactContracts.object(evidence.getFirst()));
        second.put("id", "E-002"); second.put("path", "src/OtherLegacyOperation.java"); second.put("type", "OtherLegacyOperation");
        second.put("observation", "Another class declares the same requested operation name."); second.put("supports", List.of()); evidence.add(second);
        other.put("evidenceIds", List.of("E-002")); other.put("findingStatus", "UNCERTAIN"); result.put("evidence", evidence);
        var coverage = new LinkedHashMap<>(ArtifactContracts.object(result.get("analysisCoverage")));
        coverage.put("scope", Json.object("included", List.of(SOURCE_PATH, "src/OtherLegacyOperation.java"), "excluded", List.of(), "inaccessible", List.of()));
        coverage.put("inventory", Json.object("filesDiscovered", 2, "filesInspected", 2));
        coverage.put("areas", ArtifactContracts.SOURCE_AREAS.stream().map(name -> area(name, "NOT_INSPECTED", SOURCE_PATH,
                "Dependent analysis stopped because operation identity remains ambiguous.")).toList());
        coverage.put("limitations", List.of("No resolved source operation or complete source behavior claim is available."));
        result.put("analysisCoverage", coverage);
        return result;
    }

    static Map<String, Object> target(MigrationInput input) {
        List<Map<String, Object>> findings = List.of(
                targetFinding("F-001", "Build", "The descriptor declares Java 21 and a Spring Boot starter.", "E-001", "pom.xml"),
                targetFinding("F-002", "Data contract", "The inspected target uses an immutable Value record containing a String.", "E-002", TARGET_PATH),
                targetFinding("F-003", "Mapping", "Mapper.map constructs Value from its String input.", "E-002", TARGET_PATH),
                targetFinding("F-004", "Service", "Service delegates to a constructor-provided Mapper.", "E-002", TARGET_PATH),
                targetFinding("F-005", "Static test source", "verifiesMapping compares the mapped value with its input and throws on mismatch.", "E-002", TARGET_PATH));
        var architecture = new LinkedHashMap<>(emptyArrays("style layers dependencyFlows interfaceImplementationPatterns domainBoundaries representativeFlows findings"));
        architecture.put("summary", "One fixture container demonstrates data, mapping, service delegation and a static test method.");
        architecture.put("findings", List.of(findings.get(0)));
        var database = new LinkedHashMap<>(emptyArrays("technologies repositoryStyles transactions pagination filtering schemaMigrations findings"));
        database.put("operations", emptyArrays("insert update delete select join customQuery"));
        var domain = new LinkedHashMap<>(emptyArrays("entities domainObjects requestDtos responseDtos internalDtos conversionBoundaries apiExposurePatterns findings"));
        domain.put("findings", List.of(findings.get(1)));
        var mapping = new LinkedHashMap<>(emptyArrays("technologies styles locations usagePatterns representativeExamples findings"));
        mapping.put("mapperPresence", "OBSERVED"); mapping.put("findings", List.of(findings.get(2)));
        var service = new LinkedHashMap<>(emptyArrays("interfacePatterns implementationPatterns dependencyInjection businessLogicPlacement transactionBoundaries validationResponsibilities exceptionBehavior findings"));
        service.put("findings", List.of(findings.get(3)));
        var testing = new LinkedHashMap<>(emptyArrays("structure frameworks controllerTests serviceTests repositoryTests integrationTests mockingConventions fixturesAndTestData findings"));
        testing.put("findings", List.of(findings.get(4)));
        return Json.object("analysisVersion", 1, "status", "SUCCESS", "project", Json.object("name", "target-fixture", "root", input.targetRoot().toString(),
                        "buildSystem", property("Maven"), "javaVersion", Json.object("findingStatus", "OBSERVED", "declared", List.of("21"), "effective", "",
                                "evidenceIds", List.of("E-001")), "springBootVersion", property("3.4.0"), "frameworks", List.of(), "importantDirectories", List.of()),
                "modules", List.of(Json.object("name", "target-fixture", "path", "", "type", "APPLICATION", "sourceRoots", List.of("src"), "testRoots", List.of(),
                        "resourceRoots", List.of(), "packages", List.of(), "dependenciesOnModules", List.of(), "responsibilities", List.of("Fixture conventions"), "evidenceIds", List.of("E-001", "E-002"))),
                "architecture", architecture, "database", database, "domainAndDto", domain, "mapping", mapping, "service", service,
                "api", emptyArrays("controllerConventions endpointConventions requestPatterns responsePatterns httpHandling validation errorHandling handlerPatterns findings"),
                "supportingConventions", emptyArrays("constants enums configuration properties aspects utilities logging findings"), "testing", testing,
                "codingConventions", emptyArrays("naming packages classDesign methodDesign nullabilityAndOptional exceptions logging formatting implementationGuide"),
                "evidence", List.of(Json.object("id", "E-001", "kind", "BUILD", "path", "pom.xml", "symbol", "", "location", "descriptor",
                                "observation", "The descriptor declares compiler release 21 and spring-boot-starter 3.4.0.", "supports", List.of("F-001")),
                        Json.object("id", "E-002", "kind", "SOURCE", "path", TARGET_PATH, "symbol", "TargetConventions", "location", "class body",
                                "observation", "The source contains Value, Mapper, Service and verifiesMapping declarations.", "supports", List.of("F-002", "F-003", "F-004", "F-005"))),
                "uncertainties", List.of(), "conflicts", List.of(), "blockingQuestions", List.of(), "analysisCoverage", Json.object("scope",
                        Json.object("included", List.of("pom.xml", TARGET_PATH), "excluded", List.of(), "inaccessible", List.of()),
                        "inventory", Json.object("filesDiscovered", 2, "filesInspected", 2, "modulesDiscovered", 1, "modulesInspected", 1),
                        "areas", ArtifactContracts.TARGET_AREAS.stream().map(name -> area(name,
                                Set.of("DATABASE", "API", "SUPPORTING_CONVENTIONS").contains(name) ? "NOT_APPLICABLE" : "COMPLETE",
                                TARGET_PATH, "Entire small target fixture inspected; only directly visible patterns reported.")).toList(),
                        "samplingNotes", List.of("The fixture contains one descriptor and one Java convention source; no runtime behavior was executed."), "limitations", List.of()));
    }

    /** An actionable migration intent; validation expectations are future work, never execution results. */
    static Map<String, Object> plan(MigrationInput input, byte[] acceptedSource, byte[] acceptedTarget) {
        var source = Json.parse(MigrationInput.utf8(acceptedSource));
        var target = Json.parse(MigrationInput.utf8(acceptedTarget));
        String pointer = input.callerInformation().containsKey("migration")
                ? "/migration/settings/sourceOperationName" : "/sourceOperationName";
        var caller = Json.object("type", "CALLER_MIGRATION_INFORMATION", "artifactRole", "CALLER_MIGRATION_REQUEST",
                "resolutionReference", null, "sourceArtifactFingerprint", input.fingerprint(), "reference", pointer);
        var sourceBinding = analysisBinding(source, "SOURCE_ANALYSIS", acceptedSource);
        sourceBinding.put("requestedOperation", input.requestedOperation());
        sourceBinding.put("resolvedEntryPointId", ArtifactContracts.object(source.get("analysisScope")).get("resolvedEntryPointId"));
        var targetBinding = analysisBinding(target, "TARGET_ANALYSIS", acceptedTarget);
        targetBinding.put("relevantModules", List.of(Json.object("name", "target-fixture", "path", "", "targetEvidenceIds", List.of("E-001", "E-002"))));
        targetBinding.put("relevantAnalysisAreas", List.of("DOMAIN_AND_DTO", "MAPPING", "SERVICE", "TESTING").stream().map(area ->
                Json.object("area", area, "coverageResult", "COMPLETE", "migrationRelevance", "Preserve the requested operation using the inspected target data, mapper, service and test conventions.")).toList());
        var requirement = Json.object("id", "MR-001", "description", "Migrate the requested String identity operation into the target service conventions.",
                "derivation", "SOURCE_BEHAVIOR_PRESERVATION", "source", Json.object("primaryType", "CALLER_MIGRATION_INFORMATION", "references", List.of(caller)),
                "sourceCallerInfoIds", List.of("MI-001"), "sourceFindingIds", List.of("F-001"), "sourceEvidenceIds", List.of("E-001"),
                "acceptanceIntent", "The migrated operation returns its input String unchanged.", "affectedCapabilities", List.of("String identity operation"));
        var reused = decision("CD-001", "MAPPER", "PERSISTENCE_MAPPING", "REUSE_EXISTING", TARGET_PATH,
                List.of("F-002", "F-003"), List.of(), "The existing Mapper and Value already preserve the String payload; retain their contracts.",
                "PRESERVATION", "Retain the observed Mapper.map and Value payload behavior unchanged.");
        var service = decision("CD-002", "SERVICE_IMPLEMENTATION", "SERVICE_API", "CREATE_NEW", "src/ReadItemService.java",
                List.of("F-002", "F-003", "F-004"), List.of("CD-001"),
                "Create ReadItemService in the evidenced src root and default package. Use the observed constructor-provided Mapper convention. The caller operation name and source input/output finding determine the public contract; reuse the existing mapper and expose its unchanged String payload.",
                "IMPLEMENTATION", "Provide the requested String identity operation using the existing target Mapper through constructor injection.");
        service.put("callableContracts", List.of(Json.object("name", input.requestedOperation(), "parameters", List.of(Json.object("name", "value", "type", "String")),
                "returnType", "String", "signatureSemantics", List.of(), "requirementIds", List.of("MR-001"),
                "targetFindingIds", List.of("F-004"), "targetEvidenceIds", List.of("E-002"))));
        var tests = decision("CD-003", "AUTOMATED_TEST_SOURCE", "TEST_SOURCE", "CREATE_NEW", "src/ReadItemContractTest.java",
                List.of("F-004", "F-005"), List.of("CD-002"),
                "Create ReadItemContractTest alongside the evidenced static assertion test convention. The separate exact file keeps service and test responsibilities independently owned. Check the accepted source identity behavior through the CD-002 operation contract.",
                "IMPLEMENTATION", "Add a static assertion test exercising ReadItemService with representative String input and detecting any changed result.");
        var mapping = Json.object("id", "TM-001", "requirementId", "MR-001", "targetScopes", List.of("AUTOMATED_TEST_SOURCE", "MAPPER", "SERVICE_IMPLEMENTATION"),
                "targetLayers", List.of("PERSISTENCE_MAPPING", "SERVICE_API", "TEST_SOURCE"), "targetComponents", List.of("AUTOMATED_TEST_SOURCE", "MAPPER", "SERVICE_IMPLEMENTATION"),
                "existingPaths", List.of(TARGET_PATH), "targetFindingIds", List.of("F-002", "F-003", "F-004", "F-005"), "targetEvidenceIds", List.of("E-002"),
                "targetUncertaintyIds", List.of(), "targetConflictIds", List.of(), "targetBlockingQuestionIds", List.of(), "targetCoverageReferences", List.of("/analysisCoverage/areas/4", "/analysisCoverage/areas/5", "/analysisCoverage/areas/8"),
                "componentDecisionIds", List.of("CD-001", "CD-002", "CD-003"), "mappingRationale", "Reuse the observed payload mapper and introduce a source-compatible service operation plus focused test source.", "unresolvedIssues", List.of());
        return Json.object("planningVersion", 1, "status", "SUCCESS", "migrationMode", "SOURCE_TO_TARGET", "sourceProject", sourceBinding, "targetProject", targetBinding,
                "migrationRequest", Json.object("summary", "Migrate " + input.requestedOperation() + " preserving its observed identity behavior.", "sourceInformationProvided", false,
                        "sourceInformationReferences", List.of(), "sourceCallerInfoIds", List.of("MI-001"), "scopeConstraints", List.of(), "normalizationNotes", List.of()),
                "requirements", List.of(requirement), "targetMappings", List.of(mapping), "componentDecisions", List.of(reused, service, tests),
                "implementationOrder", List.of(step("STEP-001", 1, TrustedInputs.Role.SERVICE, "CD-002", "src/ReadItemService.java", List.of(), List.of("CD-001")),
                        step("STEP-002", 2, TrustedInputs.Role.TESTS, "CD-003", "src/ReadItemContractTest.java", List.of("STEP-001"), List.of())),
                "validationPlan", Json.object("existingRelevantTests", List.of(), "plannedTestChanges", List.of(Json.object("id", "VT-001", "requirementIds", List.of("MR-001"),
                                "componentDecisionId", "CD-003", "action", "CREATE_NEW", "expectedPath", "src/ReadItemContractTest.java", "testScope", "ReadItemService String identity behavior",
                                "rationale", "The observed mapper test does not exercise the new service entry point.", "targetFindingIds", List.of("F-005"), "targetEvidenceIds", List.of("E-002"))),
                        "validationExpectations", List.of(Json.object("id", "VE-001", "requirementIds", List.of("MR-001"), "acceptanceIntent", requirement.get("acceptanceIntent"),
                                "verificationType", "AUTOMATED_TEST", "observableOutcome", "The service identity assertions execute and pass for the supplied String examples.",
                                "testChangeIds", List.of("VT-001"), "targetEvidenceIds", List.of("E-002"))), "coverageGaps", List.of()),
                "risks", List.of(), "manualReviewItems", List.of(), "blockingIssues", List.of(),
                "coverage", Json.object("requirements", Json.object("total", 1, "mapped", 1, "withComponentDecision", 1, "withValidationExpectation", 1),
                        "decisions", Json.object("total", 3, "reuseExisting", 1, "extendExisting", 0, "createNew", 2, "manualReviewRequired", 0),
                        "sourceReferences", Json.object("callerInfoIds", List.of("MI-001"), "findingIds", List.of("F-001"), "evidenceIds", List.of("E-001"),
                                "uncertaintyIds", List.of(), "conflictIds", List.of(), "blockingQuestionIds", List.of(), "coverageReferences", List.of()),
                        "targetReferences", Json.object("findingIds", List.of("F-002", "F-003", "F-004", "F-005"), "evidenceIds", List.of("E-001", "E-002"),
                                "uncertaintyIds", List.of(), "conflictIds", List.of(), "blockingQuestionIds", List.of(), "coverageReferences", List.of("/analysisCoverage/areas/4", "/analysisCoverage/areas/5", "/analysisCoverage/areas/8")),
                        "expectedTouchedFiles", List.of("src/ReadItemContractTest.java", "src/ReadItemService.java"), "undeterminedTouchedFiles", List.of(), "noImplementationChangeRequired", false,
                        "planningAreas", List.of("INPUT_VALIDATION", "REQUIREMENT_EXTRACTION", "TARGET_MAPPING", "PLANNING_DECISIONS", "EXECUTION_ORDERING", "VALIDATION_PLANNING", "OUTPUT_VALIDATION").stream()
                                .map(phase -> Json.object("phase", phase, "result", "COMPLETE", "notes", "Derived from accepted caller and analysis artifacts; validation is planned, not executed.")).toList(),
                        "notes", List.of("This plan requires future implementation and automated validation; planning SUCCESS is not migration or validation SUCCESS.")));
    }

    private static Map<String, Object> analysisBinding(Map<String, Object> analysis, String role, byte[] bytes) {
        var project = ArtifactContracts.object(analysis.get("project"));
        return new LinkedHashMap<>(Json.object("name", project.get("name"), "root", project.get("root"), "analysisVersion", analysis.get("analysisVersion"),
                "analysisStatus", analysis.get("status"), "analysisFingerprint", Json.fingerprint(role, bytes)));
    }

    static Map<String, Object> decision(String id, String component, String layer, String action, String path,
                                               List<String> findings, List<String> dependencies, String rationale, String responsibilityClass, String responsibility) {
        boolean create = action.equals("CREATE_NEW");
        return new LinkedHashMap<>(Json.object("id", id, "requirementIds", List.of("MR-001"), "targetComponent", component, "targetLayer", layer, "targetScope", List.of(component),
                "existingPath", create ? null : path, "expectedLocation", create ? path : null, "expectedLocationKind", create ? "EXACT_PATH" : "NOT_APPLICABLE",
                "decision", action, "rationale", rationale, "targetFindingIds", findings, "targetEvidenceIds", List.of("E-002"), "targetUncertaintyIds", List.of(),
                "targetConflictIds", List.of(), "targetBlockingQuestionIds", List.of(), "targetCoverageReferences", List.of(), "implementationConstraints", List.of(),
                "callableContracts", List.of(), "declarationContracts", List.of(), "dependencies", dependencies,
                "expectedChangeScope", Json.object("changeType", create ? "CREATE" : "NO_CHANGE", "responsibilities", List.of(Json.object("responsibilityClass", responsibilityClass, "description", responsibility)),
                        "expectedPaths", create ? List.of(path) : List.of()), "confidence", "MEDIUM"));
    }

    static Map<String, Object> step(String id, int sequence, TrustedInputs.Role role, String decision, String path,
                                          List<String> predecessors, List<String> reused) {
        return Json.object("id", id, "sequence", sequence, "specialistRole", role.id, "requirementIds", List.of("MR-001"), "componentDecisionIds", List.of(decision),
                "objective", "Implement the exact assigned component responsibility.", "prerequisiteStepIds", predecessors, "reusedComponentDecisionIds", reused,
                "expectedPaths", List.of(path), "implementationConstraints", List.of(),
                "completionEvidenceExpected", List.of("Observed exact-path change and independent responsibility verification for the assigned decision."));
    }

    static Map<String, Object> emptyArrays(String keys) {
        Map<String, Object> result = new LinkedHashMap<>(); for (String key : keys.split(" ")) result.put(key, List.of()); return result;
    }
    private static Map<String, Object> area(String name, String result, String path, String notes) {
        return Json.object("area", name, "result", result, "inspectedPaths", List.of(path), "sampleSize", 1, "notes", notes);
    }
    private static Map<String, Object> property(String value) {
        return Json.object("findingStatus", "OBSERVED", "value", value, "evidenceIds", List.of("E-001"));
    }
    private static Map<String, Object> targetFinding(String id, String topic, String statement, String evidence, String path) {
        return Json.object("id", id, "topic", topic, "findingStatus", "OBSERVED", "statement", statement,
                "scope", List.of(path), "prevalence", "ISOLATED", "sampleBasis", "Complete fixture declaration inspected",
                "evidenceIds", List.of(evidence), "implementationConstraint", "");
    }
}
