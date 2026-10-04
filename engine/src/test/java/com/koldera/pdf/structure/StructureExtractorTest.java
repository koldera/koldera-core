package com.koldera.pdf.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureExtractorTest {

  @TempDir Path tmp;

  private final StructureExtractor extractor = new StructureExtractor();

  private StructureResult snapshot(String name, byte[] bytes) throws IOException {
    return extractor.extract(SyntheticPdfFactory.write(tmp.resolve(name), bytes), 10_485_760);
  }

  @Test
  void reportsStoredStructureOrderNotContentOrderWithVerifiedMcidsAndPreviews() throws IOException {
    // Content order: Intro, Second, Third; structure tree deliberately says Third, Intro, Second.
    byte[] bytes = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Intro heading"), Block.of("P", "Second paragraph"), Block.of("P", "Third paragraph")).structureOrder(2, 0, 1));

    StructureResult result = snapshot("reversed.pdf", bytes);

    assertEquals(StructureResult.Status.INSPECTED, result.status());
    assertTrue(result.document().usableTags());
    assertEquals(1, result.document().pages());
    List<StructureNode> nodes = result.nodes();
    assertEquals(4, nodes.size(), "Document + 3 blocks");
    assertEquals("Document", nodes.get(0).role());
    assertNull(nodes.get(0).parentId());
    assertEquals(List.of("P", "H1", "P"), nodes.subList(1, 4).stream().map(StructureNode::role).toList());
    assertEquals(List.of("Third paragraph", "Intro heading", "Second paragraph"), nodes.subList(1, 4).stream().map(StructureNode::textPreview).toList());
    for (int i = 0; i < nodes.size(); i++) {
      assertEquals(i, nodes.get(i).logicalIndex());
    }
    assertEquals(nodes.get(0).nodeId(), nodes.get(1).parentId());
    assertEquals(1, nodes.get(1).pageNumber());
    assertNotNull(nodes.get(1).boundingBox());
    assertTrue(nodes.get(1).boundingBox().height() > 0);
    // Private mapping: paths through /K, one MCID each, all on page 1.
    assertEquals(List.of(0, 0), nodes.get(1).privateMapping().path());
    assertEquals(List.of(0, 2), nodes.get(3).privateMapping().path());
    assertEquals(1, nodes.get(1).privateMapping().contentRefs());
    assertEquals(List.of(1), nodes.get(1).privateMapping().pages());
    assertEquals("content", nodes.get(1).privateMapping().kidKinds());
    assertEquals("elements", nodes.get(0).privateMapping().kidKinds());
    assertEquals(3, nodes.get(0).privateMapping().elementKids());
    // Boxes follow the drawn positions: "Third paragraph" was drawn lowest on the page.
    assertTrue(nodes.get(1).boundingBox().y() < nodes.get(2).boundingBox().y(), "structure-first node is visually below the heading");
  }

  @Test
  void figureWithoutTextPreviewsItsAlternateDescriptionAndFigureWithoutAltStaysEmpty() throws IOException {
    // P1-A probe gap: a reader announces /Alt; the probe must show it, and must not invent anything when it is absent.
    byte[] bytes = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Images"), Block.of("Figure", ""), Block.of("Figure", ""), Block.of("P", "After")).alt(1, "Company logo"));

    StructureResult result = snapshot("figures.pdf", bytes);

    List<StructureNode> nodes = result.nodes();
    assertEquals(java.util.Arrays.asList("Images", "Company logo", null, "After"), nodes.subList(1, 5).stream().map(StructureNode::textPreview).toList());
    assertEquals("Figure", nodes.get(2).role());
    assertEquals(1, nodes.get(2).privateMapping().contentRefs(), "the image is still marked content bound to the element");
  }

  @Test
  void rightToLeftRunNestedInSpansWithActualTextPreviewsItsLogicalTextAndBox() throws IOException {
    // P1-A probe gap (corpus html-16): Chromium wraps RTL text in /ReversedChars and per-glyph
    // /Span <</ActualText>> sequences that carry no MCID of their own. The preview must be the
    // logical text a reader gets (ActualText over placeholder glyphs, reversed run un-reversed),
    // and the box must still come from the drawn glyphs.
    byte[] bytes = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Mixed"), Block.of("P", "Reversed run."), Block.of("P", "After")).reversedRun(1));

    StructureResult result = snapshot("rtl.pdf", bytes);

    List<StructureNode> nodes = result.nodes();
    assertEquals(List.of("Mixed", "Reversed run.", "After"), nodes.subList(1, 4).stream().map(StructureNode::textPreview).toList());
    assertNotNull(nodes.get(2).boundingBox(), "glyphs inside the nested sequences still position the element");
    assertEquals(1, nodes.get(2).privateMapping().contentRefs());
  }

  @Test
  void nodeIdsAreOpaqueStableForTheSameBytesAndDifferentForOtherBytes() throws IOException {
    byte[] a = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Alpha"), Block.of("P", "Beta")));
    byte[] b = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Alpha"), Block.of("P", "Gamma")));

    StructureResult first = snapshot("a.pdf", a);
    StructureResult again = snapshot("a2.pdf", a);
    StructureResult other = snapshot("b.pdf", b);

    assertEquals(first.nodes().stream().map(StructureNode::nodeId).toList(), again.nodes().stream().map(StructureNode::nodeId).toList());
    assertNotEquals(first.nodes().get(1).nodeId(), other.nodes().get(1).nodeId());
    for (StructureNode node : first.nodes()) {
      assertTrue(node.nodeId().matches("^n[a-f0-9]{16}$"), node.nodeId());
      assertFalse(node.nodeId().contains(" 0 R"));
    }
  }

  @Test
  void focusOrderComesFromAnnotationsMappedThroughTheParentTree() throws IOException {
    byte[] bytes = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Title"), Block.of("P", "Read the terms"), Block.of("P", "Tail")).link(1));

    StructureResult result = snapshot("link.pdf", bytes);

    assertTrue(result.focusOrderMeasured());
    assertEquals(1, result.focusOrderNodeIds().size());
    StructureNode link = result.nodes().stream().filter(n -> n.role().equals("Link")).findFirst().orElseThrow();
    assertEquals(link.nodeId(), result.focusOrderNodeIds().get(0));
    assertEquals(1, link.privateMapping().objectRefs());
    assertEquals(1, result.document().annotations());
    StructureNode paragraph = result.nodes().stream().filter(n -> "Read the terms".equals(n.textPreview())).findFirst().orElseThrow();
    assertEquals(paragraph.nodeId(), link.parentId());
    assertEquals(1, paragraph.privateMapping().objectRefs(), "OBJR in the subtree makes the paragraph unsafe to reorder");
  }

  @Test
  void untaggedDocumentHasNoNodesNoFocusMeasurementAndAFinding() throws IOException {
    StructureResult result = snapshot("plain.pdf", SyntheticPdfFactory.simpleUntagged());

    assertEquals(StructureResult.Status.INSPECTED, result.status());
    assertFalse(result.document().usableTags());
    assertTrue(result.nodes().isEmpty());
    assertFalse(result.focusOrderMeasured());
    assertTrue(result.findings().stream().anyMatch(f -> f.code().equals("PDF_STRUCTURE_TREE_MISSING")));
  }

  @Test
  void pageDigestsCoverContentStreamsTextTokensAndAnnotations() throws IOException {
    byte[] two = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "One"), new Block("P", "Two words here", 2)));

    StructureResult result = snapshot("two.pdf", two);

    assertEquals(2, result.document().pageDigests().size());
    PageDigest p1 = result.document().pageDigests().get(0);
    PageDigest p2 = result.document().pageDigests().get(1);
    assertEquals(1, p1.page());
    assertEquals(2, p2.page());
    assertNotEquals(p1.contentStreamSha256(), p2.contentStreamSha256());
    assertNotEquals(p1.textTokenMultisetSha256(), p2.textTokenMultisetSha256());
    assertEquals(1, p1.mcidCount());
    assertEquals(1, p2.mcidCount());
    assertEquals(0, p1.annotationCount());
    // The token multiset is order-independent: the same words in another order hash alike.
    assertEquals(StructureExtractor.tokenMultisetHash(List.of("b", "a", "a")), StructureExtractor.tokenMultisetHash(List.of("a", "b", "a")));
    assertNotEquals(StructureExtractor.tokenMultisetHash(List.of("a", "b")), StructureExtractor.tokenMultisetHash(List.of("a", "b", "b")));
  }

  @Test
  void signedEncryptedAndCorruptInputsAreTypedAndNothingIsWritten() throws IOException {
    StructureResult signed = snapshot("signed.pdf", SyntheticPdfFactory.signed());
    assertEquals(StructureResult.Status.INSPECTED, signed.status());
    assertTrue(signed.document().signed());

    StructureResult encrypted = snapshot("enc.pdf", SyntheticPdfFactory.encrypted("pw"));
    assertEquals(StructureResult.Status.FAILED, encrypted.status());
    assertEquals(StructureResult.ErrorCode.UNSUPPORTED_ENCRYPTED, encrypted.error().code());

    StructureResult corrupt = snapshot("bad.pdf", SyntheticPdfFactory.corrupt());
    assertEquals(StructureResult.ErrorCode.INVALID_INPUT, corrupt.error().code());
    assertEquals(3L, java.nio.file.Files.list(tmp).count(), "the extractor never writes files");
  }

  @Test
  void serializesToTheContractSchemaAndNeverLeaksLongText() throws IOException {
    String longText = "x".repeat(400);
    byte[] bytes = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", longText)));
    StructureResult result = snapshot("long.pdf", bytes);

    String json = StructureResultCodec.toJson(result);

    assertEquals(java.util.Set.of(), ContractSchemas.validateStructureResult(json));
    assertTrue(result.nodes().get(1).textPreview().length() <= 160);
    assertTrue(result.nodes().get(1).textPreview().endsWith("…"));
  }
}
