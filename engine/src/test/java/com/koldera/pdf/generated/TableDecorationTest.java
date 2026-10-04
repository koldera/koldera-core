package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentLinks;
import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
import static com.koldera.pdf.generated.GeneratedStructure.contentKeys;
import static com.koldera.pdf.generated.GeneratedStructure.elements;
import static com.koldera.pdf.generated.GeneratedStructure.kidTypes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.structure.NumberTreeView;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class TableDecorationTest {

  @Test
  void theCollapsedBorderSequenceOfATableBecomesAnArtifactAndTheTableThenNormalizes() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-tables.pdf")) {
      String textBefore = new PDFTextStripper().getText(doc);
      List<COSDictionary> tablesBefore = elements(doc, "Table");
      assertEquals(5, tablesBefore.size());
      assertEquals(List.of("mcid", "Caption", "TR", "TR", "TR"), kidTypes(tablesBefore.get(4)), "fixture shape: Chromium tags collapsed borders as the Table's own MCID");
      int borderMcid = ((COSInteger) GeneratedStructure.kids(tablesBefore.get(4)).get(0)).intValue();
      assertEquals(List.of("/Table:" + borderMcid), mcidTags(doc.getPage(0)).stream().filter(t -> t.startsWith("/Table")).toList());
      List<String> keysBefore = contentKeys(doc);
      assertParentTreeMatches(doc);

      assertEquals(new TableDecoration.Outcome(1, 0), TableDecoration.apply(doc));
      assertEquals(3, TableNormalizer.normalize(doc), "the three tables with a header row, the collapsed-border one included once its border sequence is gone");

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        for (COSDictionary table : elements(after, "Table")) {
          List<String> types = kidTypes(table);
          assertTrue(List.of("Caption", "THead", "TBody", "TR").containsAll(types), "Table kids " + types);
          assertFalse(types.contains("mcid") || types.contains("mcr"), "no marked content directly under Table");
        }
        assertEquals(List.of("Caption", "THead", "TBody"), kidTypes(elements(after, "Table").get(4)));
        List<String> tags = mcidTags(after.getPage(0));
        assertTrue(tags.stream().noneMatch(t -> t.endsWith(":" + borderMcid)), "MCID " + borderMcid + " is gone from the content stream: " + tags);
        assertTrue(tags.stream().noneMatch(t -> t.startsWith("/Table")), tags.toString());
        assertEquals(1, artifactSequencesOfPathsOnly(after.getPage(0)), "the border painting is one /Artifact BMC sequence");
        List<String> expectedKeys = new ArrayList<>(keysBefore);
        expectedKeys.remove("sp" + after.getPage(0).getCOSObject().getInt(COSName.STRUCT_PARENTS) + ":" + borderMcid);
        assertEquals(expectedKeys, contentKeys(after), "every other content item kept, in order");
        assertParentTreeMatches(after);
        assertParentLinks(after);
        COSArray pageArray = (COSArray) GeneratedStructure.resolve(NumberTreeView.read(GeneratedStructure.root(after).getDictionaryObject(COSName.PARENT_TREE))
            .get(after.getPage(0).getCOSObject().getInt(COSName.STRUCT_PARENTS)));
        assertEquals(COSNull.NULL, GeneratedStructure.resolve(pageArray.get(borderMcid)), "the ParentTree slot stays, emptied; no renumbering");
        assertEquals(textBefore, new PDFTextStripper().getText(after), "extracted text identical");
        assertEquals(new TableDecoration.Outcome(0, 0), TableDecoration.apply(after), "idempotent");
      }
    }
  }

  @Test
  void aTableWhoseOwnMarkedContentShowsTextIsLeftEntirelyUnchanged() throws IOException {
    try (PDDocument doc = syntheticTable("/Table <</MCID 0>> BDC BT /F1 12 Tf 10 10 Td (x) Tj ET EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false)) {
      COSDictionary table = elements(doc, "Table").get(0);
      List<String> kidsBefore = kidTypes(table);
      List<String> tagsBefore = mcidTags(doc.getPage(0));

      assertEquals(new TableDecoration.Outcome(0, 1), TableDecoration.apply(doc));

      assertEquals(kidsBefore, kidTypes(table));
      assertEquals(tagsBefore, mcidTags(doc.getPage(0)));
      assertParentTreeMatches(doc);
    }
  }

  @Test
  void aTableWhoseOwnMarkedContentPaintsAnImageXObjectIsLeftUnchanged() throws IOException {
    assertRefused(syntheticTable("/Table <</MCID 0>> BDC 0 0 5 5 re f /Im0 Do EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false));
  }

  @Test
  void aTableWhoseOwnMarkedContentHoldsAnInlineImageIsLeftUnchanged() throws IOException {
    assertRefused(syntheticTable("/Table <</MCID 0>> BDC q 5 0 0 5 0 0 cm BI /W 1 /H 1 /CS /G /BPC 8 ID 0 EI Q EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false));
  }

  @Test
  void aTableWhoseOwnMarkedContentNestsMarkedContentIsLeftUnchanged() throws IOException {
    assertRefused(syntheticTable("/Table <</MCID 0>> BDC /Span <</Lang (en)>> BDC 0 0 5 5 re f EMC EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false));
    assertRefused(syntheticTable("/Table <</MCID 0>> BDC /Artifact BMC 0 0 5 5 re f EMC EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false));
  }

  @Test
  void aTableWhoseOwnMarkedContentUsesANamedPropertyListIsLeftUnchanged() throws IOException {
    PDDocument doc = syntheticTable("/Table /P0 BDC 0 0 5 5 re f EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false);
    COSDictionary properties = new COSDictionary();
    COSDictionary p0 = new COSDictionary();
    p0.setInt(COSName.MCID, 0);
    properties.setItem(COSName.getPDFName("P0"), p0);
    PDResources resources = new PDResources();
    resources.getCOSObject().setItem(COSName.PROPERTIES, properties);
    doc.getPage(0).setResources(resources);
    assertRefused(doc);
  }

  @Test
  void aTableWhoseOwnMcidAppearsTwiceInTheStreamIsLeftUnchanged() throws IOException {
    assertRefused(syntheticTable("/Table <</MCID 0>> BDC 0 0 5 5 re f EMC /Table <</MCID 0>> BDC 5 5 5 5 re f EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false));
  }

  @Test
  void aTableWhoseParentTreeSlotDoesNotNameItIsLeftUnchanged() throws IOException {
    PDDocument doc = syntheticTable("/Table <</MCID 0>> BDC 0 0 5 5 re f EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false);
    COSArray pageSlots = (COSArray) GeneratedStructure.resolve(NumberTreeView.read(GeneratedStructure.root(doc).getDictionaryObject(COSName.PARENT_TREE)).get(0));
    COSDictionary cell = elements(doc, "TD").get(0);
    pageSlots.set(0, cell);
    assertRefused(doc, false);
  }

  @Test
  void aBorderSequenceInsideAFormXObjectIsRetaggedInTheForm() throws IOException {
    try (PDDocument doc = syntheticTable("/Table <</MCID 0>> BDC 0 0 50 50 re f EMC", true)) {
      assertEquals(new TableDecoration.Outcome(1, 0), TableDecoration.apply(doc));
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary table = elements(after, "Table").get(0);
        assertEquals(List.of("TR"), kidTypes(table));
        PDFormXObject form = (PDFormXObject) after.getPage(0).getResources().getXObject(COSName.getPDFName("Fm0"));
        List<Object> tokens = new PDFStreamParser(form).parse();
        assertTrue(tokens.contains(COSName.ARTIFACT), tokens.toString());
        assertTrue(tokens.stream().noneMatch(t -> t instanceof COSDictionary d && d.containsKey(COSName.MCID)), tokens.toString());
        assertParentTreeMatches(after);
      }
    }
  }

  // ---- helpers ----------------------------------------------------------------------------

  /** TableDecoration refuses the one table: Outcome(0,1), and the structure, ParentTree and page stream are untouched. */
  private static void assertRefused(PDDocument doc) throws IOException {
    assertRefused(doc, true);
  }

  private static void assertRefused(PDDocument doc, boolean parentTreeConsistent) throws IOException {
    try (doc) {
      COSDictionary table = elements(doc, "Table").get(0);
      List<String> kidsBefore = kidTypes(table);
      List<String> keysBefore = contentKeys(doc);
      List<String> slotsBefore = slots(doc);
      byte[] streamBefore = doc.getPage(0).getContents().readAllBytes();

      assertEquals(new TableDecoration.Outcome(0, 1), TableDecoration.apply(doc));

      assertEquals(kidsBefore, kidTypes(table), "Table /K unchanged");
      assertEquals(keysBefore, contentKeys(doc), "content items unchanged");
      assertEquals(slotsBefore, slots(doc), "ParentTree unchanged");
      assertArrayEquals(streamBefore, doc.getPage(0).getContents().readAllBytes(), "page content stream unchanged");
      if (parentTreeConsistent) {
        assertParentTreeMatches(doc);
      }
    }
  }

  /** Every ParentTree entry as "key[index]=identity of the element it names". */
  private static List<String> slots(PDDocument doc) {
    List<String> out = new ArrayList<>();
    for (var e : NumberTreeView.read(GeneratedStructure.root(doc).getDictionaryObject(COSName.PARENT_TREE)).entrySet()) {
      COSBase value = GeneratedStructure.resolve(e.getValue());
      if (value instanceof COSArray array) {
        for (int i = 0; i < array.size(); i++) {
          out.add(e.getKey() + "[" + i + "]=" + System.identityHashCode(GeneratedStructure.resolve(array.get(i))));
        }
      } else {
        out.add(e.getKey() + "=" + System.identityHashCode(value));
      }
    }
    return out;
  }

  /** "tag:mcid" for every BDC with an MCID in the page's content, in order. */
  private static List<String> mcidTags(PDPage page) throws IOException {
    List<Object> tokens = new PDFStreamParser(page).parse();
    List<String> out = new ArrayList<>();
    for (int i = 2; i < tokens.size(); i++) {
      if (tokens.get(i) instanceof Operator op && "BDC".equals(op.getName()) && tokens.get(i - 1) instanceof COSDictionary props && props.containsKey(COSName.MCID)) {
        out.add(tokens.get(i - 2) + ":" + props.getInt(COSName.MCID));
      }
    }
    return out.stream().map(s -> s.replace("COSName{", "/").replace("}", "")).toList();
  }

  /** Top-level "/Artifact BMC … EMC" sequences whose operators are only path construction/painting and graphics state. */
  private static int artifactSequencesOfPathsOnly(PDPage page) throws IOException {
    List<Object> tokens = new PDFStreamParser(page).parse();
    int count = 0;
    for (int i = 1; i < tokens.size(); i++) {
      if (tokens.get(i) instanceof Operator op && "BMC".equals(op.getName()) && COSName.ARTIFACT.equals(tokens.get(i - 1))) {
        boolean paths = true;
        boolean sawPaint = false;
        int j = i + 1;
        for (; j < tokens.size(); j++) {
          if (tokens.get(j) instanceof Operator o) {
            String n = o.getName();
            if ("EMC".equals(n)) {
              break;
            }
            sawPaint |= List.of("f", "F", "f*", "S", "s", "B", "B*", "b", "b*").contains(n);
            paths &= List.of("re", "m", "l", "c", "v", "y", "h", "f", "F", "f*", "S", "s", "B", "B*", "b", "b*", "n", "W", "W*", "q", "Q", "cm", "rg", "RG", "g", "G", "gs", "k", "K", "cs", "CS", "sc", "scn", "SC", "SCN", "w").contains(n);
          }
        }
        if (paths && sawPaint) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * One page; Document > Table > [MCID 0 (the table's own content), TR > TD (MCID 1)]. With {@code inForm},
   * the table's own content lives in form XObject Fm0 (MCR with /Stm), and the TD's MCID is not used.
   */
  static PDDocument syntheticTable(String content, boolean inForm) throws IOException {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage(new PDRectangle(200, 200));
    doc.addPage(page);
    page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
    PDStructureTreeRoot root = new PDStructureTreeRoot();
    doc.getDocumentCatalog().setStructureTreeRoot(root);
    PDStructureElement document = new PDStructureElement("Document", root);
    root.appendKid(document);
    PDStructureElement table = new PDStructureElement("Table", document);
    table.setPage(page);
    document.appendKid(table);
    PDStructureElement row = new PDStructureElement("TR", table);
    PDStructureElement cell = new PDStructureElement("TD", row);
    cell.setPage(page);
    row.appendKid(cell);
    COSArray nums = new COSArray();
    if (inForm) {
      PDFormXObject form = new PDFormXObject(doc);
      form.setBBox(new PDRectangle(200, 200));
      try (OutputStream out = form.getContentStream().createOutputStream()) {
        out.write(content.getBytes(StandardCharsets.ISO_8859_1));
      }
      form.getCOSObject().setInt(COSName.STRUCT_PARENTS, 1);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("Fm0"), form);
      page.setResources(resources);
      writePage(doc, page, "/Fm0 Do /TD <</MCID 0>> BDC 0 0 5 5 re f EMC");
      COSDictionary mcr = new COSDictionary();
      mcr.setItem(COSName.TYPE, COSName.getPDFName("MCR"));
      mcr.setItem(COSName.getPDFName("Stm"), form.getCOSObject());
      mcr.setItem(COSName.PG, page.getCOSObject());
      mcr.setInt(COSName.MCID, 0);
      table.getCOSObject().setItem(COSName.K, new COSArray(List.of(mcr, row.getCOSObject())));
      cell.getCOSObject().setItem(COSName.K, COSInteger.get(0));
      nums.add(COSInteger.get(0));
      nums.add(new COSArray(List.of(cell)));
      nums.add(COSInteger.get(1));
      nums.add(new COSArray(List.of(table)));
    } else {
      writePage(doc, page, content);
      table.getCOSObject().setItem(COSName.K, new COSArray(List.of(COSInteger.get(0), row.getCOSObject())));
      cell.getCOSObject().setItem(COSName.K, COSInteger.get(1));
      nums.add(COSInteger.get(0));
      nums.add(new COSArray(List.of(table, cell)));
    }
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, nums);
    root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
    root.setParentTreeNextKey(2);
    return doc;
  }

  private static void writePage(PDDocument doc, PDPage page, String content) throws IOException {
    PDStream stream = new PDStream(doc);
    try (OutputStream out = stream.createOutputStream()) {
      out.write(content.getBytes(StandardCharsets.ISO_8859_1));
    }
    page.setContents(stream);
  }
}
