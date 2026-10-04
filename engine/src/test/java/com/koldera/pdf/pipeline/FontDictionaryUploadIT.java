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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An untagged upload whose LiberationSans subset carries a {@code CIDSet} whose first byte is cleared
 * (so it misses CIDs present in the program), through the production rules and the real veraPDF:
 * 7.21.4.2-2 fails in the baseline; neither 7.21.4.2-2 nor 7.21.3.2-1 fails in the copy.
 */
class FontDictionaryUploadIT {

  @TempDir Path root;

  private static byte[] corruptedCidSet() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0Subset(false)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDResources resources = doc.getPage(0).getResources();
      COSDictionary type0 = resources.getFont(resources.getFontNames().iterator().next()).getCOSObject();
      COSDictionary cidFont = (COSDictionary) ((COSArray) type0.getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
      COSStream cidSet = (COSStream) ((COSDictionary) cidFont.getDictionaryObject(COSName.FONT_DESC)).getDictionaryObject(COSName.getPDFName("CIDSet"));
      byte[] bits;
      try (InputStream in = cidSet.createInputStream()) {
        bits = in.readAllBytes();
      }
      bits[0] = 0;
      try (OutputStream o = cidSet.createOutputStream(COSName.FLATE_DECODE)) {
        o.write(bits);
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void aCorruptedCidSetNoLongerFails7_21_4_2_2() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), corruptedCidSet());
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Subset", "subset.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertTrue(result.baseline().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.21.4.2-2")), context);
    WorkerResult.Action font = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_FONT_DICTIONARY_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, font.outcome(), context);
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.21.4.2-2") || r.endsWith(" 7.21.3.2-1")), context);
  }
}
