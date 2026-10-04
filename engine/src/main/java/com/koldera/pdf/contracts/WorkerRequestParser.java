package com.koldera.pdf.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Set;

/**
 * Parses and validates a worker request. Validation is schema-first (unknown properties, enum
 * values, BCP 47 syntax, limits) followed by strict Jackson mapping, so a request that is not
 * exactly what the contract allows never reaches the pipeline.
 */
public final class WorkerRequestParser {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .build();

  private WorkerRequestParser() {}

  public static WorkerRequest parse(String json) {
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (IOException e) {
      throw new RequestValidationException("request is not valid JSON: " + e.getMessage(), e);
    }
    if (node == null || !node.isObject()) {
      throw new RequestValidationException("request must be a JSON object");
    }
    Set<String> violations = ContractSchemas.validateRequest(node);
    if (!violations.isEmpty()) {
      throw new RequestValidationException(
          "request violates schema: " + String.join("; ", violations));
    }
    WorkerRequest request;
    try {
      request = MAPPER.treeToValue(node, WorkerRequest.class);
    } catch (IOException e) {
      throw new RequestValidationException("request cannot be mapped: " + e.getMessage(), e);
    }
    // The schema leaves `origin` optional; Jackson maps an absent property to null, so default it
    // here rather than in the record itself.
    if (request.origin() == null) {
      request =
          new WorkerRequest(
              request.specVersion(),
              request.jobId(),
              request.inputPath(),
              request.outputDirectory(),
              request.mode(),
              request.profile(),
              request.explicitLanguage(),
              request.explicitTitle(),
              request.sourceFileName(),
              WorkerRequest.Origin.UPLOAD,
              request.limits());
    }
    // Defence in depth: the schema pins both paths with `const`; re-check after mapping so a
    // schema edit can never widen the mounted job root silently.
    if (!WorkerRequest.CANONICAL_INPUT_PATH.equals(request.inputPath())) {
      throw new RequestValidationException(
          "inputPath must be exactly " + WorkerRequest.CANONICAL_INPUT_PATH);
    }
    if (!WorkerRequest.CANONICAL_OUTPUT_DIRECTORY.equals(request.outputDirectory())) {
      throw new RequestValidationException(
          "outputDirectory must be exactly " + WorkerRequest.CANONICAL_OUTPUT_DIRECTORY);
    }
    return request;
  }
}
