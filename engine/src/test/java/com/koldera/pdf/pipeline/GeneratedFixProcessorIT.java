package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.FailedRule;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.report.FindingsBuilder;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code fix} run over each Chromium-generated fixture with the production rules and the real
 * veraPDF: the generated-structure and XMP rules apply, the no-increase gate passes, the only
 * check still failing is {@code 5-1}, so the identifier step writes {@code pdfuaid:part=1} and the
 * identified copy validates with zero failures.
 */
class GeneratedFixProcessorIT {

  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(strings = {"generated-lists.pdf", "generated-tables.pdf", "generated-figures.pdf", "generated-links.pdf", "generated-annual-report.pdf",
    "generated-untagged-text.pdf", "generated-forms.pdf", "generated-inline-blocks.pdf",
    "generated-irregular-tables.pdf", "generated-containers.pdf",
    "generated-headings.pdf", "generated-shaded-tables.pdf", "generated-link-runs.pdf", "generated-controls.pdf"})
  void aGeneratedPdfIsNormalizedAndDeclaresPdfUaOneOnceMachineChecksAreClean(String fixture) throws IOException {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    try (InputStream in = GeneratedFixProcessorIT.class.getResourceAsStream("/generated/" + fixture)) {
      assertNotNull(in, fixture);
      Files.write(workspace.inputFile(), in.readAllBytes());
    }
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", null, null, "page.pdf",
        WorkerRequest.Origin.HTML_GENERATED, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = fixture + ": " + result.error() + " baseline=" + counts(result.baseline().failedRules())
        + " final=" + (result.finalValidation() == null ? "none" : counts(result.finalValidation().failedRules())) + " actions=" + result.actions();
    assertEquals(Status.COMPLETED, result.status(), context);
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertEquals(ActionOutcome.APPLIED, outcome(result, RuleId.PDF_GENERATED_STRUCTURE_V1), context);
    assertEquals(ActionOutcome.APPLIED, outcome(result, RuleId.PDF_XMP_METADATA_FROM_INFO_V1), context);
    assertEquals(ActionOutcome.APPLIED, outcome(result, RuleId.PDF_UA_IDENTIFIER_V1), context);
    assertEquals(ValidationStatus.PASSED, result.finalValidation().status(), "final veraPDF validation of the identified copy: " + context);
    assertEquals(List.of(), rules(result.finalValidation().failedRules()), context);
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals(FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY) && f.needsHumanReview()), context);
    assertTrue(result.manualReviewRequired());
    assertTrue(Files.exists(workspace.outputFile("remediated.pdf")), context);
    try (PDDocument doc = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertTrue(xmp.contains("http://www.aiim.org/pdfua/ns/id/"), xmp);
    }
  }

  private static List<String> rules(List<FailedRule> failed) {
    return failed.stream().map(FailedRule::ruleId).toList();
  }

  private static String counts(List<FailedRule> failed) {
    return failed.stream().map(r -> r.ruleId() + "=" + r.count()).toList().toString();
  }

  private static ActionOutcome outcome(WorkerResult result, RuleId id) {
    return result.actions().stream().filter(a -> a.ruleId() == id).map(WorkerResult.Action::outcome).findFirst().orElse(null);
  }
}
