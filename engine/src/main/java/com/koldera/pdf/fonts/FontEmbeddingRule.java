package com.koldera.pdf.fonts;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import com.koldera.pdf.structure.RenderIdentity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * {@code PDF_FONT_EMBEDDING_V1} (mode {@code fix}, uploads): a non-embedded simple font that shows visible text gets a subset
 * of the bundled metric-compatible Liberation font ({@link FontLibrary}: Arial, Helvetica, Times New Roman, Times only), when
 * {@link SubstituteEmbedder#blocker} finds no objection and text and glyph positions stay identical ({@link GlyphAudit}).
 * Fonts also reached through patterns, Type 3 glyphs or rollover appearances are left ({@link FontReach}) and named.
 */
public final class FontEmbeddingRule implements RemediationRule {

  static final String NOTHING = "every font used for visible text is embedded";
  static final String APPLIED_PREFIX = "embedded metric-compatible substitutes for ";
  static final String LEFT_PREFIX = "left not embedded: ";
  static final String NO_SUBSTITUTE = "no bundled metric-compatible font";
  static final String REACHED = "also used in a pattern, a Type 3 glyph or a rollover or down appearance";
  static final String FORM_FIELDS = "also used by form fields";
  static final String TEXT_WOULD_CHANGE = "text or glyph positions would change";
  static final String ARROW = " → ";
  private static final java.util.regex.Pattern SUBSET_TAG = java.util.regex.Pattern.compile("^[A-Z]{6}\\+");

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_FONT_EMBEDDING_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("fonts are only embedded in fix for uploaded PDFs", null, null);
    }
    return RuleDecision.apply("the non-embedded fonts are checked when this rule applies", null, null);
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    try {
      return run(document);
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not embed the fonts (" + e.getClass().getSimpleName() + ")", null, null);
    }
  }

  /**
   * The substitute pairs of an applied action, {@code {original, substitute}}, in the reason's order; empty for any other
   * action.
   */
  public static List<String[]> embedded(WorkerResult.Action action) {
    if (action.ruleId() != WorkerResult.RuleId.PDF_FONT_EMBEDDING_V1 || action.outcome() != ActionOutcome.APPLIED
        || !action.reason().startsWith(APPLIED_PREFIX)) {
      return List.of();
    }
    String reason = action.reason();
    int colon = reason.indexOf(": ");
    int end = reason.indexOf("; " + LEFT_PREFIX);
    String list = reason.substring(colon + 2, end < 0 ? reason.length() : end);
    List<String[]> pairs = new ArrayList<>();
    for (String pair : list.split(", ")) {
      int arrow = pair.indexOf(ARROW);
      if (arrow > 0) {
        pairs.add(new String[] {pair.substring(0, arrow), pair.substring(arrow + ARROW.length())});
      }
    }
    return pairs;
  }

  /** One font to embed: its index in {@link FontUses#collect} order, the use, the substitute and its display name. */
  private record Candidate(int index, FontUses.Use use, FontLibrary.Substitute substitute, String substituteName) {}

  private record Plan(List<Candidate> candidates, List<String> left) {}

  private WorkerResult.Action run(PDDocument document) throws IOException {
    Plan plan = analyze(document);
    Set<String> left = new LinkedHashSet<>(plan.left());
    List<Candidate> accepted = verified(document, plan.candidates());
    for (Candidate c : plan.candidates()) {
      if (!accepted.contains(c)) {
        left.add(name(c.use().font()) + ": " + TEXT_WOULD_CHANGE);
      }
    }
    String leftClause = left.isEmpty() ? null : LEFT_PREFIX + left.size() + " (" + String.join("; ", left) + ")";
    if (accepted.isEmpty()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, leftClause == null ? NOTHING : leftClause, null, null);
    }
    // Named before the change: the dictionaries' /BaseFont becomes the substitute's.
    Set<String> pairs = new LinkedHashSet<>();
    for (Candidate c : accepted) {
      pairs.add(name(c.use().font()) + ARROW + c.substituteName());
    }
    execute(document, accepted);
    String reason = APPLIED_PREFIX + pairs.size() + " fonts: " + String.join(", ", pairs) + (leftClause == null ? "" : "; " + leftClause);
    int n = pairs.size();
    return new WorkerResult.Action(id(), ActionOutcome.APPLIED, reason, n + (n == 1 ? " font" : " fonts") + " not embedded",
        n + (n == 1 ? " font" : " fonts") + " embedded with metric-compatible substitutes");
  }

  private static Plan analyze(PDDocument document) throws IOException {
    List<FontUses.Use> uses = FontUses.collect(document);
    FontReach reach = FontReach.walk(document);
    List<Candidate> candidates = new ArrayList<>();
    Set<String> left = new LinkedHashSet<>();
    Set<COSDictionary> handled = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<FontLibrary.Substitute, TrueTypeFont> loaded = new LinkedHashMap<>();
    Set<COSDictionary> formFonts = formFonts(document);
    try {
      for (int i = 0; i < uses.size(); i++) {
        FontUses.Use use = uses.get(i);
        PDFont font = use.font();
        handled.add(font.getCOSObject());
        if (!use.visible() || font.isEmbedded()) {
          continue;
        }
        String name = name(font);
        Optional<FontLibrary.Substitute> substitute = simple(font) ? FontLibrary.substituteFor(font.getName()) : Optional.empty();
        if (substitute.isEmpty()) {
          left.add(name + ": " + NO_SUBSTITUTE);
          continue;
        }
        if (formFonts.contains(font.getCOSObject())) {
          // A viewer regenerates field appearances from /DR with any code the user types: not coverable by a subset.
          left.add(name + ": " + FORM_FIELDS);
          continue;
        }
        if (reach.restricted(font.getCOSObject())) {
          left.add(name + ": " + REACHED);
          continue;
        }
        TrueTypeFont ttf = loaded.get(substitute.get());
        if (ttf == null) {
          ttf = substitute.get().load();
          loaded.put(substitute.get(), ttf);
        }
        Optional<String> blocker;
        try {
          blocker = SubstituteEmbedder.blocker((PDSimpleFont) font, new TreeSet<>(use.codes().keySet()), ttf);
        } catch (IOException | RuntimeException e) {
          blocker = Optional.of("the font could not be read");
        }
        if (blocker.isPresent()) {
          left.add(name + ": " + blocker.get());
          continue;
        }
        candidates.add(new Candidate(i, use, substitute.get(), SubstituteEmbedder.displayName(ttf)));
      }
    } finally {
      for (TrueTypeFont ttf : loaded.values()) {
        ttf.close();
      }
    }
    // A non-embedded font only a pattern, a Type 3 glyph or a rollover/down appearance shows: what it shows is not known.
    for (COSDictionary font : reach.fonts()) {
      if (!handled.contains(font) && reach.restricted(font) && nonEmbeddedSimple(font)) {
        String base = font.getNameAsString(COSName.BASE_FONT);
        left.add((base == null ? "unnamed font" : SUBSET_TAG.matcher(base).replaceFirst("")) + ": " + REACHED);
      }
    }
    return new Plan(candidates, List.copyOf(left));
  }

  /** The font dictionaries of the interactive form's default resources ({@code /AcroForm /DR /Font}). */
  private static Set<COSDictionary> formFonts(PDDocument document) {
    Set<COSDictionary> fonts = Collections.newSetFromMap(new IdentityHashMap<>());
    if (document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.ACRO_FORM) instanceof COSDictionary form
        && form.getDictionaryObject(COSName.DR) instanceof COSDictionary dr && dr.getDictionaryObject(COSName.FONT) instanceof COSDictionary map) {
      for (COSName key : map.keySet()) {
        if (map.getDictionaryObject(key) instanceof COSDictionary font) {
          fonts.add(font);
        }
      }
    }
    return fonts;
  }

  /** A {@code /Type1} or {@code /TrueType} dictionary (not MMType1) PDFBox loaded as such. */
  private static boolean simple(PDFont font) {
    COSName subtype = font.getCOSObject().getCOSName(COSName.SUBTYPE);
    return (font instanceof PDType1Font && COSName.TYPE1.equals(subtype)) || (font instanceof PDTrueTypeFont && COSName.TRUE_TYPE.equals(subtype));
  }

  private static boolean nonEmbeddedSimple(COSDictionary font) {
    COSName subtype = font.getCOSName(COSName.SUBTYPE);
    if (!COSName.TYPE1.equals(subtype) && !COSName.TRUE_TYPE.equals(subtype)) {
      return false;
    }
    COSDictionary descriptor = font.getCOSDictionary(COSName.FONT_DESC);
    return descriptor == null || (!descriptor.containsKey(COSName.FONT_FILE) && !descriptor.containsKey(COSName.FONT_FILE2)
        && !descriptor.containsKey(COSName.FONT_FILE3));
  }

  /**
   * The candidates whose embedding keeps every glyph's Unicode value and position on a scratch copy: all together when that
   * holds, else each one alone and then the passing ones together (none when that still fails).
   */
  private static List<Candidate> verified(PDDocument document, List<Candidate> candidates) throws IOException {
    if (candidates.isEmpty()) {
      return List.of();
    }
    List<GlyphAudit.Glyph> before = GlyphAudit.capture(document);
    byte[] scratch = RenderIdentity.scratchCopy(document);
    if (unchanged(scratch, candidates, before)) {
      return candidates;
    }
    List<Candidate> passing = new ArrayList<>();
    for (Candidate c : candidates) {
      if (unchanged(scratch, List.of(c), before)) {
        passing.add(c);
      }
    }
    if (passing.size() > 1 && !unchanged(scratch, passing, before)) {
      return List.of();
    }
    return passing;
  }

  private static boolean unchanged(byte[] scratchBytes, List<Candidate> candidates, List<GlyphAudit.Glyph> before) throws IOException {
    try (PDDocument scratch = Loader.loadPDF(scratchBytes)) {
      List<FontUses.Use> uses = FontUses.collect(scratch);
      List<Candidate> mapped = new ArrayList<>();
      for (Candidate c : candidates) {
        if (c.index() >= uses.size() || !name(uses.get(c.index()).font()).equals(name(c.use().font()))) {
          return false;
        }
        mapped.add(new Candidate(c.index(), uses.get(c.index()), c.substitute(), c.substituteName()));
      }
      execute(scratch, mapped);
      List<GlyphAudit.Glyph> after = GlyphAudit.capture(scratch);
      if (after.size() != before.size()) {
        return false;
      }
      for (int i = 0; i < before.size(); i++) {
        if (!java.util.Objects.equals(before.get(i).unicode(), after.get(i).unicode())) {
          return false;
        }
      }
      return GlyphAudit.positionFailure(before, after) == null;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private static void execute(PDDocument document, List<Candidate> candidates) throws IOException {
    for (Candidate c : candidates) {
      SortedSet<Integer> codes = new TreeSet<>(c.use().codes().keySet());
      SubstituteEmbedder.embed(document, (PDSimpleFont) c.use().font(), codes, c.substitute());
    }
    // Later rules read fonts through the document's resource cache, which still holds the fonts as they were.
    document.setResourceCache(new DefaultResourceCache());
  }

  /** The font's name as a person knows it: {@code /BaseFont} without a subset tag ({@code [A-Z]{6}+}). */
  private static String name(PDFont font) {
    String name = font.getName();
    return name == null ? "unnamed font" : SUBSET_TAG.matcher(name).replaceFirst("");
  }
}
