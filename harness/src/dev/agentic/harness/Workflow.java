package dev.agentic.harness;

import java.util.*;

/** Selected only from host-supplied configuration, never from requirement text. */
public enum Workflow {
    MIGRATION, NEW_OPERATION;

    static Workflow read(Map<String,Object> config) {
        if (!config.containsKey("workflow")) return MIGRATION;
        Object value = config.get("workflow");
        for (Workflow workflow : values()) if (workflow.name().equals(value)) return workflow;
        throw new IllegalArgumentException("UNSUPPORTED_WORKFLOW");
    }

    List<TrustedInputs.Role> roles() {
        return Arrays.stream(TrustedInputs.Role.values()).filter(role -> this == MIGRATION
                ? role != TrustedInputs.Role.REQUIREMENT_ANALYSIS && role != TrustedInputs.Role.OPERATION_PLANNING
                : role != TrustedInputs.Role.SOURCE_ANALYSIS && role != TrustedInputs.Role.PLANNING).toList();
    }

    public List<String> route() {
        return List.of(this == MIGRATION ? "00-source-analysis" : "00r-requirement-analysis",
                "01-target-analysis", this == MIGRATION ? "02-migration-planning" : "02r-operation-planning",
                "03-domain-contract-implementation", "04-persistence-mapping-implementation",
                "05-service-api-implementation", "06-test-implementation", "07-validation");
    }
}
