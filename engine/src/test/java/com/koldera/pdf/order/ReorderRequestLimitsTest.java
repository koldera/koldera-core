package com.koldera.pdf.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.ContractSchemas;
import org.junit.jupiter.api.Test;

/** {@code limits} is optional in the reorder request too; an absent limit is not enforced. */
class ReorderRequestLimitsTest {

  private static final String HEAD =
      """
      {"specVersion":"1.0","jobId":"123e4567-e89b-42d3-a456-426614174000","inputPath":"/work/in/document.pdf",\
      "outputPath":"/work/out/reordered.pdf","baseSha256":"%s","parentPath":[0],"orderedKidIndexes":[1,0]\
      """
          .formatted("0".repeat(64));

  @Test
  void aRequestWithoutLimitsIsValidAndResolvesToUnbounded() {
    String json = HEAD + "}";
    assertTrue(ContractSchemas.validateReorderRequest(json).isEmpty());

    ReorderRequest request = ReorderResultCodec.requestFromJson(json);

    assertEquals(Long.MAX_VALUE, request.limits().maxBytes());
    assertEquals(Integer.MAX_VALUE, request.limits().maxPages());
    assertEquals(Integer.MAX_VALUE, request.limits().timeoutSeconds());
  }

  @Test
  void aPartialLimitsObjectKeepsWhatItNames() {
    ReorderRequest request = ReorderResultCodec.requestFromJson(HEAD + ",\"limits\":{\"maxPages\":2}}");

    assertEquals(Long.MAX_VALUE, request.limits().maxBytes());
    assertEquals(2, request.limits().maxPages());
  }
}
