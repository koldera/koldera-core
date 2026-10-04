package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.jupiter.api.Test;

class RoleMapRuleTest {

  private final RoleMapRule rule = new RoleMapRule();

  @Test
  void participatesInFixForUploadsAndGeneratedPdfsOnly() {
    assertEquals(RuleId.PDF_ROLE_MAP_V1, rule.id());
    assertTrue(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
    assertTrue(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertFalse(rule.participates(request(Mode.SAFE_FIX, Origin.UPLOAD)), "deprecated modes keep their behaviour");
    assertFalse(rule.participates(request(Mode.STRUCTURE_FIX, Origin.UPLOAD)), "deprecated modes keep their behaviour");
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
  }

  @Test
  void mapsEveryKnownNonStandardTypeToItsStandardEquivalentAndLeavesUnknownOnesAlone() throws IOException {
    try (PDDocument doc = tree("Strong", "Em", "Aside", "Title", "Sub", "DocumentFragment", "MyCustomTag", "P", "Span")) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED));
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(
          Map.of("Strong", "Span", "Em", "Span", "Aside", "Div", "Title", "H1", "Sub", "Span", "DocumentFragment", "Div"), roleMap(doc));
      assertEquals(
          "mapped 6 non-standard structure types to their standard PDF 1.7 equivalents: Aside→Div, DocumentFragment→Div, Em→Span, Strong→Span,"
              + " Sub→Span, Title→H1; 1 non-standard type has no known standard equivalent and is left unmapped",
          action.reason());
      assertEquals("RoleMap absent", action.before());
      assertEquals("Aside→Div, DocumentFragment→Div, Em→Span, Strong→Span, Sub→Span, Title→H1", action.after());
    }
  }

  @Test
  void anAdditionThatWouldCloseACycleThroughTheExistingRoleMapIsLeftOut() throws IOException {
    try (PDDocument doc = tree("Strong", "Em")) {
      COSDictionary existing = new COSDictionary();
      existing.setName("Span", "Strong");
      doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().setItem(COSName.ROLE_MAP, existing);

      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(Map.of("Span", "Strong", "Em", "Span"), roleMap(doc), "Strong→Span would make Span→Strong→Span a cycle");
      assertTrue(action.reason().contains("Strong is left unmapped: the existing role map already leads from Span back to it"), action.reason());
    }
  }

  @Test
  void titleIsMappedToH1ForGeneratedPdfsOnlyNeverForUploads() throws IOException {
    try (PDDocument doc = tree("Title", "Strong")) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));
      assertEquals(Map.of("Strong", "Span"), roleMap(doc), "an upload's Title stays unmapped: H1 could newly fail 7.4.2-1 and roll the whole fix back");
      assertTrue(action.reason().contains("Title is left unmapped in an uploaded PDF"), action.reason());
    }
    try (PDDocument doc = tree("Title", "Strong")) {
      rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED)));
      assertEquals(Map.of("Strong", "Span", "Title", "H1"), roleMap(doc));
    }
  }

  @Test
  void aTreeTooDeepToWalkIsLeftAlone() throws IOException {
    try (PDDocument doc = tree("Strong")) {
      PDStructureElement parent = (PDStructureElement) document(doc).getKids().get(0);
      for (int i = 0; i < 600; i++) {
        PDStructureElement child = new PDStructureElement("Em", parent);
        parent.appendKid(child);
        parent = child;
      }
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertFalse(decision.applicable());
      assertEquals("the structure tree is too deep to walk; its role mapping is left unchanged", decision.reason());
    }
  }

  @Test
  void neverOverwritesOrRemovesAnExistingMappingAndNeverMapsAStandardType() throws IOException {
    try (PDDocument doc = tree("Strong", "Em", "P", "Custom")) {
      COSDictionary existing = new COSDictionary();
      existing.setName("Strong", "Emphasis");
      existing.setName("Custom", "P");
      existing.setName("Unused", "Span");
      doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().setItem(COSName.ROLE_MAP, existing);

      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(Map.of("Strong", "Emphasis", "Custom", "P", "Unused", "Span", "Em", "Span"), roleMap(doc), "only Em was added");
      assertEquals("RoleMap has 3 entries", action.before());
      assertTrue(action.reason().startsWith("mapped 1 non-standard structure type to its standard PDF 1.7 equivalent: Em→Span; existing mappings kept"), action.reason());
    }
  }

  @Test
  void aNoteEquivalentIsWrittenOnlyWhenEveryFootnoteElementHasTheIdThatNoteRequires() throws IOException {
    try (PDDocument doc = tree("FENote", "FENote")) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("FENote is left unmapped: a Note needs an /ID on every element"), action.reason());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.ROLE_MAP));
    }
    try (PDDocument doc = tree("FENote", "FENote")) {
      int i = 0;
      for (Object kid : document(doc).getKids()) {
        ((PDStructureElement) kid).getCOSObject().setItem(COSName.ID, new COSString("fn" + i++));
      }
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(Map.of("FENote", "Note"), roleMap(doc));
    }
  }

  @Test
  void skipsUntaggedDocumentsDocumentsWithNothingToMapNamespacesAndAMalformedRoleMap() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertFalse(decision.applicable());
      assertEquals("document is not tagged; there are no structure types to map", decision.reason());
    }
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("every structure type is standard or already mapped; nothing to map", action.reason());
    }
    try (PDDocument doc = tree("Strong")) {
      doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().setItem(COSName.getPDFName("Namespaces"), new COSArray());
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertFalse(decision.applicable());
      assertEquals("the structure tree uses namespaces (PDF 2.0); its role mapping is left unchanged", decision.reason());
    }
    try (PDDocument doc = tree("Strong")) {
      ((PDStructureElement) document(doc).getKids().get(0)).getCOSObject().setItem(COSName.getPDFName("NS"), new COSDictionary());
      assertFalse(rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD)).applicable(), "an element with /NS: namespaces in use");
    }
    try (PDDocument doc = tree("Strong")) {
      doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().setItem(COSName.ROLE_MAP, new COSArray());
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertFalse(decision.applicable());
      assertEquals("the existing /RoleMap is not a dictionary; it is left for a person to look at", decision.reason());
    }
  }

  @Test
  void theMappingTableOnlyTargetsStandardTypes() {
    for (String target : RoleMapRule.PDF_2_0_EQUIVALENTS.values()) {
      assertTrue(RoleMapRule.STANDARD_TYPES.contains(target), target);
    }
    for (String source : RoleMapRule.PDF_2_0_EQUIVALENTS.keySet()) {
      assertFalse(RoleMapRule.STANDARD_TYPES.contains(source), source);
    }
  }

  /** A tagged document whose Document element holds one empty element of each given type, in order. */
  static PDDocument tree(String... types) throws IOException {
    PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged());
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    PDStructureElement document = new PDStructureElement("Document", root);
    root.getCOSObject().setItem(COSName.K, document.getCOSObject());
    root.getCOSObject().removeItem(COSName.PARENT_TREE);
    for (String type : types) {
      PDStructureElement el = new PDStructureElement(type, document);
      document.appendKid(el);
    }
    return doc;
  }

  private static PDStructureNode document(PDDocument doc) {
    return (PDStructureNode) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
  }

  private static Map<String, String> roleMap(PDDocument doc) {
    COSDictionary map = (COSDictionary) doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.ROLE_MAP);
    java.util.Map<String, String> out = new java.util.HashMap<>();
    for (COSName key : map.keySet()) {
      out.put(key.getName(), map.getNameAsString(key));
    }
    return out;
  }

  static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
