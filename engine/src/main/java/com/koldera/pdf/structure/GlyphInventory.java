package com.koldera.pdf.structure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Independent fingerprint of the glyphs a document's page content shows, used by the structure gate to tell a validator that
 * re-counts font checks per marked-content sequence from a real font or glyph change. In content order it hashes every
 * {@code Tf} with its resolved font tree and every text-showing operator with its operands, walking forms. Not walked: marked
 * content, structure, appearances, Type 3, patterns and the keys in {@link #NON_PAINTING_FONT_KEYS} (none paints a glyph).
 */
public final class GlyphInventory {

  /**
   * Font-tree keys left out of the hash in every dictionary of a font tree: none of them selects or
   * paints a glyph ({@code ToUnicode} only maps codes to text; {@code CIDSet}/{@code CharSet} only
   * list which glyphs a subset holds), so the font rules may write or remove them.
   */
  static final Set<String> NON_PAINTING_FONT_KEYS = Set.of("ToUnicode", "CIDSet", "CharSet");

  private static final Set<String> TEXT_SHOWING = Set.of("Tj", "TJ", "'", "\"");
  private static final int MAX_FORM_DEPTH = 16;
  private static final int MAX_FONT_DEPTH = 12;

  private final MessageDigest md;
  private final Map<COSDictionary, String> fontHashes = new IdentityHashMap<>();

  private GlyphInventory(MessageDigest md) {
    this.md = md;
  }

  public static String digest(PDDocument document) throws IOException {
    GlyphInventory inventory = new GlyphInventory(sha256());
    int index = 0;
    for (PDPage page : document.getPages()) {
      inventory.update("page " + index++ + "\n");
      if (!page.hasContents()) {
        continue;
      }
      inventory.walk(new PDFStreamParser(page).parse(), page.getResources(), new HashSet<>(), 0);
    }
    return HexFormat.of().formatHex(inventory.md.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void update(String text) {
    md.update(text.getBytes(StandardCharsets.UTF_8));
  }

  private void walk(List<Object> tokens, PDResources resources, Set<COSStream> path, int depth) throws IOException {
    List<COSBase> operands = new ArrayList<>();
    for (Object token : tokens) {
      if (!(token instanceof Operator op)) {
        operands.add((COSBase) token);
        continue;
      }
      String name = op.getName();
      if ("Tf".equals(name) && operands.size() == 2 && operands.get(0) instanceof COSName font) {
        update("Tf " + font.getName() + " " + fontHash(resources, font) + " " + text(operands.get(1)) + "\n");
      } else if (TEXT_SHOWING.contains(name)) {
        StringBuilder line = new StringBuilder(name);
        for (COSBase operand : operands) {
          line.append(' ').append(text(operand));
        }
        update(line.append('\n').toString());
      } else if ("Do".equals(name) && operands.size() == 1 && operands.get(0) instanceof COSName xName && resources != null && depth < MAX_FORM_DEPTH) {
        PDXObject xObject;
        try {
          xObject = resources.getXObject(xName);
        } catch (IOException | RuntimeException e) {
          xObject = null;
        }
        if (xObject instanceof PDFormXObject form && !path.contains(form.getCOSObject())) {
          update("Do " + xName.getName() + "\n");
          path.add(form.getCOSObject());
          PDResources own = form.getResources();
          walk(new PDFStreamParser(form).parse(), own != null ? own : resources, path, depth + 1);
          path.remove(form.getCOSObject());
        }
      }
      operands.clear();
    }
  }

  /** SHA-256 of the resolved font dictionary tree, computed once per font dictionary. */
  private String fontHash(PDResources resources, COSName name) {
    if (resources == null) {
      return "?";
    }
    COSDictionary fonts = resources.getCOSObject().getCOSDictionary(COSName.FONT);
    COSBase entry = fonts == null ? null : fonts.getDictionaryObject(name);
    if (!(entry instanceof COSDictionary font)) {
      return "?";
    }
    String known = fontHashes.get(font);
    if (known != null) {
      return known;
    }
    MessageDigest fontDigest = sha256();
    hashTree(font, fontDigest, new HashSet<>(), 0);
    String hash = HexFormat.of().formatHex(fontDigest.digest());
    fontHashes.put(font, hash);
    return hash;
  }

  /** Deterministic serialisation of a COS tree: sorted dictionary keys, streams by length + SHA-256. */
  private static void hashTree(COSBase base, MessageDigest digest, Set<COSBase> visiting, int depth) {
    COSBase value = base instanceof COSObject ref ? ref.getObject() : base;
    if (value == null) {
      digest.update("null".getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (depth > MAX_FONT_DEPTH || visiting.contains(value)) {
      digest.update("(cut)".getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (value instanceof COSDictionary dict) {
      visiting.add(value);
      TreeMap<String, COSBase> sorted = new TreeMap<>();
      for (Map.Entry<COSName, COSBase> e : dict.entrySet()) {
        if (!NON_PAINTING_FONT_KEYS.contains(e.getKey().getName())) {
          sorted.put(e.getKey().getName(), e.getValue());
        }
      }
      if (COSName.CID_FONT_TYPE2.equals(dict.getCOSName(COSName.SUBTYPE))) {
        // A missing CIDToGIDMap means /Identity (ISO 32000-1 Table 117); writing it explicitly paints the same glyphs.
        sorted.putIfAbsent(COSName.CID_TO_GID_MAP.getName(), COSName.IDENTITY);
      }
      digest.update("<<".getBytes(StandardCharsets.UTF_8));
      for (Map.Entry<String, COSBase> e : sorted.entrySet()) {
        digest.update(("/" + e.getKey() + " ").getBytes(StandardCharsets.UTF_8));
        hashTree(e.getValue(), digest, visiting, depth + 1);
      }
      digest.update(">>".getBytes(StandardCharsets.UTF_8));
      if (value instanceof COSStream stream) {
        digest.update(("stream:" + streamDigest(stream)).getBytes(StandardCharsets.UTF_8));
      }
      visiting.remove(value);
      return;
    }
    if (value instanceof COSArray array) {
      visiting.add(value);
      digest.update("[".getBytes(StandardCharsets.UTF_8));
      for (COSBase item : array) {
        hashTree(item, digest, visiting, depth + 1);
        digest.update(" ".getBytes(StandardCharsets.UTF_8));
      }
      digest.update("]".getBytes(StandardCharsets.UTF_8));
      visiting.remove(value);
      return;
    }
    digest.update(text(value).getBytes(StandardCharsets.UTF_8));
  }

  /** Raw (still encoded) bytes: length and SHA-256; an unreadable stream hashes as such. */
  private static String streamDigest(COSStream stream) {
    try (InputStream in = stream.createRawInputStream()) {
      MessageDigest md = sha256();
      byte[] buffer = new byte[8192];
      long length = 0;
      int read;
      while ((read = in.read(buffer)) > 0) {
        md.update(buffer, 0, read);
        length += read;
      }
      return length + ":" + HexFormat.of().formatHex(md.digest());
    } catch (IOException | RuntimeException e) {
      return "unreadable";
    }
  }

  /** Stable text for a scalar operand: strings as hex bytes, numbers as floats, arrays element-wise. */
  private static String text(COSBase operand) {
    if (operand instanceof COSString s) {
      return "<" + HexFormat.of().formatHex(s.getBytes()) + ">";
    }
    if (operand instanceof COSNumber n) {
      return Float.toString(n.floatValue());
    }
    if (operand instanceof COSName n) {
      return "/" + n.getName();
    }
    if (operand instanceof COSBoolean b) {
      return Boolean.toString(b.getValue());
    }
    if (operand instanceof COSArray array) {
      StringBuilder b = new StringBuilder("[");
      for (COSBase item : array) {
        b.append(text(item instanceof COSObject ref ? ref.getObject() : item)).append(' ');
      }
      return b.append(']').toString();
    }
    if (operand instanceof COSDictionary dict) {
      return "<<" + dict.getNameAsString(COSName.TYPE) + "/" + dict.getNameAsString(COSName.BASE_ENCODING) + ">>";
    }
    return operand == null ? "null" : operand.getClass().getSimpleName();
  }
}
