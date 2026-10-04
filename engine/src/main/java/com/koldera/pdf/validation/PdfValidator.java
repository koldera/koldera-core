package com.koldera.pdf.validation;

import java.nio.file.Path;
import java.time.Duration;

/** Independent machine validation of a PDF against the explicit PDF/UA-1 profile. */
public interface PdfValidator {

  /**
   * Validates {@code pdf}, persisting the validator's raw report at {@code reportPath}. Never
   * throws for validator problems; those are reported as {@code status=error} with a typed code.
   */
  ValidationOutcome validate(Path pdf, Path reportPath, Duration timeout);
}
