package com.koldera.pdf.integrity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.integrity.CopyIntegrity.Report;
import com.koldera.pdf.structure.ContentTagger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CopyIntegrityTest {

  @TempDir Path dir;

  // ---- a clean tagged copy -------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"article", "forms", "stray", "indirect"})
  void aCopyWrittenByTheTaggerAndTheCopyWriterHasNoProblems(String fixture) throws IOException {
    byte[] input = switch (fixture) {
      case "article" -> SyntheticPdfFactory.untaggedArticle();
      case "forms" -> SyntheticPdfFactory.untaggedWithFormXObjects();
      case "stray" -> SyntheticPdfFactory.untaggedWithStrayMarkedContent();
      default -> SyntheticPdfFactory.untaggedWithIndirectSmallIntegers();
    };
    Report before;
    try (PDDocument doc = Loader.loadPDF(input)) {
      before = CopyIntegrity.inspect(doc);
    }
    Path copy = tagged(input, doc -> {});

    Report after = CopyIntegrity.inspect(copy);

    assertNull(after.parseFailure());
    assertEquals(before.problems(), after.problems(), "only what the input already had (its own indirect /Rotate and /UserUnit)");
    assertTrue(after.problems().keySet().stream().allMatch(k -> k.startsWith(CopyIntegrity.INDIRECT_NUMBER + " /Rotate") || k.startsWith(CopyIntegrity.INDIRECT_NUMBER + " /UserUnit")), after.problems().toString());
    assertNull(CopyIntegrity.regression(before, after));
  }

  // ---- parent tree -----------------------------------------------------------------------------

  @Test
  void aParentTreeEntryThatPointsAtAnElementWithoutThatMcidIsReported() throws IOException {
    Path copy = tagged(SyntheticPdfFactory.untaggedArticle(), doc -> {
      COSArray parents = pageParents(doc);
      COSBase first = parents.get(0);
      parents.set(0, parents.get(2));
      parents.set(2, first);
    });

    Map<String, Integer> problems = CopyIntegrity.inspect(copy).problems();

    assertEquals(2, problems.get(CopyIntegrity.PARENT_TREE_ENTRY_NOT_OWNED), problems.toString());
    assertEquals(2, problems.get(CopyIntegrity.MCID_WITHOUT_PARENT_TREE_ENTRY), problems.toString());
  }

  @Test
  void anEmptiedParentTreeSlotIsReportedFromBothSides() throws IOException {
    Path copy = tagged(SyntheticPdfFactory.untaggedArticle(), doc -> pageParents(doc).set(1, COSNull.NULL));

    Map<String, Integer> problems = CopyIntegrity.inspect(copy).problems();

    assertEquals(1, problems.get(CopyIntegrity.MCID_WITHOUT_PARENT_TREE_ENTRY), problems.toString());
    assertEquals(1, problems.get(CopyIntegrity.CONTENT_MCID_WITHOUT_PARENT_TREE_ENTRY), problems.toString());
  }

  @Test
  void numberTreeKeysOutOfOrderOrRepeatedAreReported() throws IOException {
    Path copy = tagged(SyntheticPdfFactory.untaggedWithRunningHeader(3), doc -> {
      COSArray nums = nums(doc);
      COSBase firstKey = nums.get(0);
      nums.set(0, nums.get(2));
      nums.set(2, firstKey);
      COSBase firstValue = nums.get(1);
      nums.set(1, nums.get(3));
      nums.set(3, firstValue);
    });

    Map<String, Integer> problems = CopyIntegrity.inspect(copy).problems();

    assertEquals(1, problems.get(CopyIntegrity.NUMBER_TREE_KEYS_UNSORTED), problems.toString());
  }

  @Test
  void aParentTreeKeyThatIsAReferenceIsReported() throws IOException {
    Path copy = tagged(SyntheticPdfFactory.untaggedArticle(), doc -> nums(doc).set(0, new COSObject(COSInteger.ZERO)));

    Map<String, Integer> problems = CopyIntegrity.inspect(copy).problems();

    assertEquals(1, problems.get(CopyIntegrity.NUMBER_TREE_KEY_NOT_INTEGER), problems.toString());
    assertEquals(1, problems.get(CopyIntegrity.INDIRECT_NUMBER + " /Nums[]"), problems.toString());
  }

  @Test
  void twoStreamsSharingOneStructParentsKeyAreReported() throws IOException {
    Path copy = tagged(SyntheticPdfFactory.untaggedWithRunningHeader(3), doc -> doc.getPage(1).getCOSObject().setInt(COSName.STRUCT_PARENTS, 0));

    Map<String, Integer> problems = CopyIntegrity.inspect(copy).problems();

    assertEquals(1, problems.get(CopyIntegrity.STRUCT_PARENTS_SHARED), problems.toString());
  }

  // ---- structure tree --------------------------------------------------------------------------

  @Test
  void anElementNamingAPageOutsideTheDocumentIsReported() throws IOException {
    Path copy = tagged(SyntheticPdfFactory.untaggedArticle(), doc -> firstElement(doc).setItem(COSName.PG, new PDPage().getCOSObject()));

    Map<String, Integer> problems = CopyIntegrity.inspect(copy).problems();

    assertEquals(1, problems.get(CopyIntegrity.DANGLING_PAGE), problems.toString());
  }

  @Test
  void anElementWhoseParentLinkIsWrongIsReported() throws IOException {
    Path copy = tagged(SyntheticPdfFactory.untaggedArticle(), doc -> firstElement(doc).setItem(COSName.P, doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject()));

    Map<String, Integer> problems = CopyIntegrity.inspect(copy).problems();

    assertEquals(1, problems.get(CopyIntegrity.WRONG_PARENT_LINK), problems.toString());
  }

  // ---- values and syntax -----------------------------------------------------------------------

  @Test
  void aNumberTheInputHeldDirectlyThatTheCopyReferencesIsARegression() throws IOException {
    byte[] input = SyntheticPdfFactory.untaggedWithIndirectSmallIntegers();
    Report before;
    Path copy = dir.resolve("default-writer.pdf");
    try (PDDocument doc = Loader.loadPDF(input)) {
      before = CopyIntegrity.inspect(doc);
      doc.getPage(0).getRotation();
      ContentTagger.apply(doc, ContentTagger.analyze(doc));
      doc.save(copy.toFile()); // the PDFBox 3.0.8 object-stream writer, not CopyWriter
    } catch (ContentTagger.UnsupportedContentException e) {
      throw new AssertionError(e);
    }

    Report after = CopyIntegrity.inspect(copy);
    String regression = CopyIntegrity.regression(before, after);

    assertNotNull(regression);
    assertTrue(regression.startsWith("copy integrity: "), regression);
    assertTrue(regression.contains(CopyIntegrity.INDIRECT_NUMBER + " /StructParents"), regression);
    assertTrue(regression.contains(CopyIntegrity.INDIRECT_NUMBER + " /Count"), regression);
  }

  @Test
  void aCopyThatOnlyALenientParserCanReadIsReported() throws IOException {
    // The cross-reference entry of object 4 (the font) points two bytes into the object.
    String pdf = new String(SyntheticPdfFactory.untaggedWithoutTrailerId(), StandardCharsets.ISO_8859_1);
    int fontOffset = pdf.indexOf("4 0 obj");
    Path broken = dir.resolve("bad-xref.pdf");
    Files.write(broken, pdf.replace(String.format("%010d 00000 n", fontOffset), String.format("%010d 00000 n", fontOffset + 2)).getBytes(StandardCharsets.ISO_8859_1));
    try (PDDocument lenient = Loader.loadPDF(broken.toFile())) {
      assertEquals(1, lenient.getNumberOfPages(), "PDFBox's default parser repairs the offset");
      assertNotNull(lenient.getPage(0).getResources().getFont(COSName.getPDFName("F1")));
    }

    Report report = CopyIntegrity.inspect(broken);

    assertNotNull(report.parseFailure());
    String regression = CopyIntegrity.regression(new Report(null, Map.of()), report);
    assertTrue(regression != null && regression.contains("does not parse strictly") && regression.contains("cross-reference"), regression);
  }

  @Test
  void aStreamWhoseLengthDoesNotMatchItsDataIsReported() throws IOException {
    String pdf = new String(SyntheticPdfFactory.untaggedWithoutTrailerId(), StandardCharsets.ISO_8859_1);
    java.util.regex.Matcher length = java.util.regex.Pattern.compile("/Length (\\d+)").matcher(pdf);
    assertTrue(length.find());
    String padded = pdf.substring(0, length.start(1)) + String.format("%-" + length.group(1).length() + "s", "7") + pdf.substring(length.end(1));
    assertEquals(pdf.length(), padded.length(), "same width, so every offset stays right");
    Path broken = dir.resolve("bad-length.pdf");
    Files.write(broken, padded.getBytes(StandardCharsets.ISO_8859_1));

    Report report = CopyIntegrity.inspect(broken);

    assertNotNull(report.parseFailure(), "PDFBox repairs it; the byte-level check does not");
    assertTrue(report.parseFailure().contains("/Length"), report.parseFailure());
  }

  @Test
  void aCopyWriterFileParsesStrictly() throws IOException {
    Path copy = tagged(SyntheticPdfFactory.untaggedWithPredictorForm(), doc -> {});

    assertNull(CopyIntegrity.inspect(copy).parseFailure());
  }

  @Test
  void anOperatorOutsideItsAllowedStateIsCounted() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithPaintingOperatorWithoutPath())) {
      assertEquals(Map.of(CopyIntegrity.OPERATOR_OUT_OF_STATE, 1), CopyIntegrity.inspect(doc).problems());
    }
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithEndPathWithoutPath())) {
      assertEquals(Map.of(), CopyIntegrity.inspect(doc).problems(), "a stray n is tolerated, as the PAC engine does");
    }
  }

  @Test
  void aReferenceToAMissingObjectIsCountedAsDanglingNotAsAnIndirectValue() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithDanglingReference())) {
      Map<String, Integer> problems = CopyIntegrity.inspect(doc).problems();

      assertEquals(Map.of(CopyIntegrity.DANGLING_REFERENCE + " /PieceInfo", 1, CopyIntegrity.INDIRECT_NUMBER + " /Rotate", 1), problems);
    }
  }

  @Test
  void onlyProblemsThatGrewAreARegression() {
    Report before = new Report(null, Map.of("a", 2, "b", 1));

    assertNull(CopyIntegrity.regression(before, new Report(null, Map.of("a", 2))));
    assertNull(CopyIntegrity.regression(before, new Report(null, Map.of("a", 1, "b", 1))));
    assertEquals("copy integrity: a 2 -> 3", CopyIntegrity.regression(before, new Report(null, Map.of("a", 3, "b", 1))));
    assertEquals("copy integrity: a 2 -> 3; c 0 -> 1", CopyIntegrity.regression(before, new Report(null, Map.of("c", 1, "a", 3))));
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** Tags {@code input}, lets {@code damage} change the in-memory copy, writes it with {@link CopyWriter}. */
  private Path tagged(byte[] input, Consumer<PDDocument> damage) throws IOException {
    Path out = dir.resolve("copy-" + System.nanoTime() + ".pdf");
    try (PDDocument doc = Loader.loadPDF(input); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      ContentTagger.apply(doc, ContentTagger.analyze(doc));
      damage.accept(doc);
      CopyWriter.save(doc, bytes);
      Files.write(out, bytes.toByteArray());
    } catch (ContentTagger.UnsupportedContentException e) {
      throw new AssertionError(e);
    }
    return out;
  }

  private static COSArray nums(PDDocument doc) {
    COSDictionary root = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
    return ((COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE)).getCOSArray(COSName.NUMS);
  }

  private static COSArray pageParents(PDDocument doc) {
    COSArray nums = nums(doc);
    assertEquals(COSInteger.ZERO, nums.get(0));
    return (COSArray) nums.getObject(1);
  }

  private static COSDictionary firstElement(PDDocument doc) {
    COSDictionary root = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
    COSDictionary document = (COSDictionary) root.getDictionaryObject(COSName.K);
    return (COSDictionary) document.getCOSArray(COSName.K).getObject(0);
  }
}
