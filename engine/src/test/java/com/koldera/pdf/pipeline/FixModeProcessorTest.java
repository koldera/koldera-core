package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.contracts.WorkerResultCodec;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.rules.ExplicitLanguageRuleTest;
import com.koldera.pdf.rules.LanguageCorrectionRuleTest;
import com.koldera.pdf.rules.TitleResolverTest;
import com.koldera.pdf.validation.PdfValidator;
import com.koldera.pdf.validation.ValidationOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code fix}: the production rule list in order, title + display title + structure + XMP, and the file-name review finding. */
class FixModeProcessorTest {

  private static final String SPEC = "ISO 14289-1:2014 ";
  private static final String FONT_RULE = SPEC + "7.21.4.1-1";
  private static final Map<String, Integer> UNTAGGED_BASELINE =
      Map.of(SPEC + "7.2-34", 1, SPEC + "7.1-10", 1, SPEC + "7.1-11", 1, SPEC + "7.1-3", 3, SPEC + "6.2-1", 1, SPEC + "7.1-8", 1, FONT_RULE, 1);

  @TempDir Path root;
  private JobWorkspace workspace;
  private ScriptedValidator validator;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(root.resolve("in"));
    workspace = JobWorkspace.open(root);
    validator = new ScriptedValidator();
  }

  @Test
  void fixOnAnUntaggedUntitledPdfWritesTitleDisplaysItTagsAndWritesXmpWithTitle() throws Exception {
    WorkerResult result = run(SyntheticPdfFactory.simpleUntagged(), TitleResolverTest.fix(null, "annual-report-2025.pdf"));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    assertEquals(List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, RuleId.PDF_TITLE_V1, RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1,
        RuleId.PDF_STRUCTURE_INFERRED_V1, RuleId.PDF_UNTAGGED_CONTENT_V1, RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1, RuleId.PDF_FIGURES_WITHOUT_ALT_V1, RuleId.PDF_LINK_TAGS_V1, RuleId.PDF_TAB_ORDER_V1, RuleId.PDF_ROLE_MAP_V1, RuleId.PDF_FONT_DICTIONARY_V1, RuleId.PDF_FONT_INVISIBLE_TEXT_V1, RuleId.PDF_FONT_EMBEDDING_V1, RuleId.PDF_FONT_TOUNICODE_V1,
        RuleId.PDF_XMP_METADATA_FROM_INFO_V1, RuleId.PDF_UA_IDENTIFIER_V1),
        result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      assertEquals("annual-report-2025", copy.getDocumentInformation().getTitle());
      assertTrue(copy.getDocumentCatalog().getViewerPreferences().displayDocTitle());
      String xmp = new String(copy.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertTrue(xmp.contains("annual-report-2025"));
    }
    WorkerResult.Finding fromFileName =
        result.findings().stream().filter(f -> f.ruleId().equals("TITLE_FROM_FILE_NAME")).findFirst().orElseThrow();
    assertTrue(fromFileName.needsHumanReview());
    assertEquals("The title was taken from the file name. Check that it describes the document.", fromFileName.message());
    assertSchemaValid(result);
  }

  @Test
  void anUntaggedUploadsImageIsMarkedAsArtifactAndReported() throws Exception {
    WorkerResult result = run(SyntheticPdfFactory.untaggedArticle(), TitleResolverTest.fix("Article", null));

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    assertEquals(List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, RuleId.PDF_TITLE_V1, RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1,
        RuleId.PDF_STRUCTURE_INFERRED_V1, RuleId.PDF_UNTAGGED_CONTENT_V1, RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1, RuleId.PDF_FIGURES_WITHOUT_ALT_V1, RuleId.PDF_LINK_TAGS_V1, RuleId.PDF_TAB_ORDER_V1, RuleId.PDF_ROLE_MAP_V1, RuleId.PDF_FONT_DICTIONARY_V1, RuleId.PDF_FONT_INVISIBLE_TEXT_V1, RuleId.PDF_FONT_EMBEDDING_V1, RuleId.PDF_FONT_TOUNICODE_V1,
        RuleId.PDF_XMP_METADATA_FROM_INFO_V1, RuleId.PDF_UA_IDENTIFIER_V1), result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    WorkerResult.Action figures = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_FIGURES_WITHOUT_ALT_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, figures.outcome(), figures.reason());
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      assertTrue(com.koldera.pdf.generated.StructTree.elementsOfType(copy, "Figure").isEmpty(), "no Figure left in the copy");
      assertEquals(Map.of(), com.koldera.pdf.integrity.CopyIntegrity.inspect(copy).problems());
    }
    List<WorkerResult.Finding> marked = result.findings().stream().filter(f -> f.ruleId().equals("FIGURE_ALT_MISSING_MARKED_ARTIFACT")).toList();
    assertEquals(1, marked.size(), result.findings().toString());
    assertEquals(1, marked.get(0).pageNumber());
    assertTrue(marked.get(0).needsHumanReview());
    assertTrue(marked.get(0).message().startsWith("1 figure on this page had no alternative text and was marked as decoration (artifact)"), marked.get(0).message());
    WorkerResult.Finding images = result.findings().stream().filter(f -> f.ruleId().equals("IMAGES_REQUIRE_HUMAN_REVIEW")).findFirst().orElseThrow();
    assertEquals("page contains image content; images without alternative text were marked as decoration (see FIGURE_ALT_MISSING_MARKED_ARTIFACT);"
        + " alternative text is never generated automatically", images.message());
    // The structure was inferred from the layout: its own review finding replaces the basic-tags one.
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals("BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW")), result.findings().toString());
    WorkerResult.Finding inferred = result.findings().stream().filter(f -> f.ruleId().equals("STRUCTURE_INFERRED")).findFirst().orElseThrow();
    assertTrue(inferred.needsHumanReview());
    assertEquals(null, inferred.pageNumber());
    assertTrue(inferred.message().startsWith("Structure was inferred from the page layout: 3 paragraphs, 0 headings, 0 lists (0 items), 0 tables;"
        + " 0 running headers and footers were marked as page furniture (artifact); 0 pages were put into column reading order, 1 kept their content"
        + " order;"), inferred.message());
    assertSchemaValid(result);
  }

  @Test
  void aFigureOnlyRetaggedAsParagraphDoesNotClaimImagesWereMarkedAsDecoration() throws Exception {
    // A tagged upload: a Figure with alternative text (a real image, kept) and a "Figure" that shows text (retagged P).
    byte[] input;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(
            TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "image"), Block.of("P", "Axis label 2025")).alt(1, "Chart")));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      var elements = ((org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement)
          doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0)).getKids();
      ((org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) elements.get(2)).setStructureType("Figure");
      doc.save(out);
      input = out.toByteArray();
    }

    WorkerResult result = run(input, TitleResolverTest.fix("Report", null));

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    WorkerResult.Action figures = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_FIGURES_WITHOUT_ALT_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, figures.outcome(), figures.reason());
    assertTrue(figures.reason().startsWith("figures without alternative text marked as artifact 0, retagged as paragraph 1"), figures.reason());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals("FIGURE_ALT_MISSING_MARKED_ARTIFACT")), result.findings().toString());
    WorkerResult.Finding images = result.findings().stream().filter(f -> f.ruleId().equals("IMAGES_REQUIRE_HUMAN_REVIEW")).findFirst().orElseThrow();
    assertFalse(images.message().contains("marked as decoration"), images.message());
    assertSchemaValid(result);
  }

  @Test
  void aUserSuppliedTitleRaisesNoFileNameFinding() throws Exception {
    WorkerResult result = run(SyntheticPdfFactory.simpleUntagged(), TitleResolverTest.fix("Annual report", "annual-report-2025.pdf"));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals("TITLE_FROM_FILE_NAME")), result.findings().toString());
  }

  @Test
  void fixOnATaggedPdfSkipsStructureLikeStructureFixDoes() throws Exception {
    WorkerResult result = run(SyntheticPdfFactory.tagged(), TitleResolverTest.fix("Report", null));
    var structure = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.SKIPPED, structure.outcome());
  }

  @Test
  void fixComputesTheGlyphInventoryLikeStructureFix() throws Exception {
    int before = PdfProcessor.glyphInventoriesComputedForTests.get();
    WorkerResult result = run(SyntheticPdfFactory.simpleUntagged(), TitleResolverTest.fix(null, "report.pdf"));
    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(2, PdfProcessor.glyphInventoriesComputedForTests.get() - before, "before and after the structure rule");
  }

  @Test
  void fixOnATaggedUploadMapsItsNonStandardStructureTypesAndKeepsEverythingElse() throws Exception {
    byte[] input = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Heading"), Block.of("Strong", "Important"), Block.of("P", "Body text")));
    WorkerResult result = run(input, TitleResolverTest.fix("Report", null));

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    WorkerResult.Action roleMap = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_ROLE_MAP_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, roleMap.outcome(), roleMap.reason());
    assertEquals("mapped 1 non-standard structure type to its standard PDF 1.7 equivalent: Strong→Span", roleMap.reason());
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      COSDictionary map = (COSDictionary) copy.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.ROLE_MAP);
      assertEquals("Span", map.getNameAsString("Strong"));
      assertEquals(1, map.size());
    }
    assertSchemaValid(result);
  }

  @Test
  void aWrongDeclaredLanguageIsCorrectedAndAPersonIsAskedToCheckIt() throws Exception {
    WorkerResult result = run(LanguageCorrectionRuleTest.cyrillicPdf("en-US"), TitleResolverTest.fix("Звіт", "zvit.pdf"));

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    WorkerResult.Action language = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, language.outcome(), language.reason());
    assertEquals(RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, result.actions().get(1).ruleId(), "right after the explicit-language rule");
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      assertEquals("uk", copy.getDocumentCatalog().getLanguage());
    }
    WorkerResult.Finding corrected = result.findings().stream().filter(f -> f.ruleId().equals("LANGUAGE_CORRECTED")).findFirst().orElseThrow();
    assertTrue(corrected.needsHumanReview());
    assertEquals("The document declared its language as 'en-US', which its text contradicts; it was changed to 'uk'. Check that this is the"
        + " document's language.", corrected.message());
    assertSchemaValid(result);
  }

  @Test
  void anUntaggedUploadsLinksEndUpInTheBasicTagsWithADescriptionAndStructureTabOrder() throws Exception {
    byte[] input;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged()); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
      link.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(70, 716, 400, 14));
      org.apache.pdfbox.pdmodel.interactive.action.PDActionURI uri = new org.apache.pdfbox.pdmodel.interactive.action.PDActionURI();
      uri.setURI("https://example.org");
      link.setAction(uri);
      doc.getPage(0).getAnnotations().add(link);
      doc.save(out);
      input = out.toByteArray();
    }

    WorkerResult result = run(input, TitleResolverTest.fix("Report", null));

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    WorkerResult.Action links = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_LINK_TAGS_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, links.outcome(), links.reason());
    assertTrue(links.reason().startsWith("links tagged 1, joined 0, references repaired 0, descriptions 1, left untagged 0"), links.reason());
    assertEquals(ActionOutcome.APPLIED, result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_TAB_ORDER_V1).findFirst().orElseThrow().outcome());
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      var paragraph = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement)
          ((org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) copy.getDocumentCatalog().getStructureTreeRoot().getKids().get(0)).getKids().get(0);
      assertEquals("P", paragraph.getStructureType());
      assertTrue(paragraph.getKids().stream().anyMatch(k -> k instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement e
          && "Link".equals(e.getStructureType())), paragraph.getKids().toString());
      COSDictionary annot = copy.getPage(0).getAnnotations().get(0).getCOSObject();
      assertEquals(SyntheticPdfFactory.FILLER_TEXT + " Page 1.", annot.getString(COSName.CONTENTS));
      assertEquals(COSName.S, copy.getPage(0).getCOSObject().getCOSName(COSName.getPDFName("Tabs")));
    }
    assertSchemaValid(result);
  }

  @Test
  void theLanguageFindingSaysAnExistingXmpLanguageWasLeftAsItWas() throws Exception {
    byte[] input;
    try (PDDocument doc = Loader.loadPDF(LanguageCorrectionRuleTest.cyrillicPdf("en-US")); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      String packet = com.koldera.pdf.rules.XmpPacketEditorTest.HEAD.replace("xmp:CreatorTool=",
          "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:language=\"en-US\" xmp:CreatorTool=") + com.koldera.pdf.rules.XmpPacketEditorTest.TAIL;
      doc.getDocumentCatalog().setMetadata(new org.apache.pdfbox.pdmodel.common.PDMetadata(doc,
          new java.io.ByteArrayInputStream(packet.getBytes(StandardCharsets.UTF_8))));
      doc.save(out);
      input = out.toByteArray();
    }

    WorkerResult result = run(input, TitleResolverTest.fix("Звіт", null));

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    WorkerResult.Finding corrected = result.findings().stream().filter(f -> f.ruleId().equals("LANGUAGE_CORRECTED")).findFirst().orElseThrow();
    assertEquals("The document declared its language as 'en-US', which its text contradicts; it was changed to 'uk'. Check that this is the"
        + " document's language. The language in the document's XMP metadata was left as it was.", corrected.message());
  }

  @Test
  void aTaggedLinkKeepsItsTagWhenUnusableTagsAreRebuilt() throws Exception {
    WorkerResult result = run(unusableWithTaggedLink(), TitleResolverTest.fix("Report", null));

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, inferred.outcome(), inferred.reason());
    assertTrue(inferred.reason().startsWith("strategy tagged_unusable:"), inferred.reason());
    WorkerResult.Action links = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_LINK_TAGS_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, links.outcome(), links.reason());
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      COSDictionary annot = copy.getPage(0).getAnnotations().get(0).getCOSObject();
      assertTrue(annot.containsKey(COSName.STRUCT_PARENT), "the link is in the new tree");
      COSDictionary root = copy.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      java.util.Map<Integer, COSBase> parents = com.koldera.pdf.structure.NumberTreeView.read((COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE));
      COSBase parent = parents.get(annot.getInt(COSName.STRUCT_PARENT));
      COSDictionary link = (COSDictionary) (parent instanceof org.apache.pdfbox.cos.COSObject o ? o.getObject() : parent);
      assertEquals("Link", link.getNameAsString(COSName.S));
      // The Link element hangs in the new tree: its ancestors lead to the new Document element.
      COSDictionary up = link;
      int guard = 0;
      while (up.getDictionaryObject(COSName.P) instanceof COSDictionary p && guard++ < 10) {
        up = p;
      }
      assertTrue(up == root, "the Link element's ancestors end at the new structure tree root");
      assertEquals("Described link", annot.getString(COSName.CONTENTS));
    }
    assertSchemaValid(result);
  }

  /** taggedWithUntaggedLines(1, 9) plus a URI link over the tagged first line, already a Link element (OBJR) with /Contents. */
  private static byte[] unusableWithTaggedLink() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9)); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(0);
      float top = page.getMediaBox().getHeight() - 60;
      org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
      link.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(70, top - 3, 200, 14));
      org.apache.pdfbox.pdmodel.interactive.action.PDActionURI uri = new org.apache.pdfbox.pdmodel.interactive.action.PDActionURI();
      uri.setURI("https://example.org");
      link.setAction(uri);
      link.setContents("Described link");
      link.setStructParent(1);
      page.getAnnotations().add(link);
      var root = doc.getDocumentCatalog().getStructureTreeRoot();
      var document = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) root.getKids().get(0);
      var paragraph = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) document.getKids().get(0);
      var linkElement = new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement("Link", paragraph);
      linkElement.setPage(page);
      var reference = new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference();
      reference.setReferencedObject(link);
      linkElement.appendKid(reference);
      paragraph.appendKid(linkElement);
      COSArray nums = ((COSDictionary) root.getCOSObject().getDictionaryObject(COSName.PARENT_TREE)).getCOSArray(COSName.NUMS);
      nums.add(COSInteger.ONE);
      nums.add(linkElement.getCOSObject());
      root.setParentTreeNextKey(2);
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void aRolledBackStructureRunIsRepeatedWithTheBasicTagger() throws Exception {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.simpleUntagged());
    validator.enqueue(failed(UNTAGGED_BASELINE));
    // First run (inferred structure): 7.1-3 must reach zero after structure tagging, so the structure gate rolls it back.
    // (A grown font check alone would not: the glyph inventory is unchanged, so the gate accepts it as a recount.)
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.1-3", 1)));
    validator.enqueue(failed(Map.of(FONT_RULE, 1)));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, Main.productionRules()).process(TitleResolverTest.fix("Report", null), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(3, validator.calls.size(), "baseline, the inferred run, the basic-tagger run");
    WorkerResult.Action basic = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, basic.outcome(), basic.reason());
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.ROLLED_BACK, inferred.outcome());
    assertTrue(inferred.reason().contains("structure gate: rule ISO 14289-1:2014 7.1-3 still fails (1)"), inferred.reason());
    assertTrue(inferred.reason().endsWith(PdfProcessor.STRUCTURE_NOTE), inferred.reason());
    assertEquals(List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, RuleId.PDF_TITLE_V1, RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1,
        RuleId.PDF_STRUCTURE_INFERRED_V1, RuleId.PDF_UNTAGGED_CONTENT_V1, RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1, RuleId.PDF_FIGURES_WITHOUT_ALT_V1, RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1, RuleId.PDF_LINK_TAGS_V1, RuleId.PDF_TAB_ORDER_V1,
        RuleId.PDF_ROLE_MAP_V1, RuleId.PDF_FONT_DICTIONARY_V1, RuleId.PDF_FONT_INVISIBLE_TEXT_V1, RuleId.PDF_FONT_EMBEDDING_V1, RuleId.PDF_FONT_TOUNICODE_V1,
        RuleId.PDF_XMP_METADATA_FROM_INFO_V1, RuleId.PDF_UA_IDENTIFIER_V1), result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      assertTrue(copy.getDocumentCatalog().getStructureTreeRoot() != null);
    }
    assertSchemaValid(result);
  }

  @Test
  void aStructureRunThatAlsoFailsWithTheBasicTaggerIsRepeatedWithoutTheRepairsToo() throws Exception {
    Files.write(workspace.inputFile(), untaggedWithLink());
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.1-3", 1)));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.1-11", 1)));
    validator.enqueue(failed(Map.of(FONT_RULE, 1)));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, Main.productionRules()).process(TitleResolverTest.fix("Report", null), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(4, validator.calls.size(), "baseline and three runs");
    assertEquals(List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, RuleId.PDF_TITLE_V1, RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1,
        RuleId.PDF_STRUCTURE_INFERRED_V1, RuleId.PDF_UNTAGGED_CONTENT_V1, RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1, RuleId.PDF_FIGURES_WITHOUT_ALT_V1, RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1, RuleId.PDF_LINK_TAGS_V1, RuleId.PDF_TAB_ORDER_V1,
        RuleId.PDF_ROLE_MAP_V1, RuleId.PDF_FONT_DICTIONARY_V1, RuleId.PDF_FONT_INVISIBLE_TEXT_V1, RuleId.PDF_FONT_EMBEDDING_V1, RuleId.PDF_FONT_TOUNICODE_V1,
        RuleId.PDF_XMP_METADATA_FROM_INFO_V1, RuleId.PDF_UA_IDENTIFIER_V1), result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertTrue(inferred.reason().contains("7.1-3") && inferred.reason().endsWith(PdfProcessor.STRUCTURE_NOTE), inferred.reason());
    WorkerResult.Action tabs = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_TAB_ORDER_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.ROLLED_BACK, tabs.outcome(), tabs.reason());
    assertTrue(tabs.reason().contains("7.1-11") && tabs.reason().endsWith(PdfProcessor.REPAIRS_NOTE), tabs.reason());
    assertEquals(ActionOutcome.APPLIED, result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1).findFirst().orElseThrow().outcome());
    assertSchemaValid(result);
  }

  // ---- the copy must never be damaged ----------------------------------------------------------

  @Test
  void fixCopyKeepsEveryNumberDirectWhenTheInputHasIndirectIntegerObjects() throws Exception {
    // LibreOffice/Quartz shape: small integers stored as their own objects. The copy used to carry
    // "6 0 R" in place of ParentTree keys, MCIDs, /StructParents and the input's own direct numbers.
    WorkerResult result = run(SyntheticPdfFactory.untaggedWithIndirectSmallIntegers(), TitleResolverTest.fix("Report", null));

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      COSDictionary page = copy.getPage(0).getCOSObject();
      assertTrue(page.getItem(COSName.STRUCT_PARENTS) instanceof COSInteger, "StructParents: " + page.getItem(COSName.STRUCT_PARENTS));
      COSDictionary pages = (COSDictionary) copy.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PAGES);
      assertTrue(pages.getItem(COSName.COUNT) instanceof COSInteger, "the input's own direct /Count stays direct: " + pages.getItem(COSName.COUNT));
      COSDictionary root = copy.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      COSArray nums = ((COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE)).getCOSArray(COSName.NUMS);
      for (int i = 0; i < nums.size(); i += 2) {
        assertTrue(nums.get(i) instanceof COSInteger, "ParentTree key " + i / 2 + ": " + nums.get(i));
      }
      COSArray elements = ((COSDictionary) root.getDictionaryObject(COSName.K)).getCOSArray(COSName.K);
      assertEquals(3, elements.size());
      for (int i = 0; i < elements.size(); i++) {
        COSBase kid = ((COSDictionary) elements.getObject(i)).getItem(COSName.K);
        assertTrue(kid instanceof COSInteger, "MCID kid of element " + i + ": " + kid);
      }
    }
  }

  @Test
  void fixRemovesPaintingOperatorsWithNoPathAndTagsTheRest() throws Exception {
    // pdfTeX shape ("h f 0 0 0 rg f"): the PAC engine cannot parse such a stream, and reported every
    // tag put into it as invalid content. The no-op operators are dropped from the copy, then it is tagged.
    WorkerResult result = run(SyntheticPdfFactory.untaggedWithStrayPaintOperatorsInPageAndForm(), TitleResolverTest.fix("Report", null));

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    WorkerResult.Action structure = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, structure.outcome(), structure.reason());
    assertTrue(structure.reason().contains("removed 3 painting operators that had no path to paint"), structure.reason());
    try (PDDocument copy = Loader.loadPDF(Files.readAllBytes(remediatedPath()))) {
      assertTrue(copy.getDocumentCatalog().getStructureTreeRoot() != null);
      assertEquals(Map.of(), com.koldera.pdf.integrity.CopyIntegrity.inspect(copy).problems());
    }
  }

  @Test
  void fixStillTagsAPageWithAStrayEndPathOperator() throws Exception {
    WorkerResult result = run(SyntheticPdfFactory.untaggedWithEndPathWithoutPath(), TitleResolverTest.fix("Report", null));

    WorkerResult.Action structure = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, structure.outcome(), structure.reason());
    assertFalse(structure.reason().contains("removed"), structure.reason());
  }

  // ---- helpers ----------------------------------------------------------------------------

  /** Runs the production rule list with a scripted baseline (untagged failures) and a final report that passes either gate. */
  private WorkerResult run(byte[] input, WorkerRequest request) throws IOException {
    Files.write(workspace.inputFile(), input);
    Files.deleteIfExists(remediatedPath());
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1)));
    return new PdfProcessor(new PdfPreflight(), validator, Main.productionRules()).process(request, workspace);
  }

  /** The plain untagged fixture with one URI link annotation over its text: the link and tab-order repairs apply to it. */
  private static byte[] untaggedWithLink() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged()); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
      link.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(70, 716, 400, 14));
      org.apache.pdfbox.pdmodel.interactive.action.PDActionURI uri = new org.apache.pdfbox.pdmodel.interactive.action.PDActionURI();
      uri.setURI("https://example.org");
      link.setAction(uri);
      doc.getPage(0).getAnnotations().add(link);
      doc.save(out);
      return out.toByteArray();
    }
  }

  private Path remediatedPath() {
    return workspace.outputFile("remediated.pdf");
  }

  private static void assertSchemaValid(WorkerResult result) {
    var errors = ContractSchemas.validateResult(WorkerResultCodec.toJson(result));
    assertTrue(errors.isEmpty(), errors::toString);
  }

  private static ValidationOutcome failed(Map<String, Integer> rules) {
    return new ValidationOutcome(ValidationStatus.FAILED, rules, Map.of(), null, null, null, "fake");
  }

  /** Returns queued outcomes in order, records calls and writes a stand-in raw report file (copied from StructureFixProcessorTest). */
  private static final class ScriptedValidator implements PdfValidator {
    record Call(Path pdf, Path report, Duration timeout) {}

    final List<Call> calls = new ArrayList<>();
    private final Deque<ValidationOutcome> queue = new ArrayDeque<>();

    void enqueue(ValidationOutcome outcome) {
      queue.add(outcome);
    }

    @Override
    public ValidationOutcome validate(Path pdf, Path reportPath, Duration timeout) {
      calls.add(new Call(pdf, reportPath, timeout));
      if (queue.isEmpty()) {
        throw new AssertionError("validator called more often than scripted");
      }
      ValidationOutcome scripted = queue.poll();
      try {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, "{\"fake\":true}", StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      return new ValidationOutcome(
          scripted.status(), scripted.failedRuleCounts(), scripted.ruleDescriptions(), reportPath, scripted.errorCode(), scripted.errorMessage(), scripted.validatorVersion());
    }
  }
}
