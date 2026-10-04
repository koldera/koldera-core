package com.koldera.pdf.fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.HeaderTable;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

/**
 * Embeds a subset of a bundled metric-compatible Liberation font ({@link FontLibrary}) into a non-embedded simple font
 * dictionary in place (uploads). Content streams are not touched: every used code keeps
 * its glyph name (so its text) and its width in the dictionary (so every glyph position); only the glyph shapes come from the
 * substitute.
 */
public final class SubstituteEmbedder {

  /** Tables written into the subset; the glyph-id dependent layout tables (GSUB, GPOS, kern, ...) are left out. */
  private static final List<String> TABLES =
      List.of("head", "hhea", "maxp", "hmtx", "loca", "glyf", "cmap", "post", "name", "OS/2", "cvt ", "fpgm", "prep", "gasp");
  private static final Set<String> KEPT_ENCODINGS = Set.of("WinAnsiEncoding", "MacRomanEncoding");
  private static final COSName CID_SET = COSName.getPDFName("CIDSet");
  private static final COSName CHAR_SET = COSName.getPDFName("CharSet");
  private static final Map<String, String> AGL = loadAgl();
  private static final int FIRST = 32;
  private static final int LAST = 255;

  private SubstituteEmbedder() {}

  /**
   * Six letters {@code A}–{@code Z} from the first six bytes of SHA-256({@code programBytes} ‖ the code points as a UTF-8
   * comma-separated decimal list, ascending), each {@code 'A' + (b & 0xFF) % 26}.
   */
  public static String subsetTag(byte[] programBytes, SortedSet<Integer> codePoints) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      sha.update(programBytes);
      String list = codePoints.stream().map(String::valueOf).collect(Collectors.joining(","));
      byte[] digest = sha.digest(list.getBytes(StandardCharsets.UTF_8));
      StringBuilder tag = new StringBuilder(6);
      for (int i = 0; i < 6; i++) {
        tag.append((char) ('A' + (digest[i] & 0xFF) % 26));
      }
      return tag.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code "Liberation Sans Regular"}, {@code "Liberation Serif Bold Italic"}: the program's family and subfamily names. */
  public static String displayName(TrueTypeFont substitute) throws IOException {
    return substitute.getNaming().getFontFamily() + " " + substitute.getNaming().getFontSubFamily();
  }

  /**
   * The first reason {@code font} cannot be embedded with {@code substitute}, or empty: a symbolic font; a used code below 32;
   * a used code whose glyph name is not a plain AGL name that the substitute's (3,1) cmap maps to a non-zero glyph; or a used
   * code whose width differs from the substitute's advance by more than 1/1000 em.
   */
  public static Optional<String> blocker(PDSimpleFont font, SortedSet<Integer> usedCodes, TrueTypeFont substitute) throws IOException {
    PDFontDescriptor descriptor = font.getFontDescriptor();
    if (descriptor != null && descriptor.isSymbolic()) {
      return Optional.of("symbolic font");
    }
    String name = displayName(substitute);
    if (styleDiffers(descriptor, substitute)) {
      return Optional.of("its style flags differ from " + name);
    }
    for (int code : usedCodes) {
      if (code < FIRST) {
        return Optional.of("code " + code + " below 32");
      }
    }
    if (!(font.getCOSObject().getDictionaryObject(COSName.WIDTHS) instanceof COSArray) && !font.isStandard14()) {
      // PDFBox would take the widths from whatever font the host substitutes: nothing host-independent to compare.
      return Optional.of("no widths in the font dictionary");
    }
    Encoding encoding = font.getEncoding();
    CmapSubtable cmap = substitute.getCmap() == null ? null
        : substitute.getCmap().getSubtable(CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_BMP);
    float scale = 1000f / substitute.getUnitsPerEm();
    for (int code : usedCodes) {
      String glyph = encoding == null ? null : encoding.getName(code);
      Integer codePoint = codePoint(glyph);
      int gid = codePoint == null || cmap == null ? 0 : cmap.getGlyphId(codePoint);
      if (gid == 0) {
        return Optional.of("no glyph for code " + code + " (" + glyph + ") in " + name);
      }
      float delta = Math.abs(font.getWidth(code) - substitute.getAdvanceWidth(gid) * scale);
      if (delta > 1.0f) {
        return Optional.of("width of code " + code + " differs by " + String.format(Locale.ROOT, "%.1f", delta) + " units from " + name);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether the descriptor says another style than the substitute has (italic flag or angle, {@code ForceBold} or
   * {@code /FontWeight} at or above 600 versus the substitute's bold bit). Without a descriptor nothing is detectable.
   */
  private static boolean styleDiffers(PDFontDescriptor descriptor, TrueTypeFont substitute) throws IOException {
    if (descriptor == null) {
      return false;
    }
    int macStyle = substitute.getHeader().getMacStyle();
    boolean subBold = (macStyle & 1) != 0;
    boolean subItalic = (macStyle & 2) != 0;
    boolean explicitUpright = descriptor.getCOSObject().getDictionaryObject(COSName.ITALIC_ANGLE) != null && descriptor.getItalicAngle() == 0 && !descriptor.isItalic();
    boolean italic = descriptor.isItalic() || descriptor.getItalicAngle() != 0;
    if (italic != subItalic && (italic || explicitUpright)) {
      return true;
    }
    boolean hasWeight = descriptor.getCOSObject().getDictionaryObject(COSName.FONT_WEIGHT) != null;
    boolean bold = descriptor.isForceBold() || (hasWeight && descriptor.getFontWeight() >= 600);
    boolean explicitlyNotBold = hasWeight && descriptor.getFontWeight() < 600 && !descriptor.isForceBold();
    return (bold && !subBold) || (subBold && explicitlyNotBold);
  }

  /**
   * Embeds the subset of {@code substitute} for the Unicode values of {@code usedCodes} into {@code font}'s dictionary (see
   * {@link FontEmbeddingRule} for the exact keys). The caller has checked {@link #blocker}; the caller resets the document's
   * resource cache.
   */
  public static void embed(PDDocument doc, PDSimpleFont font, SortedSet<Integer> usedCodes, FontLibrary.Substitute substitute) throws IOException {
    COSDictionary dict = font.getCOSObject();
    Encoding encoding = font.getEncoding();
    TreeMap<Integer, String> names = new TreeMap<>();
    TreeSet<Integer> codePoints = new TreeSet<>();
    for (int code : usedCodes) {
      String glyph = encoding.getName(code);
      Integer cp = codePoint(glyph);
      if (cp == null) {
        throw new IllegalStateException("no code point for code " + code);
      }
      names.put(code, glyph);
      codePoints.add(cp);
    }
    // Read before anything changes: the widths PDFBox applies now (dictionary, else the standard 14 AFM).
    boolean writeWidths = !(font instanceof PDTrueTypeFont) || !(dict.getDictionaryObject(COSName.WIDTHS) instanceof COSArray);
    COSArray widths = new COSArray();
    if (writeWidths) {
      for (int code = FIRST; code <= LAST; code++) {
        widths.add(number(font.getWidth(code)));
      }
    }
    PDFontDescriptor oldDescriptor = font.getFontDescriptor();
    COSBase newEncoding = encoding(dict.getDictionaryObject(COSName.ENCODING), names);

    byte[] programBytes = programBytes(substitute);
    byte[] subset;
    String postScriptName;
    COSDictionary descriptor = new COSDictionary();
    if (oldDescriptor != null) {
      for (Map.Entry<COSName, COSBase> e : oldDescriptor.getCOSObject().entrySet()) {
        descriptor.setItem(e.getKey(), e.getValue());
      }
    }
    try (TrueTypeFont ttf = substitute.load(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      String tag = subsetTag(programBytes, codePoints);
      TTFSubsetter subsetter = new TTFSubsetter(ttf, TABLES);
      subsetter.setPrefix(tag + "+");
      for (int cp : codePoints) {
        subsetter.add(cp);
      }
      subsetter.writeToStream(out);
      subset = out.toByteArray();
      postScriptName = tag + "+" + ttf.getName();
      float scale = 1000f / ttf.getUnitsPerEm();
      HeaderTable head = ttf.getHeader();
      HorizontalHeaderTable hhea = ttf.getHorizontalHeader();
      OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
      int flags = 32;
      if (FontLibrary.SERIF.equals(substitute.family())) {
        flags |= 2;
      }
      if (substitute.style() == FontLibrary.Style.ITALIC || substitute.style() == FontLibrary.Style.BOLD_ITALIC) {
        flags |= 64;
      }
      float ascent = hhea.getAscender() * scale;
      descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
      descriptor.setName(COSName.FONT_NAME, postScriptName);
      descriptor.setInt(COSName.FLAGS, flags);
      COSArray bbox = new COSArray();
      bbox.add(number(head.getXMin() * scale));
      bbox.add(number(head.getYMin() * scale));
      bbox.add(number(head.getXMax() * scale));
      bbox.add(number(head.getYMax() * scale));
      descriptor.setItem(COSName.FONT_BBOX, bbox);
      descriptor.setItem(COSName.ITALIC_ANGLE, number(ttf.getPostScript().getItalicAngle()));
      descriptor.setItem(COSName.ASCENT, number(ascent));
      descriptor.setItem(COSName.DESCENT, number(hhea.getDescender() * scale));
      descriptor.setItem(COSName.CAP_HEIGHT, os2 != null && os2.getVersion() >= 2 ? number(os2.getCapHeight() * scale) : number(ascent));
      int weight = os2 == null ? 400 : os2.getWeightClass();
      descriptor.setInt(COSName.STEM_V, Math.round(50 + (weight / 65f) * (weight / 65f)));
    }
    descriptor.removeItem(CID_SET);
    descriptor.removeItem(CHAR_SET);
    descriptor.removeItem(COSName.FONT_FILE);
    descriptor.removeItem(COSName.FONT_FILE3);
    COSStream fontFile = doc.getDocument().createCOSStream();
    try (OutputStream out = fontFile.createOutputStream(COSName.FLATE_DECODE)) {
      out.write(subset);
    }
    fontFile.setInt(COSName.LENGTH1, subset.length);
    descriptor.setItem(COSName.FONT_FILE2, fontFile);

    dict.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
    dict.setName(COSName.BASE_FONT, postScriptName);
    if (writeWidths) {
      dict.setInt(COSName.FIRST_CHAR, FIRST);
      dict.setInt(COSName.LAST_CHAR, LAST);
      dict.setItem(COSName.WIDTHS, widths);
    }
    dict.setItem(COSName.ENCODING, newEncoding);
    dict.setItem(COSName.FONT_DESC, descriptor);
  }

  /**
   * The encoding to write: the existing one when it is {@code /WinAnsiEncoding} or {@code /MacRomanEncoding}, or a dictionary
   * with such a {@code /BaseEncoding} whose {@code Differences} names are all AGL names; otherwise {@code /WinAnsiEncoding} with
   * one {@code Differences} entry per used code whose name differs from WinAnsi's at that code (the name alone when none does).
   */
  private static COSBase encoding(COSBase existing, TreeMap<Integer, String> names) {
    if (existing instanceof COSName name && KEPT_ENCODINGS.contains(name.getName())) {
      return existing;
    }
    if (existing instanceof COSDictionary dict && dict.getDictionaryObject(COSName.BASE_ENCODING) instanceof COSName base
        && KEPT_ENCODINGS.contains(base.getName()) && differencesAreAgl(dict)) {
      return existing;
    }
    COSArray differences = new COSArray();
    for (Map.Entry<Integer, String> e : names.entrySet()) {
      if (!e.getValue().equals(WinAnsiEncoding.INSTANCE.getName(e.getKey()))) {
        differences.add(COSInteger.get(e.getKey()));
        differences.add(COSName.getPDFName(e.getValue()));
      }
    }
    if (differences.size() == 0) {
      return COSName.WIN_ANSI_ENCODING;
    }
    COSDictionary written = new COSDictionary();
    written.setItem(COSName.TYPE, COSName.ENCODING);
    written.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
    written.setItem(COSName.DIFFERENCES, differences);
    return written;
  }

  private static boolean differencesAreAgl(COSDictionary encoding) {
    if (!(encoding.getDictionaryObject(COSName.DIFFERENCES) instanceof COSArray differences)) {
      return true;
    }
    for (int i = 0; i < differences.size(); i++) {
      if (differences.getObject(i) instanceof COSName name && codePoint(name.getName()) == null) {
        return false;
      }
    }
    return true;
  }

  /** The one code point of an Adobe Glyph List name without {@code .} or {@code _}; null for anything else. */
  static Integer codePoint(String glyphName) {
    if (glyphName == null || glyphName.indexOf('.') >= 0 || glyphName.indexOf('_') >= 0) {
      return null;
    }
    String unicode = AGL.get(glyphName);
    if (unicode == null || unicode.codePointCount(0, unicode.length()) != 1) {
      return null;
    }
    return unicode.codePointAt(0);
  }

  private static COSBase number(float value) {
    float rounded = Math.round(value * 1000f) / 1000f;
    return rounded == Math.rint(rounded) ? COSInteger.get((long) rounded) : new COSFloat(rounded);
  }

  private static byte[] programBytes(FontLibrary.Substitute substitute) throws IOException {
    try (InputStream in = FontLibrary.class.getResourceAsStream(substitute.resource())) {
      if (in == null) {
        throw new IOException("bundled font missing from the classpath: " + substitute.resource());
      }
      return in.readAllBytes();
    }
  }

  /** The Adobe Glyph List as PDFBox ships it ({@code glyphlist.txt}): exactly its names, no algorithmic {@code uniXXXX} forms. */
  private static Map<String, String> loadAgl() {
    try (InputStream in = org.apache.pdfbox.pdmodel.font.encoding.GlyphList.class.getResourceAsStream("/org/apache/pdfbox/resources/glyphlist/glyphlist.txt")) {
      if (in == null) {
        throw new IOException("glyphlist.txt missing from the classpath");
      }
      Map<String, String> map = new java.util.HashMap<>();
      Set<String> seen = new HashSet<>();
      for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int semi = line.indexOf(';');
        String name = line.substring(0, semi);
        StringBuilder value = new StringBuilder();
        for (String hex : line.substring(semi + 1).trim().split(" ")) {
          value.appendCodePoint(Integer.parseInt(hex, 16));
        }
        if (seen.add(name)) {
          map.put(name, value.toString());
        }
      }
      return Collections.unmodifiableMap(map);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
