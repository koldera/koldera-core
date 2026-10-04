package com.koldera.pdf.repair;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * The marked-content sequences of one content stream, as a tree over its parsed tokens, for the
 * tagged-upload repairs ({@link MarkedContentNesting}, {@link OrphanMcids}). Read-only.
 */
final class MarkedSequences {

  static final int MAX_FORM_DEPTH = 16;
  private static final java.util.Set<String> TEXT_SHOWING = java.util.Set.of("Tj", "TJ", "'", "\"");

  /**
   * One sequence: tokens {@code operandStart..open} are its opening operator with its operands,
   * {@code close} its EMC. {@code textAtOpen}/{@code textAtClose}: the text object the marker sits in ({@code qAtOpen}/{@code qAtClose}: the q/Q nesting depth there)
   * (1-based count of {@code BT} in the stream), 0 outside any text object.
   */
  static final class Seq {
    final int operandStart;
    final int open;
    int close = -1;
    final COSName tag;
    /** The properties operand as written (inline dictionary or resource name); null for BMC. */
    final COSBase properties;
    final int mcid;
    final Seq parent;
    final List<Seq> children = new ArrayList<>();
    final int textAtOpen;
    int textAtClose;
    int qAtOpen;
    int qAtClose;
    /** Text is shown inside, directly or in a form drawn inside (a form that cannot be read counts as text). */
    boolean showsText;

    Seq(int operandStart, int open, COSName tag, COSBase properties, int mcid, Seq parent, int textAtOpen) {
      this.operandStart = operandStart;
      this.open = open;
      this.tag = tag;
      this.properties = properties;
      this.mcid = mcid;
      this.parent = parent;
      this.textAtOpen = textAtOpen;
    }

    boolean artifact() {
      return COSName.ARTIFACT.equals(tag);
    }

    /** Tagged content: an MCID on a sequence that is not an artifact. */
    boolean tagged() {
      return mcid >= 0 && !artifact();
    }

    boolean hasAncestor(java.util.function.Predicate<Seq> test) {
      for (Seq up = parent; up != null; up = up.parent) {
        if (test.test(up)) {
          return true;
        }
      }
      return false;
    }

    boolean hasDescendant(java.util.function.Predicate<Seq> test) {
      for (Seq child : children) {
        if (test.test(child) || child.hasDescendant(test)) {
          return true;
        }
      }
      return false;
    }
  }

  final List<Object> tokens;
  final List<Seq> seqs = new ArrayList<>();
  boolean balanced = true;
  /** Form XObjects drawn from this stream, in draw order (each once). */
  final List<PDFormXObject> forms = new ArrayList<>();

  private MarkedSequences(List<Object> tokens) {
    this.tokens = tokens;
  }

  static MarkedSequences of(PDPage page) throws IOException {
    return of(page.hasContents() ? new PDFStreamParser(page).parse() : List.of(), page.getResources());
  }

  static MarkedSequences of(List<Object> tokens, PDResources resources) throws IOException {
    MarkedSequences scan = new MarkedSequences(tokens);
    List<Seq> open = new ArrayList<>();
    List<COSBase> operands = new ArrayList<>();
    Set<COSStream> drawn = Collections.newSetFromMap(new IdentityHashMap<>());
    int operandStart = 0;
    int textObjects = 0;
    int text = 0;
    int q = 0;
    for (int i = 0; i < tokens.size(); i++) {
      Object token = tokens.get(i);
      if (!(token instanceof Operator op)) {
        operands.add((COSBase) token);
        continue;
      }
      String name = op.getName();
      switch (name) {
        case "BMC", "BDC" -> {
          COSName tag = !operands.isEmpty() && operands.get(0) instanceof COSName t ? t : null;
          COSBase properties = "BDC".equals(name) && operands.size() > 1 ? operands.get(1) : null;
          Seq parent = open.isEmpty() ? null : open.get(open.size() - 1);
          Seq seq = new Seq(operandStart, i, tag, properties, mcid(properties, resources), parent, text);
          seq.qAtOpen = q;
          if (parent != null) {
            parent.children.add(seq);
          }
          scan.seqs.add(seq);
          open.add(seq);
        }
        case "EMC" -> {
          if (open.isEmpty()) {
            scan.balanced = false;
          } else {
            Seq seq = open.remove(open.size() - 1);
            seq.close = i;
            seq.textAtClose = text;
            seq.qAtClose = q;
          }
        }
        case "BT" -> text = ++textObjects;
        case "q" -> q++;
        case "Q" -> q--;
        case "ET" -> text = 0;
        case "Do" -> {
          if (operands.size() == 1 && operands.get(0) instanceof COSName xName && resources != null) {
            PDXObject xObject = xObject(resources, xName);
            if (xObject instanceof PDFormXObject form) {
              if (drawn.add(form.getCOSObject())) {
                scan.forms.add(form);
              }
              if (!open.isEmpty() && formShowsText(form, resources, 0, Collections.newSetFromMap(new IdentityHashMap<>()))) {
                open.forEach(s -> s.showsText = true);
              }
            }
          }
        }
        default -> { }
      }
      if (TEXT_SHOWING.contains(name)) {
        open.forEach(s -> s.showsText = true);
      }
      operands.clear();
      operandStart = i + 1;
    }
    if (!open.isEmpty()) {
      scan.balanced = false;
    }
    return scan;
  }

  /** Whether a form (or a form it draws) shows text; a form that cannot be read or nests too deep counts as text. */
  static boolean formShowsText(PDFormXObject form, PDResources parent, int depth, Set<COSStream> path) {
    if (depth >= MAX_FORM_DEPTH || !path.add(form.getCOSObject())) {
      return true;
    }
    try {
      PDResources resources = form.getResources() != null ? form.getResources() : parent;
      List<COSBase> operands = new ArrayList<>();
      for (Object token : new PDFStreamParser(form).parse()) {
        if (!(token instanceof Operator op)) {
          operands.add((COSBase) token);
          continue;
        }
        if (TEXT_SHOWING.contains(op.getName())) {
          return true;
        }
        if ("Do".equals(op.getName()) && operands.size() == 1 && operands.get(0) instanceof COSName xName && resources != null
            && xObject(resources, xName) instanceof PDFormXObject inner && formShowsText(inner, resources, depth + 1, path)) {
          return true;
        }
        operands.clear();
      }
      return false;
    } catch (IOException | RuntimeException e) {
      return true;
    } finally {
      path.remove(form.getCOSObject());
    }
  }

  private static PDXObject xObject(PDResources resources, COSName name) {
    try {
      return resources.getXObject(name);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** The MCID of a BDC's properties (inline or named), or -1. */
  static int mcid(COSBase properties, PDResources resources) {
    COSDictionary dict = null;
    if (properties instanceof COSDictionary d) {
      dict = d;
    } else if (properties instanceof COSName named && resources != null) {
      PDPropertyList list = resources.getProperties(named);
      dict = list == null ? null : list.getCOSObject();
    }
    return dict == null ? -1 : dict.getInt(COSName.MCID, -1);
  }

  /** Whether any token in {@code [from, to)} is an operator. */
  boolean hasOperator(int from, int to) {
    for (int i = Math.max(0, from); i < to && i < tokens.size(); i++) {
      if (tokens.get(i) instanceof Operator) {
        return true;
      }
    }
    return false;
  }

  /** The largest MCID used by any sequence of the stream, or -1. */
  int maxMcid() {
    int max = -1;
    for (Seq seq : seqs) {
      max = Math.max(max, seq.mcid);
    }
    return max;
  }

  /** The ParentTree array for a page (its /StructParents entry), or null. */
  static COSArray parentTreeArray(PDPage page, Map<Integer, COSBase> numbers) {
    if (!page.getCOSObject().containsKey(COSName.STRUCT_PARENTS)) {
      return null;
    }
    COSBase value = numbers.get(page.getCOSObject().getInt(COSName.STRUCT_PARENTS));
    if (value instanceof COSObject ref) {
      value = ref.getObject();
    }
    return value instanceof COSArray array ? array : null;
  }

  /** The element at slot {@code mcid} of a page's ParentTree array, or null. */
  static COSDictionary slot(COSArray array, int mcid) {
    if (array == null || mcid < 0 || mcid >= array.size()) {
      return null;
    }
    return array.getObject(mcid) instanceof COSDictionary element ? element : null;
  }
}
