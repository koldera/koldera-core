package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.inference.StructureInferenceRule;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Through the production rules and the real veraPDF: a {@code tagged_unusable} upload whose Span, Formula and paragraph carry
 * {@code /ActualText}, {@code /Alt} and {@code /Lang} is rebuilt with the descriptions kept as marked-content properties, and
 * is published although a kept soft-hyphen {@code /ActualText} changes the extracted text (the glyph text is identical).
 */
class DescribedTextUploadIT {

  @TempDir Path root;

  @Test
  void descriptionsOfOtherElementsSurviveTheRebuildOfUnusableTags() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    byte[] input = SyntheticPdfFactory.taggedUnusableWithDescribedText(false);
    Files.write(workspace.inputFile(), input);
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174001"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Described", "described.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, inferred.outcome(), context);
    assertTrue(inferred.reason().endsWith("; descriptions of 4 elements" + StructureInferenceRule.KEPT_SUFFIX), inferred.reason());
    try (PDDocument original = Loader.loadPDF(input); PDDocument copy = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      assertEquals(PdfPreflight.extractGlyphText(original), PdfPreflight.extractGlyphText(copy), "the glyph text is identical");
      // The kept soft hyphen replaces the painted "-" for a reader (the text stripper, like a reader, does not show it).
      assertTrue(PdfPreflight.extractText(original).contains("A regu-"), "the original extracts the painted hyphen");
      assertTrue(!PdfPreflight.extractText(copy).contains("A regu-"), "the copy extracts the kept soft hyphen instead");
    }
  }
}
