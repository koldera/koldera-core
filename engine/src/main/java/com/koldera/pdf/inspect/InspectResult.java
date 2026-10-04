package com.koldera.pdf.inspect;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire shape of {@code contracts/pdf-inspect-result.schema.json}: what a PDF declares and contains
 * in terms of logical structure. Evidence only — the caller decides what to do with it, and
 * nothing here is a PDF/UA conformance statement.
 */
public record InspectResult(
    @JsonProperty("specVersion") String specVersion,
    @JsonProperty("status") Status status,
    @JsonProperty("sha256") String sha256,
    @JsonProperty("bytes") long bytes,
    @JsonProperty("engineVersion") String engineVersion,
    @JsonProperty("structure") Structure structure,
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

  public record RoleCount(@JsonProperty("role") String role, @JsonProperty("count") int count) {}

  /**
   * @param usableTags the minimum for the file to count as tagged at all: marked, a structure tree
   *     root, at least one structure element and at least one content reference
   */
  public record Structure(
      @JsonProperty("pages") int pages,
      @JsonProperty("encrypted") boolean encrypted,
      @JsonProperty("markInfoMarked") boolean markInfoMarked,
      @JsonProperty("structTreeRoot") boolean structTreeRoot,
      @JsonProperty("structureElements") int structureElements,
      @JsonProperty("contentReferences") int contentReferences,
      @JsonProperty("usableTags") boolean usableTags,
      @JsonProperty("roles") List<RoleCount> roles,
      @JsonProperty("language") String language,
      @JsonProperty("title") String title) {}

  public static InspectResult failed(String sha256, long bytes, String engineVersion, ErrorCode code, String message) {
    return new InspectResult(SPEC_VERSION, Status.FAILED, sha256, Math.max(0, bytes), engineVersion, null, new Error(code, message));
  }

  public static InspectResult inspected(String sha256, long bytes, String engineVersion, Structure structure) {
    return new InspectResult(SPEC_VERSION, Status.INSPECTED, sha256, bytes, engineVersion, structure, null);
  }
}
