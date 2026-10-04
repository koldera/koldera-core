package com.koldera.pdf.inspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** JSON codec for {@link InspectResult}; same conventions as the worker result codec. */
public final class InspectResultCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private InspectResultCodec() {}

  public static String toJson(InspectResult result) {
    try {
      return MAPPER.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize inspect result", e);
    }
  }
}
