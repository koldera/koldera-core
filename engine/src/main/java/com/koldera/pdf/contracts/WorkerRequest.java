package com.koldera.pdf.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Worker input, mirroring {@code contracts/pdf-worker-request.schema.json}.
 *
 * <p>Instances are only created through {@link WorkerRequestParser}, which validates the JSON
 * against the schema before mapping; the record itself carries no validation logic.
 */
public record WorkerRequest(
    String specVersion,
    UUID jobId,
    String inputPath,
    String outputDirectory,
    Mode mode,
    String profile,
    String explicitLanguage,
    String explicitTitle,
    String sourceFileName,
    Origin origin,
    Limits limits,
    Boolean declarePdfUa,
    Boolean humanReviewAcknowledged) {

  /** Canonical container path of the immutable input document. */
  public static final String CANONICAL_INPUT_PATH = "/work/in/document.pdf";

  /** Canonical container path of the per-job writable output directory. */
  public static final String CANONICAL_OUTPUT_DIRECTORY = "/work/out";

  /** Pre-{@code declarePdfUa} shape: no PDF/UA declaration requested. */
  public WorkerRequest(
      String specVersion,
      UUID jobId,
      String inputPath,
      String outputDirectory,
      Mode mode,
      String profile,
      String explicitLanguage,
      String explicitTitle,
      String sourceFileName,
      Origin origin,
      Limits limits) {
    this(specVersion, jobId, inputPath, outputDirectory, mode, profile, explicitLanguage, explicitTitle, sourceFileName, origin, limits, null, null);
  }

  /** Pre-{@code origin} shape; kept so callers and tests that predate it still compile. */
  public WorkerRequest(
      String specVersion,
      UUID jobId,
      String inputPath,
      String outputDirectory,
      Mode mode,
      String profile,
      String explicitLanguage,
      String explicitTitle,
      String sourceFileName,
      Limits limits) {
    this(specVersion, jobId, inputPath, outputDirectory, mode, profile, explicitLanguage, explicitTitle, sourceFileName, Origin.UPLOAD, limits);
  }

  /** Pre-A1 shape (no title, no file name); kept so callers and tests that predate {@code fix} still compile. */
  public WorkerRequest(
      String specVersion,
      UUID jobId,
      String inputPath,
      String outputDirectory,
      Mode mode,
      String profile,
      String explicitLanguage,
      Limits limits) {
    this(specVersion, jobId, inputPath, outputDirectory, mode, profile, explicitLanguage, null, null, Origin.UPLOAD, limits);
  }

  public enum Mode {
    @JsonProperty("analyze")
    ANALYZE,
    /** Everything the engine applies safely on a copy. */
    @JsonProperty("fix")
    FIX
  }

  /** Where the PDF being processed came from: an upload, or a PDF generated from HTML. */
  public enum Origin {
    @JsonProperty("upload")
    UPLOAD,
    @JsonProperty("html_generated")
    HTML_GENERATED
  }

  /** Resolved limits: a limit the request left out is {@code Long.MAX_VALUE} / {@code Integer.MAX_VALUE} (effectively unlimited). */
  public record Limits(long maxBytes, int maxPages, int timeoutSeconds) {}

  public boolean isFix() {
    return mode == Mode.FIX;
  }

  /** Structure tags (and the XMP packet that goes with them) take part in this mode. */
  public boolean addsStructure() {
    return mode == Mode.FIX;
  }

  /** Whether the mode may write a remediated copy at all (never the original). */
  public boolean isMutating() {
    return mode == Mode.FIX;
  }

  /** Whether this PDF was generated from HTML, rather than uploaded by a caller. */
  public boolean isHtmlGenerated() {
    return origin == Origin.HTML_GENERATED;
  }

  /**
   * The caller asks for {@code pdfuaid:part=1} and states that a person reviewed the document. Both are required; the
   * identifier step still refuses engine-made structure, auto-artifacted figures and any remaining failure.
   */
  public boolean declaresPdfUaAfterReview() {
    return Boolean.TRUE.equals(declarePdfUa) && Boolean.TRUE.equals(humanReviewAcknowledged);
  }
}
