package com.koldera.pdf.preflight;

import com.koldera.pdf.contracts.WorkerResult.ErrorCode;

/**
 * What preflight learned about the input before any validator or rule touches it: the typed refusal (null to proceed) with
 * a content-free reason, SHA-256 and size of the original, page count, text digest, and whether it is encrypted or signed
 * (a signature, certification or DocMDP dictionary). Unreadable values are null or -1.
 */
public record PreflightOutcome(
    ErrorCode rejectCode,
    String reason,
    String sha256,
    long bytes,
    Integer pageCount,
    String textDigest,
    boolean encrypted,
    boolean signed) {

  public boolean accepted() {
    return rejectCode == null;
  }

  static PreflightOutcome rejected(ErrorCode code, String reason, String sha256, long bytes) {
    return new PreflightOutcome(code, reason, sha256, bytes, null, null, false, false);
  }
}
