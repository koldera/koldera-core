package com.koldera.pdf.structure;

import com.koldera.pdf.cli.EngineVersion;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.structure.StructureNode.Box;
import com.koldera.pdf.structure.StructureNode.PrivateMapping;
import com.koldera.pdf.structure.StructureResult.ErrorCode;
import com.koldera.pdf.structure.StructureResult.Finding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Builds the structure snapshot: a cycle-safe pre-order walk keeping the stored sibling order (never re-sorted by geometry),
 * resolving marked-content references to real page content, recording the per-page invariants the order-repair gate compares,
 * and mapping annotations back to structure elements through the ParentTree. Read-only.
 */
public final class StructureExtractor {

  private static final int HEADER_SEARCH_WINDOW = 1024;
  private static final int MAX_DEPTH = 64;
  private static final int MAX_NODES = 20000;
  private static final int PREVIEW_LENGTH = 160;
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  public StructureResult extract(Path input, long maxBytes) {
    String engine = EngineVersion.describe();
    long size;
    try {
      size = Files.size(input);
    } catch (NoSuchFileException e) {
      return StructureResult.failed(null, 0, engine, ErrorCode.INVALID_INPUT, "input document is missing");
    } catch (IOException e) {
      return StructureResult.failed(null, 0, engine, ErrorCode.INVALID_INPUT, "input document is unreadable");
    }
    if (size <= 0) {
      return StructureResult.failed(null, size, engine, ErrorCode.INVALID_INPUT, "input document is empty");
    }
    if (size > maxBytes) {
      return StructureResult.failed(null, size, engine, ErrorCode.LIMIT_EXCEEDED, "input document exceeds maxBytes=" + maxBytes);
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(input);
    } catch (IOException e) {
      return StructureResult.failed(null, size, engine, ErrorCode.INVALID_INPUT, "input document is unreadable");
    }
    String sha256 = PdfPreflight.sha256Hex(bytes);
    if (!hasPdfHeader(bytes)) {
      return StructureResult.failed(sha256, size, engine, ErrorCode.INVALID_INPUT, "input does not start with a %PDF- header");
    }
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) {
        return StructureResult.failed(sha256, size, engine, ErrorCode.UNSUPPORTED_ENCRYPTED, "encrypted documents are not inspected");
      }
      return extract(document, sha256, size, engine);
    } catch (InvalidPasswordException e) {
      return StructureResult.failed(sha256, size, engine, ErrorCode.UNSUPPORTED_ENCRYPTED, "document requires a password; encrypted documents are not inspected");
    } catch (IOException | RuntimeException e) {
      return StructureResult.failed(sha256, size, engine, ErrorCode.INVALID_INPUT, "document could not be parsed (" + e.getClass().getSimpleName() + ")");
    }
  }

  /** Snapshot of an already loaded document (used by the reorder gate before and after the change). */
  public StructureResult extract(PDDocument document, String sha256, long size, String engine) throws IOException {
    PDDocumentCatalog catalog = document.getDocumentCatalog();
    COSDictionary catalogDict = catalog.getCOSObject();
    boolean marked = false;
    if (catalogDict.getDictionaryObject(COSName.MARK_INFO) instanceof COSDictionary mi) {
      marked = mi.getBoolean("Marked", false);
    }
    COSBase rootBase = catalogDict.getDictionaryObject(COSName.STRUCT_TREE_ROOT);
    COSDictionary rootDict = rootBase instanceof COSDictionary d ? d : null;

    // Page content: MCID -> text/box, page tokens, raw content digests, annotations.
    List<PageContent> pages = new ArrayList<>();
    Map<COSDictionary, Integer> pageIndex = new IdentityHashMap<>();
    int annotationTotal = 0;
    int number = 0;
    for (PDPage page : document.getPages()) {
      number++;
      pageIndex.put(page.getCOSObject(), number);
      PageContent content = PageContent.read(document, page, number);
      annotationTotal += content.annotationCount;
      pages.add(content);
    }

    Walk walk = new Walk(sha256, pageIndex, pages);
    List<Finding> findings = new ArrayList<>();
    if (rootDict != null) {
      walk.kids(rootDict.getDictionaryObject(COSName.K), null, null, new ArrayList<>(), 0);
    }
    boolean usable = marked && rootDict != null && walk.elements > 0 && walk.contentRefs > 0;
    if (rootDict == null) {
      findings.add(new Finding("PDF_STRUCTURE_TREE_MISSING", "the document has no structure tree; reading order cannot be derived from tags and must be reviewed on the rendered pages", List.of()));
    } else if (!usable) {
      findings.add(new Finding("PDF_STRUCTURE_TAGS_UNUSABLE", "the structure tree references no page content; it carries no reading order", List.of()));
    }
    if (walk.truncated) {
      findings.add(new Finding("PDF_STRUCTURE_TREE_TRUNCATED", "the structure tree is larger than the snapshot limit; only the first " + MAX_NODES + " elements are listed", List.of()));
    }

    // Focus sequence: annotations in page order and /Annots order (or structure order when /Tabs /S), mapped through the ParentTree.
    List<String> focus = new ArrayList<>();
    boolean measured = annotationTotal > 0;
    Map<Integer, COSBase> parentTree = rootDict == null ? Map.of() : NumberTreeView.read(rootDict.getDictionaryObject(COSName.PARENT_TREE));
    int unmapped = 0;
    number = 0;
    for (PDPage page : document.getPages()) {
      number++;
      List<StructureNode> mapped = new ArrayList<>();
      List<Integer> unmappedOnPage = new ArrayList<>();
      for (PDAnnotation annotation : page.getAnnotations()) {
        COSDictionary dict = annotation.getCOSObject();
        int structParent = dict.getInt(COSName.STRUCT_PARENT, -1);
        StructureNode node = structParent < 0 ? null : walk.nodeFor(parentTree.get(structParent));
        if (node == null) {
          unmapped++;
          unmappedOnPage.add(structParent);
        } else {
          mapped.add(node);
        }
      }
      if (COSName.getPDFName("S").equals(page.getCOSObject().getCOSName(COSName.getPDFName("Tabs")))) {
        mapped.sort((a, b) -> Integer.compare(a.logicalIndex(), b.logicalIndex()));
      }
      for (StructureNode node : mapped) {
        focus.add(node.nodeId());
      }
    }
    if (unmapped > 0) {
      findings.add(new Finding("PDF_FOCUS_TARGET_UNTAGGED", unmapped + " annotation(s) are not referenced from the structure tree; their place in the reading order is unknown and the focus sequence is incomplete", List.of()));
    }

    List<PageDigest> digests = new ArrayList<>();
    for (PageContent content : pages) {
      digests.add(new PageDigest(content.number, content.contentStreamSha256, content.tokenHash, content.annotationCount, content.mcidCount));
    }
    StructureResult.Document doc = new StructureResult.Document(
        document.getNumberOfPages(),
        usable,
        isSignedOrCertified(document),
        false,
        catalog.getAcroForm() != null && !catalog.getAcroForm().getFields().isEmpty(),
        annotationTotal,
        digests);
    return new StructureResult(StructureResult.SPEC_VERSION, StructureResult.Status.INSPECTED, sha256, size, engine, doc, walk.nodes, focus, measured, findings, null);
  }

  /** Order-independent digest of extracted text tokens (NFC, whitespace-split, sorted). */
  public static String tokenMultisetHash(List<String> tokens) {
    List<String> sorted = new ArrayList<>();
    for (String token : tokens) {
      sorted.add(Normalizer.normalize(token, Normalizer.Form.NFC));
    }
    Collections.sort(sorted);
    return PdfPreflight.sha256Hex(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8));
  }

  // ---- per page content --------------------------------------------------------------------------

  private static final class PageContent {
    final int number;
    final Map<Integer, StringBuilder> textByMcid = new HashMap<>();
    final Map<Integer, double[]> boxByMcid = new HashMap<>();
    final int mcidCount;
    final int annotationCount;
    final String contentStreamSha256;
    final String tokenHash;

    private PageContent(int number, int mcidCount, int annotationCount, String contentStreamSha256, String tokenHash) {
      this.number = number;
      this.mcidCount = mcidCount;
      this.annotationCount = annotationCount;
      this.contentStreamSha256 = contentStreamSha256;
      this.tokenHash = tokenHash;
    }

    static PageContent read(PDDocument document, PDPage page, int number) throws IOException {
      // Raw content bytes: byte identity is the invariant, not a re-encoded view.
      MessageDigest raw = sha256();
      for (java.util.Iterator<PDStream> it = page.getContentStreams(); it.hasNext(); ) {
        PDStream stream = it.next();
        COSStream cos = stream.getCOSObject();
        try (InputStream in = cos.createRawInputStream()) {
          raw.update(in.readAllBytes());
        }
      }
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(number);
      stripper.setEndPage(number);
      String text = stripper.getText(document);
      List<String> tokens = new ArrayList<>();
      for (String token : text.trim().split("\\s+")) {
        if (!token.isEmpty()) {
          tokens.add(token);
        }
      }
      PDFMarkedContentExtractor extractor = new PDFMarkedContentExtractor();
      extractor.processPage(page);
      Set<Integer> mcids = new TreeSet<>();
      PageContent content = new PageContent(number, 0, page.getAnnotations().size(), hex(raw.digest()), tokenMultisetHash(tokens));
      for (PDMarkedContent mc : extractor.getMarkedContents()) {
        content.collect(mc, -1, mcids);
      }
      return new PageContent(number, mcids.size(), content.annotationCount, content.contentStreamSha256, content.tokenHash).copyFrom(content);
    }

    private PageContent copyFrom(PageContent other) {
      textByMcid.putAll(other.textByMcid);
      boxByMcid.putAll(other.boxByMcid);
      return this;
    }

    /**
     * Binds a marked-content sequence to the MCID it belongs to. A nested sequence without an MCID of
     * its own (Chromium's per-glyph {@code /Span <</ActualText>>}, a {@code /ReversedChars} run)
     * inherits the enclosing one; a nested sequence with a different MCID is content of that element.
     */
    private void collect(PDMarkedContent mc, int inherited, Set<Integer> mcids) {
      int mcid = mc.getMCID() >= 0 ? mc.getMCID() : inherited;
      String text = textOf(mc, mcid, mcids);
      if (mcid >= 0 && !mc.getContents().isEmpty()) {
        mcids.add(mcid);
      }
      if (mcid >= 0 && !text.isEmpty()) {
        textByMcid.computeIfAbsent(mcid, k -> new StringBuilder()).append(text);
      }
    }

    /**
     * The text a reader gets from one sequence: {@code /ActualText} when the producer declared it
     * (placeholder glyphs, presentation forms), otherwise the glyphs' Unicode; a {@code /ReversedChars}
     * run is drawn in visual order and is put back into logical order grapheme by grapheme. Glyph
     * positions always feed the element's box, whichever text wins.
     */
    private String textOf(PDMarkedContent mc, int mcid, Set<Integer> mcids) {
      StringBuilder glyphs = new StringBuilder();
      for (Object item : mc.getContents()) {
        if (item instanceof PDMarkedContent nested) {
          if (nested.getMCID() >= 0 && nested.getMCID() != mcid) {
            collect(nested, mcid, mcids);
          } else {
            glyphs.append(textOf(nested, mcid, mcids));
          }
        } else if (item instanceof TextPosition tp) {
          glyphs.append(tp.getUnicode());
          if (mcid >= 0) {
            grow(mcid, tp);
          }
        }
      }
      String actual = mc.getActualText();
      String text = actual != null ? actual : glyphs.toString();
      return "ReversedChars".equals(mc.getTag()) ? reverseGraphemes(text) : text;
    }

    private void grow(int mcid, TextPosition tp) {
      double x = tp.getTextMatrix().getTranslateX();
      double y = tp.getTextMatrix().getTranslateY();
      double w = tp.getWidthDirAdj();
      double h = tp.getHeightDir();
      double[] box = boxByMcid.get(mcid);
      if (box == null) {
        boxByMcid.put(mcid, new double[] {x, y, x + w, y + h});
      } else {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.max(box[2], x + w);
        box[3] = Math.max(box[3], y + h);
      }
    }

    private static String reverseGraphemes(String text) {
      BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
      graphemes.setText(text);
      List<String> clusters = new ArrayList<>();
      int start = graphemes.first();
      for (int end = graphemes.next(); end != BreakIterator.DONE; start = end, end = graphemes.next()) {
        clusters.add(text.substring(start, end));
      }
      Collections.reverse(clusters);
      return String.join("", clusters);
    }
  }

  // ---- structure walk ------------------------------------------------------------------------------

  private static final class Walk {
    final String sha256;
    final Map<COSDictionary, Integer> pageIndex;
    final List<PageContent> pages;
    final List<StructureNode> nodes = new ArrayList<>();
    final Map<COSDictionary, StructureNode> byDict = new IdentityHashMap<>();
    final Set<COSDictionary> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    int elements;
    int contentRefs;
    boolean truncated;

    Walk(String sha256, Map<COSDictionary, Integer> pageIndex, List<PageContent> pages) {
      this.sha256 = sha256;
      this.pageIndex = pageIndex;
      this.pages = pages;
    }

    StructureNode nodeFor(COSBase base) {
      COSBase resolved = base instanceof COSObject ref ? ref.getObject() : base;
      return resolved instanceof COSDictionary dict ? byDict.get(dict) : null;
    }

    /** Aggregate of one subtree, folded upwards. */
    private static final class Subtree {
      final StringBuilder text = new StringBuilder();
      double[] box;
      final TreeSet<Integer> pages = new TreeSet<>();
      int contentRefs;
      int objectRefs;

      void merge(Subtree child) {
        if (child.text.length() > 0) {
          if (text.length() > 0) {
            text.append(' ');
          }
          text.append(child.text);
        }
        union(child.box);
        pages.addAll(child.pages);
        contentRefs += child.contentRefs;
        objectRefs += child.objectRefs;
      }

      void union(double[] other) {
        if (other == null) {
          return;
        }
        if (box == null) {
          box = other.clone();
        } else {
          box[0] = Math.min(box[0], other[0]);
          box[1] = Math.min(box[1], other[1]);
          box[2] = Math.max(box[2], other[2]);
          box[3] = Math.max(box[3], other[3]);
        }
      }
    }

    /** Walks a /K value; returns the aggregate for the caller's subtree. */
    Subtree kids(COSBase kids, COSDictionary inheritedPage, StructureNode parent, List<Integer> parentPath, int depth) {
      Subtree aggregate = new Subtree();
      if (kids == null || depth > MAX_DEPTH) {
        return aggregate;
      }
      COSBase resolved = kids instanceof COSObject ref ? ref.getObject() : kids;
      if (resolved instanceof COSArray array) {
        int index = 0;
        for (COSBase item : array) {
          COSBase itemResolved = item instanceof COSObject ref ? ref.getObject() : item;
          List<Integer> path = new ArrayList<>(parentPath);
          path.add(index);
          aggregate.merge(kid(itemResolved, inheritedPage, parent, path, depth));
          index++;
        }
      } else {
        List<Integer> path = new ArrayList<>(parentPath);
        path.add(0);
        aggregate.merge(kid(resolved, inheritedPage, parent, path, depth));
      }
      return aggregate;
    }

    private Subtree kid(COSBase item, COSDictionary inheritedPage, StructureNode parent, List<Integer> path, int depth) {
      Subtree aggregate = new Subtree();
      if (item instanceof COSNumber n) {
        content(aggregate, inheritedPage, n.intValue());
      } else if (item instanceof COSDictionary dict) {
        COSName type = dict.getCOSName(COSName.TYPE);
        if (COSName.MCR.equals(type)) {
          COSDictionary page = dict.getCOSDictionary(COSName.PG);
          content(aggregate, page != null ? page : inheritedPage, dict.getInt(COSName.MCID, -1));
        } else if (COSName.OBJR.equals(type)) {
          aggregate.objectRefs++;
          COSDictionary page = dict.getCOSDictionary(COSName.PG);
          Integer pageNumber = pageIndex.get(page != null ? page : inheritedPage);
          if (pageNumber != null) {
            aggregate.pages.add(pageNumber);
          }
        } else {
          aggregate.merge(element(dict, inheritedPage, parent, path, depth));
        }
      }
      return aggregate;
    }

    private void content(Subtree aggregate, COSDictionary page, int mcid) {
      contentRefs++;
      aggregate.contentRefs++;
      Integer pageNumber = page == null ? null : pageIndex.get(page);
      if (pageNumber == null || mcid < 0) {
        return;
      }
      aggregate.pages.add(pageNumber);
      PageContent pc = pages.get(pageNumber - 1);
      StringBuilder text = pc.textByMcid.get(mcid);
      if (text != null && text.length() > 0) {
        if (aggregate.text.length() > 0) {
          aggregate.text.append(' ');
        }
        aggregate.text.append(text);
      }
      aggregate.union(pc.boxByMcid.get(mcid));
    }

    private Subtree element(COSDictionary dict, COSDictionary inheritedPage, StructureNode parent, List<Integer> path, int depth) {
      Subtree aggregate = new Subtree();
      if (visiting.contains(dict) || byDict.containsKey(dict)) {
        return aggregate; // cycle or shared reference: counted once
      }
      if (nodes.size() >= MAX_NODES) {
        truncated = true;
        return aggregate;
      }
      visiting.add(dict);
      elements++;
      int logicalIndex = nodes.size();
      COSName role = dict.getCOSName(COSName.S);
      String roleName = role == null || role.getName().isEmpty() ? "Unknown" : clip(role.getName(), 64);
      COSDictionary ownPage = dict.getCOSDictionary(COSName.PG);
      COSDictionary page = ownPage != null ? ownPage : inheritedPage;
      String nodeId = nodeId(sha256, logicalIndex, path);
      // Reserve the slot so children get a parent id and the pre-order index is stable.
      StructureNode placeholder = new StructureNode(nodeId, parent == null ? null : parent.nodeId(), roleName, logicalIndex, null, null, null, null);
      nodes.add(placeholder);
      byDict.put(dict, placeholder);

      COSBase k = dict.getDictionaryObject(COSName.K);
      String kidKinds = kidKinds(k);
      int elementKids = elementKids(k);
      Subtree children = kids(k, page, placeholder, path, depth + 1);
      aggregate.merge(children);
      Integer pageNumber = pageNumber(children, page);
      // An element with no text of its own (a Figure) is announced through /Alt; show exactly that, never a guess.
      String alt = dict.getString(COSName.ALT);
      String preview = children.text.length() > 0 ? preview(children.text.toString()) : alt != null && !alt.isBlank() ? preview(alt.trim()) : null;
      Box box = children.box == null ? null : new Box(round(children.box[0]), round(children.box[1]), round(children.box[2] - children.box[0]), round(children.box[3] - children.box[1]));
      COSObjectKey key = dict.getKey();
      PrivateMapping mapping = new PrivateMapping(List.copyOf(path), key == null ? null : key.getNumber() + " " + key.getGeneration(), kidKinds, List.copyOf(children.pages), children.contentRefs, children.objectRefs, elementKids);
      StructureNode complete = new StructureNode(nodeId, placeholder.parentId(), roleName, logicalIndex, pageNumber, preview, box, mapping);
      nodes.set(logicalIndex, complete);
      byDict.put(dict, complete);
      visiting.remove(dict);
      return aggregate;
    }

    private Integer pageNumber(Subtree children, COSDictionary page) {
      if (!children.pages.isEmpty()) {
        return children.pages.first();
      }
      return page == null ? null : pageIndex.get(page);
    }

    private static String kidKinds(COSBase k) {
      COSBase resolved = k instanceof COSObject ref ? ref.getObject() : k;
      if (resolved == null) {
        return "none";
      }
      boolean elements = false;
      boolean content = false;
      List<COSBase> items = new ArrayList<>();
      if (resolved instanceof COSArray array) {
        for (COSBase item : array) {
          items.add(item instanceof COSObject ref ? ref.getObject() : item);
        }
      } else {
        items.add(resolved);
      }
      for (COSBase item : items) {
        if (item instanceof COSNumber) {
          content = true;
        } else if (item instanceof COSDictionary dict) {
          COSName type = dict.getCOSName(COSName.TYPE);
          if (COSName.MCR.equals(type) || COSName.OBJR.equals(type)) {
            content = true;
          } else {
            elements = true;
          }
        }
      }
      return elements && content ? "mixed" : elements ? "elements" : content ? "content" : "none";
    }

    private static int elementKids(COSBase k) {
      COSBase resolved = k instanceof COSObject ref ? ref.getObject() : k;
      List<COSBase> items = new ArrayList<>();
      if (resolved instanceof COSArray array) {
        for (COSBase item : array) {
          items.add(item instanceof COSObject ref ? ref.getObject() : item);
        }
      } else if (resolved != null) {
        items.add(resolved);
      }
      int count = 0;
      for (COSBase item : items) {
        if (item instanceof COSDictionary dict) {
          COSName type = dict.getCOSName(COSName.TYPE);
          if (!COSName.MCR.equals(type) && !COSName.OBJR.equals(type)) {
            count++;
          }
        }
      }
      return count;
    }
  }

  // ---- helpers -----------------------------------------------------------------------------------------

  /** Opaque, deterministic per artifact and position; never a COS reference. */
  static String nodeId(String sha256, int logicalIndex, List<Integer> path) {
    String seed = sha256 + ":" + logicalIndex + ":" + path;
    return "n" + PdfPreflight.sha256Hex(seed.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
  }

  private static String preview(String text) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    if (normalized.length() <= PREVIEW_LENGTH) {
      return normalized;
    }
    return normalized.substring(0, PREVIEW_LENGTH - 1) + "…";
  }

  private static double round(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private static String clip(String value, int max) {
    return value.length() > max ? value.substring(0, max) : value;
  }

  private static boolean isSignedOrCertified(PDDocument document) throws IOException {
    if (!document.getSignatureDictionaries().isEmpty()) {
      return true;
    }
    COSDictionary perms = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PERMS);
    return perms != null && perms.size() > 0;
  }

  private static boolean hasPdfHeader(byte[] bytes) {
    int limit = Math.min(bytes.length, HEADER_SEARCH_WINDOW) - PDF_MAGIC.length;
    outer:
    for (int i = 0; i <= limit; i++) {
      for (int j = 0; j < PDF_MAGIC.length; j++) {
        if (bytes[i + j] != PDF_MAGIC[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] digest) {
    StringBuilder sb = new StringBuilder(64);
    for (byte b : digest) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }

  /** Insertion-ordered view used by tests and the reorder gate. */
  public static Map<String, StructureNode> byId(StructureResult result) {
    Map<String, StructureNode> map = new LinkedHashMap<>();
    for (StructureNode node : result.nodes()) {
      map.put(node.nodeId(), node);
    }
    return map;
  }
}
