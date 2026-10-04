package com.koldera.pdf.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.integrity.CopyIntegrity;
import com.koldera.pdf.integrity.CopyWriter;
import com.koldera.pdf.structure.ContentTagger.Grouping;
import com.koldera.pdf.structure.ContentTagger.ItemKind;
import com.koldera.pdf.structure.ContentTagger.ItemView;
import com.koldera.pdf.structure.ContentTagger.Node;
import com.koldera.pdf.structure.ContentTagger.PageAssignment;
import com.koldera.pdf.structure.ContentTagger.PageItems;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** The grouping seam of {@link ContentTagger}: a {@code Grouping} decides leaves and artifacts; without one the basic path is byte-identical. */
class ContentTaggerGroupingTest {

  @Test
  void aGroupingDecidesLeavesAndArtifacts() throws Exception {
    // One leaf P per text line (the line's items share a y), in reverse page order; every PATH an /Artifact /Layout.
    Grouping reversedLines = (document, pages) -> {
      List<PageAssignment> out = new ArrayList<>();
      for (PageItems page : pages) {
        Map<Integer, Integer> leafOfItem = new LinkedHashMap<>();
        Map<Integer, String> artifactOfItem = new LinkedHashMap<>();
        Map<Float, Integer> leafOfLine = new LinkedHashMap<>();
        for (ItemView item : page.items()) {
          if (item.kind() == ItemKind.TEXT) {
            leafOfItem.put(item.index(), leafOfLine.computeIfAbsent(item.y(), y -> leafOfLine.size()));
          } else if (item.kind() == ItemKind.PATH) {
            artifactOfItem.put(item.index(), "Layout");
          }
        }
        List<Node> roots = new ArrayList<>();
        for (int leaf = leafOfLine.size() - 1; leaf >= 0; leaf--) {
          roots.add(Node.leaf("P", leaf));
        }
        out.add(new PageAssignment(leafOfItem, artifactOfItem, roots));
      }
      return out;
    };
    byte[] input = SyntheticPdfFactory.untaggedArticle();
    List<String> operatorsBefore;
    try (PDDocument original = Loader.loadPDF(input)) {
      operatorsBefore = operatorsWithoutMarkedContent(original);
    }
    try (PDDocument doc = Loader.loadPDF(input)) {
      String textBefore = new PDFTextStripper().getText(doc);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, reversedLines));

      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      List<Object> kids = document.getKids();
      assertEquals(6, kids.size(), "one P per line");
      // The first line's two Tj share one MCID (the run rule): MCIDs 0..5 in content order, the tree lists them reversed.
      List<Integer> mcids = new ArrayList<>();
      for (Object kid : kids) {
        PDStructureElement p = (PDStructureElement) kid;
        assertEquals("P", p.getStructureType());
        mcids.add(((COSInteger) p.getCOSObject().getDictionaryObject(COSName.K)).intValue());
      }
      assertEquals(List.of(5, 4, 3, 2, 1, 0), mcids);
      // ParentTree: slot m of the page's array names the element holding MCID m.
      COSDictionary parentTree = (COSDictionary) doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.PARENT_TREE);
      COSArray slots = (COSArray) ((COSArray) parentTree.getDictionaryObject(COSName.NUMS)).getObject(1);
      assertEquals(6, slots.size());
      for (int k = 0; k < kids.size(); k++) {
        assertSame(((PDStructureElement) kids.get(k)).getCOSObject(), slots.getObject(mcids.get(k)));
      }
      assertEquals(Map.of(), CopyIntegrity.inspect(doc).problems());
      assertEquals(textBefore, new PDFTextStripper().getText(doc));
      String content = new String(doc.getPage(0).getContents().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
      assertTrue(content.replaceAll("\\s+", "").contains("/Artifact<</Type/Layout>>BDC"), content);
      assertEquals(operatorsBefore, operatorsWithoutMarkedContent(doc), "the content streams only gained BDC/EMC");
    }
  }

  @Test
  void theBasicPathIsByteIdenticalWithoutAGrouping() throws Exception {
    // Golden values captured with ContentTagger/ContentWalker as they were before the grouping seam.
    assertEquals("733dced2d67c29b979ae2271ff662cf61eae40526fb4c710e5aa03467e9c5b76", sha256(basic(SyntheticPdfFactory.simpleUntagged(), false)), "simpleUntagged");
    assertEquals("c349a64616393f705bb9b16794393a990c76f0995f7cc78698752a62cb6a7bf8", sha256(basic(SyntheticPdfFactory.untaggedArticle(), false)), "untaggedArticle");
    assertEquals("ef1c4423e6529d1623672627900e1c06579abe0de2222e7a1f5edac9ce31b9d5", sha256(basic(SyntheticPdfFactory.untaggedWithFormXObjects(), false)),
        "untaggedWithFormXObjects");
    // Mode fix's basic path (the rollback ladder's run without the structure steps), same capture.
    assertEquals("d4639928344ffb3d500a7af3d53b4c53c891419fdb3251589a03dda1030f1f31", sha256(basic(SyntheticPdfFactory.untaggedWithSharedTextFormOnEveryPage(3), true)),
        "untaggedWithSharedTextFormOnEveryPage(3), fix");
  }

  @Test
  void theLayoutGroupingKeepsEveryArtifactTheBasicPathMakes() throws Exception {
    // veraPDF skips artifacts in its font checks: an item the basic tagger hides must stay hidden (same type) under inference.
    for (byte[] pdf : List.of(SyntheticPdfFactory.untaggedArticle(), SyntheticPdfFactory.untaggedChapterHeaders(), SyntheticPdfFactory.untaggedReport(5),
        SyntheticPdfFactory.untaggedWithSharedTextFormOnEveryPage(3), SyntheticPdfFactory.untaggedWithFormXObjects())) {
      try (PDDocument a = Loader.loadPDF(pdf); PDDocument b = Loader.loadPDF(pdf)) {
        ContentTagger.Plan basic = ContentTagger.analyze(a, true);
        ContentTagger.Plan grouped = ContentTagger.analyze(b, true, new com.koldera.pdf.inference.LayoutGrouping(com.koldera.pdf.inference.InferenceSettings.DEFAULTS, false));
        for (int p = 0; p < basic.pages.size(); p++) {
          ContentTagger.PagePlan bp = basic.pages.get(p);
          ContentTagger.PagePlan gp = grouped.pages.get(p);
          for (int i = 0; i < bp.order.size(); i++) {
            ContentTagger.Decision bd = bp.decisions.get(bp.order.get(i));
            ContentTagger.Decision gd = gp.decisions.get(gp.order.get(i));
            if (bd.wrap() == ContentTagger.Wrap.ARTIFACT) {
              assertEquals(ContentTagger.Wrap.ARTIFACT, gd.wrap(), "page " + (p + 1) + " item " + i + " " + bp.order.get(i).digest);
              assertEquals(bd.artifactType(), gd.artifactType(), "page " + (p + 1) + " item " + i);
            }
          }
        }
      }
    }
  }

  /** Operators of the page stream in order, with BDC/BMC/EMC and the operands of BDC/BMC left out. */
  private static List<String> operatorsWithoutMarkedContent(PDDocument doc) throws Exception {
    List<String> out = new ArrayList<>();
    List<Object> pending = new ArrayList<>();
    for (Object token : new PDFStreamParser(doc.getPage(0)).parse()) {
      if (token instanceof Operator op) {
        String name = op.getName();
        if (!name.equals("BDC") && !name.equals("BMC") && !name.equals("EMC")) {
          StringBuilder sb = new StringBuilder();
          for (Object operand : pending) {
            sb.append(operand instanceof COSBase base ? base.toString() : String.valueOf(operand)).append(' ');
          }
          out.add(sb.append(name).toString());
        }
        pending.clear();
      } else {
        pending.add(token);
      }
    }
    return out;
  }

  private static byte[] basic(byte[] input, boolean fix) throws Exception {
    try (PDDocument doc = Loader.loadPDF(input); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ContentTagger.apply(doc, ContentTagger.analyze(doc, fix));
      CopyWriter.save(doc, out);
      return out.toByteArray();
    }
  }

  static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
