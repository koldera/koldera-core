package com.koldera.pdf.order;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.structure.StructureExtractor;
import com.koldera.pdf.structure.StructureNode;
import com.koldera.pdf.structure.StructureResult;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R04–R07 at the engine level: real PDFBox, real reparse, real render comparison. */
class SiblingReorderTest {

  @TempDir Path tmp;

  private final SiblingReorder reorder = new SiblingReorder();
  private final StructureExtractor extractor = new StructureExtractor();

  private static final byte[] REVERSED = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Intro heading"), Block.of("P", "Second paragraph"), Block.of("P", "Third paragraph")).structureOrder(2, 1, 0));

  private ReorderRequest request(byte[] bytes, List<Integer> parentPath, List<Integer> order) throws IOException {
    Files.createDirectories(tmp.resolve("in"));
    Files.createDirectories(tmp.resolve("out"));
    Files.write(tmp.resolve("in/document.pdf"), bytes);
    return new ReorderRequest(ReorderRequest.SPEC_VERSION, UUID.randomUUID(), ReorderRequest.CANONICAL_INPUT, ReorderRequest.CANONICAL_OUTPUT, PdfPreflight.sha256Hex(bytes), parentPath, order, new ReorderRequest.Limits(10_485_760, 50, 60));
  }

  @Test
  void approvedPermutationOfIndependentSiblingsIsAppliedWithEveryInvariantIntact() throws IOException {
    // Stored order is Third, Second, Intro (kids 0,1,2 of Document); the reviewer approves Intro, Second, Third = kids 2,1,0.
    ReorderRequest request = request(REVERSED, List.of(0), List.of(2, 1, 0));

    ReorderResult result = reorder.apply(request, tmp);

    assertEquals(ReorderResult.Status.APPLIED, result.status(), String.valueOf(result.error()));
    assertNull(result.error());
    assertEquals(List.of("H1", "P", "P"), result.appliedRoles());
    ReorderResult.Checks checks = result.checks();
    assertTrue(checks.pageCountUnchanged());
    assertTrue(checks.contentStreamsUnchanged());
    assertTrue(checks.textTokensUnchanged());
    assertTrue(checks.annotationsUnchanged());
    assertTrue(checks.mcidInventoryUnchanged());
    assertTrue(checks.parentTreeUnchanged());
    assertTrue(checks.renderIdentical());
    assertTrue(checks.sequenceMatchesApproved());
    assertTrue(checks.nodeInventoryUnchanged());

    Path output = tmp.resolve("out/reordered.pdf");
    byte[] out = Files.readAllBytes(output);
    assertEquals(PdfPreflight.sha256Hex(out), result.outputSha256());
    assertEquals(out.length, result.outputBytes());
    // Independent reparse: the new sequence is what was approved, the original bytes are untouched.
    StructureResult after = extractor.extract(output, 10_485_760);
    assertEquals(List.of("Intro heading", "Second paragraph", "Third paragraph"), after.nodes().subList(1, 4).stream().map(StructureNode::textPreview).toList());
    assertArrayEquals(REVERSED, Files.readAllBytes(tmp.resolve("in/document.pdf")));
    assertArrayEquals(render(REVERSED), render(out), "tag-only change must be pixel-identical");
    assertEquals(ContractSchemas.validateReorderResult(ReorderResultCodec.toJson(result)), java.util.Set.of());
  }

  @Test
  void identityPermutationWrongSizeDuplicatesAndOutOfRangeAreRefused() throws IOException {
    assertUnsupported(request(REVERSED, List.of(0), List.of(0, 1, 2)), "already");
    assertUnsupported(request(REVERSED, List.of(0), List.of(1, 0)), "complete");
    assertUnsupported(request(REVERSED, List.of(0), List.of(0, 0, 1)), "permutation");
    assertUnsupported(request(REVERSED, List.of(0), List.of(0, 1, 5)), "permutation");
    assertUnsupported(request(REVERSED, List.of(0, 9), List.of(1, 0)), "parent");
    assertFalse(Files.exists(tmp.resolve("out/reordered.pdf")));
  }

  @Test
  void samePageMoveUnderAFlatMultiPageParentIsAppliedAndACrossPageMoveIsRefused() throws IOException {
    // basic structure tagging hangs every P of every page directly under Document; a reviewer swaps two neighbours on page 2.
    byte[] flat = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "One"), Block.of("P", "Two"), new Block("P", "Three", 2), new Block("P", "Four", 2), new Block("P", "Five", 3)));

    ReorderResult swapped = reorder.apply(request(flat, List.of(0), List.of(0, 1, 3, 2, 4)), tmp);

    assertEquals(ReorderResult.Status.APPLIED, swapped.status(), String.valueOf(swapped.error()));
    assertTrue(swapped.checks().allTrue(), String.valueOf(swapped.checks()));
    StructureResult after = extractor.extract(tmp.resolve("out/reordered.pdf"), 10_485_760);
    assertEquals(List.of("One", "Two", "Four", "Three", "Five"), after.nodes().subList(1, 6).stream().map(StructureNode::textPreview).toList());
    Files.delete(tmp.resolve("out/reordered.pdf"));

    // Moving a page-2 paragraph in front of a page-1 paragraph is still a cross-page change.
    assertUnsupported(request(flat, List.of(0), List.of(0, 2, 1, 3, 4)), "page");
    // A permutation that touches pages 2 and 3 at once is refused even though no kid crosses a page boundary alone.
    assertUnsupported(request(flat, List.of(0), List.of(1, 0, 2, 4, 3)), "page");
    // Kids A(p1) B(p2) C(p1): swapping A and C leaves B where it is, yet B would be spoken between C and A
    // instead of between A and C: the index window of the move must lie on one page, untouched kids included.
    byte[] interleaved = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "A"), new Block("P", "B", 2), Block.of("P", "C")));
    assertUnsupported(request(interleaved, List.of(0), List.of(2, 1, 0)), "page");
  }

  @Test
  void siblingsWithAnnotationsMixedContentOrDifferentPagesAreRefused() throws IOException {
    byte[] linked = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Title"), Block.of("P", "Read the terms"), Block.of("P", "Tail")).link(1).structureOrder(2, 1, 0));
    assertUnsupported(request(linked, List.of(0), List.of(2, 1, 0)), "annotation");

    byte[] twoPages = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "One"), new Block("P", "Two", 2)).structureOrder(1, 0));
    assertUnsupported(request(twoPages, List.of(0), List.of(1, 0)), "page");

    // The paragraph with the link has mixed kids (an MCID and a Link element): not a parent whose kids may be permuted.
    ReorderRequest mixed = request(linked, List.of(0, 1), List.of(1, 0));
    assertUnsupported(mixed, "structure elements");
    assertFalse(Files.exists(tmp.resolve("out/reordered.pdf")));
  }

  @Test
  void untaggedSignedEncryptedAndStaleInputsAreRefused() throws IOException {
    assertUnsupported(request(SyntheticPdfFactory.simpleUntagged(), List.of(), List.of(1, 0)), "structure tree");

    ReorderResult signed = reorder.apply(request(SyntheticPdfFactory.signed(), List.of(), List.of(1, 0)), tmp);
    assertEquals(ReorderResult.ErrorCode.UNSUPPORTED_SIGNED_MUTATION, signed.error().code());

    ReorderResult encrypted = reorder.apply(request(SyntheticPdfFactory.encrypted("pw"), List.of(), List.of(1, 0)), tmp);
    assertEquals(ReorderResult.ErrorCode.UNSUPPORTED_ENCRYPTED, encrypted.error().code());

    ReorderRequest stale = request(REVERSED, List.of(0), List.of(2, 1, 0));
    ReorderRequest wrongHash = new ReorderRequest(stale.specVersion(), stale.jobId(), stale.inputPath(), stale.outputPath(), "0".repeat(64), stale.parentPath(), stale.orderedKidIndexes(), stale.limits());
    ReorderResult mismatch = reorder.apply(wrongHash, tmp);
    assertEquals(ReorderResult.ErrorCode.INVALID_INPUT, mismatch.error().code());
    assertFalse(Files.exists(tmp.resolve("out/reordered.pdf")));
  }

  @Test
  void nestedSiblingGroupsAreSupportedAndTableRowsAreNot() throws IOException {
    byte[] nested = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "A"), Block.of("P", "B"), Block.of("P", "C")).nested(true).structureOrder(2, 1, 0));
    ReorderResult result = reorder.apply(request(nested, List.of(0), List.of(2, 1, 0)), tmp);
    assertEquals(ReorderResult.Status.APPLIED, result.status(), String.valueOf(result.error()));
    assertEquals(List.of("Sect", "Sect", "Sect"), result.appliedRoles());
    StructureResult after = extractor.extract(tmp.resolve("out/reordered.pdf"), 10_485_760);
    assertEquals(List.of("A", "B", "C"), after.nodes().stream().filter(n -> n.textPreview() != null && n.role().equals("Sect")).map(StructureNode::textPreview).toList());

    byte[] table = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("TR", "row one"), Block.of("TR", "row two")).structureOrder(1, 0));
    // The parent here is Document, whose kids are TR elements: rows are never reordered.
    assertUnsupported(request(table, List.of(0), List.of(1, 0)), "table");
  }

  @Test
  void regressionInjectedAfterTheChangeRollsBackWithoutOutput() throws IOException {
    ReorderRequest request = request(REVERSED, List.of(0), List.of(2, 1, 0));
    SiblingReorder tampering = new SiblingReorder(document -> {
      // Simulate a writer bug: drop the last page's annotations array and blank the ParentTree.
      document.getDocumentCatalog().getStructureTreeRoot().getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.PARENT_TREE);
    });

    ReorderResult result = tampering.apply(request, tmp);

    assertEquals(ReorderResult.Status.FAILED, result.status());
    assertEquals(ReorderResult.ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertFalse(result.checks().parentTreeUnchanged());
    assertNull(result.outputSha256());
    assertFalse(Files.exists(tmp.resolve("out/reordered.pdf")));
    assertArrayEquals(REVERSED, Files.readAllBytes(tmp.resolve("in/document.pdf")));
  }

  private void assertUnsupported(ReorderRequest request, String reasonFragment) {
    ReorderResult result = reorder.apply(request, tmp);
    assertEquals(ReorderResult.Status.UNSUPPORTED, result.status(), String.valueOf(result.error()));
    assertNotNull(result.error());
    assertEquals(ReorderResult.ErrorCode.ORDER_FIX_UNSUPPORTED, result.error().code());
    assertTrue(result.error().message().toLowerCase().contains(reasonFragment), result.error().message());
    assertNull(result.outputSha256());
  }

  private static int[] render(byte[] bytes) throws IOException {
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      BufferedImage image = new PDFRenderer(doc).renderImage(0);
      return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
  }
}
