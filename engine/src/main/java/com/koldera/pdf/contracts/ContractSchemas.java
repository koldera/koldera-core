package com.koldera.pdf.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON Schema (draft 2020-12) validation for the worker and inspect contracts. The schema files are the
 * ones from the repository {@code contracts/} directory, copied verbatim onto the classpath.
 */
public final class ContractSchemas {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final JsonSchema REQUEST = load("/contracts/pdf-worker-request.schema.json");
  private static final JsonSchema RESULT = load("/contracts/pdf-worker-result.schema.json");
  private static final JsonSchema INSPECT = load("/contracts/pdf-inspect-result.schema.json");
  private static final JsonSchema STRUCTURE = load("/contracts/pdf-structure-result.schema.json");
  private static final JsonSchema REORDER_REQUEST = load("/contracts/pdf-reorder-request.schema.json");
  private static final JsonSchema REORDER_RESULT = load("/contracts/pdf-reorder-result.schema.json");

  private ContractSchemas() {}

  /** Returns schema violations for a request document; empty when valid. */
  public static Set<String> validateRequest(String json) {
    return validate(REQUEST, json);
  }

  /** Returns schema violations for a result document; empty when valid. */
  public static Set<String> validateResult(String json) {
    return validate(RESULT, json);
  }

  /** Returns schema violations for an {@code inspect} result document; empty when valid. */
  public static Set<String> validateInspectResult(String json) {
    return validate(INSPECT, json);
  }

  /** Returns schema violations for a {@code structure} result document; empty when valid. */
  public static Set<String> validateStructureResult(String json) {
    return validate(STRUCTURE, json);
  }

  public static Set<String> validateReorderRequest(String json) {
    return validate(REORDER_REQUEST, json);
  }

  public static Set<String> validateReorderResult(String json) {
    return validate(REORDER_RESULT, json);
  }

  static Set<String> validateRequest(JsonNode node) {
    return toMessages(REQUEST.validate(node));
  }

  private static Set<String> validate(JsonSchema schema, String json) {
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (IOException e) {
      return Set.of("document is not valid JSON: " + e.getMessage());
    }
    if (node == null) {
      return Set.of("document is empty");
    }
    return toMessages(schema.validate(node));
  }

  private static Set<String> toMessages(Set<ValidationMessage> messages) {
    return messages.stream()
        .map(ValidationMessage::getMessage)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static JsonSchema load(String resource) {
    SchemaValidatorsConfig config =
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    try (InputStream in = ContractSchemas.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing contract schema resource " + resource);
      }
      return factory.getSchema(in, config);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read contract schema " + resource, e);
    }
  }
}
