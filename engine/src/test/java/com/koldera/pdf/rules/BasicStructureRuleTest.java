package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.integrity.CopyIntegrity;
import com.koldera.pdf.preflight.PdfPreflight;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BasicStructureRuleTest {

  private static final Set<String> CONTENT_OPERATORS =
      Set.of("Tj", "TJ", "'", "\"", "Do", "BI", "sh", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*");

  private final BasicStructureRule rule = new BasicStructureRule();

  // ---- untagged article -------------------------------------------------------------------

  @Test
  void tagsParagraphsFigureAndArtifactInContentOrder() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1, action.ruleId());
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("tagged 3 paragraphs, 1 figures, 1 artifacts on 1 pages"), action.reason());
      assertTrue(action.reason().contains("headings, tables, alternative text and reading order need human review"), action.reason());

      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      assertNotNull(root, "StructTreeRoot must exist");
      assertTrue(doc.getDocumentCatalog().getMarkInfo().isMarked());
      List<Element> elements = documentKids(root);
      assertEquals(List.of("P", "P", "Figure", "P"), elements.stream().map(Element::type).toList());
      assertEquals(List.of(0, 1), elements.get(0).mcids(), "first paragraph spans two text objects, one MCID each");
      assertEquals(List.of(2, 3), elements.get(1).mcids());
      assertEquals(List.of(4), elements.get(2).mcids());
      assertEquals(List.of(5, 6), elements.get(3).mcids());
      assertNull(elements.get(2).dict().getDictionaryObject(COSName.ALT), "no alternative text is invented");
      assertNull(elements.get(2).dict().getDictionaryObject(COSName.ACTUAL_TEXT));
      COSDictionary figureAttributes = (COSDictionary) elements.get(2).dict().getDictionaryObject(COSName.A);
      assertNotNull(figureAttributes, "Figure carries a Layout attribute with its BBox");
      assertEquals("Layout", figureAttributes.getNameAsString(COSName.O));
      assertNotNull(figureAttributes.getCOSArray(COSName.BBOX));
      for (Element element : elements) {
        assertSame(doc.getPage(0).getCOSObject(), element.dict().getCOSDictionary(COSName.PG), "every element names its page");
        assertSame(elementParent(element.dict()), documentElement(root), "every element has /P");
      }

      // Every content operator sits inside exactly one marked-content wrapper of the expected kind.
      List<Marked> marked = markedContent(doc.getPage(0), doc.getPage(0));
      assertEquals(9, marked.size(), marked.toString());
      for (Marked m : marked) {
        assertEquals(1, m.stack().size(), "content nested exactly once: " + m);
      }
      assertTrue(marked.stream().filter(m -> m.operator().equals("Tj")).allMatch(m -> m.stack().getFirst().equals("P")), marked.toString());
      assertEquals(List.of("Figure"), marked.stream().filter(m -> m.operator().equals("Do")).map(m -> m.stack().getFirst()).toList());
      assertEquals(List.of("Artifact"), marked.stream().filter(m -> m.operator().equals("S")).map(m -> m.stack().getFirst()).toList());
      assertEquals("Layout", marked.stream().filter(m -> m.operator().equals("S")).findFirst().orElseThrow().artifactType());

      // MCIDs are unique per page and every one resolves through the ParentTree to its element.
      List<Integer> mcids = marked.stream().map(Marked::mcid).filter(x -> x >= 0).toList();
      assertEquals(8, mcids.size(), "seven text shows and one image carry an MCID");
      assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6), new HashSet<>(mcids), "MCIDs unique per page, numbered in content order");
      assertEquals(0, doc.getPage(0).getCOSObject().getInt(COSName.STRUCT_PARENTS));
      COSArray parents = parentTreeEntry(root, 0);
      assertEquals(7, parents.size());
      for (int mcid = 0; mcid < 7; mcid++) {
        COSDictionary parent = (COSDictionary) resolve(parents.get(mcid));
        assertTrue(kidMcids(parent).contains(mcid), "ParentTree entry " + mcid + " points at the element that owns it");
      }
      assertEquals(1, root.getParentTreeNextKey());
    }
  }

  static Stream<Arguments> rewrittenFixtures() {
    return Stream.of(
        Arguments.of("article", SyntheticPdfFactory.untaggedArticle()),
        Arguments.of("forms", SyntheticPdfFactory.untaggedWithFormXObjects()),
        Arguments.of("running header", SyntheticPdfFactory.untaggedWithRunningHeader(4)),
        Arguments.of("contents array", SyntheticPdfFactory.untaggedWithContentArray()),
        Arguments.of("inline image, clip, q/Q in BT", SyntheticPdfFactory.untaggedWithInlineImageClipAndNestedState()),
        Arguments.of("predictor form", SyntheticPdfFactory.untaggedWithPredictorForm()),
        Arguments.of("dangling xobject", SyntheticPdfFactory.untaggedWithDanglingXObject()),
        Arguments.of("empty and missing contents", SyntheticPdfFactory.untaggedWithEmptyAndMissingContents()),
        Arguments.of("stray marked content", SyntheticPdfFactory.untaggedWithStrayMarkedContent()),
        Arguments.of("stray marked content in artifact bands", SyntheticPdfFactory.untaggedWithStrayMarkedContentInArtifactBands()),
        Arguments.of("painting operators with no path", SyntheticPdfFactory.untaggedWithStrayPaintOperatorsInPageAndForm()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rewrittenFixtures")
  void textExtractionAndRenderingAreUnchangedAndOutputIsByteDeterministic(String name, byte[] original) throws IOException {
    String textBefore;
    List<int[]> pixelsBefore = new ArrayList<>();
    int pages;
    try (PDDocument doc = Loader.loadPDF(original)) {
      textBefore = PdfPreflight.extractText(doc);
      pages = doc.getNumberOfPages();
      for (int i = 0; i < pages; i++) {
        pixelsBefore.add(render(doc, i));
      }
    }

    byte[] first = applyAndSave(original);
    byte[] second = applyAndSave(original);

    assertArrayEquals(first, second, "two runs must produce byte-identical files");
    try (PDDocument doc = Loader.loadPDF(first)) {
      assertEquals(textBefore, PdfPreflight.extractText(doc), "text extraction unchanged");
      assertEquals(pages, doc.getNumberOfPages());
      for (int i = 0; i < pages; i++) {
        assertArrayEquals(pixelsBefore.get(i), render(doc, i), "page " + (i + 1) + " raster at 72 dpi unchanged");
        if (doc.getPage(i).hasContents()) {
          markedContent(doc.getPage(i), doc.getPage(i)); // balanced and properly nested
        }
      }
    }
  }

  // ---- content streams: arrays, inline images, clipping, nested state, empty pages ----------

  @Test
  void contentsArrayIsRewrittenAsOneStreamWithWrappersThatNeverCrossTheOldBoundary() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithContentArray())) {
      assertTrue(doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS) instanceof COSArray, "fixture has a /Contents array");

      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertFalse(doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS) instanceof COSArray, "one stream after the rewrite");
      List<Marked> marks = markedContent(doc.getPage(0), doc.getPage(0));
      assertEquals(List.of("Tj", "Tj", "Tj"), marks.stream().map(Marked::operator).toList());
      assertEquals(List.of("P", "Span"), marks.get(0).stack(), "the existing Span block is wrapped as a unit");
      assertEquals(List.of("P", "Span"), marks.get(1).stack());
      assertEquals(List.of("P"), marks.get(2).stack());
      assertEquals(marks.get(0).mcid(), marks.get(1).mcid(), "both halves of the split text object share one MCID");
      assertEquals(List.of("P"), documentKids(doc.getDocumentCatalog().getStructureTreeRoot()).stream().map(Element::type).toList(), "lines 14 pt apart form one paragraph");
    }
  }

  @Test
  void inlineImageIsAFigureClippingIsNotWrappedAndStateInsideTextObjectSplitsRuns() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithInlineImageClipAndNestedState())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("tagged 1 paragraphs, 1 figures, 0 artifacts on 1 pages"), "the W n clip is no content item: " + action.reason());
      List<Marked> marks = markedContent(doc.getPage(0), doc.getPage(0));
      assertEquals(List.of("BI", "Tj", "Tj", "Tj"), marks.stream().map(Marked::operator).toList());
      assertEquals(List.of("Figure"), marks.get(0).stack());
      assertEquals(0, marks.get(0).mcid());
      assertEquals(List.of(1, 2, 3), marks.subList(1, 4).stream().map(Marked::mcid).toList(), "q/Q inside BT close and reopen the paragraph section");
      assertTrue(marks.subList(1, 4).stream().allMatch(m -> m.stack().equals(List.of("P"))));
      List<Element> elements = documentKids(doc.getDocumentCatalog().getStructureTreeRoot());
      assertEquals(List.of("Figure", "P"), elements.stream().map(Element::type).toList());
      assertEquals(List.of(1, 2, 3), elements.get(1).mcids(), "one paragraph, three MCIDs");
      List<Object> tokens = new PDFStreamParser(doc.getPage(0)).parse();
      int clip = -1;
      for (int i = 0; i < tokens.size(); i++) {
        if (tokens.get(i) instanceof Operator op && op.getName().equals("W")) {
          clip = i;
        }
      }
      assertTrue(clip > 0);
      assertEquals("re", ((Operator) tokens.get(clip - 1)).getName(), "no wrapper between the path construction and W");
      assertEquals("n", ((Operator) tokens.get(clip + 1)).getName());
      assertFalse(tokens.get(clip + 2) instanceof Operator op2 && op2.getName().equals("EMC"), "a painted-nothing path is not wrapped");
    }
  }

  @Test
  void pagesWithoutContentsOrWithAnEmptyStreamGetNoOrAnEmptyParentTreeEntry() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithEmptyAndMissingContents())) {
      assertFalse(doc.getPage(1).hasContents());

      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("tagged 1 paragraphs, 0 figures, 0 artifacts on 3 pages"), action.reason());
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      assertEquals(0, doc.getPage(0).getCOSObject().getInt(COSName.STRUCT_PARENTS));
      assertEquals(1, parentTreeEntry(root, 0).size());
      assertFalse(doc.getPage(1).getCOSObject().containsKey(COSName.STRUCT_PARENTS), "a page without /Contents gets no StructParents");
      assertFalse(doc.getPage(1).hasContents(), "and no content stream is invented");
      assertEquals(1, doc.getPage(2).getCOSObject().getInt(COSName.STRUCT_PARENTS));
      assertEquals(0, parentTreeEntry(root, 1).size(), "an empty stream keeps an empty ParentTree entry");
      assertEquals(2, root.getParentTreeNextKey());
    }
  }

  @Test
  void predictorEncodedFormIsRewrittenWithFreshFilterParameters() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithPredictorForm())) {
      String textBefore = PdfPreflight.extractText(doc);
      assertTrue(textBefore.contains("Inside predictor form."), "fixture decodes before the rewrite: " + textBefore);
      int[] pixelsBefore = render(doc, 0);

      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("tagged 2 paragraphs, 1 figures, 0 artifacts on 1 pages"), action.reason());
      PDFormXObject form = null;
      for (COSName name : doc.getPage(0).getResources().getXObjectNames()) {
        if (doc.getPage(0).getResources().getXObject(name) instanceof PDFormXObject f) {
          form = f;
        }
      }
      assertNotNull(form);
      assertNull(form.getCOSObject().getDictionaryObject(COSName.DECODE_PARMS), "stale predictor parameters are dropped");
      assertEquals("FlateDecode", form.getCOSObject().getNameAsString(COSName.FILTER));
      assertEquals(textBefore, PdfPreflight.extractText(doc), "form text survives the rewrite");
      assertArrayEquals(pixelsBefore, render(doc, 0), "form image and text render identically");
      List<Marked> formMarks = markedContent(form, doc.getPage(0));
      assertEquals(List.of("Tj", "Do"), formMarks.stream().map(Marked::operator).toList());
      assertEquals(List.of("P"), formMarks.get(0).stack());
      assertEquals(List.of("Figure"), formMarks.get(1).stack());
    }
  }

  @Test
  void danglingXObjectNameIsKeptAsAnArtifact() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithDanglingXObject())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      List<Marked> marks = markedContent(doc.getPage(0), doc.getPage(0));
      assertEquals(List.of("Do", "Tj"), marks.stream().map(Marked::operator).toList());
      assertEquals(List.of("Artifact"), marks.get(0).stack(), "a name that draws nothing stays decorative");
      assertEquals(List.of("P"), marks.get(1).stack());
    }
  }

  @Test
  void xObjectThatCannotBeLoadedMakesTheRuleSkipInsteadOfGuessing() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithUnknownXObjectSubtype())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));

      assertFalse(decision.applicable());
      assertTrue(decision.reason().contains("XObject"), decision.reason());
      assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, decision).outcome());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
    }
  }

  // ---- forms, running headers, nesting ------------------------------------------------------

  @Test
  void recursesIntoAFormDrawnOnceAndMarksARepeatedVectorFormAsArtifact() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithFormXObjects())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("tagged 2 paragraphs, 0 figures, 2 artifacts on 1 pages"), action.reason());
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      assertEquals(List.of("P", "P"), documentKids(root).stream().map(Element::type).toList());

      PDPage page = doc.getPage(0);
      List<Marked> pageMarks = markedContent(page, page);
      assertEquals(List.of("Tj", "Do", "Do", "Do"), pageMarks.stream().map(Marked::operator).toList());
      assertEquals(0, pageMarks.get(1).stack().size(), "a form drawn once is not wrapped: its own stream carries the tags");
      assertEquals(List.of("Artifact"), pageMarks.get(2).stack());
      assertEquals(List.of("Artifact"), pageMarks.get(3).stack());

      List<Marked> formMarks = new ArrayList<>();
      for (COSName name : page.getResources().getXObjectNames()) {
        if (page.getResources().getXObject(name) instanceof PDFormXObject form && !markedContent(form, page).isEmpty()) {
          formMarks.addAll(markedContent(form, page));
        }
      }
      List<Marked> formText = formMarks.stream().filter(m -> m.operator().equals("Tj")).toList();
      assertEquals(1, formText.size(), formMarks.toString());
      assertEquals(List.of("P"), formText.get(0).stack());
      assertEquals(0, formText.get(0).mcid(), "MCIDs are numbered per content stream (ISO 32000-1 14.7.4.4)");
      assertEquals(1, parentTreeEntry(root, 0).size(), "page stream: one MCID");
      PDFormXObject textForm = null;
      for (COSName name : page.getResources().getXObjectNames()) {
        if (page.getResources().getXObject(name) instanceof PDFormXObject form && markedContent(form, page).stream().anyMatch(m -> m.operator().equals("Tj"))) {
          textForm = form;
        }
      }
      assertNotNull(textForm);
      assertEquals(1, textForm.getStructParents(), "the once-drawn form owns its ParentTree key");
      assertEquals(1, parentTreeEntry(root, 1).size());
      assertEquals(2, root.getParentTreeNextKey());
      COSDictionary formParagraph = documentKids(root).get(1).dict();
      COSDictionary mcr = (COSDictionary) resolve(formParagraph.getDictionaryObject(COSName.K));
      assertEquals("MCR", mcr.getNameAsString(COSName.TYPE), "an MCID inside a form is referenced through an MCR naming the stream");
      assertSame(textForm.getCOSObject(), mcr.getDictionaryObject(COSName.getPDFName("Stm")));
      assertEquals(0, mcr.getInt(COSName.MCID));
    }
  }

  @Test
  void strayMcidsAreAdoptedNeverNestedOrArtifactedAndNewMcidsNeverCollide() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithStrayMarkedContent())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      List<Element> kids = documentKids(root);
      assertEquals(List.of("P", "P", "Figure", "P"), kids.stream().map(Element::type).toList(), "content order: plain line, stray page paragraph, then the form's figure and span");

      PDPage page = doc.getPage(0);
      List<Marked> pageMarks = markedContent(page, page);
      assertEquals(List.of("Tj", "Tj", "Do"), pageMarks.stream().map(Marked::operator).toList());
      assertEquals(List.of("P"), pageMarks.get(0).stack());
      assertEquals(List.of("P"), pageMarks.get(1).stack(), "the existing /P <</MCID 0>> block is kept, not wrapped in a second sequence");
      assertEquals(0, pageMarks.get(1).mcid());
      assertNotEquals(0, pageMarks.get(0).mcid(), "a new MCID never collides with an existing one in the same stream");
      assertEquals(List.of(), pageMarks.get(2).stack());
      assertEquals(List.of(pageMarks.get(0).mcid()), kids.get(0).mcids());
      assertEquals(List.of(0), kids.get(1).mcids());

      PDFormXObject form = (PDFormXObject) page.getResources().getXObject(COSName.getPDFName("Fx1"));
      List<Marked> formMarks = markedContent(form, page);
      assertEquals(List.of("S", "Tj"), formMarks.stream().map(Marked::operator).toList());
      assertEquals(List.of("Figure"), formMarks.get(0).stack(), "an existing tagged vector block is adopted as a Figure, never wrapped in an Artifact");
      assertEquals(2, formMarks.get(0).mcid());
      assertEquals(List.of("Span"), formMarks.get(1).stack());
      assertEquals(5, formMarks.get(1).mcid());
      assertEquals(List.of(2), kids.get(2).mcids());
      assertEquals(List.of(5), kids.get(3).mcids());

      COSArray pageParents = parentTreeEntry(root, page.getCOSObject().getInt(COSName.STRUCT_PARENTS));
      assertSame(kids.get(1).dict(), resolve(pageParents.get(0)));
      assertSame(kids.get(0).dict(), resolve(pageParents.get(pageMarks.get(0).mcid())));
      COSArray formParents = parentTreeEntry(root, form.getStructParents());
      assertEquals(6, formParents.size(), "ParentTree covers the highest adopted MCID");
      assertSame(kids.get(2).dict(), resolve(formParents.get(2)));
      assertSame(kids.get(3).dict(), resolve(formParents.get(5)));
      assertTrue(action.reason().contains("tagged 3 paragraphs, 1 figures, 0 artifacts on 1 pages"), action.reason());
    }
  }

  @Test
  void strayMcidsInThePaginationBandOrOutsideTheCropBoxAreAdoptedNotArtifacted() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithStrayMarkedContentInArtifactBands())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("tagged 9 paragraphs, 0 figures, 0 artifacts on 3 pages"), action.reason());
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      List<Element> kids = documentKids(root);
      assertEquals(9, kids.size());
      assertTrue(kids.stream().allMatch(k -> k.type().equals("P")), kids.toString());
      for (int p = 0; p < 3; p++) {
        PDPage page = doc.getPage(p);
        List<Marked> marks = markedContent(page, page);
        assertEquals(List.of("Tj", "Tj", "Tj"), marks.stream().map(Marked::operator).toList());
        assertEquals(List.of("P"), marks.get(0).stack(), "the header block keeps its own /P and gets no Artifact wrapper");
        assertEquals(0, marks.get(0).mcid());
        assertEquals(List.of("P"), marks.get(1).stack());
        assertEquals(List.of("Span"), marks.get(2).stack(), "the block outside the crop box keeps its own /Span and gets no Artifact wrapper");
        assertEquals(1, marks.get(2).mcid());
        COSArray parents = parentTreeEntry(root, page.getCOSObject().getInt(COSName.STRUCT_PARENTS));
        assertEquals(3, parents.size());
        assertSame(kids.get(p * 3).dict(), resolve(parents.get(0)), "MCID 0 resolves to the adopted header paragraph");
        assertSame(kids.get(p * 3 + 1).dict(), resolve(parents.get(marks.get(1).mcid())));
        assertSame(kids.get(p * 3 + 2).dict(), resolve(parents.get(1)), "MCID 1 resolves to the adopted out-of-crop paragraph");
      }
    }
  }

  @Test
  void strayMcidInsideAnArtifactOrMixedWithUnmarkedContentIsRefusedBeforeValidation() throws IOException {
    for (byte[] bytes : new byte[][] {SyntheticPdfFactory.untaggedWithStrayMcidInsideArtifact(), SyntheticPdfFactory.untaggedWithStrayMcidMixedWithUnmarkedText()}) {
      try (PDDocument doc = Loader.loadPDF(bytes)) {
        RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));

        assertFalse(decision.applicable(), decision.reason());
        assertTrue(decision.reason().contains("marked content") && decision.reason().contains("MCID"), decision.reason());
        assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, decision).outcome());
        assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
      }
    }
  }

  @Test
  void repeatedHeaderTextBecomesAPaginationArtifactWhilePageNumbersStayParagraphs() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithRunningHeader(4))) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("tagged 8 paragraphs, 0 figures, 4 artifacts on 4 pages"), action.reason());
      for (int i = 0; i < 4; i++) {
        List<Marked> marks = markedContent(doc.getPage(i), doc.getPage(i));
        assertEquals(3, marks.size());
        assertEquals(List.of("Artifact"), marks.get(0).stack(), "running header");
        assertEquals("Pagination", marks.get(0).artifactType());
        assertEquals(List.of("P"), marks.get(1).stack(), "body");
        assertEquals(List.of("P"), marks.get(2).stack(), "page number differs per page: not inferred as pagination");
        assertEquals(i, doc.getPage(i).getCOSObject().getInt(COSName.STRUCT_PARENTS));
        assertEquals(2, parentTreeEntry(doc.getDocumentCatalog().getStructureTreeRoot(), i).size());
      }
    }
  }

  @Test
  void unbalancedExistingMarkedContentIsSkippedNotGuessed() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedUnbalancedMarkedContent())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));

      assertFalse(decision.applicable());
      assertTrue(decision.reason().contains("marked content"), decision.reason());
      assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, decision).outcome());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
    }
  }

  @Test
  void paintingOperatorsWithNoPathAreRemovedFromTheCopyAndTheRestIsTagged() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithStrayPaintOperatorsInPageAndForm())) {
      assertEquals(Map.of(CopyIntegrity.OPERATOR_OUT_OF_STATE, 3), CopyIntegrity.inspect(doc).problems());
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("removed 3 painting operators that had no path to paint"), action.reason());
      assertEquals(Map.of(), CopyIntegrity.inspect(doc).problems(), "no operator out of state is left, page or form");
      PDPage page = doc.getPage(0);
      List<String> pageOps = markedContent(page, page).stream().map(Marked::operator).toList();
      assertEquals(List.of("Tj", "f", "f", "Do", "Tj"), pageOps, "the valid 0 0 m f and the filled triangle stay; the bare f is gone");
      PDFormXObject form = (PDFormXObject) page.getResources().getXObject(COSName.getPDFName("Fx1"));
      assertEquals(List.of("S", "Tj", "f"), markedContent(form, page).stream().map(Marked::operator).toList(), "the bare S and b* are gone");
    }
  }

  @Test
  void aPaintingOperatorWithNoPathButWithOperandsInFrontIsRefusedAndNothingIsRewritten() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithStrayPaintOperatorWithOperands())) {
      PDPage page = doc.getPage(0);
      PDFormXObject form = (PDFormXObject) page.getResources().getXObject(COSName.getPDFName("Fx1"));
      byte[] pageBefore = page.getContents().readAllBytes();
      byte[] formBefore = form.getContents().readAllBytes();

      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      WorkerResult.Action action = rule.apply(doc, decision);

      assertFalse(decision.applicable(), decision.reason());
      assertTrue(decision.reason().contains("stray operands"), decision.reason());
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
      assertArrayEquals(pageBefore, page.getContents().readAllBytes(), "page content stream byte-unchanged");
      assertArrayEquals(formBefore, form.getContents().readAllBytes(), "form content stream byte-unchanged");
    }
  }

  /**
   * the conservative walk keeps the pre-branch tagging:
   * painting operators with no path are neither removed nor refused there. The expected streams are
   * what an earlier engine build wrote for these fixtures, byte for byte.
   */
  private static final String PAINTS_PAGE_AT_0449F7B = "BT\n"
        + "/F1 12 Tf\n"
        + "72 720 Td\n"
        + "/P <</MCID 0 >> BDC\n"
        + "(Text before the figure.) Tj\n"
        + "EMC\n"
        + "ET\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "0 0 m\n"
        + "f\n"
        + "EMC\n"
        + "0 0 1 rg\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "100 300 m\n"
        + "200 300 l\n"
        + "200 400 l\n"
        + "h\n"
        + "f\n"
        + "EMC\n"
        + "0 0 0 rg\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "f\n"
        + "EMC\n"
        + "q\n"
        + "1 0 0 1 72 500 cm\n"
        + "/Fx1 Do\n"
        + "Q\n"
        + "BT\n"
        + "/F1 12 Tf\n"
        + "72 200 Td\n"
        + "/P <</MCID 1 >> BDC\n"
        + "(Text after the figure.) Tj\n"
        + "EMC\n"
        + "ET\n";
  private static final String PAINTS_FORM_AT_0449F7B = "1 0 0 RG\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "0 0 m\n"
        + "200 0 l\n"
        + "S\n"
        + "EMC\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "S\n"
        + "EMC\n"
        + "BT\n"
        + "/F1 10 Tf\n"
        + "0 50 Td\n"
        + "/P <</MCID 0 >> BDC\n"
        + "(Label inside the figure.) Tj\n"
        + "EMC\n"
        + "ET\n"
        + "0 1 0 rg\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "10 10 50 20 re\n"
        + "f\n"
        + "EMC\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "b*\n"
        + "EMC\n";
  private static final String OPERANDS_PAGE_AT_0449F7B = "BT\n"
        + "/F1 12 Tf\n"
        + "72 720 Td\n"
        + "/P <</MCID 0 >> BDC\n"
        + "(Text before.) Tj\n"
        + "EMC\n"
        + "ET\n"
        + "0 0 0 rg\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "1 f\n"
        + "EMC\n"
        + "q\n"
        + "Q\n"
        + "q\n"
        + "1 0 0 1 72 500 cm\n"
        + "/Fx1 Do\n"
        + "Q\n"
        + "BT\n"
        + "/F1 12 Tf\n"
        + "72 200 Td\n"
        + "/P <</MCID 1 >> BDC\n"
        + "(Text after.) Tj\n"
        + "EMC\n"
        + "ET\n";
  private static final String OPERANDS_FORM_AT_0449F7B = "BT\n"
        + "/F1 10 Tf\n"
        + "0 50 Td\n"
        + "/P <</MCID 0 >> BDC\n"
        + "(Label inside the figure.) Tj\n"
        + "EMC\n"
        + "ET\n"
        + "/Artifact <</Type /Layout >> BDC\n"
        + "2 S\n"
        + "EMC\n";

  @Test
  void strayPaintingOperatorsAreKeptAndNothingIsTaggedWhenRemovingThemWouldChangeTheRendering() throws IOException {
    // Test seam: the check runs on a scratch copy; drawing into it simulates a renderer that painted the stray operator.
    BasicStructureRule guarded = new BasicStructureRule(scratch -> {
      try (PDPageContentStream cs = new PDPageContentStream(scratch, scratch.getPage(0), PDPageContentStream.AppendMode.APPEND, false)) {
        cs.addRect(0, 0, 50, 50);
        cs.fill();
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    });
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithStrayPaintOperatorsInPageAndForm())) {
      RuleDecision decision = guarded.inspect(doc, request(WorkerRequest.Mode.FIX));

      assertFalse(decision.applicable(), decision.reason());
      assertTrue(decision.reason().contains("3 painting operators that have no path") && decision.reason().contains("rendering of page 1"), decision.reason());
      assertEquals(ActionOutcome.SKIPPED, guarded.apply(doc, decision).outcome());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
      assertEquals(Map.of(CopyIntegrity.OPERATOR_OUT_OF_STATE, 3), CopyIntegrity.inspect(doc).problems(), "the copy keeps its content streams");
    }
  }

  @Test
  void theScratchCheckLeavesTheDocumentAndItsMissingTrailerIdUntouched() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithStrayPaintOperatorsInPageAndForm())) {
      doc.getDocument().getTrailer().removeItem(COSName.ID);
      java.util.Map<COSName, COSBase> trailerBefore = new java.util.LinkedHashMap<>();
      doc.getDocument().getTrailer().entrySet().forEach(e -> trailerBefore.put(e.getKey(), e.getValue()));

      assertTrue(rule.inspect(doc, request(WorkerRequest.Mode.FIX)).applicable());

      assertNull(doc.getDocument().getTrailer().getItem(COSName.ID), "the scratch save must not leave a wall-clock /ID behind");
      assertEquals(trailerBefore.keySet(), doc.getDocument().getTrailer().keySet());
      trailerBefore.forEach((k, v) -> assertSame(v, doc.getDocument().getTrailer().getItem(k), "trailer " + k.getName()));
      assertEquals(Map.of(CopyIntegrity.OPERATOR_OUT_OF_STATE, 3), CopyIntegrity.inspect(doc).problems(), "inspect never rewrites the document");
    }
  }

  @Test
  void anEndPathOperatorWithNoPathIsStillTagged() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithEndPathWithoutPath())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("tagged 2 paragraphs, 0 figures, 1 artifacts on 1 pages"), action.reason());
    }
  }

  // ---- preconditions ------------------------------------------------------------------------

  @Test
  void alreadyTaggedDocumentIsSkipped() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));

      assertFalse(decision.applicable());
      assertTrue(decision.reason().contains("already tagged"), decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
    }
  }

  @Test
  void onlyFixModeApplies() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      assertFalse(rule.inspect(doc, request(WorkerRequest.Mode.ANALYZE)).applicable());
      assertTrue(rule.inspect(doc, request(WorkerRequest.Mode.FIX)).applicable());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot(), "inspect never mutates");
      assertNull(doc.getDocumentCatalog().getMarkInfo());
    }
  }

  // ---- shared form XObjects (mode fix only) -------------------------------------------------

  @Test
  void fixWrapsASharedFormOnEveryPageAsPagination() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithSharedTextFormOnEveryPage(3))) {
      String textBefore = PdfPreflight.extractText(doc);
      String unchanged = com.koldera.pdf.structure.RenderIdentity.changes(doc, List.of(1, 2, 3), scratch -> {
        BasicStructureRule scratchRule = new BasicStructureRule();
        WorkerResult.Action scratchAction = scratchRule.apply(scratch, scratchRule.inspect(scratch, request(WorkerRequest.Mode.FIX)));
        assertEquals(ActionOutcome.APPLIED, scratchAction.outcome(), scratchAction.reason());
      });
      assertNull(unchanged, "rendering of pages 1-3 and the extracted text are identical");

      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("shared forms 3 (page furniture 3)"), action.reason());
      assertEquals(textBefore, PdfPreflight.extractText(doc), "text digest unchanged");
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      assertEquals(List.of("P", "P", "P"), documentKids(root).stream().map(Element::type).toList(), "the body lines only");
      for (int p = 0; p < 3; p++) {
        PDPage page = doc.getPage(p);
        String tokens = tokenText(page);
        assertTrue(tokens.contains("q 1 0 0 1 72 800 cm /Artifact <</Type /Pagination>> BDC /Fm1 Do EMC Q"), tokens);
        List<Marked> marks = markedContent(page, page);
        assertEquals(List.of("Do", "Tj"), marks.stream().map(Marked::operator).toList());
        assertEquals(List.of("Artifact"), marks.get(0).stack());
        assertEquals("Pagination", marks.get(0).artifactType());
        assertEquals(List.of("P"), marks.get(1).stack());
      }
    }
  }

  @Test
  void fixTagsASharedFormAtTwoPositionsAsTwoParagraphs() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithSharedTextFormAtTwoPositions())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("shared forms 2 (page furniture 0)"), action.reason());
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      List<Element> elements = documentKids(root);
      assertEquals(List.of("P", "P", "P"), elements.stream().map(Element::type).toList());
      assertEquals(List.of(List.of(0), List.of(1), List.of(2)), elements.stream().map(Element::mcids).toList());
      PDPage page = doc.getPage(0);
      String tokens = tokenText(page);
      assertTrue(tokens.contains("q 1 0 0 1 72 600 cm /P <</MCID 1>> BDC /Fm1 Do EMC Q"), tokens);
      assertTrue(tokens.contains("q 1 0 0 1 72 300 cm /P <</MCID 2>> BDC /Fm1 Do EMC Q"), tokens);
      COSArray parents = parentTreeEntry(root, page.getCOSObject().getInt(COSName.STRUCT_PARENTS));
      assertEquals(3, parents.size());
      for (int m = 0; m < 3; m++) {
        assertSame(elements.get(m).dict(), resolve(parents.get(m)), "ParentTree slot " + m);
      }
    }
  }

  @Test
  void aSharedFormHoldingMarkedContentIsRefused() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithSharedFormHoldingMarkedContent())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      WorkerResult.Action action = rule.apply(doc, decision);

      assertFalse(decision.applicable(), decision.reason());
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertTrue(action.reason().contains("a form XObject drawn more than once contains marked content; its structure is not guessed"), action.reason());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
    }
  }

  /** The page's tokens as one line: operators by name, names as /N, dictionaries as <</K v ...>>. */
  private static String tokenText(PDPage page) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (Object token : new PDFStreamParser(page).parse()) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(token instanceof Operator op ? op.getName() : tokenText((COSBase) token));
    }
    return sb.toString();
  }

  private static String tokenText(COSBase base) {
    if (base instanceof COSName name) {
      return "/" + name.getName();
    }
    if (base instanceof org.apache.pdfbox.cos.COSInteger integer) {
      return Long.toString(integer.longValue());
    }
    if (base instanceof COSNumber number) {
      return Float.toString(number.floatValue());
    }
    if (base instanceof COSDictionary dict) {
      StringBuilder sb = new StringBuilder("<<");
      for (Map.Entry<COSName, COSBase> e : dict.entrySet()) {
        sb.append(sb.length() > 2 ? " " : "").append('/').append(e.getKey().getName()).append(' ').append(tokenText(e.getValue()));
      }
      return sb.append(">>").toString();
    }
    return String.valueOf(base);
  }

  // ---- helpers ------------------------------------------------------------------------------

  private byte[] applyAndSave(byte[] original) throws IOException {
    try (PDDocument doc = Loader.loadPDF(original); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static int[] render(PDDocument doc, int pageIndex) throws IOException {
    BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, 72);
    BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    rgb.getGraphics().drawImage(image, 0, 0, null);
    return ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData().clone();
  }

  record Element(String type, COSDictionary dict, List<Integer> mcids) {}

  record Marked(String operator, List<String> stack, int mcid, String artifactType) {}

  private static COSDictionary documentElement(PDStructureTreeRoot root) {
    COSBase k = resolve(root.getCOSObject().getDictionaryObject(COSName.K));
    if (k instanceof COSArray array) {
      assertEquals(1, array.size(), "single Document root");
      k = resolve(array.get(0));
    }
    COSDictionary document = (COSDictionary) k;
    assertEquals("Document", document.getNameAsString(COSName.S));
    return document;
  }

  private static COSDictionary elementParent(COSDictionary element) {
    return (COSDictionary) resolve(element.getDictionaryObject(COSName.P));
  }

  private static List<Element> documentKids(PDStructureTreeRoot root) {
    COSBase k = resolve(documentElement(root).getDictionaryObject(COSName.K));
    COSArray kids = k instanceof COSArray array ? array : new COSArray(List.of(k));
    List<Element> elements = new ArrayList<>();
    for (COSBase kid : kids) {
      COSDictionary dict = (COSDictionary) resolve(kid);
      elements.add(new Element(dict.getNameAsString(COSName.S), dict, kidMcids(dict)));
    }
    return elements;
  }

  private static List<Integer> kidMcids(COSDictionary element) {
    COSBase k = resolve(element.getDictionaryObject(COSName.K));
    List<Integer> mcids = new ArrayList<>();
    if (k instanceof COSNumber n) {
      mcids.add(n.intValue());
    } else if (k instanceof COSDictionary mcr) {
      mcids.add(mcr.getInt(COSName.MCID, -1));
    } else if (k instanceof COSArray array) {
      for (COSBase item : array) {
        if (resolve(item) instanceof COSNumber n) {
          mcids.add(n.intValue());
        } else if (resolve(item) instanceof COSDictionary mcr) {
          mcids.add(mcr.getInt(COSName.MCID, -1));
        }
      }
    }
    return mcids;
  }

  private static COSArray parentTreeEntry(PDStructureTreeRoot root, int structParents) throws IOException {
    PDNumberTreeNode tree = root.getParentTree();
    assertNotNull(tree, "ParentTree present");
    Map<Integer, COSBase> numbers = new java.util.TreeMap<>();
    COSArray nums = tree.getCOSObject().getCOSArray(COSName.NUMS);
    for (int i = 0; i + 1 < nums.size(); i += 2) {
      numbers.put(((COSNumber) nums.get(i)).intValue(), resolve(nums.get(i + 1)));
    }
    assertTrue(numbers.containsKey(structParents), "ParentTree has an entry for StructParents " + structParents + ": " + numbers.keySet());
    return (COSArray) numbers.get(structParents);
  }

  private static COSBase resolve(COSBase base) {
    return base instanceof COSObject ref ? ref.getObject() : base;
  }

  /** Content operators of one stream with the marked-content tags enclosing each (innermost last). */
  private static List<Marked> markedContent(PDContentStream stream, PDPage page) throws IOException {
    List<Object> tokens = new PDFStreamParser(stream).parse();
    Deque<COSName> tags = new ArrayDeque<>();
    Deque<COSDictionary> props = new ArrayDeque<>();
    // One stack for all three bracket kinds: a marked-content sequence may never cross BT/ET or q/Q.
    Deque<String> nesting = new ArrayDeque<>();
    List<Marked> result = new ArrayList<>();
    List<COSBase> operands = new ArrayList<>();
    for (Object token : tokens) {
      if (!(token instanceof Operator op)) {
        operands.add((COSBase) token);
        continue;
      }
      switch (op.getName()) {
        case "BDC", "BMC" -> {
          tags.addLast((COSName) operands.get(0));
          COSDictionary dict = operands.size() > 1 && operands.get(1) instanceof COSDictionary d ? d : new COSDictionary();
          props.addLast(dict);
          nesting.addLast("MC");
        }
        case "EMC" -> {
          assertEquals("MC", nesting.pollLast(), "EMC closes the innermost bracket");
          tags.removeLast();
          props.removeLast();
        }
        case "BT" -> nesting.addLast("BT");
        case "ET" -> assertEquals("BT", nesting.pollLast(), "ET closes the innermost bracket: no marked content crosses a text object");
        case "q" -> nesting.addLast("q");
        case "Q" -> assertEquals("q", nesting.pollLast(), "Q closes the innermost bracket: no marked content crosses q/Q");
        default -> {
          if (CONTENT_OPERATORS.contains(op.getName())) {
            int mcid = -1;
            String artifactType = null;
            for (COSDictionary dict : props) {
              if (dict.containsKey(COSName.MCID)) {
                mcid = dict.getInt(COSName.MCID);
              }
              if (dict.containsKey(COSName.TYPE)) {
                artifactType = dict.getNameAsString(COSName.TYPE);
              }
            }
            result.add(new Marked(op.getName(), tags.stream().map(COSName::getName).toList(), mcid, artifactType));
          }
        }
      }
      operands.clear();
    }
    assertTrue(tags.isEmpty(), "marked content balanced in " + (stream == page ? "page" : "form"));
    assertTrue(nesting.isEmpty(), "every BT/q/BDC is closed in " + (stream == page ? "page" : "form"));
    return result;
  }

  private static WorkerRequest request(WorkerRequest.Mode mode) {
    return new WorkerRequest(
        "1.0",
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode,
        "ua1",
        null,
        new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
