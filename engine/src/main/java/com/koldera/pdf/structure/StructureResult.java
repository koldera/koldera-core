package com.koldera.pdf.structure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire shape of {@code contracts/pdf-structure-result.schema.json}: the structure tree in its
 * stored order with verified marked-content references, per-page invariants and the annotation
 * (focus) sequence. Evidence for a human reviewer and for the order-repair gate — no verdict.
 */
public record StructureResult(
    @JsonProperty("specVersion") String specVersion,
    @JsonProperty("status") Status status,
    @JsonProperty("sha256") String sha256,
    @JsonProperty("bytes") long bytes,
    @JsonProperty("engineVersion") String engineVersion,
    @JsonProperty("document") Document document,
    @JsonProperty("nodes") List<StructureNode> nodes,
    @JsonProperty("focusOrderNodeIds") List<String> focusOrderNodeIds,
    @JsonProperty("focusOrderMeasured") boolean focusOrderMeasured,
    @JsonProperty("findings") List<Finding> findings,
    @JsonProperty("error") Error error) {

  public static final String SPEC_VERSION = "1.0";

  public enum Status {
    @JsonProperty("inspected")
    INSPECTED,
    @JsonProperty("failed")
    FAILED
  }

  public enum ErrorCode {
    INVALID_INPUT,
    UNSUPPORTED_ENCRYPTED,
    LIMIT_EXCEEDED,
    WORKER_ERROR
  }

  public record Error(@JsonProperty("code") ErrorCode code, @JsonProperty("message") String message) {}

  public record Finding(@JsonProperty("code") String code, @JsonProperty("message") String message, @JsonProperty("nodeIds") List<String> nodeIds) {}

  public record Document(
      @JsonProperty("pages") int pages,
      @JsonProperty("usableTags") boolean usableTags,
      @JsonProperty("signed") boolean signed,
      @JsonProperty("encrypted") boolean encrypted,
      @JsonProperty("hasAcroForm") boolean hasAcroForm,
      @JsonProperty("annotations") int annotations,
      @JsonProperty("pageDigests") List<PageDigest> pageDigests) {}

  public static StructureResult failed(String sha256, long bytes, String engineVersion, ErrorCode code, String message) {
    return new StructureResult(SPEC_VERSION, Status.FAILED, sha256, Math.max(0, bytes), engineVersion, null, List.of(), List.of(), false, List.of(), new Error(code, message));
  }
}
