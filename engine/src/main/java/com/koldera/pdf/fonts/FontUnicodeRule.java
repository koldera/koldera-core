package com.koldera.pdf.fonts;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

/**
 * {@code PDF_FONT_TOUNICODE_V1} (mode {@code fix}, uploads): writes or completes the {@code ToUnicode} map of fonts whose used
 * codes do not all map to Unicode, from the file's own data only: existing entries, values PDFBox already extracts, glyph
 * names (AGL, TeX table), the embedded TrueType {@code cmap}, the Macintosh glyph order. Nothing else is guessed; unmapped
 * codes are named. Existing valid entries are kept in a new stream; the glyph audit proves only glyphs without a value gained one.
 */
public final class FontUnicodeRule implements RemediationRule {

  /** The standard Macintosh glyph order is a source, under two-source agreement. */
  static final boolean USE_MAC_GLYPH_ORDER = true;

  static final String NOTHING = "every font used for text maps its codes to Unicode";
  private static final String NO_SOURCE = "no Unicode source for the unmapped codes; ";
  private static final int MAX_NAMES = 5;
  private static final Pattern SUBSET_TAG = Pattern.compile("^[A-Z]{6}\\+");
  private static final Pattern CHANGED_COUNTS = Pattern.compile(
      "; text changed for \\d+ codes \\((\\d+) from standard glyph names, (\\d+) from TeX glyph names, (\\d+) from the font program,"
          + " (\\d+) from the standard Macintosh glyph order\\)");
  private static final Set<String> STANDARD_ENCODINGS = Set.of("WinAnsiEncoding", "MacRomanEncoding", "MacExpertEncoding", "StandardEncoding");
  private static final Set<String> CJK_ORDERINGS = Set.of("GB1", "CNS1", "Japan1", "Korea1");

  /** Codes without a Unicode source: how many font/code pairs, in how many fonts, and the fonts' distinct names (subset tag removed, "Type 3 font" for a nameless one), sorted. */
  public record Unmapped(int codes, int fonts, List<String> names) {}

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_FONT_TOUNICODE_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("ToUnicode maps are only written in fix for uploaded PDFs", null, null);
    }
    return RuleDecision.apply("the fonts' Unicode mappings are checked when this rule applies", null, null);
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    try {
      return run(document);
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not write the ToUnicode maps (" + e.getClass().getSimpleName() + ")", null, null);
    }
  }

  private WorkerResult.Action run(PDDocument document) throws IOException {
    Outcome outcome = analyze(document, true);
    Unmapped unmapped = outcome.unmapped();
    String unmappedTail = unmapped == null ? "" : unmapped.codes() + " codes in " + unmapped.fonts() + " fonts";
    if (outcome.written() == 0) {
      return unmapped == null
          ? new WorkerResult.Action(id(), ActionOutcome.SKIPPED, NOTHING, null, null)
          : new WorkerResult.Action(id(), ActionOutcome.SKIPPED, NO_SOURCE + unmappedTail + " stay unmapped: " + names(unmapped.names()), null, null);
    }
    EnumMap<GlyphNames.Source, Integer> counts = outcome.counts();
    EnumMap<GlyphNames.Source, Integer> changed = outcome.changed();
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    int changedTotal = changed.values().stream().mapToInt(Integer::intValue).sum();
    int written = outcome.written();
    String reason = "ToUnicode written for " + written + " fonts (" + total + " codes: " + counts.get(GlyphNames.Source.EXISTING)
        + " kept from the existing text, " + perSource(counts) + ")";
    if (outcome.invalidReplaced() > 0) {
      reason += INVALID + outcome.invalidReplaced();
    }
    reason += CHANGED + changedTotal + " codes" + (changedTotal == 0 ? "" : " (" + perSource(changed) + ")");
    if (unmapped != null) {
      reason += "; " + unmappedTail + " have no source and stay unmapped: " + names(unmapped.names());
    }
    return new WorkerResult.Action(id(), ActionOutcome.APPLIED, reason, written + (written == 1 ? " font" : " fonts") + " without a Unicode value for every used code",
        "ToUnicode written for " + written + (written == 1 ? " font" : " fonts"));
  }

  private static final String INVALID = "; invalid entries replaced ";
  private static final String CHANGED = "; text changed for ";

  private static String perSource(Map<GlyphNames.Source, Integer> counts) {
    return counts.get(GlyphNames.Source.AGL) + " from standard glyph names, " + counts.get(GlyphNames.Source.TEX) + " from TeX glyph names, "
        + counts.get(GlyphNames.Source.PROGRAM) + " from the font program, " + counts.get(GlyphNames.Source.MAC_ORDER)
        + " from the standard Macintosh glyph order";
  }

  /**
   * The whole document's result: fonts written, written codes per source, codes whose extracted text
   * changed per source, invalid existing entries dropped from rewritten maps, and the codes without a
   * source ({@code null} when none).
   */
  private record Outcome(
      int written, EnumMap<GlyphNames.Source, Integer> counts, EnumMap<GlyphNames.Source, Integer> changed, int invalidReplaced, Unmapped unmapped) {}

  private static EnumMap<GlyphNames.Source, Integer> zeros() {
    EnumMap<GlyphNames.Source, Integer> m = new EnumMap<>(GlyphNames.Source.class);
    for (GlyphNames.Source source : GlyphNames.Source.values()) {
      m.put(source, 0);
    }
    return m;
  }

  /** With {@code write} the maps are written; without, the document is only read. */
  private static Outcome analyze(PDDocument document, boolean write) throws IOException {
    EnumMap<GlyphNames.Source, Integer> counts = zeros();
    EnumMap<GlyphNames.Source, Integer> changed = zeros();
    int written = 0;
    int invalid = 0;
    int unmappedCodes = 0;
    int unmappedFonts = 0;
    List<String> unmappedNames = new ArrayList<>();
    for (FontUses.Use use : FontUses.collect(document)) {
      PDFont font = use.font();
      if (!(font instanceof PDSimpleFont) && !(font instanceof PDType0Font)) {
        continue;
      }
      Plan plan;
      try {
        plan = plan(font, use.codes().keySet());
      } catch (IOException | RuntimeException e) {
        // A damaged font: nothing is written for it; its codes count as unmapped.
        plan = new Plan(null, 0, new TreeMap<>(), use.codes().size());
      }
      if (plan.unmapped() > 0) {
        unmappedCodes += plan.unmapped();
        unmappedFonts++;
        unmappedNames.add(displayName(font));
      }
      if (plan.resolved().isEmpty() || !write) {
        continue;
      }
      TreeMap<Integer, String> textBefore = new TreeMap<>();
      for (int code : plan.resolved().keySet()) {
        textBefore.put(code, extractedOrRaw(font, code));
      }
      invalid += writeToUnicode(document, font.getCOSObject(), plan);
      CMap now = existingToUnicode(font.getCOSObject());
      for (Map.Entry<Integer, GlyphNames.Resolved> e : plan.resolved().entrySet()) {
        GlyphNames.Source source = e.getValue().source();
        counts.merge(source, 1, Integer::sum);
        // PDFBox's extraction reads the ToUnicode map first: the new map's value is the copy's text for this code.
        String after = now == null ? null : now.toUnicode(e.getKey());
        if (!Objects.equals(textBefore.get(e.getKey()), after)) {
          changed.merge(source, 1, Integer::sum);
        }
      }
      written++;
    }
    Unmapped unmapped = unmappedCodes == 0 ? null : new Unmapped(unmappedCodes, unmappedFonts, List.copyOf(new TreeSet<>(unmappedNames)));
    return new Outcome(written, counts, changed, invalid, unmapped);
  }

  /** The text PDFBox's stripper shows for {@code code}: the extracted text, else a simple font's raw code, else nothing. */
  private static String extractedOrRaw(PDFont font, int code) throws IOException {
    String text = GlyphAudit.extractedText(font, code);
    if (text == null && font instanceof PDSimpleFont) {
      return String.valueOf((char) code);
    }
    return text;
  }

  /**
   * The codes of the document's fonts that no source in the file maps to Unicode (what this rule would
   * leave unmapped), read only; empty when there are none or the fonts cannot be read.
   */
  public static Optional<Unmapped> unmapped(PDDocument document) {
    try {
      return Optional.ofNullable(analyze(document, false).unmapped());
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * What to write for one font: {@code existing} = the parsed ToUnicode map (or null), {@code codeBytes}
   * of its codes, {@code resolved} = code → new value (not the kept existing entries), {@code unmapped}
   * = used codes that stay without a Unicode value.
   */
  private record Plan(CMap existing, int codeBytes, SortedMap<Integer, GlyphNames.Resolved> resolved, int unmapped) {}

  private static Plan plan(PDFont font, Set<Integer> codes) throws IOException {
    if (mappableWithoutToUnicode(font, codes)) {
      return new Plan(null, 0, new TreeMap<>(), 0);
    }
    CMap existing = existingToUnicode(font.getCOSObject());
    int codeBytes = font instanceof PDType0Font ? 2 : 1;
    List<Integer> needing = new ArrayList<>();
    for (int code : codes) {
      if (existing == null || !usable(existing.toUnicode(code))) {
        needing.add(code);
      }
    }
    TreeMap<Integer, GlyphNames.Resolved> resolved = new TreeMap<>();
    if (needing.isEmpty()) {
      return new Plan(existing, codeBytes, resolved, 0);
    }
    if (font instanceof PDType0Font type0 && !identityEncoded(type0)) {
      // Not written: codes of a non-identity CMap are not simply the code space of a two-byte ToUnicode.
      int unmapped = 0;
      for (int code : needing) {
        if (!usable(font.toUnicode(code))) {
          unmapped++;
        }
      }
      return new Plan(existing, codeBytes, resolved, unmapped);
    }
    List<Integer> pending = new ArrayList<>();
    for (int code : needing) {
      GlyphNames.Resolved r = resolve(font, code);
      if (r != null) {
        resolved.put(code, r);
      } else {
        pending.add(code);
      }
    }
    if (!pending.isEmpty() && USE_MAC_GLYPH_ORDER && font instanceof PDTrueTypeFont trueType && trueType.isEmbedded() && symbolic(font)) {
      TrueTypeFont program = trueType.getTrueTypeFont();
      TreeMap<Integer, Integer> codeToGid = new TreeMap<>();
      for (int code : codes) {
        codeToGid.put(code, trueType.codeToGID(code));
      }
      Optional<Map<Integer, String>> mac = MacGlyphOrder.mapping(codeToGid, program.getNumberOfGlyphs());
      if (mac.isPresent()) {
        for (var it = pending.iterator(); it.hasNext(); ) {
          int code = it.next();
          String value = mac.get().get(code);
          if (usable(value)) {
            resolved.put(code, new GlyphNames.Resolved(value, GlyphNames.Source.MAC_ORDER));
            it.remove();
          }
        }
      }
    }
    return new Plan(existing, codeBytes, resolved, pending.size());
  }

  /** One code's value from the sources before the Macintosh order, or null. */
  private static GlyphNames.Resolved resolve(PDFont font, int code) throws IOException {
    String pdfbox = font.toUnicode(code);
    if (usable(pdfbox)) {
      return new GlyphNames.Resolved(pdfbox, GlyphNames.Source.EXISTING);
    }
    String extracted = GlyphAudit.extractedText(font, code);
    if (font instanceof PDSimpleFont simple) {
      Encoding encoding = simple.getEncoding();
      if (encoding != null) {
        Optional<GlyphNames.Resolved> named = GlyphNames.resolve(encoding.getName(code)).filter(r -> usable(r.unicode()));
        if (named.isPresent()) {
          // A glyph the extraction already shows keeps exactly that text; the TeX table agrees with it by construction.
          return usable(extracted) && !extracted.equals(named.get().unicode())
              ? new GlyphNames.Resolved(extracted, GlyphNames.Source.EXISTING)
              : named.get();
        }
      }
    }
    if (usable(extracted)) {
      // PDFBox's text extraction already shows this from its own glyph lists: that exact text is kept.
      return new GlyphNames.Resolved(extracted, GlyphNames.Source.EXISTING);
    }
    TrueTypeFont program = null;
    int gid = 0;
    if (font instanceof PDTrueTypeFont trueType && trueType.isEmbedded()) {
      program = trueType.getTrueTypeFont();
      gid = trueType.codeToGID(code);
    } else if (font instanceof PDType0Font type0 && type0.isEmbedded() && type0.getDescendantFont() instanceof PDCIDFontType2 cid) {
      program = cid.getTrueTypeFont();
      gid = type0.codeToGID(code);
    }
    if (program == null || gid <= 0) {
      return null;
    }
    PostScriptTable post = program.getPostScript();
    if (post != null) {
      Optional<GlyphNames.Resolved> named = GlyphNames.resolve(post.getName(gid)).filter(r -> usable(r.unicode()));
      if (named.isPresent()) {
        return named.get();
      }
    }
    if (program.getCmap() != null) {
      TreeSet<Integer> codePoints = new TreeSet<>();
      for (CmapSubtable subtable : program.getCmap().getCmaps()) {
        boolean unicode = subtable.getPlatformId() == 0
            || (subtable.getPlatformId() == 3 && (subtable.getPlatformEncodingId() == 1 || subtable.getPlatformEncodingId() == 10));
        if (!unicode) {
          continue;
        }
        List<Integer> mapped = subtable.getCharCodes(gid);
        if (mapped != null) {
          codePoints.addAll(mapped);
        }
      }
      if (codePoints.size() == 1) {
        int cp = codePoints.first();
        if (Character.isValidCodePoint(cp)) {
          String value = new String(Character.toChars(cp));
          if (usable(value)) {
            return new GlyphNames.Resolved(value, GlyphNames.Source.PROGRAM);
          }
        }
      }
    }
    return null;
  }

  /**
   * Rule 2: a font without ToUnicode whose codes a validator already maps from the font dictionary: a
   * simple non-Type 3 font with a standard encoding (name, or dictionary whose base encoding is absent
   * or standard; a TrueType font non-symbolic) whose every used code's name is a plain AGL name, or a
   * Type 0 font on a predefined CJK CMap of an Adobe character collection.
   */
  private static boolean mappableWithoutToUnicode(PDFont font, Set<Integer> codes) {
    COSDictionary dict = font.getCOSObject();
    if (dict.containsKey(COSName.TO_UNICODE)) {
      return false;
    }
    COSBase encodingEntry = dict.getDictionaryObject(COSName.ENCODING);
    if (font instanceof PDType0Font type0) {
      if (!(encodingEntry instanceof COSName name) || name.getName().equals("Identity-H") || name.getName().equals("Identity-V")) {
        return false;
      }
      PDCIDFont descendant = type0.getDescendantFont();
      if (descendant == null || descendant.getCIDSystemInfo() == null) {
        return false;
      }
      return "Adobe".equals(descendant.getCIDSystemInfo().getRegistry()) && CJK_ORDERINGS.contains(descendant.getCIDSystemInfo().getOrdering());
    }
    if (font instanceof PDType3Font || !(font instanceof PDSimpleFont simple)) {
      return false;
    }
    boolean standard;
    if (encodingEntry instanceof COSName name) {
      standard = STANDARD_ENCODINGS.contains(name.getName());
    } else if (encodingEntry instanceof COSDictionary encodingDict) {
      COSBase base = encodingDict.getDictionaryObject(COSName.BASE_ENCODING);
      standard = base == null || (base instanceof COSName baseName && STANDARD_ENCODINGS.contains(baseName.getName()));
    } else {
      standard = false;
    }
    if (!standard) {
      return false;
    }
    if (font instanceof PDTrueTypeFont) {
      PDFontDescriptor descriptor = font.getFontDescriptor();
      if (descriptor == null || descriptor.isSymbolic()) {
        return false;
      }
    }
    Encoding encoding = simple.getEncoding();
    if (encoding == null) {
      return false;
    }
    for (int code : codes) {
      String name = encoding.getName(code);
      if (name == null || name.indexOf('.') >= 0 || name.indexOf('_') >= 0 || !GlyphAudit.valid(GlyphList.getAdobeGlyphList().toUnicode(name))) {
        return false;
      }
    }
    return true;
  }

  private static boolean identityEncoded(PDType0Font font) {
    COSBase encoding = font.getCOSObject().getDictionaryObject(COSName.ENCODING);
    return encoding instanceof COSName name && (name.getName().equals("Identity-H") || name.getName().equals("Identity-V"));
  }

  private static boolean symbolic(PDFont font) {
    PDFontDescriptor descriptor = font.getFontDescriptor();
    return descriptor != null && descriptor.isSymbolic();
  }

  /** The font's ToUnicode stream parsed as PDFBox does, or null when there is none or it cannot be read. */
  private static CMap existingToUnicode(COSDictionary font) {
    COSBase base = font.getDictionaryObject(COSName.TO_UNICODE);
    if (!(base instanceof COSStream stream)) {
      return null;
    }
    try (InputStream in = stream.createInputStream()) {
      return new CMapParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * A new ToUnicode stream for {@code font}: every usable existing entry plus the resolved codes; the old, possibly shared,
   * stream is left as is. Invalid entries (U+0000, U+FEFF, unpaired surrogates, U+FFFD) are dropped; returns how many.
   */
  private static int writeToUnicode(PDDocument document, COSDictionary font, Plan plan) throws IOException {
    TreeMap<Integer, String> entries = new TreeMap<>();
    int invalid = 0;
    if (plan.existing() != null) {
      int limit = plan.codeBytes() == 1 ? 0x100 : 0x10000;
      for (int code = 0; code < limit; code++) {
        String value = plan.existing().toUnicode(code);
        if (usable(value)) {
          entries.put(code, value);
        } else if (value != null) {
          invalid++;
        }
      }
    }
    for (Map.Entry<Integer, GlyphNames.Resolved> e : plan.resolved().entrySet()) {
      entries.put(e.getKey(), e.getValue().unicode());
    }
    COSStream stream = document.getDocument().createCOSStream();
    try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
      out.write(ToUnicodeCMapWriter.write(plan.codeBytes(), entries));
    }
    font.setItem(COSName.TO_UNICODE, stream);
    return invalid;
  }

  /** A value worth writing: valid and not U+FFFD alone. */
  private static boolean usable(String value) {
    return GlyphAudit.mappedText(value);
  }

  private static String displayName(PDFont font) {
    String name = font.getName();
    return name == null ? "Type 3 font" : SUBSET_TAG.matcher(name).replaceFirst("");
  }

  /** Distinct names, sorted, at most five joined by {@code ", "}, then {@code " and K more"}. */
  public static String names(List<String> names) {
    List<String> distinct = new ArrayList<>(new TreeSet<>(names));
    String shown = String.join(", ", distinct.subList(0, Math.min(MAX_NAMES, distinct.size())));
    return distinct.size() > MAX_NAMES ? shown + " and " + (distinct.size() - MAX_NAMES) + " more" : shown;
  }

  /**
   * The codes whose extracted text this rule changed, per source (counts above zero only), parsed from
   * an applied action's "; text changed for K codes (…)"; empty for any other action and when no text
   * changed (a written value equal to the text PDFBox already extracted changes nothing).
   */
  public static Optional<SortedMap<GlyphNames.Source, Integer>> derived(WorkerResult.Action action) {
    if (action.ruleId() != WorkerResult.RuleId.PDF_FONT_TOUNICODE_V1 || action.outcome() != ActionOutcome.APPLIED || action.reason() == null) {
      return Optional.empty();
    }
    Matcher m = CHANGED_COUNTS.matcher(action.reason());
    if (!m.find()) {
      return Optional.empty();
    }
    TreeMap<GlyphNames.Source, Integer> out = new TreeMap<>();
    List<GlyphNames.Source> order = List.of(GlyphNames.Source.AGL, GlyphNames.Source.TEX, GlyphNames.Source.PROGRAM, GlyphNames.Source.MAC_ORDER);
    for (int i = 0; i < order.size(); i++) {
      int n = Integer.parseInt(m.group(i + 1));
      if (n > 0) {
        out.put(order.get(i), n);
      }
    }
    return out.isEmpty() ? Optional.empty() : Optional.of(Collections.unmodifiableSortedMap(out));
  }
}
