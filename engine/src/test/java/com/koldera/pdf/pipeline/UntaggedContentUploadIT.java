package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.FailedRule;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tagged uploads through the production rules and the real veraPDF: untagged painting leaves no
 * 7.1-3, an artifact inside tagged content leaves no 7.1-1/7.1-2 ({@code PDF_UNTAGGED_CONTENT_V1}).
 */
class UntaggedContentUploadIT {

  @TempDir Path root;

  @Test
  void untaggedRulingLinesLeaveNoUntaggedContentCheck() throws Exception {
    WorkerResult result = run(SyntheticPdfFactory.taggedWithUntaggedRules());

    String context = context(result);
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertEquals(ActionOutcome.APPLIED, action(result).outcome(), context);
    assertTrue(result.baseline().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.1-3")), "the fixture fails 7.1-3: " + context);
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.1-3")), "7.1-3 still fails: " + context);
  }

  @Test
  void anArtifactInsideTaggedContentLeavesNoNestingCheck() throws Exception {
    WorkerResult result = run(nested());

    String context = context(result);
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertEquals(ActionOutcome.APPLIED, action(result).outcome(), context);
    assertTrue(result.baseline().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.1-1") || r.endsWith(" 7.1-2")),
        "the fixture fails the nesting checks: " + context);
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.1-1") || r.endsWith(" 7.1-2")),
        "a nesting check still fails: " + context);
  }

  private WorkerResult run(byte[] input) throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), input);
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Report", "report.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));
    return new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);
  }

  /** {@link SyntheticPdfFactory#tagged()} with the paragraph's content replaced by text, an artifact rule, then text, all under MCID 1. */
  private static byte[] nested() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = doc.getPage(0);
      String font = page.getResources().getFontNames().iterator().next().getName();
      String content = "/H1 <</MCID 0>> BDC BT /" + font + " 18 Tf 72 720 Td (Synthetic heading) Tj ET EMC"
          + " /P <</MCID 1>> BDC BT /" + font + " 12 Tf 72 680 Td (Before the rule) Tj ET /Artifact BMC 72 670 m 300 670 l S EMC"
          + " BT /" + font + " 12 Tf 72 650 Td (After the rule) Tj ET EMC";
      PDStream stream = new PDStream(doc);
      try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
        os.write(content.getBytes(StandardCharsets.ISO_8859_1));
      }
      page.setContents(stream);
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static WorkerResult.Action action(WorkerResult result) {
    return result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_UNTAGGED_CONTENT_V1).findFirst().orElseThrow();
  }

  private static String context(WorkerResult result) {
    return result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
  }
}
