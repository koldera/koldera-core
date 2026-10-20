package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;

/**
 * {@code PDF_XMP_METADATA_FROM_INFO_V1}: writes a minimal XMP packet to a catalog without {@code /Metadata}: {@code dc:title},
 * {@code dc:language}, {@code xmp:CreatorTool}, {@code pdf:Producer} from Info and {@code /Lang}; never a title guess or {@code pdfuaid:part}.
 * Untagged documents the basic rule tags and tagged ones too, and an existing packet lacking
 * {@code dc:title}/{@code dc:language} gets them via {@link XmpPacketEditor} without changing any existing byte.
 */
public final class XmpMetadataRule implements RemediationRule {

  private final BasicStructureRule structureRule;

  public XmpMetadataRule(BasicStructureRule structureRule) {
    this.structureRule = structureRule;
  }

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_XMP_METADATA_FROM_INFO_V1;
  }

  /**
   * Only in {@code fix}; {@code analyze} results never list this rule.
   */
  @Override
  public boolean participates(WorkerRequest request) {
    return request.addsStructure() || generatedRun(request);
  }

  private static boolean generatedRun(WorkerRequest request) {
    return request.isFix() && request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    return inspect(document, request, Set.of());
  }

  /**
   * In a {@code fix} upload run with the structure strategy, a catalog without {@code /Metadata} gets the minimal packet whether
   * or not it is tagged. Ladder runs without the structure steps and every other mode: tagged uploads get none, untagged ones
   * only when the basic structure rule applies.
   */
  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request, Set<WorkerResult.RuleId> excluded) {
    PDDocumentCatalog catalog = document.getDocumentCatalog();
    String before = catalog.getMetadata() != null ? "Metadata present" : null;
    if (!participates(request)) {
      return RuleDecision.skip("an XMP packet is only written in fix mode", before, null);
    }
    if (catalog.getMetadata() != null) {
      return request.isFix() ? inspectExistingPacket(catalog.getMetadata())
          : RuleDecision.skip("catalog already has /Metadata; existing metadata is never overwritten", before, null);
    }
    if (request.isFix() && !request.isHtmlGenerated() && !excluded.contains(WorkerResult.RuleId.PDF_STRUCTURE_INFERRED_V1)) {
      return RuleDecision.apply(
          "catalog has no /Metadata; a minimal XMP packet can be derived from Info and /Lang without any conformance claim",
          null,
          describe(document));
    }
    if (catalog.getStructureTreeRoot() != null && generatedRun(request)) {
      return RuleDecision.apply(
          "generated tagged PDF without /Metadata; a minimal XMP packet can be derived from Info and /Lang without any conformance claim",
          null,
          describe(document));
    }
    if (catalog.getStructureTreeRoot() != null) {
      return RuleDecision.skip("document is already tagged; this run adds no structure and leaves metadata as found", before, null);
    }
    if (!structureRule.applicable(document, request)) {
      return RuleDecision.skip("basic structure tagging does not apply to this document, so no metadata packet is written either", before, null);
    }
    return RuleDecision.apply(
        "catalog has no /Metadata; a minimal XMP packet can be derived from Info and /Lang without any conformance claim",
        null,
        describe(document));
  }

  /** What the packet will carry, read at the time of the call (the language rule may run first). */
  private static String describe(PDDocument document) {
    PDDocumentInformation info = document.getDocumentInformation();
    String title = info == null ? null : info.getTitle();
    String language = document.getDocumentCatalog().getLanguage();
    return "dc:title=" + (title == null || title.isBlank() ? "absent" : "from Info") + ", dc:language=" + (language == null || language.isBlank() ? "absent" : language);
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    PDDocumentCatalog catalog = document.getDocumentCatalog();
    if (decision.before() != null) {
      return addToExistingPacket(document, decision);
    }
    if (catalog.getMetadata() != null) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "catalog gained /Metadata between inspect and apply", "Metadata present", null);
    }
    String after = describe(document);
    try {
      byte[] packet = packet(document).getBytes(StandardCharsets.UTF_8);
      PDMetadata metadata = new PDMetadata(document, new ByteArrayInputStream(packet));
      catalog.setMetadata(metadata);
      if (catalog.getMetadata() == null) {
        return new WorkerResult.Action(id(), ActionOutcome.FAILED, "/Metadata did not persist", null, after);
      }
    } catch (IOException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not write the metadata stream (" + e.getClass().getSimpleName() + ")", null, after);
    }
    return new WorkerResult.Action(
        id(),
        ActionOutcome.APPLIED,
        "wrote a minimal XMP packet (" + after + "); no PDF/UA identifier is written: PDF_UA_IDENTIFIER_V1 adds one only in mode fix,"
            + " on request (declarePdfUa) after human review (humanReviewAcknowledged), when the machine checks pass;"
            + " whether the title and language are correct"
            + " requires human review",
        null,
        after);
  }

  /**
   * Mode {@code fix} only: an existing packet lacking {@code dc:title} or {@code dc:language} gets them via {@link XmpPacketEditor}
   * (one inserted {@code rdf:Description}, nothing else changed), decided at apply time after the title and language rules ran.
   */
  private static RuleDecision inspectExistingPacket(PDMetadata metadata) {
    XmpPacketEditor.Packet packet;
    try {
      packet = XmpPacketEditor.read(metadata.toByteArray());
    } catch (IOException e) {
      packet = XmpPacketEditor.Packet.refused("the metadata stream cannot be read");
    }
    if (packet.unreadable() != null) {
      return RuleDecision.skip("catalog already has /Metadata that is not added to: " + packet.unreadable() + "; it is left unchanged", "Metadata present", null);
    }
    if (packet.hasTitle() && packet.hasLanguage()) {
      return RuleDecision.skip(bothPresent(packet), "Metadata present", null);
    }
    String missing = !packet.hasTitle() && !packet.hasLanguage() ? "dc:title, dc:language" : packet.hasTitle() ? "dc:language" : "dc:title";
    return RuleDecision.apply(
        "the existing XMP packet has no " + missing + "; what the document says is added without changing anything else",
        "Metadata present without " + missing,
        missing);
  }

  /** The skip reason when the packet has both properties; an empty dc:title is never called a title. */
  private static String bothPresent(XmpPacketEditor.Packet packet) {
    return packet.titleBlank()
        ? "the existing XMP packet has dc:language and an empty dc:title, which is not filled in place; it is left unchanged"
        : "the existing XMP packet already has dc:title and dc:language; it is left unchanged";
  }

  private WorkerResult.Action addToExistingPacket(PDDocument document, RuleDecision decision) {
    PDMetadata metadata = document.getDocumentCatalog().getMetadata();
    if (metadata == null) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "catalog lost /Metadata between inspect and apply", decision.before(), null);
    }
    try {
      byte[] packet = metadata.toByteArray();
      XmpPacketEditor.Packet current = XmpPacketEditor.read(packet);
      if (current.unreadable() != null) {
        return new WorkerResult.Action(
            id(), ActionOutcome.SKIPPED, "the existing XMP packet is not added to: " + current.unreadable() + "; it is left unchanged", decision.before(), null);
      }
      PDDocumentInformation info = document.getDocumentInformation();
      String infoTitle = info == null ? null : info.getTitle();
      String language = document.getDocumentCatalog().getLanguage();
      String title = !current.hasTitle() && infoTitle != null && !infoTitle.isBlank() ? infoTitle : null;
      String lang = !current.hasLanguage() && language != null && !language.isBlank() ? language : null;
      if (title == null && lang == null) {
        String reason = current.hasTitle() && current.hasLanguage()
            ? bothPresent(current)
            : "the existing XMP packet lacks " + (current.hasTitle() ? "dc:language" : current.hasLanguage() ? "dc:title" : "dc:title and dc:language")
                + ", but the document has " + (current.hasTitle() ? "no /Lang" : current.hasLanguage() ? "no Info title" : "no Info title and no /Lang")
                + " to copy; nothing is invented";
        return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, reason, decision.before(), null);
      }
      metadata.importXMPMetadata(XmpPacketEditor.add(packet, title, lang));
      List<String> added = new ArrayList<>();
      List<String> after = new ArrayList<>();
      if (title != null) {
        added.add("dc:title (from Info)");
        after.add("dc:title=from Info");
      }
      if (lang != null) {
        added.add("dc:language (" + lang + ")");
        after.add("dc:language=" + lang);
      }
      return new WorkerResult.Action(
          id(),
          ActionOutcome.APPLIED,
          "added " + String.join(" and ", added) + " to the existing XMP packet; nothing in it was changed or removed; whether the title and"
              + " language are correct requires human review",
          decision.before(),
          String.join(", ", after));
    } catch (IOException e) {
      return new WorkerResult.Action(
          id(), ActionOutcome.SKIPPED, "the existing XMP packet could not be added to (" + e.getMessage() + "); it is left unchanged", decision.before(), null);
    }
  }

  static String packet(PDDocument document) {
    PDDocumentInformation info = document.getDocumentInformation();
    String title = info == null ? null : info.getTitle();
    String creator = info == null ? null : info.getCreator();
    String producer = info == null ? null : info.getProducer();
    String language = document.getDocumentCatalog().getLanguage();
    StringBuilder xml = new StringBuilder();
    xml.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n");
    xml.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n");
    xml.append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n");
    xml.append("  <rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
        .append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n");
    if (title != null && !title.isBlank()) {
      xml.append("   <dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(escape(title)).append("</rdf:li></rdf:Alt></dc:title>\n");
    }
    if (language != null && !language.isBlank()) {
      xml.append("   <dc:language><rdf:Bag><rdf:li>").append(escape(language)).append("</rdf:li></rdf:Bag></dc:language>\n");
    }
    if (creator != null && !creator.isBlank()) {
      xml.append("   <xmp:CreatorTool>").append(escape(creator)).append("</xmp:CreatorTool>\n");
    }
    if (producer != null && !producer.isBlank()) {
      xml.append("   <pdf:Producer>").append(escape(producer)).append("</pdf:Producer>\n");
    }
    xml.append("  </rdf:Description>\n");
    xml.append(" </rdf:RDF>\n");
    xml.append("</x:xmpmeta>\n");
    xml.append("<?xpacket end=\"w\"?>\n");
    return xml.toString();
  }

  /** XML 1.0 text: markup escaped, C0 controls blanked, non-characters and lone surrogates replaced by U+FFFD. */
  static String escape(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        default -> {
          if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
            sb.append(' ');
          } else if (c == '\uFFFE' || c == '\uFFFF') {
            sb.append('\uFFFD');
          } else if (Character.isHighSurrogate(c)) {
            if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
              sb.append(c).append(value.charAt(++i));
            } else {
              sb.append('\uFFFD');
            }
          } else if (Character.isLowSurrogate(c)) {
            sb.append('\uFFFD');
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
