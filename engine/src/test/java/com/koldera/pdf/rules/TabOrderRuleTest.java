package com.koldera.pdf.rules;

import static com.koldera.pdf.rules.RoleMapRuleTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

class TabOrderRuleTest {

  private final TabOrderRule rule = new TabOrderRule();

  @Test
  void participatesInFixForUploadsOnly() {
    assertEquals(RuleId.PDF_TAB_ORDER_V1, rule.id());
    assertTrue(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)), "Chromium writes the tab order of generated PDFs");
    assertFalse(rule.participates(request(Mode.SAFE_FIX, Origin.UPLOAD)), "deprecated modes keep their behaviour");
    assertFalse(rule.participates(request(Mode.STRUCTURE_FIX, Origin.UPLOAD)), "deprecated modes keep their behaviour");
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
  }

  @Test
  void everyPageWithAnnotationsGetsStructureTabOrderAndPagesWithoutAnnotationsAreLeftAlone() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("P", "First page"), new Block("P", "Second page", 2), new Block("P", "Third page", 3)).link(0)))) {
      PDAnnotationLink second = new PDAnnotationLink();
      second.setRectangle(new PDRectangle(72, 700, 50, 12));
      doc.getPage(2).getAnnotations().add(second);
      doc.getPage(2).getCOSObject().setItem(COSName.getPDFName("Tabs"), COSName.getPDFName("R"));

      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(COSName.S, doc.getPage(0).getCOSObject().getCOSName(COSName.getPDFName("Tabs")));
      assertNull(doc.getPage(1).getCOSObject().getItem(COSName.getPDFName("Tabs")), "a page without annotations is not touched");
      assertEquals(COSName.S, doc.getPage(2).getCOSObject().getCOSName(COSName.getPDFName("Tabs")), "row order becomes structure order");
      assertEquals("set /Tabs /S (structure order) on 2 pages with annotations", action.reason());
      assertEquals("2 pages with annotations without /Tabs /S", action.before());
      assertEquals("/Tabs /S", action.after());
    }
  }

  @Test
  void pagesThatAlreadyUseStructureOrderLeaveNothingToDo() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Linked")).link(0)))) {
      doc.getPage(0).getCOSObject().setItem(COSName.getPDFName("Tabs"), COSName.S);
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("every page with annotations already uses structure order (/Tabs /S)", action.reason());
    }
  }

  @Test
  void anUntaggedDocumentIsLeftAloneBecauseStructureOrderMeansNothingWithoutAStructureTree() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged())) {
      PDAnnotationLink link = new PDAnnotationLink();
      link.setRectangle(new PDRectangle(72, 700, 50, 12));
      doc.getPage(0).getAnnotations().add(link);
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), "the structure rule may still tag the document before this rule applies");
      WorkerResult.Action action = rule.apply(doc, decision);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("document is not tagged; structure order (/Tabs /S) needs a structure tree", action.reason());
      assertNull(doc.getPage(0).getCOSObject().getItem(COSName.getPDFName("Tabs")));
    }
  }

  @Test
  void aDocumentWithoutAnnotationsIsSkippedAtInspection() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertFalse(decision.applicable());
      assertEquals("no page has annotations; no tab order to set", decision.reason());
    }
  }
}
