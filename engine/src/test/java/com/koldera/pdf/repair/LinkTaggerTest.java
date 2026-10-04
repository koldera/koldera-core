package com.koldera.pdf.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.integrity.CopyIntegrity;
import com.koldera.pdf.structure.NumberTreeView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

class LinkTaggerTest {

  private static final COSName OBJR = COSName.getPDFName("OBJR");

  // Blocks of taggedBlocks are drawn at x=72, baselines 720, 680, 640, ... (P: Helvetica 11).
  private static byte[] blocks(Block... blocks) {
    return SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(blocks));
  }

  private static PDAnnotationLink link(PDRectangle rect, String uri) {
    PDAnnotationLink link = new PDAnnotationLink();
    link.setRectangle(rect);
    if (uri != null) {
      PDActionURI action = new PDActionURI();
      action.setURI(uri);
      link.setAction(action);
    }
    return link;
  }

  /** Saves and reloads, so annotations added in memory become indirect objects as in a real file. */
  private static PDDocument reload(PDDocument doc) throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.save(out);
      doc.close();
      return Loader.loadPDF(out.toByteArray());
    }
  }

  private static PDStructureElement documentElement(PDDocument doc) {
    return (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
  }

  private static PDStructureElement kid(PDStructureNode node, int index) {
    return (PDStructureElement) node.getKids().stream().filter(k -> k instanceof PDStructureElement).toList().get(index);
  }

  private static COSDictionary annotation(PDDocument doc, int page, int index) throws IOException {
    return doc.getPage(page).getAnnotations().get(index).getCOSObject();
  }

  private static COSBase parentTreeValue(PDDocument doc, int key) {
    COSBase value = NumberTreeView.read(doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.PARENT_TREE)).get(key);
    return value instanceof COSObject ref ? ref.getObject() : value;
  }

  private static PDDocument saved(PDDocument doc) throws IOException {
    PDDocument again = reload(doc);
    CopyIntegrity.Report report = CopyIntegrity.inspect(again);
    assertTrue(report.problems().isEmpty(), report.problems().toString());
    return again;
  }

  @Test
  void aLinkOverTextBecomesALinkElementInsideTheParagraphRightAfterItsText() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("H1", "Heading"), Block.of("P", "Read the annual report online"), Block.of("P", "Closing words")));
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(70, 676, 220, 14), "https://example.org/report"));
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals(1, outcome.tagged());
    assertEquals(0, outcome.left());
    PDStructureElement paragraph = kid(documentElement(doc), 1);
    List<Object> kids = paragraph.getKids();
    assertEquals(2, kids.size(), kids.toString());
    assertEquals(1, kids.get(0), "the paragraph's own MCID first");
    PDStructureElement linkElement = (PDStructureElement) kids.get(1);
    assertEquals("Link", linkElement.getStructureType());
    assertSame(paragraph.getCOSObject(), linkElement.getCOSObject().getDictionaryObject(COSName.P));
    PDObjectReference reference = (PDObjectReference) linkElement.getKids().get(0);
    COSDictionary annot = annotation(doc, 0, 0);
    assertSame(annot, reference.getCOSObject().getDictionaryObject(COSName.OBJ));
    int key = annot.getInt(COSName.STRUCT_PARENT);
    assertSame(linkElement.getCOSObject(), parentTreeValue(doc, key));
    assertEquals(key + 1, doc.getDocumentCatalog().getStructureTreeRoot().getParentTreeNextKey());
    assertEquals("Read the annual report online", annot.getString(COSName.CONTENTS), "the text under the link");
    assertEquals(1, outcome.described());

    try (PDDocument copy = saved(doc)) {
      assertEquals("Link", kid(kid(documentElement(copy), 1), 0).getStructureType());
    }
  }

  @Test
  void aLinkOverTheTextOfAnExistingLinkElementJoinsIt() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "Intro"), Block.of("Link", "example.org")));
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(70, 676, 80, 14), "https://example.org"));
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals(1, outcome.joined());
    assertEquals(0, outcome.tagged());
    PDStructureElement existing = kid(documentElement(doc), 1);
    assertEquals(2, existing.getKids().size(), "its MCID and the new object reference");
    assertTrue(existing.getKids().get(1) instanceof PDObjectReference);
    assertSame(existing.getCOSObject(), parentTreeValue(doc, annotation(doc, 0, 0).getInt(COSName.STRUCT_PARENT)));
    saved(doc).close();
  }

  @Test
  void aLinkOverAnImageBecomesALinkElementNextToTheFigure() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "Intro"), Block.of("Figure", "unused")));
    // The Figure block draws a 24x24 image at (72, 676).
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(72, 676, 24, 24), "https://example.org/image"));
    doc = reload(doc);

    LinkTagger.apply(doc);

    PDStructureElement document = documentElement(doc);
    assertEquals("Figure", kid(document, 1).getStructureType());
    PDStructureElement linkElement = kid(document, 2);
    assertEquals("Link", linkElement.getStructureType(), "a sibling after the Figure, not inside it");
    assertEquals("Link to https://example.org/image", annotation(doc, 0, 0).getString(COSName.CONTENTS), "no text under it: the URI");
    saved(doc).close();
  }

  @Test
  void aLinkOverNothingGoesToTheNearestBlock() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "First"), Block.of("P", "Second"), Block.of("P", "Third")));
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(72, 600, 20, 10), null));
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals(1, outcome.tagged());
    assertEquals("Link", kid(kid(documentElement(doc), 2), 0).getStructureType(), "Third (baseline 640) is nearest to y 600-610 straight below the text");
    assertNull(annotation(doc, 0, 0).getString(COSName.CONTENTS), "no text, no URI, no destination: no description is invented");
    assertEquals(0, outcome.described());
    saved(doc).close();
  }

  @Test
  void anAnnotationTheTreeAlreadyReferencesOnlyGetsItsStructParentBack() throws IOException {
    PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Linked text")).link(0)));
    COSDictionary annot = annotation(doc, 0, 0);
    annot.removeItem(COSName.STRUCT_PARENT);
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals(1, outcome.repaired());
    assertEquals(0, outcome.tagged());
    COSDictionary reloaded = annotation(doc, 0, 0);
    COSBase holder = parentTreeValue(doc, reloaded.getInt(COSName.STRUCT_PARENT));
    assertEquals("Link", ((COSDictionary) holder).getNameAsString(COSName.S));
    saved(doc).close();
  }

  @Test
  void anAnnotationWithAStructParentAndItsExistingDescriptionAreLeftAlone() throws IOException {
    PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Linked text")).link(0)));
    annotation(doc, 0, 0).setString(COSName.CONTENTS, "Own description");
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals(0, outcome.tagged() + outcome.joined() + outcome.repaired() + outcome.described());
    assertEquals("Own description", annotation(doc, 0, 0).getString(COSName.CONTENTS));
  }

  @Test
  void anExistingLinkElementWithoutADescriptionGetsOne() throws IOException {
    PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Linked text")).link(0)));
    PDActionURI uri = new PDActionURI();
    uri.setURI("https://example.org/tagged");
    ((PDAnnotationLink) doc.getPage(0).getAnnotations().get(0)).setAction(uri);
    doc = reload(doc);
    LinkTagger.Outcome outcome = LinkTagger.apply(doc);
    assertEquals(1, outcome.described(), "LinkContents fills the tagged link's /Contents");
    assertEquals("Link to https://example.org/tagged", annotation(doc, 0, 0).getString(COSName.CONTENTS));
  }

  @Test
  void aDirectAnnotationIsMadeIndirectSoTheObjectReferenceCanPointAtIt() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "Read the annual report online")));
    doc = reload(doc);
    PDAnnotationLink direct = link(new PDRectangle(70, 716, 150, 14), "https://example.org");
    direct.getCOSObject().setDirect(true);
    COSArray annots = new COSArray();
    annots.add(direct.getCOSObject());
    doc.getPage(0).getCOSObject().setItem(COSName.ANNOTS, annots);

    assertEquals(1, LinkTagger.apply(doc).tagged());

    try (PDDocument copy = saved(doc)) {
      COSArray reloaded = (COSArray) copy.getPage(0).getCOSObject().getDictionaryObject(COSName.ANNOTS);
      assertTrue(reloaded.get(0) instanceof COSObject, "written once, as an indirect object");
      PDStructureElement linkElement = kid(kid(documentElement(copy), 0), 0);
      assertSame(((COSObject) reloaded.get(0)).getObject(), ((PDObjectReference) linkElement.getKids().get(0)).getCOSObject().getDictionaryObject(COSName.OBJ));
    }
  }

  @Test
  void aPageWithoutTaggedContentKeepsItsLinkUntaggedAndSaysSo() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "Only page one has content")));
    PDPage empty = new PDPage(PDRectangle.A4);
    doc.addPage(empty);
    empty.getAnnotations().add(link(new PDRectangle(72, 700, 50, 12), "https://example.org"));
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals(0, outcome.tagged());
    assertEquals(1, outcome.left());
    assertTrue(annotation(doc, 1, 0).getItem(COSName.STRUCT_PARENT) == null);
  }

  @Test
  void aParentTreeWithKidsIsAppendedToInItsLastLeaf() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "Read the annual report online")));
    COSDictionary root = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
    COSDictionary flat = (COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE);
    COSDictionary leaf = new COSDictionary();
    leaf.setItem(COSName.NUMS, flat.getDictionaryObject(COSName.NUMS));
    COSArray limits = new COSArray();
    limits.add(COSInteger.get(0));
    limits.add(COSInteger.get(0));
    leaf.setItem(COSName.LIMITS, limits);
    COSDictionary tree = new COSDictionary();
    COSArray kids = new COSArray();
    kids.add(leaf);
    tree.setItem(COSName.KIDS, kids);
    root.setItem(COSName.PARENT_TREE, tree);
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(70, 716, 150, 14), "https://example.org"));
    doc = reload(doc);

    assertEquals(1, LinkTagger.apply(doc).tagged());
    try (PDDocument copy = saved(doc)) {
      COSDictionary annot = annotation(copy, 0, 0);
      assertNotNull(parentTreeValue(copy, annot.getInt(COSName.STRUCT_PARENT)));
    }
  }

  @Test
  void countingLinksChangesNothingAndHiddenLinksAreNotCounted() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "Text")));
    doc = reload(doc);
    PDAnnotationLink direct = link(new PDRectangle(70, 716, 50, 14), "https://example.org");
    direct.getCOSObject().setDirect(true);
    PDAnnotationLink hidden = link(new PDRectangle(70, 716, 50, 14), "https://example.org/hidden");
    hidden.setHidden(true);
    COSArray annots = new COSArray();
    annots.add(direct.getCOSObject());
    annots.add(hidden.getCOSObject());
    doc.getPage(0).getCOSObject().setItem(COSName.ANNOTS, annots);

    assertEquals(1, LinkTagger.untaggedLinks(doc), "the hidden link is not work to do");
    assertTrue(direct.getCOSObject().isDirect(), "counting does not touch the document");
    LinkTagger.Outcome outcome = LinkTagger.apply(doc);
    assertEquals(1, outcome.tagged());
    assertNull(hidden.getCOSObject().getItem(COSName.STRUCT_PARENT), "a hidden link is left alone");
  }

  @Test
  void anElementTheParentTreeNamesButTheTreeNoLongerHoldsIsNotUsed() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "Kept"), Block.of("P", "Detached")));
    // Detach the second paragraph from the tree; its ParentTree slot still names it.
    PDStructureElement document = documentElement(doc);
    COSArray kids = (COSArray) document.getCOSObject().getDictionaryObject(COSName.K);
    kids.remove(1);
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(70, 676, 100, 14), "https://example.org"));
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals(1, outcome.tagged());
    assertEquals("Link", kid(kid(documentElement(doc), 0), 0).getStructureType(), "the nearest element that is in the tree");
  }

  @Test
  void anUntaggedDocumentIsRefused() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged())) {
      assertEquals("document is not tagged", LinkTagger.apply(doc).refusal());
    }
  }

  @Test
  void aTreeWithoutAParentTreeIsRefusedWithoutAnyChange() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "Text")));
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(70, 716, 50, 14), "https://example.org"));
    doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().removeItem(COSName.PARENT_TREE);
    doc = reload(doc);
    LinkTagger.Outcome outcome = LinkTagger.apply(doc);
    assertEquals("the structure tree has no ParentTree that can be added to", outcome.refusal());
    assertNull(annotation(doc, 0, 0).getItem(COSName.STRUCT_PARENT));
  }

  @Test
  void theSameInputGivesTheSameTree() throws IOException {
    byte[] first = tagOnce();
    byte[] second = tagOnce();
    assertTrue(java.util.Arrays.equals(first, second), "deterministic");
  }

  private static byte[] tagOnce() throws IOException {
    PDDocument doc = Loader.loadPDF(blocks(Block.of("P", "One link here"), Block.of("P", "Another link there")));
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(70, 716, 60, 14), "https://a.example"));
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(70, 676, 60, 14), "https://b.example"));
    doc = reload(doc);
    LinkTagger.apply(doc);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.setDocumentId(7L);
      doc.save(out);
      doc.close();
      return out.toByteArray();
    }
  }

  static boolean isObjr(Object kid) {
    return kid instanceof PDObjectReference r && OBJR.equals(r.getCOSObject().getCOSName(COSName.TYPE));
  }
}
