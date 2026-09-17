package dev.agentic.harness;

import java.util.Map;

/** Low-level Responses-specific transport seam. A mock implementation is not runtime provider attestation. */
public interface ResponsesClient {
    String create(String exactRequestBody) throws Exception;
    String retrieve(String responseId) throws Exception;
    String inputItems(String responseId, String after) throws Exception;
    Map<String, Object> configuration();
    void rejectCredentialMaterial(String text);
}
