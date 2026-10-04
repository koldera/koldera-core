package com.koldera.pdf.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Deterministic JSON encoding of {@link WorkerResult}: fixed record field order, pretty printed. */
public final class WorkerResultCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private WorkerResultCodec() {}

  public static String toJson(WorkerResult result) {
    try {
      return MAPPER.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize worker result", e);
    }
  }

  public static WorkerResult fromJson(String json) {
    try {
      return MAPPER.readValue(json, WorkerResult.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot parse worker result: " + e.getMessage(), e);
    }
  }
}
