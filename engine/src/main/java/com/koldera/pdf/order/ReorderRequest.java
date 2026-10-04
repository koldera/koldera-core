package com.koldera.pdf.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** Wire shape of {@code contracts/pdf-reorder-request.schema.json}. */
public record ReorderRequest(
    @JsonProperty("specVersion") String specVersion,
    @JsonProperty("jobId") UUID jobId,
    @JsonProperty("inputPath") String inputPath,
    @JsonProperty("outputPath") String outputPath,
    @JsonProperty("baseSha256") String baseSha256,
    @JsonProperty("parentPath") List<Integer> parentPath,
    @JsonProperty("orderedKidIndexes") List<Integer> orderedKidIndexes,
    @JsonProperty("limits") Limits limits) {

  public static final String SPEC_VERSION = "1.0";
  public static final String CANONICAL_INPUT = "/work/in/document.pdf";
  public static final String CANONICAL_OUTPUT = "/work/out/reordered.pdf";

  public record Limits(@JsonProperty("maxBytes") long maxBytes, @JsonProperty("maxPages") int maxPages, @JsonProperty("timeoutSeconds") int timeoutSeconds) {}
}
