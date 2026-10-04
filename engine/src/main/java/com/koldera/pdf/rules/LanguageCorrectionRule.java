package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.language.ScriptDetector;
import com.koldera.pdf.language.ScriptDetector.Detection;
import com.koldera.pdf.language.ScriptDetector.Script;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * {@code PDF_LANG_CORRECTED_FROM_TEXT_V1} (uploads, mode {@code fix}): replaces a catalog {@code /Lang} that the text clearly
 * contradicts ({@code x-unknown}, or a Latin-script language over Arabic, Hebrew, Cyrillic or CJK text, per {@link ScriptDetector}
 * on the first {@link #PAGES_READ} pages). A caller's {@code explicitLanguage} fits the script or is kept; otherwise the language
 * the letters show. A missing {@code /Lang} and XMP {@code dc:language} are untouched. An applied correction raises {@code LANGUAGE_CORRECTED}.
 */
public final class LanguageCorrectionRule implements RemediationRule {

  static final int PAGES_READ = 30;

  /**
   * Appended to the applied reason when the document's XMP packet already has a {@code dc:language}: the
   * packet is never rewritten, so it keeps the old value (the finding says so).
   */
  public static final String XMP_LANGUAGE_KEPT = "; the XMP dc:language was left as it was";

  private static boolean xmpHasLanguage(PDDocument document) {
    org.apache.pdfbox.pdmodel.common.PDMetadata metadata = document.getDocumentCatalog().getMetadata();
    if (metadata == null) {
      return false;
    }
    try {
      XmpPacketEditor.Packet packet = XmpPacketEditor.read(metadata.toByteArray());
      return packet.unreadable() == null ? packet.hasLanguage() : true;
    } catch (IOException e) {
      return true;
    }
  }

  /** The text scripts a Latin tag is corrected over (a fixed list). */
  private static final Set<Script> CONTRADICTING = Set.of(Script.ARABIC, Script.HEBREW, Script.CYRILLIC, Script.CJK);

  private static final Map<String, Script> SCRIPT_SUBTAGS = Map.ofEntries(
      Map.entry("latn", Script.LATIN), Map.entry("cyrl", Script.CYRILLIC), Map.entry("arab", Script.ARABIC), Map.entry("hebr", Script.HEBREW),
      Map.entry("grek", Script.GREEK), Map.entry("hans", Script.CJK), Map.entry("hant", Script.CJK), Map.entry("hani", Script.CJK),
      Map.entry("jpan", Script.CJK), Map.entry("kore", Script.CJK), Map.entry("hang", Script.CJK), Map.entry("thai", Script.THAI));

  /** Languages written in one script only (multi-script languages such as sr, az, uz, kk are left out on purpose). */
  private static final Map<String, Script> LANGUAGE_SCRIPTS = languageScripts();

  private static Map<String, Script> languageScripts() {
    java.util.HashMap<String, Script> m = new java.util.HashMap<>();
    for (String l : "en fr de es it pt nl pl cs sk sl hr bs ro hu fi sv da no nb nn is et lv lt tr id ms vi sq ga cy eu ca gl mt sw tl fil af lb mi so ha yo ig zu xh st tn".split(" ")) {
      m.put(l, Script.LATIN);
    }
    for (String l : "uk ru be bg mk".split(" ")) {
      m.put(l, Script.CYRILLIC);
    }
    for (String l : "ar fa ur ps".split(" ")) {
      m.put(l, Script.ARABIC);
    }
    for (String l : "he iw yi".split(" ")) {
      m.put(l, Script.HEBREW);
    }
    for (String l : "zh ja ko".split(" ")) {
      m.put(l, Script.CJK);
    }
    m.put("el", Script.GREEK);
    m.put("th", Script.THAI);
    return Map.copyOf(m);
  }

  /** What to do: {@code language} is the replacement, or null when the tag is kept; {@code reason} says why. */
  record Correction(String language, String reason) {}

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    String existing = document.getDocumentCatalog().getLanguage();
    if (!participates(request)) {
      return RuleDecision.skip("the language is only corrected in fix for uploaded PDFs", existing, null);
    }
    if (existing == null) {
      return RuleDecision.skip("the catalog has no /Lang; a missing language is only set from explicit input", null, null);
    }
    if (!isUnknown(existing) && scriptOf(existing) != Script.LATIN) {
      // Cheap exit before reading any text: only x-unknown and Latin-script tags can be corrected.
      return RuleDecision.skip("the declared language '" + existing + "' is kept: only x-unknown or a Latin-script tag is ever corrected", existing, null);
    }
    Detection detection;
    try {
      detection = ScriptDetector.detect(text(document));
    } catch (IOException | RuntimeException e) {
      return RuleDecision.skip("the declared language '" + existing + "' is kept: the text could not be read (" + e.getClass().getSimpleName() + ")", existing, null);
    }
    Correction correction = decide(existing, request.explicitLanguage(), detection);
    return correction.language() == null
        ? RuleDecision.skip(correction.reason(), existing, null)
        : RuleDecision.apply(correction.reason(), existing, correction.language());
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    String current = document.getDocumentCatalog().getLanguage();
    if (current == null || !current.equals(decision.before())) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "the catalog /Lang changed between inspect and apply", current, null);
    }
    document.getDocumentCatalog().setLanguage(decision.after());
    return new WorkerResult.Action(
        id(),
        ActionOutcome.APPLIED,
        "replaced the catalog /Lang: " + decision.reason() + "; whether the new language is right requires human review"
            + (xmpHasLanguage(document) ? XMP_LANGUAGE_KEPT : ""),
        decision.before(),
        decision.after());
  }

  static Correction decide(String existing, String explicit, Detection detection) {
    boolean unknown = isUnknown(existing);
    Script declared = scriptOf(existing);
    if (!unknown && declared != Script.LATIN) {
      return new Correction(null, "the declared language '" + existing + "' is kept: only x-unknown or a Latin-script tag is ever corrected");
    }
    if (detection.script() == null) {
      return new Correction(null, "the declared language '" + existing + "' is kept: " + detection.note());
    }
    String opening;
    if (unknown) {
      opening = "the declared language '" + existing + "' names no language, and the text is " + detection.script().label();
    } else if (!CONTRADICTING.contains(detection.script())) {
      return new Correction(null, "the declared language '" + existing + "' is written in Latin script and the text is " + detection.script().label() + "; it is kept");
    } else {
      opening = String.format(Locale.ROOT, "the declared language '%s' is written in Latin script, but %.0f%% of the text's letters are %s",
          existing, detection.share() * 100, detection.script().label());
    }
    if (explicit != null) {
      if (scriptOf(explicit) == detection.script()) {
        return new Correction(explicit, opening + "; the caller's language '" + explicit + "' is written in that script");
      }
      return new Correction(null, "the declared language '" + existing + "' is kept: the caller's language '" + explicit
          + "' is not written in the text's script (" + detection.script().label() + "), and a language the caller supplied is never"
          + " overridden; a person should check both");
    }
    if (detection.language() != null) {
      return new Correction(detection.language(), opening + "; the text's letters show '" + detection.language() + "'");
    }
    String shortOpening = unknown ? opening : "the declared language '" + existing + "' is written in Latin script, but the text is " + detection.script().label();
    return new Correction(null, shortOpening + "; " + detection.note() + ", and no explicit language in that script was supplied; the language is left for a person");
  }

  private static boolean isUnknown(String tag) {
    return "x-unknown".equalsIgnoreCase(tag.strip());
  }

  /** The script a BCP 47 tag's language is written in: an explicit script subtag, else the primary language; null when not known. */
  static Script scriptOf(String tag) {
    String[] parts = tag.strip().toLowerCase(Locale.ROOT).split("[-_]");
    for (int i = 1; i < parts.length; i++) {
      if (parts[i].length() == 4 && SCRIPT_SUBTAGS.containsKey(parts[i])) {
        return SCRIPT_SUBTAGS.get(parts[i]);
      }
    }
    return LANGUAGE_SCRIPTS.get(parts[0]);
  }

  private static String text(PDDocument document) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setStartPage(1);
    stripper.setEndPage(Math.min(PAGES_READ, document.getNumberOfPages()));
    return stripper.getText(document);
  }
}
