package com.koldera.pdf.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.integrity.CopyIntegrity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

/** Fixes for the upload link tagger. */
class LinkTaggerEdgeCasesTest {

  private static PDDocument reload(PDDocument doc) throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.save(out);
      doc.close();
      return Loader.loadPDF(out.toByteArray());
    }
  }

  private static PDAnnotationLink link(PDRectangle rect) {
    PDAnnotationLink link = new PDAnnotationLink();
    link.setRectangle(rect);
    PDActionURI uri = new PDActionURI();
    uri.setURI("https://example.org");
    link.setAction(uri);
    return link;
  }

  @Test
  void aParentTreeWhoseLeafHasNoLimitsIsRefusedWithAReason() throws IOException {
    PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Read the report"))));
    COSDictionary root = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
    COSDictionary flat = (COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE);
    COSDictionary leaf = new COSDictionary();
    leaf.setItem(COSName.NUMS, flat.getDictionaryObject(COSName.NUMS));
    COSDictionary tree = new COSDictionary();
    COSArray kids = new COSArray();
    kids.add(leaf);
    tree.setItem(COSName.KIDS, kids);
    root.setItem(COSName.PARENT_TREE, tree);
    doc.getPage(0).getAnnotations().add(link(new PDRectangle(70, 716, 150, 14)));
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals("the structure tree has no ParentTree that can be added to", outcome.refusal());
    assertNull(doc.getPage(0).getAnnotations().get(0).getCOSObject().getItem(COSName.STRUCT_PARENT));
    doc.close();
  }

  @Test
  void aParentTreeKeyAFormXObjectClaimsIsNeverReusedForAnAnnotation() throws IOException {
    PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Linked text")).link(0)));
    COSDictionary annot = doc.getPage(0).getAnnotations().get(0).getCOSObject();
    int oldKey = annot.getInt(COSName.STRUCT_PARENT);
    annot.removeItem(COSName.STRUCT_PARENT);
    // A form on the page claims the key the annotation lost (a producer's shared key).
    PDFormXObject form = new PDFormXObject(new PDStream(doc));
    form.setBBox(new PDRectangle(0, 0, 10, 10));
    form.getCOSObject().setItem(COSName.STRUCT_PARENT, COSInteger.get(oldKey));
    PDResources resources = doc.getPage(0).getResources();
    resources.put(COSName.getPDFName("Fm9"), form);
    doc = reload(doc);

    LinkTagger.Outcome outcome = LinkTagger.apply(doc);

    assertEquals(1, outcome.repaired());
    int newKey = doc.getPage(0).getAnnotations().get(0).getCOSObject().getInt(COSName.STRUCT_PARENT);
    assertNotEquals(oldKey, newKey, "the form keeps its key; the annotation gets a new one");
    doc.close();
  }

  @Test
  void twentyThousandLinksAreTaggedInBoundedTime() throws IOException {
    int pages = 60;
    int blocksPerPage = 18;
    int linksPerBlock = 19;
    List<Block> blocks = new ArrayList<>();
    for (int p = 1; p <= pages; p++) {
      for (int b = 0; b < blocksPerPage; b++) {
        blocks.add(new Block("P", "Links to many places on this line of text", p));
      }
    }
    PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(new TaggedOptions(blocks, null, -1, false, java.util.Map.of(), java.util.Set.of())));
    int links = 0;
    for (int p = 0; p < pages; p++) {
      for (int b = 0; b < blocksPerPage; b++) {
        float baseline = 720 - b * 40;
        for (int l = 0; l < linksPerBlock; l++) {
          doc.getPage(p).getAnnotations().add(link(new PDRectangle(72 + l * 10, baseline - 3, 9, 13)));
          links++;
        }
      }
    }
    doc = reload(doc);

    long start = System.nanoTime();
    LinkTagger.Outcome outcome = LinkTagger.apply(doc);
    long ms = (System.nanoTime() - start) / 1_000_000;

    assertEquals(links, outcome.tagged(), "every link placed");
    assertTrue(links >= 20_000, "fixture size " + links);
    assertTrue(ms < 60_000, links + " links took " + ms + " ms");
    System.out.println("LinkTaggerEdgeCasesTest: " + links + " links tagged in " + ms + " ms");
    try (PDDocument copy = reload(doc)) {
      assertTrue(CopyIntegrity.inspect(copy).problems().isEmpty(), CopyIntegrity.inspect(copy).problems().toString());
    }
  }
}
