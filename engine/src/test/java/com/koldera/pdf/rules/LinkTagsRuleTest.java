package com.koldera.pdf.rules;

import static com.koldera.pdf.rules.RoleMapRuleTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

public class LinkTagsRuleTest {

  private final LinkTagsRule rule = new LinkTagsRule();

  @Test
  void participatesInFixForUploadsOnly() {
    assertEquals(RuleId.PDF_LINK_TAGS_V1, rule.id());
    assertTrue(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)), "generated links are tagged by Chromium and described by the generated rule");
    assertFalse(rule.participates(request(Mode.SAFE_FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.STRUCTURE_FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
  }

  /** A tagged page whose second paragraph is under an untagged Link annotation. */
  public static byte[] taggedWithUntaggedLink() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Intro"), Block.of("P", "Visit the project site"))));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDAnnotationLink link = new PDAnnotationLink();
      link.setRectangle(new PDRectangle(70, 676, 200, 14));
      PDActionURI uri = new PDActionURI();
      uri.setURI("https://example.org");
      link.setAction(uri);
      doc.getPage(0).getAnnotations().add(link);
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void tagsTheLinkAndSaysWhatItDid() throws IOException {
    try (PDDocument doc = Loader.loadPDF(taggedWithUntaggedLink())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());
      assertEquals("1 link annotation outside the structure tree", decision.before());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("links tagged 1, joined 0, references repaired 0, descriptions 1, left untagged 0; each Link element holds only the"
          + " annotation, next to the text under it, which stays in its paragraph; whether each description says where the link goes"
          + " requires human review", action.reason());
      assertEquals("every link annotation in a Link element with a description", action.after());
      assertEquals(0, com.koldera.pdf.repair.LinkTagger.untaggedLinks(doc));
    }
  }

  @Test
  void anUntaggedDocumentIsSkippedWhenTheStructureRuleDidNotTagIt() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged())) {
      PDAnnotationLink link = new PDAnnotationLink();
      link.setRectangle(new PDRectangle(70, 716, 200, 14));
      doc.getPage(0).getAnnotations().add(link);
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), "the structure rule may tag the document before this rule applies");
      WorkerResult.Action action = rule.apply(doc, decision);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("links are not tagged: document is not tagged", action.reason());
    }
  }

  @Test
  void aDocumentWithoutLinkAnnotationsIsSkipped() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertFalse(decision.applicable());
      assertEquals("every link annotation is already in the structure tree and described", decision.reason());
    }
  }
}
