package com.koldera.pdf.fonts;

import com.koldera.pdf.preflight.PdfPreflight;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Glyph-level text audit for font repairs that may only <em>fill</em> Unicode values: every glyph shown by the
 * page content, in the text stripper's order, with code, Unicode value, position and advance.
 * {@link #fillOnlyFailure} holds when the glyph sequence is unchanged, already valid text is unchanged, glyphs
 * without text gain at most a real character, and the text digest without those glyphs is identical.
 */
public final class GlyphAudit extends PDFTextStripper {

  public static final String FAILURE_PREFIX = "text check: ";

  /**
   * One shown glyph: {@code page} 1-based, {@code ordinal} 0-based per page in content order,
   * {@code unicode} = {@code font.toUnicode(code)} (may be {@code null}), {@code x}/{@code y} = the
   * text rendering matrix translation, {@code advance} = displacement x &times; font size &times;
   * horizontal scaling; floats rounded to 0.01.
   */
  public record Glyph(int page, int ordinal, String fontName, int code, String unicode, float x, float y, float advance) {}

  /**
   * The glyph list PDFBox's text stripper resolves names with ({@code LegacyPDFStreamEngine}): the
   * AGL plus PDFBox's {@code additional.txt} (TeX names such as {@code summationtext},
   * {@code epsilon1}, {@code angbracketleft}, {@code prime}, {@code radicaltp}).
   */
  private static final GlyphList EXTRACTION_GLYPHS = loadExtractionGlyphs();

  /** Largest difference in {@code x}, {@code y} or {@code advance} that {@link #positionFailure} ignores (the rounding step). */
  static final float POSITION_TOLERANCE = 0.01f;

  /** Slack for float arithmetic on values already rounded to 0.01. */
  private static final float FLOAT_SLACK = 1e-4f;

  private final List<Glyph> sink;
  private final boolean aglUnicode;
  private final List<String> texts;
  private final Set<Long> fixedMask;
  private final Set<Long> ownMask;
  private int nextOrdinal;
  private boolean dropping;

  /**
   * Collector options: {@code sink} receives glyphs, {@code aglUnicode} fills the Unicode value, {@code texts}
   * receives per-glyph text, {@code fixedMask} lists glyphs left out, {@code ownMask} masks (and records) unmapped glyphs.
   */
  private GlyphAudit(List<Glyph> sink, boolean aglUnicode, List<String> texts, Set<Long> fixedMask, Set<Long> ownMask) {
    this.sink = sink;
    this.aglUnicode = aglUnicode;
    this.texts = texts;
    this.fixedMask = fixedMask;
    this.ownMask = ownMask;
    setSortByPosition(true);
  }

  /** Every glyph of the document's page content, in content order per page. */
  public static List<Glyph> capture(PDDocument document) throws IOException {
    List<Glyph> glyphs = new ArrayList<>();
    new GlyphAudit(glyphs, true, null, null, null).writeText(document, Writer.nullWriter());
    return glyphs;
  }

  /**
   * One stripper pass: the glyphs, per glyph the text the stripper resolves for it
   * ({@code font.toUnicode(code, EXTRACTION_GLYPHS)}; {@code null} when it falls back to the raw
   * code of a simple font or to nothing), the mask and the masked digest. With {@code mask ==
   * null} the pass masks the document's own glyphs without mapped text and returns them as the mask.
   */
  private record Pass(List<Glyph> glyphs, List<String> texts, Set<Long> mask, String digest) {
    static Pass run(PDDocument document, Set<Long> mask) throws IOException {
      List<Glyph> glyphs = new ArrayList<>();
      List<String> texts = new ArrayList<>();
      Set<Long> own = mask == null ? new HashSet<>() : null;
      String text = new GlyphAudit(glyphs, false, texts, mask, own).getText(document);
      return new Pass(glyphs, texts, mask == null ? own : mask, PdfPreflight.normalizedTextDigest(text));
    }
  }

  private static GlyphList loadExtractionGlyphs() {
    try (InputStream additional = GlyphList.class.getResourceAsStream("/org/apache/pdfbox/resources/glyphlist/additional.txt")) {
      return additional == null ? GlyphList.getAdobeGlyphList() : new GlyphList(GlyphList.getAdobeGlyphList(), additional);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The text the stripper extracts for {@code code} of {@code font} from the ToUnicode map or its glyph lists ({@code null} = none). */
  static String extractedText(PDFont font, int code) throws IOException {
    return font.toUnicode(code, EXTRACTION_GLYPHS);
  }

  /** Text the stripper already extracts from its own tables counts as mapped; replacement-character-only text does not. */
  static boolean mappedText(String text) {
    return valid(text) && !text.chars().allMatch(c -> c == 0xFFFD);
  }

  /** Non-null, non-empty, no U+0000, U+FEFF, U+FFFE and no unpaired surrogate. */
  public static boolean valid(String unicode) {
    if (unicode == null || unicode.isEmpty()) {
      return false;
    }
    for (int i = 0; i < unicode.length(); i++) {
      char c = unicode.charAt(i);
      if (c == '\u0000' || c == 0xFEFF || c == 0xFFFE) {
        return false;
      }
      if (Character.isHighSurrogate(c)) {
        if (i + 1 < unicode.length() && Character.isLowSurrogate(unicode.charAt(i + 1))) {
          i++;
          continue;
        }
        return false;
      }
      if (Character.isLowSurrogate(c)) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@link PdfPreflight#normalizedTextDigest} of the extracted text without the glyphs whose key
   * {@code ((long) page << 32) | ordinal} is in {@code masked}. They are dropped, not replaced, so the digest is
   * symmetric: a composite-font glyph without text yields no text position, but one after a fill does.
   */
  public static String maskedTextDigest(PDDocument document, Set<Long> masked) throws IOException {
    return PdfPreflight.normalizedTextDigest(new GlyphAudit(null, false, null, Set.copyOf(masked), null).getText(document));
  }

  /**
   * {@code null} when {@code after} only filled glyphs that had no text in {@code before}. A glyph is mapped when the
   * stripper already extracts valid text (ToUnicode, AGL or PDFBox's {@code additional.txt}); that text must not change.
   * Only unmapped glyphs may gain a real character, and the text without them must be identical ({@link #maskedTextDigest}).
   * The stripper resolves e.g. {@code summationtext} through {@code additional.txt}, so such a glyph counts as mapped.
   */
  public static String fillOnlyFailure(PDDocument before, PDDocument after) throws IOException {
    Pass beforePass = Pass.run(before, null);
    Pass afterPass = Pass.run(after, beforePass.mask());
    List<Glyph> b = beforePass.glyphs();
    List<Glyph> a = afterPass.glyphs();
    List<String> bText = beforePass.texts();
    List<String> aText = afterPass.texts();
    if (b.size() != a.size()) {
      return FAILURE_PREFIX + "the glyph sequence changed (" + b.size() + " glyphs before, " + a.size() + " after)";
    }
    for (int i = 0; i < b.size(); i++) {
      Glyph was = b.get(i);
      Glyph now = a.get(i);
      if (was.page() != now.page() || was.ordinal() != now.ordinal()) {
        return FAILURE_PREFIX + "the glyph sequence changed (" + b.size() + " glyphs before, " + a.size() + " after)";
      }
      if (was.code() != now.code()) {
        return FAILURE_PREFIX + describe(was) + " changed from code " + was.code() + " to code " + now.code();
      }
      String wasText = bText.get(i);
      String nowText = aText.get(i);
      if (mappedText(wasText)) {
        if (!wasText.equals(nowText)) {
          return FAILURE_PREFIX + describe(was) + " changed its text from " + quote(wasText) + " to " + quote(nowText);
        }
      } else if (nowText != null && !nowText.equals(wasText) && !mappedText(nowText)) {
        // no real character: invalid, or U+FFFD alone
        return FAILURE_PREFIX + describe(was) + " gained an invalid Unicode value";
      }
    }
    if (!beforePass.digest().equals(afterPass.digest())) {
      return FAILURE_PREFIX + "the extracted text changed outside the glyphs that had no Unicode value";
    }
    return null;
  }

  /** {@code null} when every glyph keeps its {@code x}, {@code y} and {@code advance} within {@link #POSITION_TOLERANCE}. */
  public static String positionFailure(List<Glyph> before, List<Glyph> after) {
    if (before.size() != after.size()) {
      return "the glyph sequence changed (" + before.size() + " glyphs before, " + after.size() + " after)";
    }
    for (int i = 0; i < before.size(); i++) {
      Glyph was = before.get(i);
      Glyph now = after.get(i);
      if (differs(was.x(), now.x()) || differs(was.y(), now.y()) || differs(was.advance(), now.advance())) {
        return describe(was) + " moved";
      }
    }
    return null;
  }

  @Override
  public void processPage(PDPage page) throws IOException {
    nextOrdinal = 0;
    super.processPage(page);
  }

  /**
   * The audit is about glyphs: a marked-content {@code /ActualText} is not substituted for them, so a
   * copy whose rebuild kept an element's replacement text on its marked content (the structure rule)
   * is audited on the same glyph text as the original.
   */
  @Override
  public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
    super.beginMarkedContentSequence(tag, honorActualText ? properties : PdfPreflight.withoutActualText(properties));
  }

  /** Only {@link #sameReplacementText}: marked-content {@code /ActualText} is substituted, as a reader gets it. */
  private boolean honorActualText;

  /**
   * Whether the reader text ({@code /ActualText} substituted) is the same in {@code copy} and {@code baseline} (the original
   * with descriptions kept and tags discarded) once the glyphs that had no Unicode value in {@code original} are left out
   * on both sides, also inside {@code /ActualText} sequences. Glyph keys agree in all three because every rewrite is
   * marker-only or font-only; the fills are judged by {@link #fillOnlyFailure}.
   */
  public static boolean sameReplacementText(PDDocument original, PDDocument baseline, PDDocument copy) throws IOException {
    Set<Long> mask = Set.copyOf(Pass.run(original, null).mask());
    return replacementDigest(baseline, mask).equals(replacementDigest(copy, mask));
  }

  private static String replacementDigest(PDDocument document, Set<Long> mask) throws IOException {
    GlyphAudit audit = new GlyphAudit(null, false, null, mask, null);
    audit.honorActualText = true;
    return PdfPreflight.normalizedTextDigest(audit.getText(document));
  }

  @Override
  protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
    int ordinal = nextOrdinal++;
    long key = key(getCurrentPageNo(), ordinal);
    if (sink != null) {
      PDTextState state = getGraphicsState().getTextState();
      float advance = displacement.getX() * state.getFontSize() * state.getHorizontalScaling() / 100f;
      sink.add(new Glyph(getCurrentPageNo(), ordinal, font.getName(), code, aglUnicode ? font.toUnicode(code) : null,
          round(textRenderingMatrix.getTranslateX()), round(textRenderingMatrix.getTranslateY()), round(advance)));
    }
    boolean drop;
    if (texts != null || ownMask != null) {
      String text = font.toUnicode(code, EXTRACTION_GLYPHS);
      if (texts != null) {
        texts.add(text);
      }
      drop = ownMask != null ? !mappedText(text) : fixedMask != null && fixedMask.contains(key);
      if (drop && ownMask != null) {
        ownMask.add(key);
      }
    } else {
      drop = fixedMask != null && fixedMask.contains(key);
    }
    // super.showGlyph calls processTextPosition synchronously (at most once) for this glyph.
    dropping = drop;
    try {
      super.showGlyph(textRenderingMatrix, font, code, displacement);
    } finally {
      dropping = false;
    }
  }

  @Override
  protected void processTextPosition(TextPosition text) {
    if (dropping) {
      return;
    }
    super.processTextPosition(text);
  }

  private static boolean differs(float before, float after) {
    return Math.abs(before - after) > POSITION_TOLERANCE + FLOAT_SLACK;
  }

  private static long key(int page, int ordinal) {
    return ((long) page << 32) | ordinal;
  }

  private static String describe(Glyph glyph) {
    return "glyph " + (glyph.ordinal() + 1) + " on page " + glyph.page() + " (" + glyph.fontName() + ", code " + glyph.code() + ")";
  }

  private static float round(float value) {
    return (float) (Math.round(value * 100.0) / 100.0);
  }

  /** Java string literal of {@code value} (quotes, backslash escapes, non-ASCII as {@code \}u{@code XXXX}); {@code null} unquoted. */
  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20 || c > 0x7E) {
            b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
