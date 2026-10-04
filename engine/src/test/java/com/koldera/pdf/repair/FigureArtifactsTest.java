package com.koldera.pdf.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.integrity.CopyIntegrity;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.structure.NumberTreeView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class FigureArtifactsTest {

  private static PDDocument load(TaggedOptions options) throws IOException {
    return Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(options));
  }

  private static PDDocument reload(PDDocument doc) throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.save(out);
      doc.close();
      return Loader.loadPDF(out.toByteArray());
    }
  }

  private static List<PDStructureElement> elements(PDDocument doc) {
    List<PDStructureElement> out = new ArrayList<>();
    collect(doc.getDocumentCatalog().getStructureTreeRoot(), out);
    return out;
  }

  private static void collect(PDStructureNode node, List<PDStructureElement> out) {
    for (Object kid : node.getKids()) {
      if (kid instanceof PDStructureElement element) {
        out.add(element);
        collect(element, out);
      }
    }
  }

  private static PDStructureElement first(PDDocument doc, String type) {
    return elements(doc).stream().filter(e -> type.equals(e.getStructureType())).findFirst().orElseThrow();
  }

  private static List<Object> tokens(PDDocument doc) throws IOException {
    return new PDFStreamParser(doc.getPage(0)).parse();
  }

  private static COSArray pageSlots(PDDocument doc) {
    COSDictionary tree = (COSDictionary) doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.PARENT_TREE);
    COSBase value = NumberTreeView.read(tree).get(doc.getPage(0).getCOSObject().getInt(COSName.STRUCT_PARENTS));
    return (COSArray) (value instanceof COSObject o ? o.getObject() : value);
  }

  @Test
  void aFigureWithoutAltBecomesAnArtifactAndLeavesTheTree() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")))) {
      String textBefore = PdfPreflight.extractText(doc);
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(new TreeMap<>(Map.of(1, 1)), outcome.artifactedPerPage());
      assertEquals(0, outcome.keptWithAlt() + outcome.retaggedAsParagraph() + outcome.keptWithAnnotation() + outcome.keptOther());
      try (PDDocument copy = reload(doc)) {
        assertTrue(elements(copy).stream().noneMatch(e -> "Figure".equals(e.getStructureType())), "no Figure element left");
        assertEquals("P", first(copy, "P").getStructureType());
        List<Object> tokens = tokens(copy);
        // P keeps its BDC with MCID 0; the image sequence is /Artifact BMC … EMC without an MCID.
        int bdc = 0;
        int artifactBmc = 0;
        for (int i = 0; i < tokens.size(); i++) {
          if (tokens.get(i) instanceof Operator op && op.getName().equals("BDC")) {
            bdc++;
            assertEquals(COSName.P, tokens.get(i - 2));
          }
          if (tokens.get(i) instanceof Operator op && op.getName().equals("BMC")) {
            assertEquals(COSName.ARTIFACT, tokens.get(i - 1));
            artifactBmc++;
            int draw = nextOperatorIndex(tokens, i, "Do");
            assertTrue(draw > i && draw < nextOperatorIndex(tokens, i, "EMC"), "the image is drawn inside the artifact sequence");
          }
        }
        assertEquals(1, bdc);
        assertEquals(1, artifactBmc);
        COSArray slots = pageSlots(copy);
        assertTrue(slots.get(1) == null || slots.get(1) instanceof COSNull, "the Figure's ParentTree slot is null: " + slots.get(1));
        assertEquals(textBefore, PdfPreflight.extractText(copy));
        assertEquals(Map.of(), CopyIntegrity.inspect(copy).problems());
      }
    }
  }

  private static int nextOperatorIndex(List<Object> tokens, int from, String name) {
    for (int i = from + 1; i < tokens.size(); i++) {
      if (tokens.get(i) instanceof Operator op && op.getName().equals(name)) {
        return i;
      }
    }
    return from;
  }

  @Test
  void aFigureWithAltIsKept() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")).alt(1, "Bar chart of sales"))) {
      List<Object> before = tokens(doc);
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(1, outcome.keptWithAlt());
      assertTrue(outcome.artifactedPerPage().isEmpty());
      assertEquals("Figure", first(doc, "Figure").getStructureType());
      assertEquals(before.toString(), tokens(doc).toString(), "content unchanged");
    }
  }

  @Test
  void aBlankAltCountsAsMissing() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")).alt(1, "   "))) {
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(new TreeMap<>(Map.of(1, 1)), outcome.artifactedPerPage());
      assertTrue(elements(doc).stream().noneMatch(e -> "Figure".equals(e.getStructureType())));
    }
  }

  @Test
  void aFigureWithActualTextIsKept() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")))) {
      first(doc, "Figure").setActualText("Logo");
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(1, outcome.keptWithAlt());
      assertTrue(outcome.artifactedPerPage().isEmpty());
    }
  }

  @Test
  void aFigureWhoseContentShowsTextBecomesAParagraph() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Axis label 2025")))) {
      PDStructureElement element = first(doc, "P");
      element.setStructureType("Figure");
      String textBefore = PdfPreflight.extractText(doc);

      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(1, outcome.retaggedAsParagraph());
      assertTrue(outcome.artifactedPerPage().isEmpty());
      assertEquals("P", element.getStructureType());
      assertEquals(List.of(0), element.getKids(), "the text is still inside its MCID");
      assertTrue(tokens(doc).stream().noneMatch(t -> t instanceof Operator op && op.getName().equals("BMC")), "nothing marked as artifact");
      assertEquals(textBefore, PdfPreflight.extractText(doc));
    }
  }

  @Test
  void aFigureHoldingAnAnnotationIsKept() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")))) {
      PDStructureElement figure = first(doc, "Figure");
      COSDictionary objr = new COSDictionary();
      objr.setItem(COSName.TYPE, COSName.getPDFName("OBJR"));
      objr.setItem(COSName.OBJ, new COSDictionary());
      COSArray kids = new COSArray();
      kids.add(figure.getCOSObject().getItem(COSName.K));
      kids.add(objr);
      figure.getCOSObject().setItem(COSName.K, kids);

      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(1, outcome.keptWithAnnotation());
      assertTrue(outcome.artifactedPerPage().isEmpty());
      assertEquals("Figure", first(doc, "Figure").getStructureType());
    }
  }

  @Test
  void aRoleMappedFigureIsTreated() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Image", "image")).roleMap(Map.of("Image", "Figure")))) {
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(new TreeMap<>(Map.of(1, 1)), outcome.artifactedPerPage());
      assertTrue(elements(doc).stream().noneMatch(e -> "Image".equals(e.getStructureType())));
    }
  }

  @Test
  void aFigureInsideAnotherElementsMarkedContentIsKept() throws Exception {
    // The image's sequence sits inside the P's sequence: marking it as artifact would put an artifact inside tagged content.
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")))) {
      List<Object> tokens = new ArrayList<>(tokens(doc));
      // Move the P's closing EMC after the Figure's EMC: find the first EMC (P's) and remove it, then append one at the end.
      int firstEmc = nextOperatorIndex(tokens, -1, "EMC");
      tokens.remove(firstEmc);
      tokens.add(Operator.getOperator("EMC"));
      PDStream stream = new PDStream(doc);
      try (OutputStream out = stream.createOutputStream()) {
        new ContentStreamWriter(out).writeTokens(tokens);
      }
      doc.getPage(0).setContents(stream);
      List<Object> before = tokens(doc);

      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(1, outcome.keptOther());
      assertTrue(outcome.artifactedPerPage().isEmpty());
      assertEquals(before.toString(), tokens(doc).toString());
    }
  }

  @Test
  void aFigureOnASecondPageIsCountedThere() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("Figure", "a"), new Block("P", "Two", 2), new Block("Figure", "b", 2), new Block("Figure", "c", 2)))) {
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(new TreeMap<>(Map.of(1, 1, 2, 2)), outcome.artifactedPerPage());
      assertFalse(elements(doc).stream().anyMatch(e -> "Figure".equals(e.getStructureType())));
      try (PDDocument copy = reload(doc)) {
        assertEquals(Map.of(), CopyIntegrity.inspect(copy).problems());
      }
    }
  }

  // ---- further cases ------------------------------------------------------------------------------

  private static final COSName IM1 = COSName.getPDFName("Im1");
  private static final COSName F1 = COSName.getPDFName("F1");
  private static final COSName FM1 = COSName.getPDFName("Fm1");

  /** One A4 page with /Im1 (a small raster) and /F1 (Helvetica) in its resources, the given content, and a tree root holding a Document. */
  private static PDDocument rawPage(String content) throws IOException {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    PDResources resources = new PDResources();
    resources.put(IM1, image(doc));
    resources.put(F1, new PDType1Font(Standard14Fonts.FontName.HELVETICA));
    page.setResources(resources);
    page.setContents(stream(doc, content));
    PDStructureTreeRoot root = new PDStructureTreeRoot();
    doc.getDocumentCatalog().setStructureTreeRoot(root);
    root.appendKid(new PDStructureElement("Document", root));
    return doc;
  }

  private static PDImageXObject image(PDDocument doc) throws IOException {
    BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 8; x++) {
      for (int y = 0; y < 8; y++) {
        img.setRGB(x, y, (x + y) % 2 == 0 ? 0x333333 : 0xcccccc);
      }
    }
    return LosslessFactory.createFromImage(doc, img);
  }

  private static PDStream stream(PDDocument doc, String content) throws IOException {
    PDStream stream = new PDStream(doc);
    try (OutputStream out = stream.createOutputStream()) {
      out.write(content.getBytes(StandardCharsets.US_ASCII));
    }
    return stream;
  }

  private static PDFormXObject form(PDDocument doc, String content) throws IOException {
    PDFormXObject form = new PDFormXObject(doc);
    form.setBBox(new PDRectangle(0, 0, 200, 60));
    PDResources resources = new PDResources();
    resources.put(IM1, image(doc));
    resources.put(F1, new PDType1Font(Standard14Fonts.FontName.HELVETICA));
    form.setResources(resources);
    try (OutputStream out = form.getContentStream().createOutputStream()) {
      out.write(content.getBytes(StandardCharsets.US_ASCII));
    }
    return form;
  }

  private static PDStructureElement document(PDDocument doc) {
    return (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
  }

  /** An element of {@code type} appended under {@code parent}, on page 1, whose /K is the given entries (one entry: not an array). */
  private static PDStructureElement element(PDDocument doc, String type, PDStructureElement parent, COSBase... kids) {
    PDStructureElement element = new PDStructureElement(type, parent);
    element.setPage(doc.getPage(0));
    element.getCOSObject().setItem(COSName.K, kids.length == 1 ? kids[0] : new COSArray(List.of(kids)));
    parent.appendKid(element);
    return element;
  }

  private static COSDictionary mcr(PDFormXObject form, int mcid) {
    COSDictionary mcr = new COSDictionary();
    mcr.setItem(COSName.TYPE, COSName.getPDFName("MCR"));
    mcr.setItem(COSName.getPDFName("Stm"), form);
    mcr.setInt(COSName.MCID, mcid);
    return mcr;
  }

  /** A flat ParentTree: key → array of holders. */
  private static void parentTree(PDDocument doc, Map<Integer, List<PDStructureElement>> entries) {
    COSArray nums = new COSArray();
    for (Map.Entry<Integer, List<PDStructureElement>> e : new TreeMap<>(entries).entrySet()) {
      nums.add(COSInteger.get(e.getKey()));
      COSArray slots = new COSArray();
      e.getValue().forEach(holder -> slots.add(holder.getCOSObject()));
      nums.add(slots);
    }
    COSDictionary tree = new COSDictionary();
    tree.setItem(COSName.NUMS, nums);
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    root.getCOSObject().setItem(COSName.PARENT_TREE, tree);
    root.setParentTreeNextKey(10);
  }

  private static long count(List<Object> tokens, String operator) {
    return tokens.stream().filter(t -> t instanceof Operator op && op.getName().equals(operator)).count();
  }

  private static String imageAt(int y) {
    return " q 24 0 0 24 72 " + y + " cm /Im1 Do Q ";
  }

  @Test
  void aNestedSequenceOfTheFiguresOwnKidLosesItsMarkers() throws Exception {
    PDDocument built = rawPage("/Figure <</MCID 0>> BDC /Span <</MCID 1>> BDC" + imageAt(700) + "EMC EMC\n");
    built.getPage(0).setStructParents(0);
    PDStructureElement figure = element(built, "Figure", document(built), COSInteger.get(0));
    PDStructureElement span = element(built, "Span", figure, COSInteger.get(1));
    figure.getCOSObject().setItem(COSName.K, new COSArray(List.of(COSInteger.get(0), span.getCOSObject())));
    parentTree(built, Map.of(0, List.of(figure, span)));
    try (PDDocument doc = reload(built)) {
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(new TreeMap<>(Map.of(1, 1)), outcome.artifactedPerPage());
      List<Object> tokens = tokens(doc);
      assertEquals(0, count(tokens, "BDC"));
      assertEquals(1, count(tokens, "BMC"));
      assertEquals(1, count(tokens, "EMC"), "the Span's EMC went with its BDC");
      COSArray slots = pageSlots(doc);
      assertTrue(slots.get(0) instanceof COSNull && slots.get(1) instanceof COSNull, slots.toString());
      try (PDDocument copy = reload(doc)) {
        assertEquals(Map.of(), CopyIntegrity.inspect(copy).problems());
      }
    }
  }

  @Test
  void aFigureWhoseMcidLivesInAFormIsRewrittenInTheForm() throws Exception {
    PDDocument built = rawPage("/P <</MCID 0>> BDC BT /F1 11 Tf 72 650 Td (Hello) Tj ET EMC q 1 0 0 1 72 700 cm /Fm1 Do Q\n");
    PDFormXObject form = form(built, "/Figure <</MCID 0>> BDC q 24 0 0 24 0 0 cm /Im1 Do Q EMC\n");
    form.setStructParents(1);
    built.getPage(0).getResources().put(FM1, form);
    built.getPage(0).setStructParents(0);
    PDStructureElement p = element(built, "P", document(built), COSInteger.get(0));
    PDStructureElement figure = element(built, "Figure", document(built), mcr(form, 0));
    parentTree(built, Map.of(0, List.of(p), 1, List.of(figure)));
    try (PDDocument doc = reload(built)) {
      List<Object> pageBefore = tokens(doc);
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(new TreeMap<>(Map.of(1, 1)), outcome.artifactedPerPage());
      assertEquals(pageBefore.toString(), tokens(doc).toString(), "the page stream is untouched");
      PDFormXObject rewritten = (PDFormXObject) doc.getPage(0).getResources().getXObject(FM1);
      List<Object> formTokens = new PDFStreamParser(rewritten).parse();
      assertEquals(0, count(formTokens, "BDC"));
      assertEquals(1, count(formTokens, "BMC"));
      try (PDDocument copy = reload(doc)) {
        assertTrue(elements(copy).stream().noneMatch(e -> "Figure".equals(e.getStructureType())));
        assertEquals(Map.of(), CopyIntegrity.inspect(copy).problems());
      }
    }
  }

  @Test
  void aFigureInAFormDrawnTwiceThatAlsoHoldsAnotherElementsContentIsKept() throws Exception {
    PDDocument built = rawPage("q 1 0 0 1 72 700 cm /Fm1 Do Q q 1 0 0 1 72 500 cm /Fm1 Do Q\n");
    PDFormXObject form = form(built,
        "/Figure <</MCID 0>> BDC q 24 0 0 24 0 0 cm /Im1 Do Q EMC /P <</MCID 1>> BDC BT /F1 11 Tf 0 30 Td (Label) Tj ET EMC\n");
    form.setStructParents(1);
    built.getPage(0).getResources().put(FM1, form);
    PDStructureElement figure = element(built, "Figure", document(built), mcr(form, 0));
    PDStructureElement p = element(built, "P", document(built), mcr(form, 1));
    parentTree(built, Map.of(1, List.of(figure, p)));
    try (PDDocument doc = reload(built)) {
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(1, outcome.keptOther());
      assertTrue(outcome.artifactedPerPage().isEmpty());
    }
  }

  @Test
  void aParentTreeWithKidsHasTheSlotNulled() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")))) {
      COSDictionary tree = (COSDictionary) doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.PARENT_TREE);
      COSDictionary leaf = new COSDictionary();
      leaf.setItem(COSName.NUMS, tree.getDictionaryObject(COSName.NUMS));
      leaf.setItem(COSName.LIMITS, new COSArray(List.of(COSInteger.ZERO, COSInteger.ZERO)));
      tree.removeItem(COSName.NUMS);
      tree.setItem(COSName.KIDS, new COSArray(List.of(leaf)));

      assertEquals(new TreeMap<>(Map.of(1, 1)), FigureArtifacts.apply(doc).artifactedPerPage());
      assertTrue(pageSlots(doc).get(1) instanceof COSNull, pageSlots(doc).toString());
    }
  }

  /** The element (Sect in the nested fixture) that holds the Figure. */
  private static PDStructureElement holderOfFigure(PDDocument doc) {
    return elements(doc).stream()
        .filter(e -> e.getKids().stream().anyMatch(k -> k instanceof PDStructureElement f && "Figure".equals(f.getStructureType())))
        .findFirst().orElseThrow();
  }

  private static boolean inTree(PDDocument doc, PDStructureElement element) {
    return elements(doc).stream().anyMatch(e -> e.getCOSObject() == element.getCOSObject());
  }

  @Test
  void aSectLeftEmptyByTheFigureGoesToo() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")).nested(true))) {
      PDStructureElement sect = holderOfFigure(doc);
      assertEquals("Sect", sect.getStructureType());
      FigureArtifacts.apply(doc);

      assertFalse(inTree(doc, sect), "the emptied Sect is gone");
      assertEquals(1, document(doc).getKids().size(), "only the P's Sect is left");
      try (PDDocument copy = reload(doc)) {
        assertEquals(Map.of(), CopyIntegrity.inspect(copy).problems());
      }
    }
  }

  @Test
  void anEmptiedTableCellStays() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")).nested(true))) {
      PDStructureElement cell = holderOfFigure(doc);
      cell.setStructureType("TD");
      assertEquals(new TreeMap<>(Map.of(1, 1)), FigureArtifacts.apply(doc).artifactedPerPage());

      assertTrue(inTree(doc, cell), "the TD is kept");
    }
  }

  @Test
  void anEmptiedElementWithAnIdStays() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")).nested(true))) {
      PDStructureElement sect = holderOfFigure(doc);
      sect.setElementIdentifier("chapter-2");
      assertEquals(new TreeMap<>(Map.of(1, 1)), FigureArtifacts.apply(doc).artifactedPerPage());

      assertTrue(inTree(doc, sect), "the Sect with an /ID is kept");
    }
  }

  @Test
  void aRemovedFiguresIdLeavesTheIdTree() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")))) {
      PDStructureElement figure = first(doc, "Figure");
      PDStructureElement p = first(doc, "P");
      figure.setElementIdentifier("fig-1");
      p.setElementIdentifier("para-1");
      COSDictionary idTree = new COSDictionary();
      idTree.setItem(COSName.NAMES, new COSArray(List.of(new COSString("fig-1"), figure.getCOSObject(), new COSString("para-1"), p.getCOSObject())));
      doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().setItem(COSName.getPDFName("IDTree"), idTree);

      assertEquals(new TreeMap<>(Map.of(1, 1)), FigureArtifacts.apply(doc).artifactedPerPage());
      COSArray names = (COSArray) idTree.getDictionaryObject(COSName.NAMES);
      assertEquals(2, names.size(), names.toString());
      assertEquals("para-1", ((COSString) names.getObject(0)).getString());
    }
  }

  @Test
  void aFigureWhoseIntegerMcidHasNoPageIsKept() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")))) {
      first(doc, "Figure").getCOSObject().removeItem(COSName.PG);
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(1, outcome.keptOther());
      assertTrue(outcome.artifactedPerPage().isEmpty());
      assertTrue(elements(doc).stream().anyMatch(e -> "Figure".equals(e.getStructureType())));
    }
  }

  @Test
  void aFigureWhoseMcidIsNotInTheContentIsKept() throws Exception {
    try (PDDocument doc = load(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image")))) {
      first(doc, "Figure").getCOSObject().setItem(COSName.K, COSInteger.get(7));
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(1, outcome.keptOther());
      assertTrue(outcome.artifactedPerPage().isEmpty());
    }
  }

  @Test
  void aFigureNestedInAnArtifactedFigureIsCountedToo() throws Exception {
    PDDocument built = rawPage("/Figure <</MCID 0>> BDC" + imageAt(700) + "EMC /Figure <</MCID 1>> BDC" + imageAt(600) + "EMC\n");
    built.getPage(0).setStructParents(0);
    PDStructureElement outer = element(built, "Figure", document(built), COSInteger.get(0));
    PDStructureElement inner = element(built, "Figure", outer, COSInteger.get(1));
    outer.getCOSObject().setItem(COSName.K, new COSArray(List.of(COSInteger.get(0), inner.getCOSObject())));
    parentTree(built, Map.of(0, List.of(outer, inner)));
    try (PDDocument doc = reload(built)) {
      FigureArtifacts.Outcome outcome = FigureArtifacts.apply(doc);

      assertEquals(new TreeMap<>(Map.of(1, 2)), outcome.artifactedPerPage());
      assertEquals(2, count(tokens(doc), "BMC"));
      assertEquals(0, count(tokens(doc), "BDC"));
    }
  }
}
