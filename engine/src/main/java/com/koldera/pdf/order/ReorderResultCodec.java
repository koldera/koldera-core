package com.koldera.pdf.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON codec for the reorder request and result. */
public final class ReorderResultCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private ReorderResultCodec() {}

  public static String toJson(ReorderResult result) {
    try {
      return MAPPER.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize reorder result", e);
    }
  }

  public static ReorderRequest requestFromJson(String json) {
    try {
      // `limits` and every limit in it are optional; an absent limit is not enforced.
      JsonNode tree = MAPPER.readTree(json);
      if (tree instanceof ObjectNode root) {
        ObjectNode limits = root.has("limits") && root.get("limits") instanceof ObjectNode o ? o : root.putObject("limits");
        if (!limits.has("maxBytes")) {
          limits.put("maxBytes", Long.MAX_VALUE);
        }
        if (!limits.has("maxPages")) {
          limits.put("maxPages", Integer.MAX_VALUE);
        }
        if (!limits.has("timeoutSeconds")) {
          limits.put("timeoutSeconds", Integer.MAX_VALUE);
        }
      }
      return MAPPER.treeToValue(tree, ReorderRequest.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot parse reorder request: " + e.getMessage(), e);
    }
  }
}
