package com.koldera.pdf.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** Wire shape of {@code contracts/pdf-reorder-result.schema.json}. */
public record ReorderResult(
    @JsonProperty("specVersion") String specVersion,
    @JsonProperty("jobId") UUID jobId,
    @JsonProperty("status") Status status,
    @JsonProperty("engineVersion") String engineVersion,
    @JsonProperty("baseSha256") String baseSha256,
    @JsonProperty("outputSha256") String outputSha256,
    @JsonProperty("outputBytes") long outputBytes,
    @JsonProperty("checks") Checks checks,
    @JsonProperty("appliedRoles") List<String> appliedRoles,
    @JsonProperty("error") Error error) {

  public static final String SPEC_VERSION = "1.0";

  public enum Status {
    @JsonProperty("applied")
    APPLIED,
    @JsonProperty("unsupported")
    UNSUPPORTED,
    @JsonProperty("failed")
    FAILED
  }

  public enum ErrorCode {
    INVALID_INPUT,
    ORDER_FIX_UNSUPPORTED,
    INTEGRITY_REGRESSION,
    UNSUPPORTED_SIGNED_MUTATION,
    UNSUPPORTED_ENCRYPTED,
    LIMIT_EXCEEDED,
    WORKER_ERROR
  }

  public record Error(@JsonProperty("code") ErrorCode code, @JsonProperty("message") String message) {}

  public record Checks(
      @JsonProperty("pageCountUnchanged") Boolean pageCountUnchanged,
      @JsonProperty("contentStreamsUnchanged") Boolean contentStreamsUnchanged,
      @JsonProperty("textTokensUnchanged") Boolean textTokensUnchanged,
      @JsonProperty("annotationsUnchanged") Boolean annotationsUnchanged,
      @JsonProperty("mcidInventoryUnchanged") Boolean mcidInventoryUnchanged,
      @JsonProperty("parentTreeUnchanged") Boolean parentTreeUnchanged,
      @JsonProperty("renderIdentical") Boolean renderIdentical,
      @JsonProperty("sequenceMatchesApproved") Boolean sequenceMatchesApproved,
      @JsonProperty("nodeInventoryUnchanged") Boolean nodeInventoryUnchanged) {

    public static Checks none() {
      return new Checks(null, null, null, null, null, null, null, null, null);
    }

    public boolean allTrue() {
      return Boolean.TRUE.equals(pageCountUnchanged) && Boolean.TRUE.equals(contentStreamsUnchanged) && Boolean.TRUE.equals(textTokensUnchanged)
          && Boolean.TRUE.equals(annotationsUnchanged) && Boolean.TRUE.equals(mcidInventoryUnchanged) && Boolean.TRUE.equals(parentTreeUnchanged)
          && Boolean.TRUE.equals(renderIdentical) && Boolean.TRUE.equals(sequenceMatchesApproved) && Boolean.TRUE.equals(nodeInventoryUnchanged);
    }
  }

  public static ReorderResult failed(UUID jobId, String engine, String baseSha256, ErrorCode code, String message, Checks checks) {
    Status status = code == ErrorCode.ORDER_FIX_UNSUPPORTED ? Status.UNSUPPORTED : Status.FAILED;
    return new ReorderResult(SPEC_VERSION, jobId, status, engine, baseSha256, null, 0, checks == null ? Checks.none() : checks, List.of(), new Error(code, message));
  }
}
