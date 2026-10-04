package com.koldera.pdf.repair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** One-page tagged PDFs written from a raw content stream, and a readable form of a stream's tokens. */
final class ContentFixtures {

  private ContentFixtures() {}

  /**
   * One A4 page with {@code content} (Helvetica as /F1), a tree Document &gt; P whose /K lists
   * {@code mcids} (an integer when one, an array otherwise) and a ParentTree array for the page with
   * the P at those slots and null at the others below the largest.
   */
  static byte[] page(String content, int... mcids) throws IOException {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
      page.setResources(resources);
      PDStream stream = new PDStream(doc);
      try (OutputStream os = stream.createOutputStream()) {
        os.write(content.getBytes(StandardCharsets.ISO_8859_1));
      }
      page.setContents(stream);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      PDStructureElement p = new PDStructureElement("P", document);
      p.setPage(page);
      document.appendKid(p);
      COSArray k = new COSArray();
      int max = -1;
      for (int mcid : mcids) {
        k.add(COSInteger.get(mcid));
        max = Math.max(max, mcid);
      }
      p.getCOSObject().setItem(COSName.K, mcids.length == 1 ? k.get(0) : k);
      COSArray parents = new COSArray();
      for (int i = 0; i <= max; i++) {
        parents.add(COSNull.NULL);
      }
      for (int mcid : mcids) {
        parents.set(mcid, p.getCOSObject());
      }
      COSArray nums = new COSArray();
      nums.add(COSInteger.ZERO);
      nums.add(parents);
      COSDictionary parentTree = new COSDictionary();
      parentTree.setItem(COSName.NUMS, nums);
      root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
      root.setParentTreeNextKey(1);
      page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDMarkInfo markInfo = new PDMarkInfo();
      markInfo.setMarked(true);
      doc.getDocumentCatalog().setMarkInfo(markInfo);
      doc.getDocumentCatalog().setLanguage("en");
      doc.getDocumentInformation().setTitle("Nesting fixture");
      doc.setDocumentId(70L);
      doc.save(out);
      return out.toByteArray();
    }
  }

  /** The page's content stream tokens, space-separated: names as /N, numbers, strings in (), dictionaries as {@code <</K v>>}. */
  static String tokens(PDPage page) throws IOException {
    List<String> out = new ArrayList<>();
    for (Object token : new PDFStreamParser(page).parse()) {
      out.add(token instanceof Operator op ? op.getName() : write((COSBase) token));
    }
    return String.join(" ", out);
  }

  static String normalize(String content) {
    return content.trim().replaceAll("\\s+", " ");
  }

  private static String write(COSBase base) {
    if (base instanceof COSObject ref) {
      return write(ref.getObject());
    }
    if (base instanceof COSName name) {
      return "/" + name.getName();
    }
    if (base instanceof COSInteger i) {
      return Long.toString(i.longValue());
    }
    if (base instanceof COSFloat f) {
      float v = f.floatValue();
      return v == Math.rint(v) ? Long.toString((long) v) : Float.toString(v);
    }
    if (base instanceof COSString s) {
      return "(" + s.getString() + ")";
    }
    if (base instanceof COSDictionary dict) {
      StringBuilder b = new StringBuilder("<<");
      boolean first = true;
      for (var e : dict.entrySet()) {
        b.append(first ? "" : " ").append('/').append(e.getKey().getName()).append(' ').append(write(e.getValue()));
        first = false;
      }
      return b.append(">>").toString();
    }
    return String.valueOf(base);
  }

  /** The ParentTree array of page 1 as element types ({@code null} for a slot without an element). */
  static List<String> parentTreeTypes(PDDocument doc) {
    COSDictionary tree = (COSDictionary) doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.PARENT_TREE);
    COSArray nums = (COSArray) tree.getDictionaryObject(COSName.NUMS);
    COSArray slots = (COSArray) nums.getObject(1);
    List<String> out = new ArrayList<>();
    for (int i = 0; i < slots.size(); i++) {
      out.add(slots.getObject(i) instanceof COSDictionary element ? element.getNameAsString(COSName.S) : null);
    }
    return out;
  }

  /** The P element of a {@link #page} fixture. */
  static PDStructureElement paragraph(PDDocument doc) {
    PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
    return (PDStructureElement) document.getKids().stream().filter(k -> k instanceof PDStructureElement).findFirst().orElseThrow();
  }

  /** The P element's /K as a readable string: {@code 0} or {@code [0 1]}. */
  static String paragraphKids(PDDocument doc) {
    COSBase k = paragraph(doc).getCOSObject().getDictionaryObject(COSName.K);
    if (k instanceof COSArray array) {
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < array.size(); i++) {
        parts.add(write(array.getObject(i)));
      }
      return "[" + String.join(" ", parts) + "]";
    }
    return write(k);
  }
}
