package dev.agentic.harness;

import java.nio.file.*;
import java.util.*;
import static dev.agentic.harness.RuntimeTest.*;
import static dev.agentic.harness.OperationImplementationFixtures.*;

/** Independent synthetic project/evidence plus host-reviewed outputs. No company/project discovery. */
final class CustomerFilterDemoFixture {
    static final String MAIN = "src/main/java/demo/customer", TEST = "src/test/java/demo/customer";
    static final String RULES = "src/main/resources/operation-conventions.properties", SCHEMA = "src/main/resources/customer-filter-schema.sql";
    static final List<String> PATHS = List.of(MAIN + "/CustomerFilter.java", MAIN + "/AddCustomerFilterRequest.java",
            MAIN + "/CustomerFilterResponse.java", MAIN + "/CustomerFilterRepository.java", MAIN + "/CustomerFilterMapper.java",
            MAIN + "/CustomerFilterService.java", MAIN + "/CustomerFilterController.java", TEST + "/AddCustomerFilterTest.java");
    static final List<String> DECISIONS = List.of("REUSE", "CREATE", "REUSE", "MODIFY", "REUSE", "MODIFY", "MODIFY", "CREATE");
    static final List<String> TEST_NAMES = List.of("insertsAfterTitleAndShiftsSiblingsAtomically", "invalidAndDuplicateRequestsLeaveStateUnchanged",
            "missingReferenceLeavesStateUnchanged", "preservesSpringApiContract");
    final Path target, config;
    final MigrationInput input;
    final Map<String,String> original = new TreeMap<>(), approved = new TreeMap<>();
    final Map<String,Object> analysis;
    private final Properties rules = new Properties();
    private final Map<String,String> evidenceIds = new LinkedHashMap<>();
    private final List<Map<String,Object>> evidence = new ArrayList<>(), findings = new ArrayList<>();

    CustomerFilterDemoFixture(Path parent, Path repository) throws Exception {
        Path example = repository.resolve("harness/examples/customer-filter");
        Path root = Files.createTempDirectory(parent, "customer-filter-demo-");
        target = Files.createDirectory(root.resolve("customer-management")); config = root.resolve("new-operation.json");
        for (Path ancestor = parent.toRealPath(); ancestor != null; ancestor = ancestor.getParent())
            if (Files.exists(ancestor.resolve(".git"))) throw new IllegalArgumentException("NON_GIT_FIXTURE_PARENT_REQUIRED");
        try (var paths = Files.walk(example.resolve("target-project"))) {
            for (Path path : paths.sorted().toList()) {
                String relative = example.resolve("target-project").relativize(path).toString();
                if (Files.isDirectory(path)) Files.createDirectories(target.resolve(relative));
                else { String text = Files.readString(path); original.put(relative, text); Files.writeString(target.resolve(relative), text, StandardOpenOption.CREATE_NEW); }
            }
        }
        try (var paths = Files.walk(example.resolve("approved"))) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList())
                approved.put(example.resolve("approved").relativize(path).toString(), Files.readString(path));
        }
        var data = Json.parse(Files.readString(example.resolve("new-operation.json")));
        data.put("targetProjectPath", target.toString()); Files.write(config, Json.bytes(data));
        input = MigrationConfigLoader.load(config);
        rules.load(new java.io.StringReader(original.get(RULES)));
        analysis = analysis();
    }
    private Map<String,Object> analysis() {
        var a = copy(AnalysisFixtures.target(input));
        for (String section : List.of("architecture", "database", "domainAndDto", "mapping", "service", "api", "supportingConventions", "testing"))
            map(a.get(section)).put("findings", List.of());
        for (String path : original.keySet()) addEvidence(path, path.equals("pom.xml") ? "BUILD" : path.startsWith(TEST) ? "TEST" : path.endsWith(".java") ? "SOURCE" : "CONFIGURATION", original.get(path));
        for (String path : List.of("", "src/main/java", MAIN, "src/main/resources", "src/test/java", TEST)) addEvidence(path, "DIRECTORY", "Existing directory " + path);
        for (String key : OperationPlanContract.CONVENTIONS) fact("conventions", key, rules.getProperty(key), RULES);
        fact("operation", "physicalTable", "CUSTOMER_FILTER", SCHEMA);
        fact("operation", "endpointPath", "/customer-filters", PATHS.get(6));
        Map<String,String> columns = Map.of("id", "FILTER_ID", "label", "DISPLAY_LABEL", "parentKey", "PARENT_KEY", "filterOrder", "FILTER_ORDER", "after", "FILTER_ID");
        for (String direction : List.of("requestFields", "responseFields")) {
            var fields = (List<?>)input.callerInformation().get(direction);
            for (int i = 0; i < fields.size(); i++) {
                String name = (String)map(fields.get(i)).get("name"), subject = "/" + direction + "/" + i;
                fact(subject, "databaseColumn", columns.get(name), SCHEMA);
                fact(subject, "javaField", name, name.equals("after") ? PATHS.get(3) : PATHS.get(0));
                fact(subject, "javaType", name.equals("filterOrder") ? "int" : "String", PATHS.get(0));
            }
        }
        for (int i = 0; i < PATHS.size(); i++) {
            String kind = OperationPlanContract.COMPONENTS.get(i), path = PATHS.get(i);
            if (DECISIONS.get(i).equals("CREATE")) {
                fact(kind, "creationDirectory", i == 7 ? TEST : MAIN, i == 7 ? TEST : MAIN);
                fact(kind, "creationPath", path, RULES);
            } else fact(kind, "existingPath", path, path);
        }
        fact("placement", "strategy", rules.getProperty("strategy"), RULES);
        fact("placement", "parentKey", "CUSTOMER_INFORMATION", PATHS.get(3));
        fact("placement", "after", "CUSTOMER_TITLE", PATHS.get(3));
        // Derive from the existing seed and positionAfter implementation, never caller order or runtime constants.
        var matcher = java.util.regex.Pattern.compile("new CustomerFilter\\(\"CUSTOMER_TITLE\", \"Customer Title\", \"CUSTOMER_INFORMATION\", ([0-9]+)\\)")
                .matcher(original.get(PATHS.get(3)));
        if (!matcher.find()) throw new IllegalStateException("DEMO_REFERENCE_EVIDENCE_MISSING");
        fact("placement", "filterOrder", Integer.parseInt(matcher.group(1)) + 1, PATHS.get(3));
        fact("placement", "coordinatedChanges", rules.getProperty("behavior"), RULES);
        fact("placement", "transactionConsistency", rules.getProperty("consistency"), RULES);
        for (String key : List.of("behavior", "testOutcome", "validationExpectation")) fact("operation", key, rules.getProperty(key), RULES);
        map(a.get("architecture")).put("summary", "Synthetic Spring customer-management API with an in-memory ordered-filter repository");
        map(a.get("architecture")).put("findings", findings); a.put("evidence", evidence);
        var project = map(a.get("project")); project.put("name", "customer-management");
        project.put("buildSystem", Json.object("findingStatus", "OBSERVED", "value", "Maven", "evidenceIds", List.of(evidenceIds.get("pom.xml"))));
        project.put("javaVersion", Json.object("findingStatus", "OBSERVED", "declared", List.of("21"), "effective", "", "evidenceIds", List.of(evidenceIds.get("pom.xml"))));
        project.put("springBootVersion", Json.object("findingStatus", "NOT_APPLICABLE", "value", "", "evidenceIds", List.of(evidenceIds.get("pom.xml"))));
        a.put("modules", List.of(Json.object("name", "customer-management", "path", "", "type", "APPLICATION", "sourceRoots", List.of("src/main/java"),
                "testRoots", List.of("src/test/java"), "resourceRoots", List.of("src/main/resources"), "packages", List.of("demo.customer"),
                "dependenciesOnModules", List.of(), "responsibilities", List.of("Customer hierarchy filters"), "evidenceIds", List.of(evidenceIds.get("pom.xml")))));
        var coverage = map(a.get("analysisCoverage")); coverage.put("scope", Json.object("included", new ArrayList<>(original.keySet()), "excluded", List.of(), "inaccessible", List.of()));
        coverage.put("inventory", Json.object("filesDiscovered", original.size(), "filesInspected", original.size(), "modulesDiscovered", 1, "modulesInspected", 1));
        for (Object item : (List<?>)coverage.get("areas")) { var area = map(item); area.put("result", "COMPLETE"); area.put("inspectedPaths", new ArrayList<>(original.keySet())); area.put("sampleSize", original.size()); }
        coverage.put("samplingNotes", List.of("Every synthetic target file was read through the host broker; schema SQL is documentation only."));
        return copy(a);
    }
    private void addEvidence(String path, String kind, String text) {
        String id = "E-%03d".formatted(evidence.size() + 1); evidenceIds.put(path, id);
        evidence.add(new LinkedHashMap<>(Json.object("id", id, "kind", kind, "path", path, "symbol", "", "location", "complete fixture file",
                "observation", text.lines().filter(line -> !line.isBlank()).findFirst().orElseThrow(), "supports", new ArrayList<String>())));
    }
    private void fact(String subject, String property, Object value, String path) {
        String id = "F-%03d".formatted(findings.size() + 1), evidenceId = evidenceIds.get(path);
        if (original.containsKey(path) && !property.equals("existingPath")) {
            String literal = String.valueOf(value);
            String excerpt = original.get(path).lines().filter(line -> line.contains(literal)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("DEMO_FACT_NOT_IN_TARGET: " + property + "=" + literal));
            addEvidence(path, path.endsWith(".java") ? "SOURCE" : "CONFIGURATION", excerpt);
            evidenceId = evidenceIds.get(path);
        }
        findings.add(Json.object("id", id, "topic", OperationPlanContract.FACT_TOPIC, "findingStatus", "OBSERVED",
                "statement", Json.write(Json.object("operationName", "addCustomerFilter", "operationType", "INSERT", "tableName", "CUSTOMER_FILTER",
                        "subject", subject, "property", property, "value", value)), "scope", List.of(path), "prevalence", "CONSISTENT",
                "sampleBasis", "Complete synthetic target evidence", "evidenceIds", List.of(evidenceId), "implementationConstraint", "Preserve observed conventions"));
        String selected = evidenceId;
        var e = evidence.stream().filter(v -> selected.equals(v.get("id"))).findFirst().orElseThrow();
        var supports = new ArrayList<>(ArtifactContracts.list(e.get("supports"))); supports.add(id); e.put("supports", supports);
    }
    Map<String,Object> plan(Map<String,byte[]> accepted) {
        var p = OperationPlanFixtures.plan(input, accepted); var t = Json.parse(MigrationInput.utf8(accepted.get("TARGET_ANALYSIS")));
        List<List<String>> dependencies = List.of(List.of(), List.of("CD-001"), List.of(), List.of("CD-001", "CD-002"), List.of("CD-001", "CD-003"),
                List.of("CD-002", "CD-004", "CD-005"), List.of("CD-006"), List.of("CD-004", "CD-006", "CD-007"));
        for (int i = 0; i < PATHS.size(); i++) {
            var c = row(p, "componentDecisions", i); c.put("status", "RESOLVED"); c.put("decision", DECISIONS.get(i)); c.put("targetPath", PATHS.get(i));
            c.put("basis", OperationPlanFixtures.resolved(t, (String)c.get("component"), DECISIONS.get(i).equals("CREATE") ? "creationDirectory" : "existingPath"));
            c.put("behavioralChangeIds", List.of("BC-001")); c.put("dependencies", dependencies.get(i));
        }
        var placement = map(p.get("placement"));
        var values = new ArrayList<>(ArtifactContracts.list(placement.get("resolvedValues")));
        values.add(Json.object("field", "filterOrder", "origin", "TARGET_EVIDENCE", "value", map(OperationPlanFixtures.resolved(t, "placement", "filterOrder")).get("value"),
                "support", OperationPlanFixtures.resolved(t, "placement", "filterOrder"))); placement.put("resolvedValues", values);
        placement.put("coordinatedChanges", OperationPlanFixtures.resolved(t, "placement", "coordinatedChanges"));
        placement.put("transactionConsistency", OperationPlanFixtures.resolved(t, "placement", "transactionConsistency"));
        p.put("risks", List.of(Json.object("id", "RISK-001", "code", "COORDINATED_ORDER_UPDATE", "description", "Siblings must shift atomically within one parent",
                "affectedReferences", List.of("/placement"), "resolutionRoute", "REFRESH_TARGET_ANALYSIS")));
        var steps = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < 4; i++) steps.add(Json.object("id", "STEP-00" + (i + 1), "sequence", i + 1,
                "specialistRole", OperationImplementation.ROLES.get(i).id, "componentDecisionIds", List.of(List.of("CD-002"), List.of("CD-004"), List.of("CD-006", "CD-007"), List.of("CD-008")).get(i),
                "prerequisiteStepIds", i == 0 ? List.of() : i == 1 ? List.of("STEP-001") : i == 2 ? List.of("STEP-001", "STEP-002") : List.of("STEP-002", "STEP-003")));
        p.put("implementationOrder", steps); return p;
    }
    Map<String,byte[]> accepted() {
        var a = new LinkedHashMap<String,byte[]>(); a.put("OPERATION_REQUIREMENT", exact(OperationRequirement.normalize(input)));
        a.put("TARGET_ANALYSIS", exact(analysis)); a.put("OPERATION_PLAN", exact(plan(a))); return a;
    }
    byte[] policy(Map<String,byte[]> accepted) {
        var plan = Json.parse(MigrationInput.utf8(accepted.get("OPERATION_PLAN"))); var files = new ArrayList<Map<String,Object>>();
        for (Object item : (List<?>)plan.get("componentDecisions")) {
            var c = map(item); if (c.get("decision").equals("REUSE")) continue;
            files.add(Json.object("componentDecisionId", c.get("id"), "path", c.get("targetPath"), "expectedText", approved.get(c.get("targetPath")),
                    "obligationsFingerprint", Json.fingerprint("OPERATION_COMPONENT_OBLIGATIONS", Json.bytes(OperationImplementation.obligations(plan, c)))));
        }
        return Json.bytes(Json.object("authorityVersion", 1, "authorityKind", "NEW_OPERATION_EXACT_FILES_V1", "lineage", OperationImplementation.lineage(input, accepted), "files", files));
    }
    Map<String,Object> answer(Map<String,Object> turn) {
        String role = (String)map(turn.get("binding")).get("role"); var d = data(turn);
        if (role.equals("MASTER")) return envelope(turn, "DECISION", null, null, null, d.get("proposedDecision"), List.of());
        if (role.equals("00r-requirement-analysis")) return artifact(turn, "OPERATION_REQUIREMENT", OperationRequirement.normalize(input));
        if (role.equals("01-target-analysis")) {
            var operations = new ArrayList<List<String>>();
            for (String path : evidenceIds.keySet()) if (!original.containsKey(path)) operations.add(List.of("LIST_TARGET_PATHS", path));
            for (String path : original.keySet()) operations.add(List.of("READ_TARGET_TEXT", path));
            int count = ((List<?>)d.get("toolResults")).size();
            if (count < operations.size()) return envelope(turn, "TOOL_REQUEST", null, null, tool(turn, "TARGET", operations.get(count).get(0), operations.get(count).get(1)), null, List.of());
            return artifact(turn, "TARGET_ANALYSIS", analysis);
        }
        if (role.equals("02r-operation-planning")) {
            var a = new LinkedHashMap<String,byte[]>();
            for (Object item : (List<?>)d.get("acceptedArtifacts")) { var v = map(item); a.put((String)v.get("role"), ((String)v.get("exactText")).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            return artifact(turn, "OPERATION_PLAN", plan(a));
        }
        if (role.equals("07-validation")) return artifact(turn, "VALIDATION_RESULT", map(d.get("resultContract")));
        var grants = (List<?>)d.get("mutationGrants"); int used = ((List<?>)d.get("toolResults")).size();
        if (used < grants.size()) {
            var g = map(grants.get(used)); String path = (String)g.get("pathKey");
            return envelope(turn, "TOOL_REQUEST", null, null, replace(tool(turn, "TARGET", g.get("action").equals("CREATE") ? "CREATE_TARGET_FILE" : "WRITE_TARGET_TEXT", path),
                    "grantId", g.get("grantId"), "expectedBeforeFingerprint", map(g.get("expectedBeforeState")).get("contentFingerprint"), "content", approved.get(path)), null, List.of());
        }
        var dispatch = map(d.get("dispatch")); var assignment = map(d.get("assignment"));
        return artifact(turn, (String)d.get("artifactRole"), Json.object("implementationVersion", 1, "workflow", "NEW_OPERATION", "agent", role, "status", "SUCCESS",
                "lineage", d.get("lineage"), "runAuthorityBundleFingerprint", dispatch.get("runAuthorityBundleFingerprint"), "dispatchFingerprint", d.get("dispatchFingerprint"),
                "predecessorAcceptance", d.get("predecessorAcceptance"), "assignmentFingerprint", Json.fingerprint("NEW_OPERATION_STAGE_ASSIGNMENT", Json.bytes(assignment)),
                "completedComponentDecisionIds", ((List<?>)assignment.get("componentDecisions")).stream().map(RuntimeTest::map).map(c -> c.get("id")).toList(),
                "changedFiles", grants.stream().map(RuntimeTest::map).map(g -> g.get("pathKey")).toList(), "grantEffects", d.get("observedGrantEffects")));
    }
}
