package com.koldera.pdf.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

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
      return MAPPER.readValue(json, ReorderRequest.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot parse reorder request: " + e.getMessage(), e);
    }
  }
}
