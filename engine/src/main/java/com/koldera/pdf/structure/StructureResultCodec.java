package com.koldera.pdf.structure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** JSON codec for {@link StructureResult}. */
public final class StructureResultCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private StructureResultCodec() {}

  public static String toJson(StructureResult result) {
    try {
      return MAPPER.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize structure result", e);
    }
  }

  public static StructureResult fromJson(String json) {
    try {
      return MAPPER.readValue(json, StructureResult.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot parse structure result: " + e.getMessage(), e);
    }
  }
}
