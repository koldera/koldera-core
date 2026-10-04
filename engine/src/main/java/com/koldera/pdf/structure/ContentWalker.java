package com.koldera.pdf.structure;

import com.koldera.pdf.structure.ContentTagger.UnsupportedContentException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDPostScriptXObject;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

/**
 * Read-only first pass over one content stream: tracks graphics and text state far enough to record each text-showing operator,
 * image, path painting and marked-content block as an {@link Item} in content order. Forms drawn once are walked inline; forms
 * drawn more often are opaque (vector-only ones are paths; in mode {@code fix} one with text or images is a {@link Kind#FORM_SHARED}).
 */
final class ContentWalker {

  /**
   * What the walk may do beyond the deprecated {@code structure_fix} behaviour ({@code (false,
   * false)}): drop painting operators with no path, and wrap each draw of a shared form with text or
   * images instead of refusing the document. Mode {@code fix} uses {@code (true, true)}.
   */
  record WalkOptions(boolean removeStrayPaints, boolean wrapSharedForms) {}

  enum Kind {
    /** A text-showing operator, or an existing non-artifact marked-content block that contains text. */
    TEXT,
    /** An image XObject, inline image, or an existing block whose content is images only. */
    FIGURE,
    /** Path painting, shading, a repeated vector-only form, or an existing block of paths. */
    PATH,
    /** An existing {@code /Artifact} block: copied as is, never nested in tagged content. */
    EXISTING_ARTIFACT,
    /** {@code Do} of a form drawn exactly once: its own stream carries the tags. */
    FORM_ONCE,
    /**
     * {@code Do} of a form drawn more than once that shows text or images (mode {@code fix} only):
     * the draw is wrapped from the invoking stream as one unit; the form's own stream is untouched.
     */
    FORM_SHARED
  }

  /** One content item: token range {@code [start, end)} within its stream, in content order. */
  static final class Item {
    final Kind kind;
    final StreamPlan stream;
    final int start;
    final int end;
    final float x;
    final float y;
    final float size;
    final String digest;
    final PDRectangle bbox;
    /** A BT/ET/q/Q boundary passed since the previous text item of this stream. */
    final boolean boundaryBefore;
    /** The item lies entirely in the top or bottom 5 % band of the page ("T"/"B"), or null. */
    final String band;
    final boolean outsideCropBox;
    /**
     * MCIDs the existing marked content of this item already carries (a block from an embedded,
     * once-tagged figure in an untagged document). Such an item is adopted: copied unchanged, its
     * MCIDs become the kids of the element created for it, and no wrapper is nested around it.
     */
    final List<Integer> existingMcids;
    /** {@link Kind#FORM_SHARED} only: the form's identity ({@code "form|<num> <gen>"} or {@code "form|direct<n>"}). */
    final String formKey;
    /** {@link Kind#FORM_SHARED} only: whether the form (or a form it draws) shows text. */
    final boolean formText;
    /**
     * {@link Kind#TEXT} only: how many text-showing operators ({@code Tj TJ ' "}) precede this item's
     * (first) one in token order of its stream, counting every such operator of the stream; {@code -1}
     * for other kinds and for a marked block whose text is drawn only by a form. Joins the item to the
     * text positions a text extraction pass records per operator (inference).
     */
    int textOrdinal = -1;

    /**
     * {@link Kind#TEXT} only: how many text-showing operators of its stream the item holds, from {@link
     * #textOrdinal} on (1 for a lone operator; a marked block holds every one inside it).
     */
    int textCount = 0;

    /** A {@link Kind#FORM_SHARED} draw. */
    Item(StreamPlan stream, int start, int end, float x, float y, String formKey, String digest, PDRectangle bbox, boolean boundaryBefore, boolean formText) {
      this.kind = Kind.FORM_SHARED;
      this.stream = stream;
      this.start = start;
      this.end = end;
      this.x = x;
      this.y = y;
      this.size = 0;
      this.digest = digest;
      this.bbox = bbox;
      this.boundaryBefore = boundaryBefore;
      this.band = null;
      this.outsideCropBox = false;
      this.existingMcids = List.of();
      this.formKey = formKey;
      this.formText = formText;
    }

    Item(Kind kind, StreamPlan stream, int start, int end, float x, float y, float size, String digest, PDRectangle bbox, boolean boundaryBefore, String band, boolean outsideCropBox) {
      this(kind, stream, start, end, x, y, size, digest, bbox, boundaryBefore, band, outsideCropBox, List.of());
    }

    Item(Kind kind, StreamPlan stream, int start, int end, float x, float y, float size, String digest, PDRectangle bbox, boolean boundaryBefore, String band, boolean outsideCropBox, List<Integer> existingMcids) {
      this.existingMcids = List.copyOf(existingMcids);
      this.formKey = null;
      this.formText = false;
      this.kind = kind;
      this.stream = stream;
      this.start = start;
      this.end = end;
      this.x = x;
      this.y = y;
      this.size = size;
      this.digest = digest;
      this.bbox = bbox;
      this.boundaryBefore = boundaryBefore;
      this.band = band;
      this.outsideCropBox = outsideCropBox;
    }
  }

  /** A page content stream or a once-drawn form's stream: the parsed tokens and its items. */
  static final class StreamPlan {
    final PDContentStream owner;
    final List<Object> tokens;
    final List<Item> items = new ArrayList<>();
    /** Next MCID candidate for allocation; MCIDs are unique per content stream, not per page. */
    int mcidCount;
    /** MCIDs the stream's existing marked content already uses; never handed out again. */
    final Set<Integer> reservedMcids = new HashSet<>();

    StreamPlan(PDContentStream owner, List<Object> tokens) {
      this.owner = owner;
      this.tokens = tokens;
    }

    /** The lowest MCID that is neither allocated nor already present in the stream. */
    int allocateMcid() {
      while (reservedMcids.contains(mcidCount)) {
        mcidCount++;
      }
      return mcidCount++;
    }

    /** Size of the ParentTree array for this stream: one slot per MCID up to the highest one in use. */
    int mcidSlots() {
      int slots = mcidCount;
      for (int reserved : reservedMcids) {
        slots = Math.max(slots, reserved + 1);
      }
      return slots;
    }

    /** Text-showing operators seen so far in token order (the next {@link Item#textOrdinal}). */
    int textOperators;

    /** Token indexes of painting operators with no current path: no-ops, left out of the rewritten stream. */
    final Set<Integer> strayPaints = new HashSet<>();

    /** Reserved MCIDs that an element actually adopted (an empty existing sequence reserves but is not adopted). */
    int adoptedMcids;

    /** MCIDs in use: allocated plus adopted (unused slots below the highest reserved id are not MCIDs). */
    int mcidsInUse() {
      int allocated = 0;
      for (int id = 0; id < mcidCount; id++) {
        if (!reservedMcids.contains(id)) {
          allocated++;
        }
      }
      return allocated + adoptedMcids;
    }
  }

  private static final int MAX_FORM_DEPTH = 8;
  private static final Set<String> TEXT_SHOWING = Set.of("Tj", "TJ", "'", "\"");
  private static final Set<String> MARKED_CONTENT = Set.of("BDC", "BMC");

  /** Document-wide census of form XObjects: effective draw counts and whether they hold real content. */
  static final class FormCensus {
    private final Map<COSStream, Integer> effectiveCount = new IdentityHashMap<>();
    private final Map<COSStream, Boolean> hasTextOrImage = new IdentityHashMap<>();
    private final Map<COSStream, Boolean> hasText = new IdentityHashMap<>();
    private final Map<COSStream, Boolean> hasMarkedContent = new IdentityHashMap<>();
    /** Index (in document order, counting every form draw) of each form's first draw. */
    private final Map<COSStream, Integer> firstDraw = new IdentityHashMap<>();
    private int draws;
    private final Map<COSStream, List<Object>> parsed = new IdentityHashMap<>();
    private final Map<PDPage, List<Object>> pageTokens = new IdentityHashMap<>();

    void count(PDPage page) throws IOException, UnsupportedContentException {
      if (!page.hasContents()) {
        return;
      }
      visit(pageTokens(page), page.getResources(), new HashSet<>(), 0);
    }

    /** Parsed once per page (the concatenation of the page's /Contents), shared with the walk. */
    List<Object> pageTokens(PDPage page) throws IOException {
      List<Object> tokens = pageTokens.get(page);
      if (tokens == null) {
        tokens = new PDFStreamParser(page).parse();
        pageTokens.put(page, tokens);
      }
      return tokens;
    }

    /** Every draw of a form is visited, so nested draws are counted per enclosing draw. */
    private void visit(List<Object> tokens, PDResources resources, Set<COSStream> path, int depth) throws IOException, UnsupportedContentException {
      List<COSBase> operands = new ArrayList<>();
      for (Object token : tokens) {
        if (!(token instanceof Operator op)) {
          operands.add((COSBase) token);
          continue;
        }
        if ("Do".equals(op.getName()) && operands.size() == 1 && operands.get(0) instanceof COSName name && resources != null) {
          PDXObject xObject = xObject(resources, name);
          if (xObject instanceof PDFormXObject form) {
            COSStream key = form.getCOSObject();
            if (path.contains(key)) {
              throw new UnsupportedContentException("form XObjects reference each other in a cycle");
            }
            if (depth >= MAX_FORM_DEPTH) {
              throw new UnsupportedContentException("form XObjects are nested deeper than " + MAX_FORM_DEPTH + " levels");
            }
            effectiveCount.merge(key, 1, Integer::sum);
            firstDraw.putIfAbsent(key, draws);
            draws++;
            path.add(key);
            visit(tokens(form), resourcesOf(form, resources), path, depth + 1);
            path.remove(key);
          }
        }
        operands.clear();
      }
    }

    int effectiveCount(PDFormXObject form) {
      return effectiveCount.getOrDefault(form.getCOSObject(), 0);
    }

    /**
     * A deterministic identity for a form: its object number and generation, or, for a form without
     * one, the index of its first draw in the document. Never an identity hash.
     */
    String formKey(PDFormXObject form) {
      COSObjectKey key = form.getCOSObject().getKey();
      if (key != null) {
        return "form|" + key.getNumber() + " " + key.getGeneration();
      }
      return "form|direct" + firstDraw.getOrDefault(form.getCOSObject(), -1);
    }

    /** Whether the form (or a form it draws) shows text; memoised, cycle-safe. */
    boolean hasText(PDFormXObject form, PDResources fallback) throws IOException, UnsupportedContentException {
      return anyOperator(form, fallback, TEXT_SHOWING, hasText, new HashSet<>());
    }

    /** Whether the form (or a form it draws) contains marked content ({@code BDC}/{@code BMC}); memoised, cycle-safe. */
    boolean hasMarkedContent(PDFormXObject form, PDResources fallback) throws IOException, UnsupportedContentException {
      return anyOperator(form, fallback, MARKED_CONTENT, hasMarkedContent, new HashSet<>());
    }

    /** Whether one of {@code operators} occurs in the form or a form it draws; a cycle or excess depth counts as yes. */
    private boolean anyOperator(PDFormXObject form, PDResources fallback, Set<String> operators, Map<COSStream, Boolean> memo, Set<COSStream> path)
        throws IOException, UnsupportedContentException {
      COSStream key = form.getCOSObject();
      Boolean known = memo.get(key);
      if (known != null) {
        return known;
      }
      if (path.contains(key) || path.size() >= MAX_FORM_DEPTH) {
        return true;
      }
      path.add(key);
      boolean result = false;
      PDResources resources = resourcesOf(form, fallback);
      List<COSBase> operands = new ArrayList<>();
      for (Object token : tokens(form)) {
        if (!(token instanceof Operator op)) {
          operands.add((COSBase) token);
          continue;
        }
        String name = op.getName();
        if (operators.contains(name)) {
          result = true;
        } else if ("Do".equals(name) && operands.size() == 1 && operands.get(0) instanceof COSName xName && resources != null
            && xObject(resources, xName) instanceof PDFormXObject nested && anyOperator(nested, resources, operators, memo, path)) {
          result = true;
        }
        operands.clear();
        if (result) {
          break;
        }
      }
      path.remove(key);
      memo.put(key, result);
      return result;
    }

    List<Object> tokens(PDFormXObject form) throws IOException {
      List<Object> tokens = parsed.get(form.getCOSObject());
      if (tokens == null) {
        tokens = new PDFStreamParser(form).parse();
        parsed.put(form.getCOSObject(), tokens);
      }
      return tokens;
    }

    /** Whether the form (or a form it draws) shows text or images; memoised, cycle-safe. */
    boolean hasTextOrImage(PDFormXObject form, PDResources fallback) throws IOException, UnsupportedContentException {
      return hasTextOrImage(form, fallback, new HashSet<>());
    }

    private boolean hasTextOrImage(PDFormXObject form, PDResources fallback, Set<COSStream> path) throws IOException, UnsupportedContentException {
      COSStream key = form.getCOSObject();
      Boolean known = hasTextOrImage.get(key);
      if (known != null) {
        return known;
      }
      if (path.contains(key) || path.size() >= MAX_FORM_DEPTH) {
        return true;
      }
      path.add(key);
      boolean result = false;
      PDResources resources = resourcesOf(form, fallback);
      List<COSBase> operands = new ArrayList<>();
      for (Object token : tokens(form)) {
        if (!(token instanceof Operator op)) {
          operands.add((COSBase) token);
          continue;
        }
        String name = op.getName();
        if (TEXT_SHOWING.contains(name) || "BI".equals(name) || ("BDC".equals(name) && existingMcid(operands, resources) >= 0)) {
          result = true;
        } else if ("Do".equals(name) && operands.size() == 1 && operands.get(0) instanceof COSName xName && resources != null) {
          PDXObject xObject = xObject(resources, xName);
          if (xObject instanceof PDImageXObject || (xObject instanceof PDFormXObject nested && hasTextOrImage(nested, resources, path))) {
            result = true;
          }
        }
        operands.clear();
        if (result) {
          break;
        }
      }
      path.remove(key);
      hasTextOrImage.put(key, result);
      return result;
    }
  }

  /**
   * The {@code /MCID} of a {@code BDC} operand pair, inline dictionary or named {@code /Properties}
   * resource; {@code -1} when there is none.
   */
  static int existingMcid(List<COSBase> operands, PDResources resources) {
    if (operands.size() != 2) {
      return -1;
    }
    COSDictionary properties = null;
    if (operands.get(1) instanceof COSDictionary inline) {
      properties = inline;
    } else if (operands.get(1) instanceof COSName named && resources != null) {
      COSDictionary all = resources.getCOSObject().getCOSDictionary(COSName.PROPERTIES);
      if (all != null && all.getDictionaryObject(named) instanceof COSDictionary dict) {
        properties = dict;
      }
    }
    if (properties == null || !(properties.getDictionaryObject(COSName.MCID) instanceof COSNumber number)) {
      return -1;
    }
    int mcid = number.intValue();
    return mcid >= 0 ? mcid : -1;
  }

  static PDResources resourcesOf(PDFormXObject form, PDResources fallback) {
    PDResources own = form.getResources();
    return own != null ? own : fallback;
  }

  /**
   * Resolves a {@code Do} operand. A name that is not in the resources yields {@code null} (nothing
   * is drawn; the operator is kept as an artifact). An XObject that cannot be loaded or has an
   * unknown subtype is not guessed at: the whole document is refused.
   */
  static PDXObject xObject(PDResources resources, COSName name) throws UnsupportedContentException {
    COSDictionary xObjects = resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
    if (xObjects == null || xObjects.getDictionaryObject(name) == null) {
      return null;
    }
    PDXObject xObject;
    try {
      xObject = resources.getXObject(name);
    } catch (IOException | RuntimeException e) {
      throw new UnsupportedContentException("an XObject could not be read; nothing is guessed");
    }
    if (xObject == null || xObject instanceof PDImageXObject || xObject instanceof PDFormXObject || xObject instanceof PDPostScriptXObject) {
      return xObject;
    }
    throw new UnsupportedContentException("an XObject has an unknown subtype; nothing is guessed");
  }

  // ---- walk ------------------------------------------------------------------------------------------------

  private final FormCensus census;
  private final PDRectangle cropBox;
  private final List<Item> pageOrder;
  private final List<StreamPlan> streams;
  /** Mode {@code fix} only: painting operators with no path are dropped (or refused); {@code structure_fix} tags them as before. */
  private final boolean removeStrayPaints;
  /** Mode {@code fix} only: a shared form with text or images is wrapped per draw instead of refusing the document. */
  private final boolean wrapSharedForms;

  ContentWalker(FormCensus census, PDRectangle cropBox, List<Item> pageOrder, List<StreamPlan> streams, WalkOptions options) {
    this.census = census;
    this.cropBox = cropBox;
    this.pageOrder = pageOrder;
    this.streams = streams;
    this.removeStrayPaints = options.removeStrayPaints();
    this.wrapSharedForms = options.wrapSharedForms();
  }

  /** Walks the page stream and, inline, every once-drawn form it draws. */
  void walkPage(PDPage page) throws IOException, UnsupportedContentException {
    if (!page.hasContents()) {
      return;
    }
    StreamPlan plan = new StreamPlan(page, census.pageTokens(page));
    streams.add(plan);
    walk(plan, page.getResources(), new Matrix(), 0, 0, "");
  }

  /**
   * @param inheritedFontSize text state at the {@code Do} for a once-drawn form (a form may rely on
   *     the invoking stream's font); the page starts with none
   */
  private void walk(StreamPlan plan, PDResources resources, Matrix initialCtm, int depth, float inheritedFontSize, String inheritedFontName)
      throws IOException, UnsupportedContentException {
    List<Object> tokens = plan.tokens;
    Matrix ctm = initialCtm;
    Deque<Matrix> saved = new ArrayDeque<>();
    Matrix tm = new Matrix();
    Matrix tlm = new Matrix();
    float fontSize = inheritedFontSize;
    String fontName = inheritedFontName;
    float leading = 0;
    int btDepth = 0;
    boolean boundary = true;
    int operandStart = 0;
    int pathStart = -1;
    // existing marked-content block being spanned (top level only)
    int mcDepth = 0;
    int blockStart = -1;
    int blockBtDepth = 0;
    COSName blockTag = null;
    boolean blockText = false;
    boolean blockImage = false;
    boolean blockPaint = false;
    float blockX = 0;
    float blockY = 0;
    float blockSize = 0;
    PDRectangle blockBox = null;
    StringBuilder blockDigest = null;
    int blockTextOrdinal = -1;
    int blockTextCount = 0;
    List<Integer> blockMcids = new ArrayList<>();
    // Inside a top-level block: which open BDC levels carry an MCID, and whether any content item
    // was drawn while no MCID level was open (such a block cannot be adopted without guessing).
    Deque<Boolean> mcidLevels = new ArrayDeque<>();
    int openMcidLevels = 0;
    boolean blockContentOutsideMcid = false;
    List<float[]> pathPoints = new ArrayList<>();

    for (int i = 0; i < tokens.size(); i++) {
      Object token = tokens.get(i);
      if (!(token instanceof Operator op)) {
        continue;
      }
      String name = op.getName();
      List<COSBase> operands = operands(tokens, operandStart, i);
      switch (name) {
        case "q" -> {
          saved.push(ctm);
          boundary = true;
        }
        case "Q" -> {
          if (!saved.isEmpty()) {
            ctm = saved.pop();
          }
          boundary = true;
        }
        case "cm" -> {
          if (operands.size() == 6) {
            ctm = matrix(operands).multiply(ctm);
          }
        }
        case "BT" -> {
          tm = new Matrix();
          tlm = new Matrix();
          btDepth++;
          boundary = true;
        }
        case "ET" -> {
          btDepth = Math.max(0, btDepth - 1);
          boundary = true;
        }
        case "Tf" -> {
          if (operands.size() == 2) {
            fontName = operands.get(0) instanceof COSName f ? f.getName() : "";
            fontSize = number(operands.get(1));
          }
        }
        case "Td", "TD" -> {
          if (operands.size() == 2) {
            float tx = number(operands.get(0));
            float ty = number(operands.get(1));
            tlm = Matrix.getTranslateInstance(tx, ty).multiply(tlm);
            tm = tlm.clone();
            if ("TD".equals(name)) {
              leading = -ty;
            }
          }
        }
        case "TL" -> {
          if (operands.size() == 1) {
            leading = number(operands.get(0));
          }
        }
        case "T*" -> {
          tlm = Matrix.getTranslateInstance(0, -leading).multiply(tlm);
          tm = tlm.clone();
        }
        case "Tm" -> {
          if (operands.size() == 6) {
            tlm = matrix(operands);
            tm = tlm.clone();
          }
        }
        case "Tj", "TJ", "'", "\"" -> {
          if (!"Tj".equals(name) && !"TJ".equals(name)) {
            tlm = Matrix.getTranslateInstance(0, -leading).multiply(tlm);
            tm = tlm.clone();
          }
          Matrix trm = tm.multiply(ctm);
          float x = trm.getTranslateX();
          float y = trm.getTranslateY();
          float size = fontSize * (float) Math.sqrt(Math.abs(trm.getScaleX() * trm.getScaleY() - trm.getShearX() * trm.getShearY()));
          String digest = name + "|" + fontName + "|" + operandDigest(operands) + "|" + Math.round(x) + "," + Math.round(y);
          int ordinal = plan.textOperators++;
          if (mcDepth > 0) {
            if (blockTextOrdinal < 0) {
              blockTextOrdinal = ordinal;
            }
            blockTextCount++;
            if (!blockText) {
              blockText = true;
              blockX = x;
              blockY = y;
              blockSize = size;
            }
            blockContentOutsideMcid |= openMcidLevels == 0;
            blockDigest.append(digest).append(';');
          } else {
            Item item = new Item(Kind.TEXT, plan, operandStart, i + 1, x, y, size, digest, null, boundary, band(y, y), !contains(cropBox, x, y));
            item.textOrdinal = ordinal;
            item.textCount = 1;
            emit(item);
            boundary = false;
          }
        }
        case "m", "l", "c", "v", "y", "h", "re" -> {
          if (pathStart < 0) {
            pathStart = operandStart;
            pathPoints.clear();
          }
          collectPoints(name, operands, ctm, pathPoints);
        }
        case "W", "W*" -> {
          // clipping keeps the path open until its painting/no-op operator
        }
        case "n" -> {
          pathStart = -1;
          pathPoints.clear();
        }
        case "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "sh" -> {
          if (removeStrayPaints && pathStart < 0 && !"sh".equals(name)) {
            // Painting with no current path paints nothing (ISO 32000-1 8.5.3), but a strict content
            // parser (the PAC engine) rejects the whole stream, and with it every tag placed in it.
            // The operator is dropped from the copy (verified by rendering; see BasicStructureRule).
            // A stray "n" is accepted there, so it is kept here.
            if (operandStart != i) {
              // operands in front of it would attach to the next operator once it is dropped
              throw new UnsupportedContentException(
                  "a content stream paints with no path and with stray operands (ISO 32000-2 Figure 9); nothing is tagged");
            }
            plan.strayPaints.add(i);
            operandStart = i + 1;
            continue;
          }
          int start = pathStart >= 0 ? pathStart : operandStart;
          String digest = "path|" + tokenDigest(tokens, start, i + 1);
          String band = "sh".equals(name) || pathPoints.isEmpty() ? null : band(pathPoints);
          if (mcDepth > 0) {
            blockPaint = true;
            blockContentOutsideMcid |= openMcidLevels == 0;
            blockDigest.append(digest).append(';');
          } else {
            // The box of the path's points (unused by the basic decisions; the layout inference reads it).
            PDRectangle box = pathPoints.isEmpty() ? null : pointsBox(pathPoints);
            emit(new Item(Kind.PATH, plan, start, i + 1, 0, 0, 0, digest, box, boundary, band, false));
            boundary = true;
          }
          pathStart = -1;
          pathPoints.clear();
        }
        case "BI" -> {
          PDRectangle box = unitSquare(ctm);
          if (mcDepth > 0) {
            if (!blockImage) {
              blockImage = true;
              blockBox = box;
            }
            blockContentOutsideMcid |= openMcidLevels == 0;
            blockDigest.append("BI;");
          } else {
            emit(new Item(Kind.FIGURE, plan, operandStart, i + 1, 0, 0, 0, "BI", box, boundary, null, false));
            boundary = true;
          }
        }
        case "Do" -> {
          PDXObject xObject = operands.size() == 1 && operands.get(0) instanceof COSName xName && resources != null ? xObject(resources, xName) : null;
          String digest = "Do|" + operandDigest(operands);
          if (xObject instanceof PDImageXObject) {
            PDRectangle box = unitSquare(ctm);
            if (mcDepth > 0) {
              if (!blockImage) {
                blockImage = true;
                blockBox = box;
              }
              blockContentOutsideMcid |= openMcidLevels == 0;
              blockDigest.append(digest).append(';');
            } else {
              emit(new Item(Kind.FIGURE, plan, operandStart, i + 1, 0, 0, 0, digest, box, boundary, null, false));
              boundary = true;
            }
          } else if (xObject instanceof PDFormXObject form) {
            PDResources formResources = resourcesOf(form, resources);
            boolean content = census.hasTextOrImage(form, resources);
            if (mcDepth > 0) {
              if (content) {
                blockText = true;
                if (blockSize == 0) {
                  Matrix at = form.getMatrix().multiply(ctm);
                  blockX = at.getTranslateX();
                  blockY = at.getTranslateY();
                  blockSize = fontSize > 0 ? fontSize : 12;
                }
              } else {
                blockPaint = true;
              }
              blockContentOutsideMcid |= openMcidLevels == 0;
              blockDigest.append(digest).append(';');
            } else if (census.effectiveCount(form) == 1) {
              if (depth >= MAX_FORM_DEPTH) {
                throw new UnsupportedContentException("form XObjects are nested deeper than " + MAX_FORM_DEPTH + " levels");
              }
              emit(new Item(Kind.FORM_ONCE, plan, operandStart, i + 1, 0, 0, 0, digest, null, boundary, null, false));
              boundary = true;
              StreamPlan child = new StreamPlan(form, census.tokens(form));
              streams.add(child);
              walk(child, formResources, form.getMatrix().multiply(ctm), depth + 1, fontSize, fontName);
            } else if (content && wrapSharedForms) {
              if (census.hasMarkedContent(form, resources)) {
                throw new UnsupportedContentException("a form XObject drawn more than once contains marked content; its structure is not guessed");
              }
              Matrix at = form.getMatrix().multiply(ctm);
              float x = at.getTranslateX();
              float y = at.getTranslateY();
              String formKey = census.formKey(form);
              String formDigest = formKey + "|" + Math.round(x) + "," + Math.round(y);
              emit(new Item(plan, operandStart, i + 1, x, y, formKey, formDigest, formBox(form, ctm), boundary, census.hasText(form, resources)));
              boundary = true;
            } else if (content) {
              throw new UnsupportedContentException("a form XObject drawn more than once contains text or images; its structure is not guessed");
            } else {
              PDRectangle box = formBox(form, ctm);
              emit(new Item(Kind.PATH, plan, operandStart, i + 1, 0, 0, 0, digest, box, boundary, band(box), false));
              boundary = true;
            }
          } else {
            // Dangling name (nothing is drawn) or a PostScript XObject (ignored by every reader): decorative.
            if (mcDepth > 0) {
              blockPaint = true;
              blockContentOutsideMcid |= openMcidLevels == 0;
              blockDigest.append(digest).append(';');
            } else {
              emit(new Item(Kind.PATH, plan, operandStart, i + 1, 0, 0, 0, digest, null, boundary, null, false));
              boundary = true;
            }
          }
        }
        case "BDC", "BMC" -> {
          if (mcDepth == 0) {
            blockStart = operandStart;
            blockBtDepth = btDepth;
            blockTag = operands.isEmpty() || !(operands.get(0) instanceof COSName tag) ? COSName.getPDFName("") : tag;
            blockText = false;
            blockImage = false;
            blockPaint = false;
            blockX = 0;
            blockY = 0;
            blockSize = 0;
            blockBox = null;
            blockDigest = new StringBuilder(blockTag.getName()).append(':');
            blockTextOrdinal = -1;
            blockTextCount = 0;
            blockMcids = new ArrayList<>();
            mcidLevels.clear();
            openMcidLevels = 0;
            blockContentOutsideMcid = false;
          }
          int existing = "BDC".equals(name) ? existingMcid(operands, resources) : -1;
          if (existing >= 0) {
            blockMcids.add(existing);
            plan.reservedMcids.add(existing);
            openMcidLevels++;
          }
          mcidLevels.push(existing >= 0);
          mcDepth++;
        }
        case "EMC" -> {
          if (mcDepth == 0) {
            throw new UnsupportedContentException("existing marked content is not balanced (EMC without BDC/BMC)");
          }
          mcDepth--;
          if (!mcidLevels.isEmpty() && mcidLevels.pop()) {
            openMcidLevels--;
          }
          if (mcDepth == 0) {
            if (btDepth != blockBtDepth) {
              throw new UnsupportedContentException("existing marked content crosses a text object boundary");
            }
            if (!blockMcids.isEmpty() && COSName.ARTIFACT.equals(blockTag)) {
              throw new UnsupportedContentException("existing marked content nests an MCID inside an Artifact; nothing is guessed");
            }
            if (!blockMcids.isEmpty() && blockContentOutsideMcid) {
              throw new UnsupportedContentException("existing marked content mixes an MCID with unmarked content; nothing is guessed");
            }
            String digest = blockDigest.toString();
            if (COSName.ARTIFACT.equals(blockTag)) {
              emit(new Item(Kind.EXISTING_ARTIFACT, plan, blockStart, i + 1, 0, 0, 0, digest, null, boundary, null, false));
              boundary = true;
            } else if (blockText) {
              Item item = new Item(Kind.TEXT, plan, blockStart, i + 1, blockX, blockY, blockSize, digest, null, boundary, band(blockY, blockY), !contains(cropBox, blockX, blockY), blockMcids);
              item.textOrdinal = blockTextOrdinal;
              item.textCount = blockTextCount;
              emit(item);
              boundary = false;
            } else if (blockImage) {
              emit(new Item(Kind.FIGURE, plan, blockStart, i + 1, 0, 0, 0, digest, blockBox, boundary, null, false, blockMcids));
              boundary = true;
            } else if (!blockMcids.isEmpty()) {
              // Vector content the author already marked as real content (an embedded figure's own
              // /Figure block): adopted as a Figure, never demoted to an artifact. No /BBox: the block
              // is not rewritten and its extent is not measured (the class Javadoc states this).
              emit(new Item(Kind.FIGURE, plan, blockStart, i + 1, 0, 0, 0, digest, null, boundary, null, false, blockMcids));
              boundary = true;
            } else if (blockPaint) {
              emit(new Item(Kind.PATH, plan, blockStart, i + 1, 0, 0, 0, digest, null, boundary, null, false));
              boundary = true;
            }
            // a block without content items is state only: copied unchanged
          }
        }
        default -> {
          // colour, state, compatibility, marked-content points, Type 3 glyph metrics: copied unchanged
        }
      }
      operandStart = i + 1;
    }
    if (mcDepth != 0) {
      throw new UnsupportedContentException("existing marked content is not balanced (BDC/BMC without EMC)");
    }
  }

  private void emit(Item item) {
    item.stream.items.add(item);
    pageOrder.add(item);
  }

  // ---- geometry helpers ------------------------------------------------------------------------------------

  /** Top/bottom 5 % of the crop box in unrotated user space; a /Rotate 90 page's header is not detected. */
  private String band(float minY, float maxY) {
    float height = cropBox.getHeight();
    if (height <= 0) {
      return null;
    }
    float slice = 0.05f * height;
    if (minY >= cropBox.getUpperRightY() - slice) {
      return "T";
    }
    if (maxY <= cropBox.getLowerLeftY() + slice) {
      return "B";
    }
    return null;
  }

  private String band(List<float[]> points) {
    float minY = Float.MAX_VALUE;
    float maxY = -Float.MAX_VALUE;
    for (float[] p : points) {
      minY = Math.min(minY, p[1]);
      maxY = Math.max(maxY, p[1]);
    }
    return band(minY, maxY);
  }

  private String band(PDRectangle box) {
    return box == null ? null : band(box.getLowerLeftY(), box.getUpperRightY());
  }

  private static boolean contains(PDRectangle box, float x, float y) {
    float tolerance = 1f;
    return x >= box.getLowerLeftX() - tolerance
        && x <= box.getUpperRightX() + tolerance
        && y >= box.getLowerLeftY() - tolerance
        && y <= box.getUpperRightY() + tolerance;
  }

  private static void collectPoints(String op, List<COSBase> operands, Matrix ctm, List<float[]> into) {
    switch (op) {
      case "m", "l" -> {
        if (operands.size() == 2) {
          into.add(point(ctm, number(operands.get(0)), number(operands.get(1))));
        }
      }
      case "c" -> {
        if (operands.size() == 6) {
          for (int k = 0; k < 6; k += 2) {
            into.add(point(ctm, number(operands.get(k)), number(operands.get(k + 1))));
          }
        }
      }
      case "v", "y" -> {
        if (operands.size() == 4) {
          into.add(point(ctm, number(operands.get(0)), number(operands.get(1))));
          into.add(point(ctm, number(operands.get(2)), number(operands.get(3))));
        }
      }
      case "re" -> {
        if (operands.size() == 4) {
          float x = number(operands.get(0));
          float y = number(operands.get(1));
          float w = number(operands.get(2));
          float h = number(operands.get(3));
          into.add(point(ctm, x, y));
          into.add(point(ctm, x + w, y));
          into.add(point(ctm, x, y + h));
          into.add(point(ctm, x + w, y + h));
        }
      }
      default -> {
        // h: no new point
      }
    }
  }

  private static float[] point(Matrix ctm, float x, float y) {
    var p = ctm.transformPoint(x, y);
    return new float[] {(float) p.getX(), (float) p.getY()};
  }

  private static PDRectangle pointsBox(List<float[]> points) {
    float minX = Float.MAX_VALUE;
    float minY = Float.MAX_VALUE;
    float maxX = -Float.MAX_VALUE;
    float maxY = -Float.MAX_VALUE;
    for (float[] p : points) {
      minX = Math.min(minX, p[0]);
      minY = Math.min(minY, p[1]);
      maxX = Math.max(maxX, p[0]);
      maxY = Math.max(maxY, p[1]);
    }
    return new PDRectangle(minX, minY, maxX - minX, maxY - minY);
  }

  static PDRectangle unitSquare(Matrix ctm) {
    return transformedBox(ctm, new float[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}});
  }

  /** The form's BBox (or unit square when absent) mapped through its Matrix and the CTM. */
  static PDRectangle formBox(PDFormXObject form, Matrix ctm) {
    PDRectangle bbox = form.getBBox();
    Matrix at = form.getMatrix().multiply(ctm);
    if (bbox == null) {
      return unitSquare(at);
    }
    return transformedBox(
        at,
        new float[][] {
          {bbox.getLowerLeftX(), bbox.getLowerLeftY()},
          {bbox.getUpperRightX(), bbox.getLowerLeftY()},
          {bbox.getLowerLeftX(), bbox.getUpperRightY()},
          {bbox.getUpperRightX(), bbox.getUpperRightY()}
        });
  }

  private static PDRectangle transformedBox(Matrix ctm, float[][] corners) {
    float minX = Float.MAX_VALUE;
    float minY = Float.MAX_VALUE;
    float maxX = -Float.MAX_VALUE;
    float maxY = -Float.MAX_VALUE;
    for (float[] corner : corners) {
      float[] p = point(ctm, corner[0], corner[1]);
      minX = Math.min(minX, p[0]);
      minY = Math.min(minY, p[1]);
      maxX = Math.max(maxX, p[0]);
      maxY = Math.max(maxY, p[1]);
    }
    return new PDRectangle(minX, minY, maxX - minX, maxY - minY);
  }

  // ---- token helpers ---------------------------------------------------------------------------------------

  private static List<COSBase> operands(List<Object> tokens, int from, int to) {
    List<COSBase> operands = new ArrayList<>(Math.max(0, to - from));
    for (int k = from; k < to; k++) {
      if (tokens.get(k) instanceof COSBase base) {
        operands.add(base);
      }
    }
    return operands;
  }

  private static Matrix matrix(List<COSBase> operands) {
    return new Matrix(
        number(operands.get(0)), number(operands.get(1)), number(operands.get(2)), number(operands.get(3)), number(operands.get(4)), number(operands.get(5)));
  }

  private static float number(COSBase base) {
    return base instanceof COSNumber n ? n.floatValue() : 0f;
  }

  private static String operandDigest(List<COSBase> operands) {
    StringBuilder sb = new StringBuilder();
    for (COSBase operand : operands) {
      appendDigest(sb, operand);
    }
    return sb.toString();
  }

  private static String tokenDigest(List<Object> tokens, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int k = from; k < to; k++) {
      Object token = tokens.get(k);
      if (token instanceof Operator op) {
        sb.append(op.getName()).append(' ');
      } else if (token instanceof COSBase base) {
        appendDigest(sb, base);
      }
    }
    return sb.toString();
  }

  private static void appendDigest(StringBuilder sb, COSBase base) {
    if (base instanceof COSString s) {
      sb.append('(').append(java.util.HexFormat.of().formatHex(s.getBytes())).append(')');
    } else if (base instanceof COSNumber n) {
      sb.append(String.format(Locale.ROOT, "%.2f", n.floatValue())).append(' ');
    } else if (base instanceof COSName n) {
      sb.append('/').append(n.getName()).append(' ');
    } else if (base instanceof COSArray array) {
      sb.append('[');
      for (COSBase element : array) {
        appendDigest(sb, element);
      }
      sb.append(']');
    } else {
      sb.append('?');
    }
  }
}
