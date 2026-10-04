package com.koldera.pdf.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real veraPDF CLI. Requires {@code VERAPDF_CMD} (path to the launcher) or a {@code
 * verapdf} on PATH; fails, never skips, when the binary is missing so a green {@code mvn verify}
 * always means the real validator ran.
 */
class VeraPdfCliValidatorIT {

  @TempDir Path tmp;

  @Test
  void untaggedFixtureFailsExplicitUa1WithNonEmptyMachineReport() throws IOException {
    VeraPdfCliValidator validator = VeraPdfCliValidator.fromEnvironment();
    Path pdf = SyntheticPdfFactory.write(tmp.resolve("scratch/candidate.pdf"), SyntheticPdfFactory.simpleUntagged());
    Path report = tmp.resolve("out/baseline-report.json");

    ValidationOutcome outcome = validator.validate(pdf, report, Duration.ofSeconds(120));

    assertEquals(ValidationStatus.FAILED, outcome.status(), outcome.errorMessage());
    assertFalse(outcome.failedRuleCounts().isEmpty());
    assertTrue(outcome.failedRuleCounts().containsKey("ISO 14289-1:2014 7.1-11"), "StructTreeRoot rule: " + outcome.failedRuleCounts());
    assertTrue(outcome.failedRuleCounts().containsKey("ISO 14289-1:2014 7.2-34"), "Lang rule: " + outcome.failedRuleCounts());
    assertTrue(outcome.failedRuleCounts().containsKey("ISO 14289-1:2014 7.1-10"), "DisplayDocTitle rule: " + outcome.failedRuleCounts());
    assertNotNull(outcome.validatorVersion());
    assertTrue(Files.size(report) > 1000, "raw report persisted");
    String raw = Files.readString(report);
    assertTrue(raw.contains("PDF/UA-1"), "explicit ua1 profile in raw report");
    assertTrue(raw.contains("\"" + VeraPdfCliValidator.PASS_FLAG_FIELD + "\":false") || raw.contains("\"" + VeraPdfCliValidator.PASS_FLAG_FIELD + "\" : false"));
  }

  @Test
  void languageAndTitleFixtureNoLongerFailsThoseTwoRules() throws IOException {
    VeraPdfCliValidator validator = VeraPdfCliValidator.fromEnvironment();
    byte[] bytes =
        SyntheticPdfFactory.build(
            SyntheticPdfFactory.Options.plain().language("en-US").infoTitle("Fixture Title").displayDocTitle(true));
    Path pdf = SyntheticPdfFactory.write(tmp.resolve("scratch/candidate.pdf"), bytes);

    ValidationOutcome outcome = validator.validate(pdf, tmp.resolve("out/r.json"), Duration.ofSeconds(120));

    assertEquals(ValidationStatus.FAILED, outcome.status(), "still untagged, still fails other rules");
    assertFalse(outcome.failedRuleCounts().containsKey("ISO 14289-1:2014 7.2-34"), outcome.failedRuleCounts().toString());
    assertFalse(outcome.failedRuleCounts().containsKey("ISO 14289-1:2014 7.1-10"), outcome.failedRuleCounts().toString());
  }

  @Test
  void corruptInputIsValidatorErrorNotPass() throws IOException {
    VeraPdfCliValidator validator = VeraPdfCliValidator.fromEnvironment();
    Path pdf = SyntheticPdfFactory.write(tmp.resolve("scratch/candidate.pdf"), SyntheticPdfFactory.corrupt());

    ValidationOutcome outcome = validator.validate(pdf, tmp.resolve("out/r.json"), Duration.ofSeconds(120));

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertEquals(ErrorCode.VALIDATOR_ERROR, outcome.errorCode());
  }

  @Test
  void missingBinaryIsUnavailable() throws IOException {
    VeraPdfCliValidator validator =
        new VeraPdfCliValidator(List.of(tmp.resolve("no-such-verapdf").toString()), new DefaultProcessRunner());
    Path pdf = SyntheticPdfFactory.write(tmp.resolve("scratch/candidate.pdf"), SyntheticPdfFactory.simpleUntagged());

    ValidationOutcome outcome = validator.validate(pdf, tmp.resolve("out/r.json"), Duration.ofSeconds(30));

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertEquals(ErrorCode.VALIDATOR_UNAVAILABLE, outcome.errorCode());
  }
}
